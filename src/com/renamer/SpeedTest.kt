package com.renamer

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** v1.16: the speed-test box's engine — measures the user's
 *  CURRENT internet (whatever network is active right now,
 *  VPN or not) through speedtest.net's public test flow,
 *  in the background with plain HTTP(S). No WebView, no
 *  provider page, no captcha, ever.
 *
 *  Flow (the classic speedtest.net flow, verified live
 *  2026-11-17):
 *   1. GET https://www.speedtest.net/speedtest-config.php
 *      -> the client's own lat/lon.
 *   2. GET https://www.speedtest.net/speedtest-servers-static.php
 *      -> the server list; the nearest ones are tried first.
 *   3. Per server: ping = the fastest of 3 GETs of
 *      <base>latency.txt; download = 4 parallel GETs of
 *      <base>random1000x1000.jpg (~2 MB each) until a 6s
 *      deadline (or the early exit); upload = 4 parallel
 *      POSTs of 512 KB to <base>upload.php until a 5s
 *      deadline (or the early exit).
 *  The servers answer the first hop with a 307 redirect to
 *  https://server-<id>.prod.hosts.ooklaserver.net:8080/...,
 *  which is followed manually (up to 5 hops); a dead server
 *  (500/timeout) is skipped for the next nearest one. Any
 *  failure of the whole flow returns null — the box shows
 *  its failure state and a retry. Everything has a timeout;
 *  the whole run is bounded by ~20s (v1.17, was ~75s).
 *  A Canceller aborts a run at once (see below) — the
 *  activity cancels the run the moment the default
 *  network changes (VPN on/off, IP change). */
object SpeedTest {

    data class Result(val downloadMbps: Double, val uploadMbps: Double, val pingMs: Long)

    /** Posted back on the calling thread — the activity
     *  forwards each value to the UI as it lands. */
    interface Callback {
        fun onPing(ms: Long)
        fun onDownload(mbps: Double)
        fun onUpload(mbps: Double)
    }

    private const val CONFIG_URL = "https://www.speedtest.net/speedtest-config.php"
    private const val SERVERS_URL = "https://www.speedtest.net/speedtest-servers-static.php"
    private const val MAX_REDIRECTS = 5
    // v1.17: much faster — ~20s total bound (was ~75s).
    // Ping = fastest of 3 (was 5), download ~6s (was
    // 12s), upload ~5s (was 10s); each phase also stops
    // early once enough samples exist (see the early-exit
    // thresholds below).
    private const val PING_TRIES = 3
    private const val DL_THREADS = 4
    private const val DL_SECONDS = 6L
    private const val UL_THREADS = 4
    private const val UL_SECONDS = 5L
    private const val UL_CHUNK = 512 * 1024
    private const val MAX_SERVERS_TRIED = 3
    private const val RUN_DEADLINE_MS = 20_000L
    /** A phase stops early once this much arrived AND
     *  3s elapsed (a fast line finishes in ~3s instead
     *  of the full window); a slow line still gets the
     *  full window. */
    private const val DL_EARLY_BYTES = 6_000_000L
    private const val UL_EARLY_BYTES = 2_000_000L
    private const val EARLY_SECONDS = 3L
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    /** v1.17: a run's cancel handle. cancel() sets the
     *  flag AND disconnects every connection the run
     *  currently holds open, so a thread blocked in a
     *  read/connect aborts at once instead of waiting
     *  out its socket timeout — no phase keeps running
     *  after the user switches VPN/network. The
     *  activity owns one Canceller per run and cancels
     *  it the moment the default network changes. */
    class Canceller {
        @Volatile var cancelled = false
        private val conns = java.util.Collections.synchronizedSet(HashSet<HttpURLConnection>())
        fun register(c: HttpURLConnection) {
            if (!cancelled) conns += c
        }
        fun cancel() {
            cancelled = true
            val copy: List<HttpURLConnection>
            synchronized(conns) { copy = conns.toList(); conns.clear() }
            for (c in copy) { try { c.disconnect() } catch (_: Exception) {} }
        }
    }

    private data class Server(val base: String, val distKm: Double)

    fun run(cb: Callback, cancel: Canceller? = null): Result? {
        val runEnd = System.currentTimeMillis() + RUN_DEADLINE_MS
        // 1. Where am I (the client's own lat/lon)?
        val cfg = getBytes(CONFIG_URL, 5000, 5000, cancel) ?: return null
        if (cancel?.cancelled == true) return null
        val cfgText = try { String(cfg, Charsets.UTF_8) } catch (_: Exception) { return null }
        val myLat = attr(cfgText, "lat")?.toDoubleOrNull() ?: return null
        val myLon = attr(cfgText, "lon")?.toDoubleOrNull() ?: return null
        // 2. The server list; nearest first.
        if (System.currentTimeMillis() > runEnd) return null
        if (cancel?.cancelled == true) return null
        val srv = getBytes(SERVERS_URL, 5000, 8000, cancel) ?: return null
        if (cancel?.cancelled == true) return null
        val srvText = try { String(srv, Charsets.UTF_8) } catch (_: Exception) { return null }
        val servers = parseServers(srvText, myLat, myLon).take(MAX_SERVERS_TRIED)
        if (servers.isEmpty()) return null
        // 3. Nearest first — a dead server is skipped.
        for (s in servers) {
            if (System.currentTimeMillis() > runEnd) return null
            if (cancel?.cancelled == true) return null
            val r = testServer(s.base, runEnd, cb, cancel) ?: continue
            return r
        }
        return null
    }

    private fun testServer(base: String, runEnd: Long, cb: Callback, cancel: Canceller?): Result? {
        // Ping: the fastest of 3 latency.txt round trips.
        var bestPing = Long.MAX_VALUE
        var ok = 0
        repeat(PING_TRIES) {
            if (System.currentTimeMillis() > runEnd) return null
            if (cancel?.cancelled == true) return null
            val ms = timedGetMs(base + "latency.txt", 2500, 4000, cancel)
            if (ms >= 0) { ok++; if (ms < bestPing) bestPing = ms }
        }
        if (ok == 0) return null
        cb.onPing(bestPing)
        // Download, then upload — each posts its value live.
        if (System.currentTimeMillis() > runEnd) return null
        if (cancel?.cancelled == true) return null
        val dl = downloadMbps(base, runEnd, cancel) ?: return null
        cb.onDownload(dl)
        if (System.currentTimeMillis() > runEnd) return null
        if (cancel?.cancelled == true) return null
        val ul = uploadMbps(base, runEnd, cancel) ?: return null
        cb.onUpload(ul)
        return Result(dl, ul, bestPing)
    }

    /** One latency.txt round trip (redirects included),
     *  in ms, or -1 on any failure. */
    private fun timedGetMs(url: String, connectTimeout: Int, readTimeout: Int, cancel: Canceller?): Long {
        val t0 = System.nanoTime()
        var conn: HttpURLConnection? = null
        return try {
            conn = openFollowed(url, "GET", null, connectTimeout, readTimeout, cancel) ?: return -1
            if (conn.responseCode !in 200..299) return -1
            try {
                val inp = conn.inputStream
                val buf = ByteArray(256)
                inp.read(buf)
                inp.close()
            } catch (_: Exception) {}
            (System.nanoTime() - t0) / 1_000_000
        } catch (_: Exception) { -1 } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /** 4 parallel downloads of the ~2 MB random file until
     *  the 6s deadline (or the early exit: >= 6 MB and
     *  >= 3s); MB/s over the real active window. */
    private fun downloadMbps(base: String, runEnd: Long, cancel: Canceller?): Double? {
        val deadline = minOf(System.currentTimeMillis() + DL_SECONDS * 1000, runEnd)
        val totalBytes = AtomicLong(0)
        val finish = AtomicLong(0)
        val stopEarly = java.util.concurrent.atomic.AtomicBoolean(false)
        val latch = CountDownLatch(DL_THREADS)
        val t0 = System.nanoTime()
        repeat(DL_THREADS) {
            Thread {
                try {
                    val buf = ByteArray(16384)
                    while (System.currentTimeMillis() < deadline &&
                        cancel?.cancelled != true && !stopEarly.get()) {
                        var conn: HttpURLConnection? = null
                        try {
                            conn = openFollowed(base + "random1000x1000.jpg", "GET", null, 5000, 5000, cancel) ?: break
                            if (conn.responseCode !in 200..299) break
                            val inp = conn.inputStream
                            while (System.currentTimeMillis() < deadline &&
                                cancel?.cancelled != true && !stopEarly.get()) {
                                val n = try { inp.read(buf) } catch (_: Exception) { -1 }
                                if (n < 0) break
                                totalBytes.addAndGet(n.toLong())
                                // v1.17: early exit — enough samples.
                                if (totalBytes.get() >= DL_EARLY_BYTES &&
                                    System.nanoTime() - t0 >= EARLY_SECONDS * 1_000_000_000L) {
                                    stopEarly.set(true)
                                    break
                                }
                            }
                            try { inp.close() } catch (_: Exception) {}
                        } catch (_: Exception) { break } finally {
                            try { conn?.disconnect() } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {} finally {
                    finish.accumulateAndGet(System.nanoTime()) { a, b -> maxOf(a, b) }
                    latch.countDown()
                }
            }.apply { isDaemon = true }.start()
        }
        try { latch.await(DL_SECONDS + 6, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
        if (cancel?.cancelled == true) return null
        val end = if (finish.get() > 0) finish.get() else System.nanoTime()
        val secs = (end - t0) / 1e9
        val bytes = totalBytes.get()
        if (secs <= 0 || bytes < 50_000) return null
        return bytes * 8.0 / secs / 1_000_000.0
    }

    /** 4 parallel POSTs of a fixed 512 KB payload until the
     *  5s deadline (or the early exit: >= 2 MB and >= 3s);
     *  MB/s over the real active window. Only a POST the
     *  server answered 2xx for is counted. */
    private fun uploadMbps(base: String, runEnd: Long, cancel: Canceller?): Double? {
        val payload = ByteArray(UL_CHUNK) { (it % 251).toByte() }
        val deadline = minOf(System.currentTimeMillis() + UL_SECONDS * 1000, runEnd)
        val totalBytes = AtomicLong(0)
        val finish = AtomicLong(0)
        val stopEarly = java.util.concurrent.atomic.AtomicBoolean(false)
        val latch = CountDownLatch(UL_THREADS)
        val t0 = System.nanoTime()
        repeat(UL_THREADS) {
            Thread {
                try {
                    while (System.currentTimeMillis() < deadline &&
                        cancel?.cancelled != true && !stopEarly.get()) {
                        var conn: HttpURLConnection? = null
                        try {
                            conn = openFollowed(base + "upload.php", "POST", payload, 5000, 10_000, cancel) ?: break
                            if (conn.responseCode !in 200..299) break
                            totalBytes.addAndGet(payload.size.toLong())
                            if (totalBytes.get() >= UL_EARLY_BYTES &&
                                System.nanoTime() - t0 >= EARLY_SECONDS * 1_000_000_000L) {
                                stopEarly.set(true)
                            } else {
                                try { conn.inputStream.readBytes() } catch (_: Exception) {}
                            }
                        } catch (_: Exception) { break } finally {
                            try { conn?.disconnect() } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {} finally {
                    finish.accumulateAndGet(System.nanoTime()) { a, b -> maxOf(a, b) }
                    latch.countDown()
                }
            }.apply { isDaemon = true }.start()
        }
        try { latch.await(UL_SECONDS + 8, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
        if (cancel?.cancelled == true) return null
        val end = if (finish.get() > 0) finish.get() else System.nanoTime()
        val secs = (end - t0) / 1e9
        val bytes = totalBytes.get()
        if (secs <= 0 || bytes < 50_000) return null
        return bytes * 8.0 / secs / 1_000_000.0
    }

    /** A GET body, or null on any failure. */
    private fun getBytes(url: String, connectTimeout: Int, readTimeout: Int, cancel: Canceller?): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = openFollowed(url, "GET", null, connectTimeout, readTimeout, cancel) ?: return null
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.readBytes()
        } catch (_: Exception) { null } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /** Open a connection and follow redirects MANUALLY (up
     *  to 5 hops) — HttpURLConnection's automatic redirect
     *  handling is unreliable for POST 307s, and the
     *  speedtest servers answer the first hop with a 307
     *  to their https host. Returns the final connection
     *  (response code already read), or null. The caller
     *  owns the connection and must disconnect it. */
    private fun openFollowed(
        urlStr: String, method: String, body: ByteArray?,
        connectTimeout: Int, readTimeout: Int, cancel: Canceller? = null
    ): HttpURLConnection? {
        var current = urlStr
        repeat(MAX_REDIRECTS + 1) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(current).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    this.connectTimeout = connectTimeout
                    this.readTimeout = readTimeout
                    requestMethod = method
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Accept", "*/*")
                    if (body != null) {
                        doOutput = true
                        setFixedLengthStreamingMode(body.size)
                        setRequestProperty("Content-Type", "application/octet-stream")
                    }
                }
                // v1.17: register BEFORE any I/O, so a cancel
                // aborts even a connect still in progress.
                cancel?.register(conn)
                if (cancel?.cancelled == true) {
                    try { conn.disconnect() } catch (_: Exception) {}
                    return null
                }
                if (body != null) {
                    conn.outputStream.use { it.write(body) }
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                    try { conn.disconnect() } catch (_: Exception) {}
                    if (loc.isNullOrBlank()) return null
                    current = try { URL(URL(current), loc).toString() } catch (_: Exception) { return null }
                } else {
                    return conn
                }
            } catch (_: Exception) {
                try { conn?.disconnect() } catch (_: Exception) {}
                return null
            }
        }
        return null
    }

    /** First name="..." attribute in a tag (the config's
     *  <client> tag carries the client's own lat/lon). */
    private fun attr(text: String, name: String): String? {
        val m = Regex("""$name="([^"]*)"""").find(text) ?: return null
        return m.groupValues.getOrNull(1)
    }

    private fun parseServers(xml: String, myLat: Double, myLon: Double): List<Server> {
        val out = ArrayList<Server>()
        val tagRe = Regex("""<server\s[^>]*/?>""")
        for (m in tagRe.findAll(xml)) {
            val tag = m.value
            val url = attr(tag, "url") ?: continue
            if (!url.endsWith("upload.php")) continue
            val lat = attr(tag, "lat")?.toDoubleOrNull() ?: continue
            val lon = attr(tag, "lon")?.toDoubleOrNull() ?: continue
            val base = url.substring(0, url.length - "upload.php".length)
            out += Server(base, haversineKm(myLat, myLon, lat, lon))
        }
        return out.sortedBy { it.distKm }
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(min(1.0, sqrt(a)))
    }
}
