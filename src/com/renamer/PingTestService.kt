package com.renamer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ping test that keeps running when the screen turns off or the
 * user leaves the app: a foreground service (notification, channel
 * "ping") holding a PARTIAL_WAKE_LOCK for the duration of the test.
 *
 * Contract with SubscriptionActivity (all in prefs "renamer_subs"):
 *  input  - "ping_test_list": configs to test, newline-joined
 *           (already deduped and filtered by the activity).
 *           "sub_source_map": normalized dedup key -> sub url.
 *           "subs": sub urls, "sub_stats": previous per-sub stats.
 *           "ping_route_count": 1..5 routes (default 5);
 *           "ping_route_url_1..5": the delay URL per route
 *           (all five default to the same gstatic
 *           generate_204 URL, 2.75). A blank or invalid
 *           URL falls back to its default. "ping_resume":
 *           true when this run continues a stopped run (see below).
 *           "ping_speed_test_on": true when the speed-test
 *           checkbox is on (2.77); "ping_speeds": measured
 *           download speeds (MB/s) keyed by dedup key,
 *           merged in batches like ping_delays.
 *  live   - "ping_healthy_live": healthy configs, newline-joined,
 *           appended in completion order as each one is found
 *           (flushed in batches, not per config).
 *           "ping_live_sub": per-sub healthy counts (JSON
 *           url -> count), bumped per find, like the live list.
 *           "ping_progress_done" / "ping_progress_total" /
 *           "ping_progress_healthy": ints, updated in batches
 *           (every ~50 configs or ~1 s, never per config).
 *           "ping_delays": measured delays keyed by dedup key,
 *           MERGED in batches (never replaced), so a resume
 *           keeps the earlier segments' delays.
 *  output - "ping_done": true when finished (or failed/stopped
 *           with an error in "ping_error"). "ping_running": true
 *           only while the test is actually running.
 *           "ping_stopped": true when the test was stopped
 *           mid-run; "ping_remaining": the configs not yet
 *           tested, so the activity can resume exactly them.
 *           "ping_full_stop": set while a full stop is in
 *           flight; "ping_cancelled": set after a full stop
 *           (no resume; see the stop-branch below).
 *           "last_healthy": final healthy list, sorted by delay.
 *           "box2": same final list (original names; the activity
 *           applies the desired name on top when it resumes).
 *           "sub_stats": per-sub received/healthy JSON, updated.
 *           "ping_applied": false at start; the activity sets it
 *           true once it has applied the final result to the UI.
 *
 * Multi-route (2.74): the list is split into N contiguous
 * chunks (N = route count); each chunk runs in its own pool
 * (concurrency 16 per pool, as before) with its own route URL,
 * all pools simultaneously. Every route measures exactly like
 * the primary one: same speedtest Xray config, same
 * measureOutboundDelay, same 200/204 rule, same TCP pre-check.
 * With 1 route the behaviour is identical to before.
 * (2.75: all routes default to the same URL —
 * https://www.gstatic.com/generate_204 — so a config
 * green here is green in v2ray. 2.77: five routes,
 * count 1..5, default 5.)
 *
 * The per-config measurement is the same v2rayNG logic as
 * SubscriptionActivity.measureOne: TCP pre-check for simple types,
 * then Libv2ray.measureOutboundDelay with the route's test URL.
 * XraySpeedtest.kt gained measureOneWithUrl for this
 * (2.75: TEST_URL3 removed; all routes use gstatic).
 */
class PingTestService : Service() {

    companion object {
        const val PREFS = "renamer_subs"
        const val CHANNEL_ID = "ping"
        const val NOTIF_ID = 1001
        // 2.74 multi-route defaults; 2.75: all routes are
        // the SAME URL (gstatic generate_204), so a config
        // green here is green in v2ray. 2.77: five routes.
        const val ROUTE_URL_1_DEFAULT = "https://www.gstatic.com/generate_204"
        const val ROUTE_URL_2_DEFAULT = "https://www.gstatic.com/generate_204"
        const val ROUTE_URL_3_DEFAULT = "https://www.gstatic.com/generate_204"
        const val ROUTE_URL_4_DEFAULT = "https://www.gstatic.com/generate_204"
        const val ROUTE_URL_5_DEFAULT = "https://www.gstatic.com/generate_204"
    }

    @Volatile private var worker: Thread? = null
    @Volatile private var stopped = false
    /** FULL STOP (2.75): set by the FULL_STOP action; when true the
     *  stop-branch writes NO remaining state — the test is gone,
     *  not paused. Reset on every fresh start. */
    @Volatile private var cancelAll = false
    private var wakeLock: PowerManager.WakeLock? = null

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)

    override fun onBind(intent: Intent?): IBinder? = null

    private fun applySpeedtestPrefs() {
        try {
            val sp = getSharedPreferences("renamer_vpn", MODE_PRIVATE)
            XraySpeedtest.testUrlOverride = sp.getString("delay_test_url", null)?.takeIf { it.isNotBlank() }
            XraySpeedtest.concurrencyOverride = sp.getString("delay_test_concurrency", null)?.toIntOrNull()
        } catch (_: Exception) {}
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // FULL STOP (2.75): cancel everything, write NO remaining
        // state. Handled before the alive-check and before
        // "stopped = false", so it also works when the worker
        // already finished (paused case) or is mid-stop.
        if (intent?.action == "FULL_STOP") {
            cancelAll = true
            stopped = true
            return START_NOT_STICKY
        }
        applySpeedtestPrefs()
        if (worker?.isAlive == true) return START_NOT_STICKY
        stopped = false
        cancelAll = false
        startForegroundWithNotification(0, 0)
        val wl = try {
            (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "renamer:ping")
        } catch (_: Throwable) { null }
        wakeLock = wl
        try { wl?.acquire() } catch (_: Throwable) {}
        worker = Thread { runTest() }.also { it.start() }
        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification(done: Int, total: Int) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    L("تست پینگ", "Ping test"),
                    NotificationManager.IMPORTANCE_LOW
                )
                nm.createNotificationChannel(ch)
            }
            val text = if (total > 0)
                L("در حال تست پینگ... $done از $total", "Testing ping... $done of $total")
            else
                L("در حال تست پینگ...", "Testing ping...")
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
            val notif = builder
                .setContentTitle(L("تست پینگ اشتراک‌ها", "Subscription ping test"))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (_: Throwable) {}
    }

    private fun runTest() {
        var pools: List<java.util.concurrent.ExecutorService> = emptyList()
        try {
            val resume = prefs().getBoolean("ping_resume", false)
            if (!resume) {
                // Fresh run: reset the live state, exactly as before.
                prefs().edit()
                    .putBoolean("ping_running", true)
                    .putBoolean("ping_done", false)
                    .putBoolean("ping_applied", false)
                    .putString("ping_error", "")
                    .putString("ping_live_sub", "{}")
                    .putString("ping_healthy_live", "")
                    .putString("ping_speeds", "{}")
                    .putString("ping_remaining", "")
                    .putBoolean("ping_stopped", false)
                    .putBoolean("ping_resume", false)
                    .putInt("ping_progress_done", 0)
                    .putInt("ping_progress_healthy", 0)
                    .apply()
            } else {
                // Resume: keep the counters and the healthy
                // list found so far; only reopen the run.
                prefs().edit()
                    .putBoolean("ping_running", true)
                    .putBoolean("ping_done", false)
                    .putBoolean("ping_applied", false)
                    .putString("ping_error", "")
                    .putBoolean("ping_stopped", false)
                    .putBoolean("ping_resume", false)
                    .apply()
            }
            val list = (prefs().getString("ping_test_list", "") ?: "")
                .lines().map { it.trim() }.filter { it.isNotBlank() }
            val total = prefs().getInt("ping_progress_total", 0)
                .let { if (it > 0) it else list.size }
            prefs().edit().putInt("ping_progress_total", total).apply()
            val baseDone = prefs().getInt("ping_progress_done", 0)
            val baseHealthy = prefs().getInt("ping_progress_healthy", 0)
            if (list.isEmpty()) {
                finishWith(currentLiveHealthy(), null)
                return
            }
            if (!CoreInit.init(applicationContext)) {
                finishWith(currentLiveHealthy(), CoreInit.lastError ?: "Xray init failed")
                return
            }
            // 2.77: speed test ON means every config whose
            // ping passes is speed-tested at once, on the
            // same pool worker — NOT a second phase after
            // the sweep finishes.
            val speedOn = prefs().getBoolean("ping_speed_test_on", false)
            // ---- multi-route split (2.74) ----
            val routes = routeUrls()
            val routeCount = routes.size
            val chunkSize = (list.size + routeCount - 1) / routeCount
            val chunks = list.chunked(chunkSize)
            val done = AtomicInteger(baseDone)
            val healthyCount = AtomicInteger(baseHealthy)
            val completed = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
            val liveBatch = java.util.Collections.synchronizedList(mutableListOf<String>())
            val delaysBatch = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Long>>())
            val speedsBatch = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Double>>())
            val latch = java.util.concurrent.CountDownLatch(list.size)
            // Batched pref writes (2.74 slowness fix): the
            // renamer_subs prefs file holds megabyte strings
            // (fetched list, test list, live list), and every
            // apply() rewrites the whole file — writing it per
            // config made a 3000-config test hammer the disk
            // 3000+ times. Flush at most every ~50 configs or
            // ~1 s, plus a forced flush at the end / on stop.
            var lastFlushMs = System.currentTimeMillis()
            var lastFlushedDone = baseDone
            val flushLock = Any()
            fun flushProgress(force: Boolean) {
                val now = System.currentTimeMillis()
                val d = done.get()
                if (!force && d - lastFlushedDone < 50 && now - lastFlushMs < 1000) return
                synchronized(flushLock) {
                    val d2 = done.get()
                    val now2 = System.currentTimeMillis()
                    if (!force && d2 - lastFlushedDone < 50 && now2 - lastFlushMs < 1000) return
                    val batch: List<String>
                    synchronized(liveBatch) { batch = liveBatch.toList(); liveBatch.clear() }
                    val h = healthyCount.get()
                    val ed = prefs().edit()
                        .putInt("ping_progress_done", d2)
                        .putInt("ping_progress_healthy", h)
                    if (batch.isNotEmpty()) {
                        val cur = prefs().getString("ping_healthy_live", "") ?: ""
                        val next = if (cur.isBlank()) batch.joinToString("\n")
                            else cur + "\n" + batch.joinToString("\n")
                        ed.putString("ping_healthy_live", next)
                    }
                    ed.apply()
                    val db: List<Pair<String, Long>>
                    synchronized(delaysBatch) { db = delaysBatch.toList(); delaysBatch.clear() }
                    if (db.isNotEmpty() && (force || db.size >= 200)) {
                        mergeDelays(db)
                    } else if (db.isNotEmpty()) {
                        delaysBatch.addAll(db)
                    }
                    val sb: List<Pair<String, Double>>
                    synchronized(speedsBatch) { sb = speedsBatch.toList(); speedsBatch.clear() }
                    if (sb.isNotEmpty() && (force || sb.size >= 200)) {
                        mergeSpeeds(sb)
                    } else if (sb.isNotEmpty()) {
                        speedsBatch.addAll(sb)
                    }
                    lastFlushedDone = d2
                    lastFlushMs = now2
                    try { startForegroundWithNotification(d2, total) } catch (_: Throwable) {}
                }
            }
            pools = chunks.mapIndexed { routeIdx, chunk ->
                val url = routes.getOrElse(routeIdx) { routes[0] }
                val startIdx = routeIdx * chunkSize
                Executors.newFixedThreadPool(XraySpeedtest.poolSize()).also { pool ->
                    chunk.forEachIndexed { j, cfg ->
                        pool.submit {
                            try {
                                if (stopped) return@submit
                                val idx = startIdx + j
                                val delay = try {
                                    XraySpeedtest.measureOneWithUrl(cfg, url)
                                } catch (_: Throwable) { -1L }
                                if (stopped) return@submit
                                completed.add(idx)
                                delaysBatch.add(cfg to delay)
                                if (delay >= 0) {
                                    healthyCount.incrementAndGet()
                                    liveBatch.add(cfg)
                                    // 2.77: speed test, right here on the
                                    // same pool worker, the moment the
                                    // ping passes — NOT a second phase
                                    // after the sweep. Bounded (8 s), so
                                    // it cannot stall the sweep; a stop
                                    // interrupts it via shutdownNow.
                                    if (speedOn && !stopped) {
                                        val mbps = try {
                                            XraySpeedtest.measureDownloadMbps(
                                                this@PingTestService, cfg, 8000
                                            )
                                        } catch (_: Throwable) { -1.0 }
                                        if (!stopped && mbps > 0) {
                                            speedsBatch.add(cfg to mbps)
                                        }
                                    }
                                }
                                done.incrementAndGet()
                                flushProgress(force = false)
                            } finally {
                                latch.countDown()
                            }
                        }
                    }
                }
            }
            // Wait for all tasks, but wake up every 200 ms
            // so a stop takes effect at once instead of
            // waiting for every in-flight measurement.
            while (!latch.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                if (stopped) {
                    for (pool in pools) {
                        try { pool.shutdownNow() } catch (_: Throwable) {}
                    }
                    break
                }
            }
            for (pool in pools) {
                try { pool.shutdown() } catch (_: Throwable) {}
            }
            flushProgress(force = true)
            if (stopped) {
                // FULL STOP (2.75): clear everything, write NO
                // remaining state — the test is gone, not paused.
                // (The activity also clears synchronously; this
                // covers the running-service path.)
                if (cancelAll) {
                    prefs().edit()
                        .putString("ping_remaining", "")
                        .putString("ping_test_list", "")
                        .putBoolean("ping_stopped", false)
                        .putBoolean("ping_running", false)
                        .putBoolean("ping_done", false)
                        .putBoolean("ping_resume", false)
                        .putBoolean("ping_full_stop", false)
                        .putBoolean("ping_cancelled", true)
                        .putInt("ping_progress_done", 0)
                        .putInt("ping_progress_total", 0)
                        .putInt("ping_progress_healthy", 0)
                        .apply()
                    cancelAll = false
                    AppLog.append(this, "ping",
                        "تست پینگ کاملاً متوقف شد.",
                        "Ping test fully stopped.")
                    return
                }
                // Stopped mid-run: persist the configs not
                // yet tested, so the activity can resume
                // exactly them. Never write -1 for untested
                // configs, never clobber measured delays
                // (already merged above).
                val remaining = list.filterIndexed { i, _ -> !completed.contains(i) }
                val d = done.get()
                val h = healthyCount.get()
                prefs().edit()
                    .putString("ping_remaining", remaining.joinToString("\n"))
                    .putBoolean("ping_stopped", true)
                    .putBoolean("ping_running", false)
                    .putBoolean("ping_done", false)
                    .putInt("ping_progress_done", d)
                    .putInt("ping_progress_healthy", h)
                    .apply()
                AppLog.append(this, "ping",
                    "تست پینگ متوقف شد؛ $d از $total تست شد، $h سالم. ${remaining.size} کانفیگ باقی ماند.",
                    "Ping test stopped; $d of $total tested, $h healthy. ${remaining.size} configs left.")
                return
            }
            finishWith(finalHealthySorted(), null)
        } catch (t: Throwable) {
            try {
                prefs().edit()
                    .putBoolean("ping_done", true)
                    .putBoolean("ping_running", false)
                    .putString("ping_error", t.message ?: t.javaClass.simpleName)
                    .apply()
            } catch (_: Throwable) {}
        } finally {
            try { for (pool in pools) pool.shutdownNow() } catch (_: Throwable) {}
            try {
                if (wakeLock?.isHeld == true) wakeLock?.release()
            } catch (_: Throwable) {}
            wakeLock = null
            try { stopForeground(true) } catch (_: Throwable) {}
            stopSelf()
        }
    }

    /** The route URLs for this run (2.74, 2.77): count from
     *  ping_route_count (1..5, default 5), each URL from
     *  its ping_route_url_N pref, falling back to its
     *  default when blank or invalid — never an empty URL.
     *  All five defaults are the SAME gstatic generate_204
     *  URL (2.75), so a config green here is green in v2ray. */
    private fun routeUrls(): List<String> {
        val p = prefs()
        val n = p.getInt("ping_route_count", 5).coerceIn(1, 5)
        val defaults = listOf(
            ROUTE_URL_1_DEFAULT, ROUTE_URL_2_DEFAULT, ROUTE_URL_3_DEFAULT,
            ROUTE_URL_4_DEFAULT, ROUTE_URL_5_DEFAULT
        )
        val raws = listOf(
            p.getString("ping_route_url_1", null),
            p.getString("ping_route_url_2", null),
            p.getString("ping_route_url_3", null),
            p.getString("ping_route_url_4", null),
            p.getString("ping_route_url_5", null)
        )
        return (0 until n).map { i -> resolveRouteUrl(raws[i], defaults[i]) }
    }

    private fun resolveRouteUrl(raw: String?, default: String): String {
        val u = raw?.trim() ?: ""
        if (u.isBlank()) return default
        return try {
            val uri = java.net.URI(u)
            val scheme = uri.scheme?.lowercase()
            if ((scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()) u else default
        } catch (_: Exception) { default }
    }

    /** Merge this batch of measured delays into the stored
     *  ping_delays map (dedup key -> ms, -1 = failed).
     *  Merged, never replaced, so a resume keeps the
     *  earlier segments' delays — and the connect line's
     *  "last ping" display keeps working across tests. */
    private fun mergeDelays(batch: List<Pair<String, Long>>) {
        try {
            val j = JSONObject(prefs().getString("ping_delays", "{}") ?: "{}")
            for ((c, d) in batch) j.put(dedupKey(c), d)
            prefs().edit().putString("ping_delays", j.toString()).apply()
        } catch (_: Exception) {}
    }

    /** 2.77: merge measured download speeds into the
     *  stored ping_speeds map (dedup key -> MB/s).
     *  Merged, never replaced, like ping_delays. */
    private fun mergeSpeeds(batch: List<Pair<String, Double>>) {
        try {
            val j = JSONObject(prefs().getString("ping_speeds", "{}") ?: "{}")
            for ((c, sp) in batch) j.put(dedupKey(c), sp)
            prefs().edit().putString("ping_speeds", j.toString()).apply()
        } catch (_: Exception) {}
    }

    /** The healthy configs found so far in this run
     *  (all segments), as stored in ping_healthy_live. */
    private fun currentLiveHealthy(): List<String> {
        return (prefs().getString("ping_healthy_live", "") ?: "")
            .lines().map { it.trim() }.filter { it.isNotBlank() }
    }

    /** Final healthy list: every healthy config found
     *  across all segments, sorted by its measured delay
     *  (missing delay sorts last) — or, when the speed
     *  test was ON for this run, by measured download
     *  speed, best first (missing speed sorts last). */
    private fun finalHealthySorted(): List<String> {
        return try {
            val live = currentLiveHealthy()
            if (prefs().getBoolean("ping_speed_test_on", false)) {
                val speeds = JSONObject(prefs().getString("ping_speeds", "{}") ?: "{}")
                val scored = live.map { cfg ->
                    val sp = try { speeds.optDouble(dedupKey(cfg), -1.0) } catch (_: Exception) { -1.0 }
                    cfg to sp
                }
                scored.sortedByDescending { it.second }.map { it.first }
            } else {
                val delays = JSONObject(prefs().getString("ping_delays", "{}") ?: "{}")
                val scored = live.map { cfg ->
                    val d = try { delays.optLong(dedupKey(cfg), Long.MAX_VALUE) } catch (_: Exception) { Long.MAX_VALUE }
                    cfg to d
                }
                scored.sortedBy { it.second }.map { it.first }
            }
        } catch (_: Exception) {
            currentLiveHealthy()
        }
    }

    /** Writes the final sorted list, per-sub healthy stats, and
     *  the completion flags. Called only on a clean finish. */
    private fun finishWith(healthySorted: List<String>, error: String?) {
        try {
            // Per-sub healthy counts, from the persisted source map.
            val source = mutableMapOf<String, String>()
            try {
                val j = JSONObject(prefs().getString("sub_source_map", "{}") ?: "{}")
                for (key in j.keys()) source[key] = j.optString(key, "")
            } catch (_: Throwable) {}
            val healthyPerSub = mutableMapOf<String, Int>()
            for (cfg in healthySorted) {
                val src = source[dedupKey(cfg)] ?: continue
                healthyPerSub[src] = (healthyPerSub[src] ?: 0) + 1
            }
            // Keep previous received counts, replace healthy counts.
            val oldStats = mutableMapOf<String, Pair<Int, Int>>()
            try {
                val j = JSONObject(prefs().getString("sub_stats", "{}") ?: "{}")
                for (key in j.keys()) {
                    val o = j.optJSONObject(key) ?: continue
                    oldStats[key] = o.optInt("received", 0) to o.optInt("healthy", 0)
                }
            } catch (_: Throwable) {}
            val subs = (prefs().getString("subs", "") ?: "")
                .lines().map { it.trim() }.filter { it.isNotBlank() }
            val statsJson = JSONObject()
            for (url in subs) {
                val o = JSONObject()
                o.put("received", oldStats[url]?.first ?: 0)
                o.put("healthy", healthyPerSub[url] ?: 0)
                statsJson.put(url, o)
            }
            val liveSubJson = JSONObject()
            for ((u, c) in healthyPerSub) liveSubJson.put(u, c)
            prefs().edit()
                .putString("last_healthy", healthySorted.joinToString("\n"))
                .putString("box2", healthySorted.joinToString("\n"))
                .putString("sub_stats", statsJson.toString())
                .putString("ping_live_sub", liveSubJson.toString())
                .putInt("ping_progress_done", prefs().getInt("ping_progress_total", 0))
                .putInt("ping_progress_healthy", healthySorted.size)
                .putBoolean("ping_done", true)
                .putBoolean("ping_running", false)
                .putString("ping_error", error ?: "")
                .putString("ping_remaining", "")
                .putBoolean("ping_stopped", false)
                .apply()
                AppLog.append(this, "ping",
                    "تست پینگ تمام شد؛ سالم: ${healthySorted.size} از ${prefs().getInt("ping_progress_total", 0)}." + if (!error.isNullOrBlank()) " خطا: $error." else "",
                    "Ping test done; healthy: ${healthySorted.size} of ${prefs().getInt("ping_progress_total", 0)}." + if (!error.isNullOrBlank()) " Error: $error." else "")
        } catch (_: Throwable) {
            try {
                prefs().edit()
                    .putBoolean("ping_done", true)
                    .putBoolean("ping_running", false)
                    .apply()
            } catch (_: Throwable) {}
        }
    }

    override fun onDestroy() {
        stopped = true
        try {
            // If the worker is still going (stop button / system
            // kill), mark the test as no longer running, but never
            // overwrite a finished result.
            if (prefs().getBoolean("ping_done", false) != true) {
                prefs().edit().putBoolean("ping_running", false).apply()
            }
        } catch (_: Throwable) {}
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Throwable) {}
        wakeLock = null
        worker = null
        super.onDestroy()
    }

    /** Same normalized key as SubscriptionActivity.dedupKey, so the
     *  service's source lookup matches the activity's keys exactly. */
    private fun dedupKey(cfg: String): String {
        return try {
            if (cfg.startsWith("vmess://")) {
                var raw = cfg.removePrefix("vmess://").substringBefore("#").trim()
                while (raw.length % 4 != 0) raw += "="
                val j = JSONObject(
                    String(android.util.Base64.decode(raw, android.util.Base64.URL_SAFE), Charsets.UTF_8)
                )
                j.remove("ps")
                canonicalJson(j)
            } else {
                normalizeUri(cfg)
            }
        } catch (_: Exception) { cfg.substringBefore("#") }
    }

    private fun canonicalJson(j: JSONObject): String {
        val keys = mutableListOf<String>()
        for (k in j.keys()) keys += k
        keys.sort()
        val sb = StringBuilder("{")
        for ((i, k) in keys.withIndex()) {
            if (i > 0) sb.append(",")
            sb.append(JSONObject.quote(k)).append(":")
            when (val v = j.get(k)) {
                is String -> sb.append(JSONObject.quote(v))
                is JSONObject -> sb.append(canonicalJson(v))
                is org.json.JSONArray -> sb.append(v.toString())
                JSONObject.NULL -> sb.append("null")
                else -> sb.append(v.toString())
            }
        }
        sb.append("}")
        return sb.toString()
    }

    private fun normalizeUri(cfg: String): String {
        return try {
            val noFrag = cfg.substringBefore("#")
            val uri = java.net.URI(noFrag)
            val query = uri.rawQuery
            val sortedQuery = if (query.isNullOrBlank()) null else
                query.split("&").filter { it.isNotBlank() }.sorted().joinToString("&")
            val sb = StringBuilder()
            sb.append(uri.scheme).append("://")
            if (uri.rawUserInfo != null) sb.append(uri.rawUserInfo).append("@")
            if (uri.host != null) sb.append(uri.host)
            if (uri.port != -1) sb.append(":").append(uri.port)
            if (uri.rawPath != null) sb.append(uri.rawPath)
            if (sortedQuery != null) sb.append("?").append(sortedQuery)
            sb.toString()
        } catch (_: Exception) {
            cfg.substringBefore("#")
        }
    }
}
