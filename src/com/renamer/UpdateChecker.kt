package com.renamer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** 1.73 (worker C): the «بررسی بروزرسانی» update checker — the
 *  Android side. Pure logic (version compare, release parsing,
 *  progress math) lives in UpdateCheckerPure.kt and is JVM-tested;
 *  this file only does the I/O: the GitHub fetch, the APK download
 *  with progress, and the install intent.
 *
 *  NETWORK: one GET to UpdateCheckerPure.RELEASES_LATEST_URL, 15s
 *  timeouts, no auth (the repo is public; the unauthenticated
 *  rate limit is 60 req/hour per IP, and the checker runs at most
 *  once per launch + once per manual tap, so it stays far under
 *  it). A non-200 answer (incl. the 403 rate-limit) is an error,
 *  never an update and never a "latest".
 *
 *  DOWNLOAD: the release's .apk asset (its browser_download_url)
 *  into filesDir/update/<asset name> with progress callbacks on
 *  the main thread. A partial/failed download is deleted, never
 *  left behind as a broken file. A finished download whose size
 *  disagrees with the asset's declared size is treated as failed
 *  too (truncated download must never be offered for install).
 *
 *  INSTALL: the APK is served to the system installer through the
 *  app's own minimal provider (ApkFileProvider.kt — the build has
 *  no androidx.core, so there is no androidx FileProvider on the
 *  classpath; the provider below is the same contract: a
 *  content:// URI + FLAG_GRANT_READ_URI_PERMISSION). The manifest
 *  declares REQUEST_INSTALL_PACKAGES; on Android 8+ the install
 *  also needs the per-app "allow from this source" switch — when
 *  it is off, the user is sent to that system setting instead of
 *  getting a dead install tap.
 *
 *  SESSION: the auto-check runs ONCE per process launch
 *  (autoCheckDone) and a [بعدا] dismissal lasts the session
 *  (dismissedThisSession) — the box comes back on the next launch,
 *  never nagging inside one session. A release found once in a
 *  launch is remembered (lastKnownUpdate) so a Home recreate
 *  (e.g. a night/day flip) shows the box again without a new
 *  network call. */
object UpdateChecker {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Once per process launch: the auto-check already ran. */
    @Volatile
    private var autoCheckDone = false

    /** This session: the user tapped [بعدا] on the home box. */
    @Volatile
    var dismissedThisSession = false
        private set

    /** The newest release found by a check in this launch, if any —
     *  so Home can re-show the box after a recreate without
     *  re-fetching. Null when no check found an update (yet). */
    @Volatile
    var lastKnownUpdate: UpdateCheckerPure.ReleaseInfo? = null
        private set

    sealed class CheckResult {
        /** Installed is the latest (or the latest release has no
         *  .apk to offer — same outcome for the user). */
        object Latest : CheckResult()

        /** A newer release WITH an .apk asset. */
        class Update(val release: UpdateCheckerPure.ReleaseInfo) : CheckResult()

        /** Network/API failure, or an unparsable answer. */
        object Error : CheckResult()
    }

    enum class InstallResult {
        /** The installer was opened. */
        STARTED,

        /** The system has not allowed installs from this app yet —
         *  the caller must open the permission setting first. */
        NEEDS_PERMISSION,

        /** No file to install, or no app would take the intent. */
        FAILED
    }

    // ---------- installed version ----------

    fun installedVersionName(ctx: Context): String {
        return try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    fun installedVersionCode(ctx: Context): Long {
        return try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        } catch (_: Exception) {
            0L
        }
    }

    // ---------- session guards ----------

    /** True when the once-per-launch auto-check has not run yet and
     *  the user has not dismissed the box this session. */
    fun shouldAutoCheck(): Boolean = !autoCheckDone && !dismissedThisSession

    fun markAutoCheckDone() {
        autoCheckDone = true
    }

    fun markDismissedThisSession() {
        dismissedThisSession = true
    }

    // ---------- the check ----------

    /** Background check; the result arrives on the main thread.
     *  Never throws to the caller. */
    fun checkLatest(ctx: Context, onResult: (CheckResult) -> Unit) {
        val appCtx = ctx.applicationContext
        Thread {
            val result = try {
                checkLatestSync(appCtx)
            } catch (_: Exception) {
                CheckResult.Error
            }
            mainHandler.post {
                try {
                    onResult(result)
                } catch (_: Exception) {
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun checkLatestSync(appCtx: Context): CheckResult {
        val conn =
            URL(UpdateCheckerPure.RELEASES_LATEST_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = UpdateCheckerPure.CHECK_TIMEOUT_MS
        conn.readTimeout = UpdateCheckerPure.CHECK_TIMEOUT_MS
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", UpdateCheckerPure.GITHUB_API_ACCEPT)
        conn.setRequestProperty("User-Agent", UpdateCheckerPure.USER_AGENT)
        try {
            if (conn.responseCode != 200) return CheckResult.Error
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val release = UpdateCheckerPure.parseRelease(body) ?: return CheckResult.Error
            // No .apk asset -> nothing to download, ever. A newer
            // tag without an asset is reported as "latest", not as
            // an update with a broken download behind it.
            if (!release.hasApk) return CheckResult.Latest
            val current = installedVersionName(appCtx)
            return if (UpdateCheckerPure.isUpdateAvailable(current, release.tag)) {
                lastKnownUpdate = release
                CheckResult.Update(release)
            } else {
                CheckResult.Latest
            }
        } finally {
            try {
                conn.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    // ---------- the download ----------

    /** The file a release's APK downloads to (private storage). */
    fun apkFile(ctx: Context, release: UpdateCheckerPure.ReleaseInfo): File {
        val dir = File(ctx.filesDir, UpdateCheckerPure.UPDATE_DIR_NAME)
        return File(dir, UpdateCheckerPure.sanitizeApkName(release.apkName))
    }

    /** A previously downloaded APK still on disk, if any (the
     *  newest file in the update dir). */
    fun latestDownloadedApk(ctx: Context): File? {
        return try {
            val dir = File(ctx.filesDir, UpdateCheckerPure.UPDATE_DIR_NAME)
            if (!dir.isDirectory) return null
            dir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".apk", ignoreCase = true) && it.length() > 0 }
                ?.maxByOrNull { it.lastModified() }
        } catch (_: Exception) {
            null
        }
    }

    /** Background download with progress on the main thread:
     *  onProgress(downloadedBytes, totalBytes, percentOrMinusOne),
     *  onDone(fileOrNull). Never throws to the caller; a failed
     *  download reports null and leaves no partial file. */
    fun downloadApk(
        ctx: Context,
        release: UpdateCheckerPure.ReleaseInfo,
        onProgress: (Long, Long, Int) -> Unit,
        onDone: (File?) -> Unit
    ) {
        val appCtx = ctx.applicationContext
        Thread {
            val file = try {
                downloadApkSync(appCtx, release, onProgress)
            } catch (_: Exception) {
                null
            }
            mainHandler.post {
                try {
                    onDone(file)
                } catch (_: Exception) {
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun downloadApkSync(
        appCtx: Context,
        release: UpdateCheckerPure.ReleaseInfo,
        onProgress: (Long, Long, Int) -> Unit
    ): File? {
        val url = release.apkUrl ?: return null
        val dir = File(appCtx.filesDir, UpdateCheckerPure.UPDATE_DIR_NAME)
        try {
            if (!dir.exists()) dir.mkdirs()
            // One APK at a time: a stale download from an older
            // release must never be the file offered for install.
            dir.listFiles()?.forEach { f ->
                try {
                    if (f.isFile && f.name.endsWith(".apk", ignoreCase = true)) f.delete()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        val outFile = apkFile(appCtx, release)
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = UpdateCheckerPure.DOWNLOAD_TIMEOUT_MS
        conn.readTimeout = UpdateCheckerPure.DOWNLOAD_TIMEOUT_MS
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", UpdateCheckerPure.USER_AGENT)
        // The release download redirects (github.com -> the object
        // store); HttpURLConnection follows GET redirects by default.
        try {
            if (conn.responseCode != 200) return null
            val total = conn.contentLengthLong.let { len ->
                if (len > 0) len else (release.apkSize ?: -1L)
            }
            var downloaded = 0L
            var lastPostMs = 0L
            val buf = ByteArray(8192)
            conn.inputStream.use { input ->
                FileOutputStream(outFile).use { output ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        downloaded += n
                        val now = System.currentTimeMillis()
                        if (now - lastPostMs >= 150) {
                            lastPostMs = now
                            val d = downloaded
                            val t = total
                            mainHandler.post {
                                try {
                                    onProgress(d, t, UpdateCheckerPure.percent(d, t))
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }
                    output.flush()
                }
            }
            // Final progress (100% when the total is known).
            val d = downloaded
            val t = total
            mainHandler.post {
                try {
                    onProgress(d, t, UpdateCheckerPure.percent(d, t))
                } catch (_: Exception) {
                }
            }
            // A truncated download must never be offered: when the
            // asset declares a size, the file must match it exactly.
            if (total > 0 && downloaded != total) {
                try {
                    outFile.delete()
                } catch (_: Exception) {
                }
                return null
            }
            if (downloaded <= 0) {
                try {
                    outFile.delete()
                } catch (_: Exception) {
                }
                return null
            }
            return outFile
        } catch (_: Exception) {
            try {
                outFile.delete()
            } catch (_: Exception) {
            }
            return null
        } finally {
            try {
                conn.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    // ---------- the install ----------

    /** Open the system "allow installs from this app" setting
     *  (Android 8+: ACTION_MANAGE_UNKNOWN_APP_SOURCES, this
     *  package). The user comes back and taps install again. */
    fun openInstallPermissionSettings(ctx: Context) {
        try {
            val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:" + ctx.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(i)
        } catch (_: Exception) {
        }
    }

    /** Hand a downloaded APK to the system installer. Returns
     *  NEEDS_PERMISSION when the per-app install switch is off —
     *  the caller shows the explanation and opens the setting
     *  (openInstallPermissionSettings); the user then taps install
     *  again. */
    fun installApk(ctx: Context, file: File): InstallResult {
        try {
            if (!file.exists() || file.length() <= 0) return InstallResult.FAILED
            if (Build.VERSION.SDK_INT >= 26 &&
                !ctx.packageManager.canRequestPackageInstalls()
            ) {
                return InstallResult.NEEDS_PERMISSION
            }
            val uri = ApkFileProvider.uriFor(ctx, file) ?: return InstallResult.FAILED
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, UpdateCheckerPure.APK_MIME_TYPE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(i)
            return InstallResult.STARTED
        } catch (_: Exception) {
            return InstallResult.FAILED
        }
    }
}
