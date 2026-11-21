package com.renamer

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File

/** 1.75 (worker B): the Android side of the faster downloader.
 *  Thin on purpose: the whole algorithm (probe, 4-way Range
 *  split, merge, fallback, redirect handling, 64 KiB buffer,
 *  size verification) lives in [UpdateDownloadPure] and is
 *  JVM-tested there; this file only owns the Android bits —
 *  the filesDir/update/ location, deleting stale APKs first
 *  (one APK at a time, like 1.73), and posting progress onto
 *  the main thread. Callers run this on a background thread
 *  (see UpdateChecker.downloadApk); it never throws. */
object FastApkDownloader {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Download [release]'s APK into filesDir/update/. Progress
     *  arrives on the main thread as (downloaded, total,
     *  percentOrMinusOne). Returns the file on success, null on
     *  any failure — a failed/partial download leaves no file
     *  behind. */
    fun downloadApkFile(
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
        val outFile = File(dir, UpdateCheckerPure.sanitizeApkName(release.apkName))
        // The pure download runs on the caller's background thread;
        // its progress callback comes from worker threads, so hop
        // to the main thread here before the UI sees it.
        val result = try {
            UpdateDownloadPure.download(url, outFile) { d, t, percent ->
                val dd = d
                val tt = t
                val pp = percent
                mainHandler.post {
                    try {
                        onProgress(dd, tt, pp)
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
            null
        } ?: return null
        if (!result.success) {
            try {
                outFile.delete()
            } catch (_: Exception) {
            }
            return null
        }
        return outFile
    }
}
