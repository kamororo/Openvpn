package com.renamer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.io.File
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
 *  rate limit is 60 req/hour per IP, and the checker runs once
 *  per Home entry at most (one fetch at a time, in-flight
 *  guarded), so ordinary use stays far under it). A non-200
 *  answer (incl. the 403 rate-limit) is an error,
 *  never an update and never a "latest".
 *
 *  DOWNLOAD: the release's .apk asset (its browser_download_url)
 *  into filesDir/update/<asset name> with progress callbacks on
 *  the main thread. A partial/failed download is deleted, never
 *  left behind as a broken file. A finished download whose size
 *  disagrees with the asset's declared size is treated as failed
 *  too (truncated download must never be offered for install).
 *
 *  1.75 (worker B): the download itself is now parallel
 *  multi-part (FastApkDownloader.kt + UpdateDownloadPure.kt —
 *  4 concurrent Range streams, 64 KiB buffer, single-stream
 *  fallback), and every download is RECORDED as a pending
 *  download in prefs ("renamer_update", one encoded string —
 *  see UpdateDownloadPure.encode) so the Settings download box
 *  survives leaving Settings and even a process restart: the
 *  box stays until the new version is installed. The download
 *  keeps running on its daemon thread when the user leaves
 *  Settings (it only ever used the application context), and
 *  the live snapshot below lets Settings poll its progress.
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
 *  SESSION (1.75, worker A — the once-per-launch latch is GONE):
 *  the auto-check runs on EVERY entry — cold start AND every
 *  return to the foreground (HomeActivity.onResume calls
 *  UpdateUi.autoCheckOnEntry; onCreate is always followed by
 *  onResume, so the cold start is covered too). The 1.73 latch
 *  (autoCheckDone) meant a check that ran before a release
 *  existed — or failed offline, silently — never ran again until
 *  the process died, so the box never appeared on re-entry even
 *  though a newer release was already published. Now only one
 *  fetch runs at a time (autoCheckInFlight) and a [بعدا]
 *  dismissal lasts the session FOR THAT RELEASE ONLY
 *  (dismissedTag) — a newer release than the dismissed one still
 *  shows its box. A release found once in a launch is remembered
 *  (lastKnownUpdate) so a Home recreate (e.g. a night/day flip)
 *  shows the box again without a new network call. */
object UpdateChecker {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 1.75: a fetch is already running (auto or manual — the
     *  manual check funnels through the same checkLatest). While
     *  true, an entry must NOT start a second fetch. Set by
     *  tryBeginAutoCheck, cleared when checkLatest posts its
     *  result (even when the caller is gone). */
    @Volatile
    private var autoCheckInFlight = false

    /** This session: the user tapped [بعدا] on the home box. */
    @Volatile
    var dismissedThisSession = false
        private set

    /** 1.75: the release tag the user dismissed with [بعدا] this
     *  session (null when nothing was dismissed). The dismissal
     *  blocks ONLY this release — a newer tag still shows its box. */
    @Volatile
    var dismissedTag: String? = null
        private set

    /** The newest release found by a check in this launch, if any —
     *  so Home can re-show the box after a recreate without
     *  re-fetching. Null when no check found an update (yet). */
    @Volatile
    var lastKnownUpdate: UpdateCheckerPure.ReleaseInfo? = null
        private set

    // ---------- 1.75: live download snapshot + pending record ----------

    /** Prefs file holding the one pending-download record. */
    const val PENDING_PREFS = "renamer_update"

    /** The single key in [PENDING_PREFS]: the encoded record. */
    const val PENDING_KEY = "update_pending_v1"

    /** The download currently running in this process, if any —
     *  read by the Settings box while it polls (the box must
     *  show live progress even though it was built before the
     *  download finished). */
    @Volatile
    var liveDownloading = false
        private set

    @Volatile
    var liveDownloadTag: String? = null
        private set

    @Volatile
    var liveDownloaded: Long = 0L
        private set

    @Volatile
    var liveTotal: Long = -1L
        private set

    /** The pending-download record, or null when there is none.
     *  The record is the box's memory: it survives leaving
     *  Settings and a process restart, until the new version is
     *  installed (see UpdateDownloadPure.shouldClearAsInstalled). */
    fun loadPending(ctx: Context): UpdateDownloadPure.PendingDownload? {
        return try {
            val raw = ctx.getSharedPreferences(PENDING_PREFS, Context.MODE_PRIVATE)
                .getString(PENDING_KEY, null)
            UpdateDownloadPure.decode(raw)
        } catch (_: Exception) {
            null
        }
    }

    fun savePending(ctx: Context, pending: UpdateDownloadPure.PendingDownload) {
        try {
            ctx.getSharedPreferences(PENDING_PREFS, Context.MODE_PRIVATE)
                .edit().putString(PENDING_KEY, UpdateDownloadPure.encode(pending)).apply()
        } catch (_: Exception) {
        }
    }

    fun clearPending(ctx: Context) {
        try {
            ctx.getSharedPreferences(PENDING_PREFS, Context.MODE_PRIVATE)
                .edit().remove(PENDING_KEY).apply()
        } catch (_: Exception) {
        }
    }

    private fun markPending(
        ctx: Context,
        release: UpdateCheckerPure.ReleaseInfo,
        state: UpdateDownloadPure.PendingState,
        downloaded: Long,
        total: Long,
        origin: String
    ) {
        savePending(
            ctx,
            UpdateDownloadPure.PendingDownload(
                tag = release.tag,
                apkName = release.apkName,
                apkUrl = release.apkUrl,
                apkSize = release.apkSize,
                state = state,
                downloadedBytes = downloaded,
                totalBytes = total,
                origin = origin
            )
        )
    }

    /** The file a pending record points at, when it is on disk. */
    fun pendingApkFile(ctx: Context, pending: UpdateDownloadPure.PendingDownload): File? {
        return try {
            val f = File(
                File(ctx.filesDir, UpdateCheckerPure.UPDATE_DIR_NAME),
                UpdateCheckerPure.sanitizeApkName(pending.apkName)
            )
            if (f.isFile && f.length() > 0) f else null
        } catch (_: Exception) {
            null
        }
    }

    /** The box's action for the stored record, from the file on
     *  disk (INSTALL only when the file is really there with the
     *  declared size — see UpdateDownloadPure.resolveAction). */
    fun pendingAction(ctx: Context): UpdateDownloadPure.PendingAction {
        val pending = loadPending(ctx) ?: return UpdateDownloadPure.PendingAction.NONE
        val file = pendingApkFile(ctx, pending)
        return UpdateDownloadPure.resolveAction(pending, file != null, file?.length() ?: 0L)
    }

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

    // ---------- session guards (1.75: every entry, not once per launch) ----------

    /** Claim the right to start an auto-check fetch: true when no
     *  fetch is currently running (then the caller MUST call
     *  checkLatest, which clears the claim when its result posts),
     *  false when one is already in flight — the entry then does
     *  nothing instead of stacking a second fetch. Call on the
     *  main thread, immediately before checkLatest. */
    fun tryBeginAutoCheck(): Boolean {
        if (autoCheckInFlight) return false
        autoCheckInFlight = true
        return true
    }

    /** True when [tag] is the release the user dismissed with
     *  [بعدا] this session ("v1.74" and "1.74" are the same
     *  release — compared by version, not by string). */
    fun isDismissed(tag: String): Boolean =
        UpdateCheckerPure.isDismissedTag(dismissedTag, tag)

    /** [بعدا]: dismiss the box for this session, FOR THIS RELEASE
     *  ONLY — [tag] is the release shown (its tag); a newer
     *  release found by a later check still shows its box. */
    fun markDismissedThisSession(tag: String? = null) {
        dismissedThisSession = true
        if (!tag.isNullOrBlank()) dismissedTag = tag
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
                } finally {
                    // 1.75: release the in-flight claim whatever
                    // happened — the next entry may check again.
                    // (The manual check funnels through here too;
                    // clearing an unset claim is harmless.)
                    autoCheckInFlight = false
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
     *  download reports null and leaves no partial file.
     *
     *  1.75: the download is recorded as a pending download
     *  BEFORE it starts (state DOWNLOADING) and re-recorded on
     *  finish (READY) or failure (FAILED), so the Settings box
     *  can show it on every visit until the new version is
     *  installed. The thread is a daemon on the application
     *  context, so leaving Settings (or the dialog closing)
     *  never stops it. [origin] is "settings" or "home": which
     *  flow started it (stored in the record). */
    fun downloadApk(
        ctx: Context,
        release: UpdateCheckerPure.ReleaseInfo,
        onProgress: (Long, Long, Int) -> Unit,
        onDone: (File?) -> Unit,
        origin: String = "settings"
    ) {
        val appCtx = ctx.applicationContext
        val startTotal = release.apkSize ?: -1L
        liveDownloading = true
        liveDownloadTag = release.tag
        liveDownloaded = 0L
        liveTotal = startTotal
        markPending(
            appCtx, release, UpdateDownloadPure.PendingState.DOWNLOADING,
            0L, startTotal, origin
        )
        // The pending record's byte counts are re-saved at most
        // once a second during the download (prefs writes on
        // every progress tick would churn); the live snapshot
        // above is always current for the polling box.
        var lastPendingSaveMs = 0L
        val trackedProgress: (Long, Long, Int) -> Unit = { d, t, percent ->
            liveDownloaded = d
            liveTotal = t
            val now = System.currentTimeMillis()
            if (now - lastPendingSaveMs >= 1000L) {
                lastPendingSaveMs = now
                markPending(
                    appCtx, release, UpdateDownloadPure.PendingState.DOWNLOADING,
                    d, t, origin
                )
            }
            try {
                onProgress(d, t, percent)
            } catch (_: Exception) {
            }
        }
        Thread {
            val file = try {
                downloadApkSync(appCtx, release, trackedProgress)
            } catch (_: Exception) {
                null
            }
            liveDownloading = false
            if (file != null) {
                markPending(
                    appCtx, release, UpdateDownloadPure.PendingState.READY,
                    file.length(), file.length(), origin
                )
            } else {
                markPending(
                    appCtx, release, UpdateDownloadPure.PendingState.FAILED,
                    liveDownloaded, liveTotal, origin
                )
            }
            mainHandler.post {
                try {
                    onDone(file)
                } catch (_: Exception) {
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /** 1.75: the actual bytes now come from the parallel
     *  multi-part downloader (FastApkDownloader — 4 concurrent
     *  Range streams, 64 KiB buffer, single-stream fallback);
     *  the old 8 KiB single-stream loop is gone. */
    private fun downloadApkSync(
        appCtx: Context,
        release: UpdateCheckerPure.ReleaseInfo,
        onProgress: (Long, Long, Int) -> Unit
    ): File? {
        return try {
            FastApkDownloader.downloadApkFile(appCtx, release, onProgress)
        } catch (_: Exception) {
            null
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
