package com.renamer

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** 1.75 (worker B): the FASTER update downloader + the persistent
 *  Settings download box — the pure half. No Android imports, so
 *  it is JVM-testable standalone (see TestMain175B.kt), including
 *  a REAL multi-part download against an embedded HTTP server.
 *
 *  DESIGN
 *   - Parallel multi-part: [PART_COUNT] (4) concurrent streams,
 *     each fetching one byte range with an HTTP `Range` header and
 *     writing its own part file; the parts are merged IN ORDER
 *     into the final file. GitHub release assets serve Range
 *     (via the CDN the browser_download_url redirects to), so the
 *     4 streams run in parallel over one connection each instead
 *     of one stream doing all the work.
 *   - Probe first: one tiny GET `Range: bytes=0-0`. A 206 answer
 *     PROVES Range support (a 200, or a missing/incorrect
 *     Content-Range, does not). Only then is multi-part used,
 *     and only for files >= [MIN_MULTIPART_SIZE] (256 KiB) — a
 *     small file gains nothing from splitting and would pay 4
 *     connection setups for it.
 *   - Fallback: any probe that is not a clean 206, any part that
 *     fails all its retries, or an unknown total -> the classic
 *     single stream. A failed part never produces a Franken-APK:
 *     the whole download fails and every partial/part file is
 *     deleted, exactly like 1.73 did for a failed single stream.
 *   - Buffer: 64 KiB ([BUFFER_SIZE]) reads/writes everywhere,
 *     up from the 8 KiB of 1.73 — fewer syscalls per megabyte.
 *   - Redirects: followed MANUALLY (up to [MAX_REDIRECTS]),
 *     re-applying the Range + User-Agent headers on every hop.
 *     HttpURLConnection's automatic redirect handling does not
 *     reliably resend custom headers, and the GitHub asset URL
 *     always redirects (github.com -> the object store), so a
 *     Range request that silently lost its header on the hop
 *     would come back 200 and defeat the whole point.
 *   - Progress stays exact: one AtomicLong counts every byte
 *     written by every part; percent is bytes/total via
 *     [UpdateCheckerPure.percent] (so a 4-part download reports
 *     the same 0..100 as the old single stream, never 4x100).
 *     Reports are throttled to [PROGRESS_THROTTLE_MS] (150 ms)
 *     plus a final report, like 1.73.
 *   - A download whose size disagrees with the declared total
 *     is deleted and reported as failed — a truncated APK is
 *     never offered for install (same rule as 1.73).
 *
 *  PERSISTENCE (the Settings box): a pending download is one
 *  encoded string in prefs (see [encode]/[decode]) — tag, apk
 *  name/url/size, state, downloaded/total bytes, origin
 *  ("settings" | "home"). The box's action follows
 *  [resolveAction]: READY + file on disk with the declared size
 *  -> INSTALL; DOWNLOADING -> show progress; FAILED -> RETRY;
 *  READY but the file gone/wrong size -> STALE (the caller
 *  clears it). [shouldClearAsInstalled] drops the record once
 *  the installed version is >= the pending tag — that is what
 *  makes the box disappear after the user actually installs.
 *  A DOWNLOADING record with no live download behind it (the
 *  process died mid-download) is the interrupted state: the box
 *  shows it and offers a fresh retry, never a resume of a
 *  half-written file. */
object UpdateDownloadPure {

    /** Concurrent Range streams for one download. */
    const val PART_COUNT = 4

    /** Read/write buffer everywhere (was 8 KiB in 1.73). */
    const val BUFFER_SIZE = 65536

    /** Only split files at least this big; smaller files are not
     *  worth 4 connection setups. */
    const val MIN_MULTIPART_SIZE = 256L * 1024L

    /** Progress reports at most this often (plus a final one). */
    const val PROGRESS_THROTTLE_MS = 150L

    /** Per-part attempts before the whole download fails. */
    const val MAX_PART_ATTEMPTS = 3

    /** Manual redirect hops allowed (asset URL -> CDN). */
    const val MAX_REDIRECTS = 5

    const val CONNECT_TIMEOUT_MS = 15000
    const val READ_TIMEOUT_MS = 30000

    // ---------- ranges ----------

    /** One byte range, both ends inclusive: [start]..[endInclusive]. */
    data class PartRange(val start: Long, val endInclusive: Long) {
        val size: Long get() = endInclusive - start + 1
    }

    /** Split [total] bytes into [parts] contiguous ranges that
     *  cover 0..total-1 exactly once: no gaps, no overlaps, the
     *  last part takes the remainder. Empty for total <= 0. */
    fun partRanges(total: Long, parts: Int = PART_COUNT): List<PartRange> {
        if (total <= 0 || parts <= 0) return emptyList()
        // total fits an APK (far below Int range); guard anyway.
        val count = if (total < parts) total.toInt() else parts
        val base = total / count
        val extra = (total % count).toInt()
        val out = ArrayList<PartRange>(count)
        var start = 0L
        for (i in 0 until count) {
            val size = base + if (i < extra) 1 else 0
            out += PartRange(start, start + size - 1)
            start += size
        }
        return out
    }

    /** True only for a PROVEN Range answer: HTTP 206. A 200 with
     *  `Accept-Ranges: bytes` advertises support but does not
     *  prove this server honours it for this URL. */
    fun supportsRange(responseCode: Int, @Suppress("UNUSED_PARAMETER") acceptRanges: String?): Boolean =
        responseCode == 206

    /** Multi-part only when Range is proven AND the file is big
     *  enough to be worth splitting. */
    fun shouldUseMultipart(total: Long, rangeSupported: Boolean): Boolean =
        rangeSupported && total >= MIN_MULTIPART_SIZE

    /** Total from a `Content-Range: bytes 0-0/<total>` header, or
     *  null when the header is absent/malformed. */
    fun parseContentRangeTotal(contentRange: String?): Long? {
        val h = contentRange?.trim().orEmpty()
        if (h.isEmpty()) return null
        val slash = h.lastIndexOf('/')
        if (slash < 0) return null
        return h.substring(slash + 1).trim().toLongOrNull()?.takeIf { it > 0 }
    }

    /** Concatenate part payloads IN ORDER — the byte-exact merge
     *  the downloader must produce. */
    fun mergePartBytes(parts: List<ByteArray>): ByteArray {
        var total = 0
        for (p in parts) total += p.size
        val out = ByteArray(total)
        var pos = 0
        for (p in parts) {
            p.copyInto(out, pos)
            pos += p.size
        }
        return out
    }

    /** Stream-copy part files IN ORDER into [outFile] (64 KiB
     *  buffer). False on any I/O failure. */
    fun assembleParts(partFiles: List<File>, outFile: File): Boolean {
        return try {
            val buf = ByteArray(BUFFER_SIZE)
            FileOutputStream(outFile).use { output ->
                for (part in partFiles) {
                    FileInputStream(part).use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                        }
                    }
                }
                output.flush()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    // ---------- the download ----------

    data class DownloadResult(
        val success: Boolean,
        val bytes: Long,
        val usedMultipart: Boolean,
        val total: Long
    )

    private data class Probe(val total: Long, val rangeSupported: Boolean)

    /** Open [urlStr] as a GET, following redirects manually and
     *  re-applying the Range header on every hop. Returns the
     *  final connected connection (caller reads/disconnects it),
     *  or null when no usable response comes back.
     *
     *  TEST SEAM: the call goes through [connectionOpener], which
     *  production leaves on the real implementation below. JVM
     *  tests replace it with a fake transport (this sandbox
     *  blocks JVM loopback TCP, so a real embedded server cannot
     *  be reached from a test JVM — see TestMain175B). */
    @Volatile
    internal var connectionOpener: (String, Long?, Long?) -> HttpURLConnection? =
        { url, start, end -> openConnectionReal(url, start, end) }

    private fun openConnection(
        urlStr: String,
        rangeStart: Long?,
        rangeEnd: Long?
    ): HttpURLConnection? = connectionOpener(urlStr, rangeStart, rangeEnd)

    private fun openConnectionReal(
        urlStr: String,
        rangeStart: Long?,
        rangeEnd: Long?
    ): HttpURLConnection? {
        var current = urlStr
        var redirects = 0
        while (true) {
            val conn = try {
                URL(current).openConnection() as HttpURLConnection
            } catch (_: Exception) {
                return null
            }
            conn.instanceFollowRedirects = false
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", UpdateCheckerPure.USER_AGENT)
            if (rangeStart != null) {
                val range = if (rangeEnd != null) "bytes=$rangeStart-$rangeEnd"
                else "bytes=$rangeStart-"
                conn.setRequestProperty("Range", range)
            }
            val code = try {
                conn.responseCode
            } catch (_: Exception) {
                try {
                    conn.disconnect()
                } catch (_: Exception) {
                }
                return null
            }
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                val loc = conn.getHeaderField("Location")
                try {
                    conn.disconnect()
                } catch (_: Exception) {
                }
                if (loc.isNullOrBlank() || redirects >= MAX_REDIRECTS) return null
                current = try {
                    URL(URL(current), loc).toString()
                } catch (_: Exception) {
                    return null
                }
                redirects++
                continue
            }
            return conn
        }
    }

    /** One tiny `Range: bytes=0-0` GET: 206 proves Range support
     *  and (via Content-Range) gives the total; 200 gives the
     *  total with no Range; anything else gives neither. */
    private fun probe(url: String): Probe {
        val conn = openConnection(url, 0, 0) ?: return Probe(-1, false)
        try {
            val code = conn.responseCode
            if (code == 206) {
                val total = parseContentRangeTotal(conn.getHeaderField("Content-Range")) ?: -1L
                return Probe(total, true)
            }
            if (code == 200) {
                val len = conn.contentLengthLong
                return Probe(if (len > 0) len else -1L, false)
            }
            return Probe(-1, false)
        } catch (_: Exception) {
            return Probe(-1, false)
        } finally {
            try {
                conn.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /** Download [url] into [outFile] (overwritten). Progress is
     *  (downloadedBytes, totalBytesOrMinusOne, percentOrMinusOne),
     *  throttled to [PROGRESS_THROTTLE_MS] plus a final report.
     *  A failed download deletes the partial file (and any part
     *  files) and reports success=false. Never throws. */
    fun download(
        url: String,
        outFile: File,
        onProgress: (Long, Long, Int) -> Unit = { _, _, _ -> }
    ): DownloadResult {
        try {
            outFile.parentFile?.mkdirs()
        } catch (_: Exception) {
        }
        val probeResult = probe(url)
        val total = probeResult.total
        val useMultipart = shouldUseMultipart(total, probeResult.rangeSupported)
        val downloaded = AtomicLong(0)
        val reporter = ProgressReporter(onProgress)
        val ok = if (useMultipart) {
            downloadMultipart(url, outFile, total, downloaded, reporter)
        } else {
            downloadSingle(url, outFile, downloaded, reporter)
        }
        reporter.reportFinal(downloaded.get(), total)
        if (!ok) {
            deleteQuietly(outFile)
            return DownloadResult(false, 0, useMultipart, total)
        }
        val size = try {
            outFile.length()
        } catch (_: Exception) {
            -1L
        }
        // Truncated (or empty) is a failure, never an installable file.
        if (size <= 0 || (total > 0 && size != total)) {
            deleteQuietly(outFile)
            return DownloadResult(false, 0, useMultipart, total)
        }
        return DownloadResult(true, size, useMultipart, total)
    }

    private class ProgressReporter(private val onProgress: (Long, Long, Int) -> Unit) {
        private var lastPostMs = 0L

        @Synchronized
        fun report(downloaded: Long, total: Long) {
            val now = System.currentTimeMillis()
            if (now - lastPostMs < PROGRESS_THROTTLE_MS && downloaded != total) return
            lastPostMs = now
            emit(downloaded, total)
        }

        @Synchronized
        fun reportFinal(downloaded: Long, total: Long) {
            lastPostMs = System.currentTimeMillis()
            emit(downloaded, total)
        }

        private fun emit(downloaded: Long, total: Long) {
            try {
                onProgress(downloaded, total, UpdateCheckerPure.percent(downloaded, total))
            } catch (_: Exception) {
            }
        }
    }

    private fun downloadSingle(
        url: String,
        outFile: File,
        downloaded: AtomicLong,
        reporter: ProgressReporter
    ): Boolean {
        val conn = openConnection(url, null, null) ?: return false
        try {
            if (conn.responseCode != 200 && conn.responseCode != 206) return false
            val buf = ByteArray(BUFFER_SIZE)
            conn.inputStream.use { input ->
                FileOutputStream(outFile).use { output ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        val d = downloaded.addAndGet(n.toLong())
                        reporter.report(d, conn.contentLengthLong.let { if (it > 0) it else -1L })
                    }
                    output.flush()
                }
            }
            return true
        } catch (_: Exception) {
            return false
        } finally {
            try {
                conn.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun downloadMultipart(
        url: String,
        outFile: File,
        total: Long,
        downloaded: AtomicLong,
        reporter: ProgressReporter
    ): Boolean {
        val ranges = partRanges(total, PART_COUNT)
        if (ranges.isEmpty()) return false
        val parent = outFile.parentFile ?: return false
        val partFiles = ranges.mapIndexed { i, _ ->
            File(parent, outFile.name + ".part$i")
        }
        val failed = AtomicBoolean(false)
        val pool = Executors.newFixedThreadPool(ranges.size)
        val latch = CountDownLatch(ranges.size)
        try {
            for (i in ranges.indices) {
                val range = ranges[i]
                val partFile = partFiles[i]
                pool.execute {
                    try {
                        var attemptOk = false
                        var attempt = 0
                        while (!attemptOk && attempt < MAX_PART_ATTEMPTS && !failed.get()) {
                            attempt++
                            attemptOk = downloadOnePart(
                                url, range, partFile, downloaded, reporter, total
                            )
                        }
                        if (!attemptOk) failed.set(true)
                    } catch (_: Exception) {
                        failed.set(true)
                    } finally {
                        latch.countDown()
                    }
                }
            }
            try {
                latch.await()
            } catch (_: InterruptedException) {
                failed.set(true)
            }
        } finally {
            pool.shutdownNow()
        }
        if (failed.get()) {
            partFiles.forEach { deleteQuietly(it) }
            return false
        }
        val merged = assembleParts(partFiles, outFile)
        partFiles.forEach { deleteQuietly(it) }
        return merged
    }

    /** One part: GET its exact Range, expect 206, read exactly
     *  [range.size] bytes into [partFile]. False on any shortfall
     *  (the caller retries the part). */
    private fun downloadOnePart(
        url: String,
        range: PartRange,
        partFile: File,
        downloaded: AtomicLong,
        reporter: ProgressReporter,
        total: Long
    ): Boolean {
        val conn = openConnection(url, range.start, range.endInclusive) ?: return false
        try {
            if (conn.responseCode != 206) return false
            val buf = ByteArray(BUFFER_SIZE)
            var remaining = range.size
            conn.inputStream.use { input ->
                FileOutputStream(partFile).use { output ->
                    while (remaining > 0) {
                        val want = minOf(buf.size.toLong(), remaining).toInt()
                        val n = input.read(buf, 0, want)
                        if (n < 0) return false
                        output.write(buf, 0, n)
                        remaining -= n
                        val d = downloaded.addAndGet(n.toLong())
                        reporter.report(d, total)
                    }
                    output.flush()
                }
            }
            return remaining == 0L
        } catch (_: Exception) {
            return false
        } finally {
            try {
                conn.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun deleteQuietly(f: File) {
        try {
            f.delete()
        } catch (_: Exception) {
        }
    }

    // ---------- persistence: the pending download ----------

    /** The box's state machine. DOWNLOADING = a download is (or
     *  was) in flight; READY = file on disk, tap to install;
     *  FAILED = offer a retry. */
    enum class PendingState { DOWNLOADING, READY, FAILED }

    /** What the Settings box should do for a pending record. */
    enum class PendingAction { INSTALL, DOWNLOADING, RETRY, NONE }

    /** One pending download, as stored in prefs (one encoded
     *  string — see [encode]). [origin] is "settings" or "home":
     *  which flow started it. */
    data class PendingDownload(
        val tag: String,
        val apkName: String?,
        val apkUrl: String?,
        val apkSize: Long?,
        val state: PendingState,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val origin: String
    ) {
        /** The release this pending download belongs to. */
        fun toRelease(): UpdateCheckerPure.ReleaseInfo =
            UpdateCheckerPure.ReleaseInfo(tag, tag, apkName, apkUrl, apkSize)
    }

    private const val ENCODE_VERSION = "v1"
    private const val ENCODE_FIELDS = 9

    private fun enc(s: String?): String =
        URLEncoder.encode(s ?: "", "UTF-8")

    private fun dec(s: String): String =
        URLDecoder.decode(s, "UTF-8")

    /** "v1|<tag>|<apkName>|<apkUrl>|<apkSize>|<state>|<downloaded>|
     *  <total>|<origin>", string fields URL-encoded so a `|` or
     *  odd byte in a URL/name can never break the split. */
    fun encode(p: PendingDownload): String {
        return listOf(
            ENCODE_VERSION,
            enc(p.tag),
            enc(p.apkName),
            enc(p.apkUrl),
            p.apkSize?.toString() ?: "",
            p.state.name,
            p.downloadedBytes.toString(),
            p.totalBytes.toString(),
            enc(p.origin)
        ).joinToString("|")
    }

    /** Null on anything that is not a well-formed v1 record
     *  (garbage, wrong version, wrong field count, bad numbers,
     *  unknown state, blank tag/url). A corrupt record is never
     *  treated as a download. */
    fun decode(s: String?): PendingDownload? {
        try {
            if (s.isNullOrBlank()) return null
            val parts = s.split("|")
            if (parts.size != ENCODE_FIELDS) return null
            if (parts[0] != ENCODE_VERSION) return null
            val tag = dec(parts[1])
            val apkName = dec(parts[2]).ifBlank { null }
            val apkUrl = dec(parts[3]).ifBlank { null }
            if (tag.isBlank() || apkUrl.isNullOrBlank()) return null
            val apkSize = parts[4].ifBlank { null }?.toLongOrNull()
                ?: if (parts[4].isBlank()) null else return null
            val state = try {
                PendingState.valueOf(parts[5])
            } catch (_: Exception) {
                return null
            }
            val downloaded = parts[6].toLongOrNull() ?: return null
            val total = parts[7].toLongOrNull() ?: return null
            val origin = dec(parts[8]).ifBlank { "settings" }
            if (downloaded < 0 || total < -1) return null
            return PendingDownload(tag, apkName, apkUrl, apkSize, state, downloaded, total, origin)
        } catch (_: Exception) {
            return null
        }
    }

    /** The box's action for a pending record, given the file on
     *  disk: READY counts as INSTALL only when the file exists
     *  AND matches the declared size (when one is known). A
     *  READY record whose file is gone or the wrong size is
     *  STALE -> NONE (the caller clears it); it must never offer
     *  a phantom install. */
    fun resolveAction(
        pending: PendingDownload?,
        fileExists: Boolean,
        fileSize: Long
    ): PendingAction {
        if (pending == null) return PendingAction.NONE
        return when (pending.state) {
            PendingState.DOWNLOADING -> PendingAction.DOWNLOADING
            PendingState.FAILED -> PendingAction.RETRY
            PendingState.READY -> {
                if (!fileExists) return PendingAction.NONE
                if (pending.apkSize != null && pending.apkSize > 0 &&
                    fileSize != pending.apkSize
                ) return PendingAction.NONE
                PendingAction.INSTALL
            }
        }
    }

    /** True when the installed version is already >= the pending
     *  tag — the user installed it, so the record (and the box)
     *  goes away. Malformed versions never clear a record. */
    fun shouldClearAsInstalled(installedVersionName: String, pending: PendingDownload): Boolean {
        val cmp = UpdateCheckerPure.compareVersions(pending.tag, installedVersionName)
            ?: return false
        return cmp <= 0
    }
}
