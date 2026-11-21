package com.renamer

import android.content.Context
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** 1.59 — the SHARED clean-IP scanner (the port of Asha's
 *  scanner): ONE sweep service (which Cloudflare IPs
 *  answer), TWO different verdicts, each section keeping
 *  its own clean list:
 *
 *  - WARP free: the MASQUE real-traffic verdict — the
 *    engine's own --scan run (warpScanOnce), whose SOCKS
 *    readiness IS the proof (the same real-SOCKS5 check
 *    AetherCli.socksReady does for the fetch probes).
 *  - Cloudflare section: the panel /sync verdict — the
 *    existing probeViaIp + decodeSubLinks (kept exactly;
 *    scanCleanIps feeds its candidates from this sweep's
 *    ranked responders).
 *
 *  ASHA PORT (refs: clone ~/workspace/.tmp/asha_scanner/):
 *  - ScanEngine.kt — the central seam: Phase 1 fan-out
 *    probing, Phase 1.5 stability re-check, Phase 2
 *    throughput validation, open-site fallback, smart-stop
 *    (stop at top*4, min 30), Validator as an injected
 *    interface. PORTED as: tcpSweep (Phase 1),
 *    stabilityRecheck (Phase 1.5), ScanSort.byLatency
 *    (Phase-1 ranking), the --scan engine run (Phase 2's
 *    real-traffic validation, done by the engine itself).
 *    Asha's coroutines are NOT ported — this tree has no
 *    coroutines on the build classpath (1.59 finding), so
 *    the port uses plain ExecutorService threads, the
 *    same style CloudflareActivity.scanCleanIps already
 *    uses (8 threads). The DESIGN is the port; the
 *    coroutine Flow plumbing is not.
 *  - QualityEvaluator.kt — grades S/A/B/C/F (healthy,
 *    loss, passRate, latency, throughput). PORTED as
 *    ScanQuality.evaluate (throughput defaults to 0 —
 *    the sweep measures latency only; a throughput grade
 *    needs the real-traffic verdict, which is the rung
 *    probe, not the sweep).
 *  - ResultSort.kt — byLatency (healthy → passRate desc
 *    → latency asc → tlsOk desc → ip), bySpeed, topBy-
 *    Latency. PORTED as ScanSort (byLatency + topBy-
 *    Latency; bySpeed needs throughput, which the sweep
 *    does not measure).
 *  - ScanStateStore.kt — resume snapshots. NOT ported:
 *    a sweep is idempotent and cheap to restart, and
 *    the fetch never waits on it; what IS persisted is
 *    the sweep's ranked responders (cachedResponders)
 *    so scanCleanIps can use them even before any sweep
 *    runs in this process.
 *  - core/ipsrc CloudflareRanges.V4 (bundled cf_ipv4.txt)
 *    = CdnPools.candidates("cloudflare", 105) here.
 *  - prober.rs (public aether 2.4.0): the engine's own
 *    scan does a TCP-handshake sweep of the MASQUE /24s
 *    (198.0/24 then 199.0/24 — "those that completed a
 *    TCP handshake on port 443 … each range fastest
 *    first as measured") and then MASQUE-verifies them.
 *    This service mirrors it in Kotlin: TCP sweep →
 *    fastest-first → engine --scan verdict.
 *
 *  SPEED RULE (user order): the sweep runs ONLY in the
 *  background and its results are CACHED in the
 *  per-network lists (NetworkProfile). Fetch and connect
 *  use the cached lists (the 1.58 fast path) and NEVER
 *  wait for a sweep. A full sweep runs only on first
 *  run, on a network change, or when a network's list
 *  is empty/stale. */
object SharedScanner {

    // ---- Asha's ScanResult shape (minimal fields) ----

    data class ScanResult(
        val ip: String,
        val healthy: Boolean,
        val passRate: Double,
        val avgLatencyMs: Double,
        val tlsOk: Boolean,
        val throughputMbps: Double = 0.0
    )

    data class ProbedIp(val ip: String, val latencyMs: Long, val tlsOk: Boolean = false)

    // ---- QualityEvaluator.kt (port) ----

    object ScanQuality {
        enum class Grade(val label: String) { S("S"), A("A"), B("B"), C("C"), F("F") }

        /** Asha's evaluate(): F when unhealthy, loss > 10%
         *  or passRate < 0.5; S/A/B need a throughput the
         *  sweep does not measure, so a sweep-only result
         *  tops out at C (honest: latency alone is not a
         *  speed grade). */
        fun evaluate(r: ScanResult): Grade {
            if (!r.healthy || r.passRate < 0.5) return Grade.F
            val ping = r.avgLatencyMs
            val speed = r.throughputMbps
            val stable = r.passRate >= 0.9
            return when {
                ping < 80 && speed > 20 && stable -> Grade.S
                ping < 120 && speed > 10 && stable -> Grade.A
                ping < 180 && speed > 3 -> Grade.B
                else -> Grade.C
            }
        }

        fun isStable(r: ScanResult): Boolean = r.healthy && r.passRate >= 0.9
    }

    // ---- ResultSort.kt (port: latency ranking only) ----

    object ScanSort {
        /** Healthy first, then highest pass rate, then
         *  lowest average latency (0 = unknown = worst),
         *  then TLS ok, then the IP (deterministic). */
        fun byLatency(results: List<ScanResult>): List<ScanResult> =
            results.sortedWith(
                compareByDescending<ScanResult> { it.healthy }
                    .thenByDescending { it.passRate }
                    .thenBy { if (it.avgLatencyMs <= 0) Double.MAX_VALUE else it.avgLatencyMs }
                    .thenByDescending { it.tlsOk }
                    .thenBy { it.ip }
            )

        fun topByLatency(results: List<ScanResult>, n: Int): List<ScanResult> =
            byLatency(results.filter { it.healthy }).take(n)
    }

    // ---- Phase 1: TCP probing (pure java.net) ----

    /** One TCP connect; the latency in ms, or null when
     *  the IP does not answer. Pure (JVM-testable). */
    fun tcpProbe(ip: String, port: Int, timeoutMs: Int): Long? {
        return try {
            val t0 = System.nanoTime()
            Socket().use { s ->
                s.connect(InetSocketAddress(ip, port), timeoutMs)
            }
            (System.nanoTime() - t0) / 1_000_000L
        } catch (_: Exception) {
            null
        }
    }

    /** Phase-1 fan-out: probe the targets in parallel and
     *  keep the fastest responders (smart-stop: stop
     *  submitting once maxOpen answered — Asha's
     *  smartStop, here the fetch's wide-sweep 20).
     *  Bounded by budgetMs; never throws. Pure. */
    fun tcpSweep(
        targets: List<String>,
        port: Int = 443,
        timeoutMs: Int = 1200,
        maxOpen: Int = 20,
        budgetMs: Long = 90_000,
        concurrency: Int = 32
    ): List<ProbedIp> {
        val deadline = System.currentTimeMillis() + budgetMs
        val pool: ExecutorService = Executors.newFixedThreadPool(concurrency)
        val found = AtomicInteger(0)
        val results = Collections.synchronizedList(mutableListOf<ProbedIp>())
        try {
            for (ip in targets.distinct()) {
                if (System.currentTimeMillis() >= deadline) break
                if (found.get() >= maxOpen) break
                pool.submit {
                    if (found.get() >= maxOpen) return@submit
                    val ms = tcpProbe(ip, port, timeoutMs) ?: return@submit
                    if (found.incrementAndGet() <= maxOpen) {
                        results.add(ProbedIp(ip, ms, false))
                    }
                }
            }
            pool.shutdown()
            try {
                pool.awaitTermination(
                    maxOf(1L, deadline - System.currentTimeMillis()),
                    TimeUnit.MILLISECONDS
                )
            } catch (_: Exception) {
            }
        } finally {
            pool.shutdownNow()
        }
        return results.sortedBy { it.latencyMs }
    }

    /** Phase 1.5 (Asha): re-probe each survivor `rounds`
     *  times and keep only the ones that answer again.
     *  passRate = successes/rounds; the average latency
     *  counts successful probes only. Pure. */
    fun stabilityRecheck(
        ips: List<String>,
        rounds: Int = 3,
        timeoutMs: Int = 1200,
        concurrency: Int = 16
    ): List<ScanResult> {
        val pool: ExecutorService = Executors.newFixedThreadPool(concurrency)
        val out = Collections.synchronizedList(mutableListOf<ScanResult>())
        try {
            for (ip in ips.distinct()) {
                pool.submit {
                    var ok = 0
                    var sum = 0L
                    repeat(rounds) {
                        val ms = tcpProbe(ip, 443, timeoutMs)
                        if (ms != null) { ok++; sum += ms }
                    }
                    val rate = ok.toDouble() / rounds
                    out.add(
                        ScanResult(
                            ip = ip,
                            healthy = ok > 0,
                            passRate = rate,
                            avgLatencyMs = if (ok > 0) sum.toDouble() / ok else 0.0,
                            tlsOk = false
                        )
                    )
                }
            }
            pool.shutdown()
            try {
                pool.awaitTermination(60, TimeUnit.SECONDS)
            } catch (_: Exception) {
            }
        } finally {
            pool.shutdownNow()
        }
        return out
    }

    // ---- the candidate pool (pure) ----

    /** The offsets the engine's own scan samples in each
     *  MASQUE /24 (prober.rs: the scan tries the /24s and
     *  keeps the ones that handshake; .75 and .222 are the
     *  engine's own named samples). 12 per /24 × 14 /24s
     *  = 168 candidates. */
    val MASQUE_SAMPLE_OFFSETS = listOf(1, 2, 5, 8, 16, 32, 64, 75, 128, 222, 250, 254)

    /** The shared candidate pool: the network's saved
     *  clean IPs first, then the 18 known gateways, then
     *  the MASQUE /24 samples, then the general
     *  Cloudflare range samples (CdnPools = Asha's
     *  CloudflareRanges.V4). Pure — the caller adds the
     *  saved list. */
    fun sweepCandidatePool(savedClean: List<String>): List<String> {
        val out = mutableListOf<String>()
        for (ip in savedClean) out += ip.trim()
        out += WarpFree.GATEWAYS
        for (cidr in AetherRungPlan.MASQUE_CIDRS_V4) {
            val base = cidr.substringBefore("/")
            val prefix = base.substringBeforeLast(".")
            for (off in MASQUE_SAMPLE_OFFSETS) out += "$prefix.$off"
        }
        out += CdnPools.candidates("cloudflare", 105)
        return out.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    // ---- sweep phase lines (1.61) ----
    // Pure formatters for the [warp] log phases: every
    // line carries the network key and only real
    // computed values — no fake data. The sweep emits
    // them as it runs (see sweepIfNeeded); the
    // existing summary line stays too.

    /** Sweep start: network key + candidate pool size. */
    fun sweepStartLine(networkKey: String, poolSize: Int): Pair<String, String> =
        "اسکن شبکهٔ «$networkKey» شروع شد: $poolSize آی‌پی کاندید." to
            "Scan of network \u201c$networkKey\u201d started: $poolSize candidate IPs."

    /** TCP fan-out: how many candidates answered. */
    fun sweepTcpLine(networkKey: String, answered: Int): Pair<String, String> =
        "اسکن شبکهٔ «$networkKey»: $answered آی‌پی روی ۴۴۳ جواب داد." to
            "Scan of network \u201c$networkKey\u201d: $answered IPs answered on 443."

    /** Stability re-check: how many survived 3 rounds. */
    fun sweepStabilityLine(networkKey: String, stable: Int, rounds: Int): Pair<String, String> =
        "اسکن شبکهٔ «$networkKey»: $stable آی‌پی در $rounds دور بازبینی پایدار ماند." to
            "Scan of network \u201c$networkKey\u201d: $stable IPs stayed stable over $rounds re-check rounds."

    /** Ranking: the top IPs with their latencies
     *  (top 3 is enough for the log). */
    fun sweepRankingLine(networkKey: String, top: List<ScanResult>): Pair<String, String> {
        val shown = top.take(3).joinToString("، ") { "${it.ip} (${it.avgLatencyMs.toInt()}ms)" }
        val fa = if (shown.isEmpty()) "اسکن شبکهٔ «$networkKey»: هیچ آی‌پی پایداری برای رتبه‌بندی نیست."
            else "اسکن شبکهٔ «$networkKey»: بهترین‌ها: $shown."
        val enShown = top.take(3).joinToString(", ") { "${it.ip} (${it.avgLatencyMs.toInt()}ms)" }
        val en = if (enShown.isEmpty()) "Scan of network \u201c$networkKey\u201d: no stable IP to rank."
            else "Scan of network \u201c$networkKey\u201d: best: $enShown."
        return fa to en
    }

    /** WARP verdict: the verified gateway, or an honest
     *  "none verified" when warpScanOnce returns null. */
    fun sweepVerdictLine(networkKey: String, verified: String?): Pair<String, String> =
        if (verified != null)
            "اسکن شبکهٔ «$networkKey»: دروازهٔ $verified با ترافیک واقعی تأیید شد." to
                "Scan of network \u201c$networkKey\u201d: gateway $verified verified with real traffic."
        else
            "اسکن شبکهٔ «$networkKey»: هیچ دروازه‌ای با ترافیک واقعی تأیید نشد." to
                "Scan of network \u201c$networkKey\u201d: no gateway verified with real traffic."

    // ---- the background sweep ----

    private const val PREFS = "renamer_scanner"
    private const val SWEEP_TOP = 16
    private const val SWEEP_MAX_OPEN = 20
    private const val SWEEP_BUDGET_MS = 90_000L
    const val SCAN_SOCKS_PORT = 1829
    const val SCAN_WAIT_MS = 120_000L

    private val sweepExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "shared-scanner").apply { isDaemon = true }
        }
    private val sweepRunning = AtomicBoolean(false)

    private fun respondersPrefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The last sweep's ranked TCP responders for a
     *  network (fast prefs read, no network). These are
     *  CANDIDATES, not a verdict — the Cloudflare
     *  section re-tests each one with the /sync verdict
     *  before trusting it. */
    fun cachedResponders(ctx: Context, networkKey: String): List<String> {
        return try {
            val raw = respondersPrefs(ctx).getString("responders_$networkKey", "") ?: ""
            raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(SWEEP_TOP)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveResponders(ctx: Context, networkKey: String, ips: List<String>) {
        try {
            respondersPrefs(ctx).edit()
                .putString("responders_$networkKey", ips.distinct().take(SWEEP_TOP).joinToString(","))
                .putLong("responders_at_$networkKey", System.currentTimeMillis())
                .apply()
        } catch (_: Exception) {
        }
    }

    /** A sweep is fresh when it ran in the last day AND
     *  the network already has a WARP clean list. */
    fun isFresh(ctx: Context, networkKey: String): Boolean =
        isFreshWhen(
            NetworkProfile.lastSweepAt(ctx, networkKey),
            System.currentTimeMillis(),
            NetworkProfile.warpCleanList(ctx, networkKey).isNotEmpty()
        )

    /** The fresh rule, pure (JVM-testable). */
    fun isFreshWhen(lastSweepAtMs: Long, nowMs: Long, warpNonEmpty: Boolean): Boolean {
        if (lastSweepAtMs <= 0L) return false
        if (nowMs - lastSweepAtMs > NetworkProfile.SWEEP_FRESH_MS) return false
        return warpNonEmpty
    }

    /** The background sweep (fire-and-forget, never
     *  blocks the caller, never throws): Phase 1 TCP
     *  sweep → stability re-check → ranked responders
     *  cached → the engine --scan run for the MASQUE
     *  verdict → the verified gateway merged into the
     *  network's WARP clean list. Skipped when a sweep
     *  is already running or the network is fresh. */
    fun sweepIfNeeded(ctx: Context) {
        val appCtx = ctx.applicationContext
        sweepExecutor.submit {
            val key = try {
                NetworkProfile.currentKey(appCtx)
            } catch (_: Exception) {
                return@submit
            }
            if (sweepRunning.get()) return@submit
            if (isFresh(appCtx, key)) return@submit
            if (!sweepRunning.compareAndSet(false, true)) return@submit
            try {
                val saved = NetworkProfile.warpCleanList(appCtx, key)
                val pool = sweepCandidatePool(saved)
                // 1.61 — phase lines, emitted as the sweep
                // runs (start → TCP → stability → ranking
                // → verdict); the summary line below stays.
                val (startFa, startEn) = sweepStartLine(key, pool.size)
                AppLog.append(appCtx, "warp", startFa, startEn)
                val t0 = System.currentTimeMillis()
                val responders = tcpSweep(
                    pool, port = 443, timeoutMs = 1200,
                    maxOpen = SWEEP_MAX_OPEN, budgetMs = SWEEP_BUDGET_MS, concurrency = 32
                )
                if (responders.isEmpty()) {
                    AppLog.append(appCtx, "warp",
                        "اسکن پس‌زمینهٔ شبکهٔ «$key»: هیچ آی‌پی‌ای روی ۴۴۳ جواب نداد.",
                        "Background scan for network “$key”: no IP answered on 443.")
                    return@submit
                }
                val (tcpFa, tcpEn) = sweepTcpLine(key, responders.size)
                AppLog.append(appCtx, "warp", tcpFa, tcpEn)
                val rechecked = stabilityRecheck(
                    responders.take(SWEEP_MAX_OPEN).map { it.ip },
                    rounds = 3, timeoutMs = 1200, concurrency = 16
                )
                val (stabFa, stabEn) = sweepStabilityLine(key, rechecked.size, 3)
                AppLog.append(appCtx, "warp", stabFa, stabEn)
                val ranked = ScanSort.topByLatency(rechecked, SWEEP_TOP)
                val rankedIps = ranked.map { it.ip }
                val (rankFa, rankEn) = sweepRankingLine(key, ranked)
                AppLog.append(appCtx, "warp", rankFa, rankEn)
                saveResponders(appCtx, key, rankedIps)
                val best = ranked.firstOrNull()
                AppLog.append(appCtx, "warp",
                    "اسکن پس‌زمینهٔ شبکهٔ «$key»: ${responders.size} آی‌پی جواب داد، ${rankedIps.size} تا پایدار ماند (${(System.currentTimeMillis() - t0) / 1000} ثانیه)؛ بهترین: ${best?.ip ?: "—"} (${best?.avgLatencyMs?.toInt() ?: 0}ms).",
                    "Background scan for network “$key”: ${responders.size} IPs answered, ${rankedIps.size} stayed stable (${(System.currentTimeMillis() - t0) / 1000}s); best: ${best?.ip ?: "—"} (${best?.avgLatencyMs?.toInt() ?: 0}ms).")
                // The WARP verdict: the engine's own --scan
                // run (balanced, Asha's scanner default).
                // Its SOCKS readiness IS the real-traffic
                // proof — a TCP answer alone never adds an
                // IP to the WARP clean list.
                val verified = warpScanOnce(appCtx, key)
                if (verified != null) {
                    NetworkProfile.mergeWarpClean(appCtx, key, verified)
                    NetworkProfile.saveWinner(appCtx, key, verified, "h2")
                    AppLog.append(appCtx, "warp",
                        "اسکن پس‌زمینه: دروازهٔ $verified با ترافیک واقعی تأیید شد و به لیست تمیز شبکهٔ «$key» رفت.",
                        "Background scan: gateway $verified verified with real traffic and joined network “$key”’s clean list.")
                } else {
                    // 1.61 — the verdict phase line, honest
                    // when nothing verified (was silent).
                    val (vFa, vEn) = sweepVerdictLine(key, null)
                    AppLog.append(appCtx, "warp", vFa, vEn)
                }
                NetworkProfile.touchSweep(appCtx, key)
            } catch (_: Exception) {
            } finally {
                sweepRunning.set(false)
            }
        }
    }

    /** The WARP verdict for the sweep: drive the rebuilt
     *  2.4.0 engine with --scan balanced (Asha's
     *  scanner default; AetherCliConfig's scan branch)
     *  instead of forcing a peer. The engine hunts and
     *  validates itself; the run succeeds only when its
     *  SOCKS serves a real generate_204 (AetherCli.
     *  socksReady) — a TCP answer alone is not a
     *  verdict. Returns the verified gateway, or null.
     *  Skips when a fetch/connect probe is already
     *  driving the CLI (AetherCli.start would kill it),
     *  and never throws.
     *  1.60 — the sweep NEVER registers: on a fresh
     *  install it used to race the fetch through
     *  ensureIdentityFile and register a SECOND WARP
     *  account (the 1.59 double registration, two
     *  device ids, ~2 min of slow PATCH legs, AETHER
     *  starved to a 0s budget). When no identity
     *  exists yet, the --scan verdict simply waits for
     *  the next sweep (the fetch owns registration).
     *  It also skips while a fetch is running, so it
     *  can never steal the CLI from a fetch probe. */
    fun warpScanOnce(ctx: Context, networkKey: String): String? {
        if (AetherCli.isRunning) return null
        if (WarpFree.fetchActive) return null
        if (WarpIdentity.load(ctx, "main") == null) return null
        return try {
            if (!AetherBridge.ensureIdentityFile(ctx)) return null
            val enroll = NetworkProfile.warpCleanList(ctx, networkKey).firstOrNull()
                ?: AetherBridge.enrollIp ?: "162.159.192.1"
            val args = AetherCli.buildScanArgs(SCAN_SOCKS_PORT, "balanced", quickReconnect = true)
            val ok = AetherCli.start(
                ctx, args, SCAN_SOCKS_PORT, SCAN_WAIT_MS, enroll
            ) { line ->
                if (line.contains("selected MASQUE gateway") ||
                    line.contains("using cloudflare edge") ||
                    line.contains("gool ready", ignoreCase = true) ||
                    line.contains("exited", ignoreCase = true)
                ) {
                    AppLog.append(ctx, "warp", line, line)
                }
            }
            if (!ok) return null
            AetherCli.lastSelectedGatewayIp.ifBlank { AetherCli.lastScanEdge }
                .ifBlank { null }
        } catch (_: Exception) {
            null
        } finally {
            try {
                AetherCli.stop()
            } catch (_: Exception) {
            }
        }
    }

    /** PART 3's quick scan (called from a connect
     *  attempt when the current network's clean list is
     *  empty): a TCP-only mini-sweep over the network's
     *  saved clean IPs + the 18 defaults + the general
     *  CF range samples, fastest first, bounded to ~20s
     *  and 5 open IPs. TCP answering is NOT a verdict —
     *  the replacement loop's session attempt is the
     *  real-traffic verdict for each candidate. */
    fun quickScanForConnect(
        ctx: Context,
        networkKey: String,
        exclude: Set<String>
    ): List<String> {
        val candidates = mutableListOf<String>()
        candidates += NetworkProfile.warpCleanList(ctx, networkKey)
        candidates += WarpFree.GATEWAYS
        candidates += CdnPools.candidates("cloudflare", 40)
        val pool = candidates.map { it.trim() }
            .filter { it.isNotEmpty() && it !in exclude }.distinct()
        if (pool.isEmpty()) return emptyList()
        val found = tcpSweep(
            pool, port = 443, timeoutMs = 1200,
            maxOpen = 5, budgetMs = 20_000, concurrency = 32
        )
        return found.map { it.ip }
    }

    /** HTTPS to a raw edge IP with SNI + cert
     *  verification for the host (layered socket) —
     *  moved verbatim from CloudflareActivity.
     *  probeViaIp (same bytes, same chunked decoding);
     *  the Cloudflare section's verdict is unchanged,
     *  only its home moved here so the JVM test can
     *  exercise it. Returns the response body, or null. */
    fun probeHttpViaIp(host: String, ip: String, path: String, timeoutMs: Int): String? {
        return try {
            val plain = Socket()
            plain.connect(InetSocketAddress(ip, 443), timeoutMs)
            plain.soTimeout = timeoutMs
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = factory.createSocket(plain, host, 443, true) as SSLSocket
            ssl.soTimeout = timeoutMs
            ssl.startHandshake()
            val req = "GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n"
            ssl.outputStream.write(req.toByteArray(Charsets.UTF_8))
            ssl.outputStream.flush()
            val raw = ssl.inputStream.readBytes().toString(Charsets.UTF_8)
            try {
                ssl.close()
            } catch (_: Exception) {
            }
            val body = decodeResponseBody(raw)
            if (body.isBlank()) null else body
        } catch (_: Exception) {
            null
        }
    }

    /** The response-body step of [probeHttpViaIp]:
     *  split headers/body, then minimal chunked-
     *  transfer decoding when the edge used it.
     *  Pure (JVM-testable) — the TLS handshake above
     *  is the part only the device can prove. */
    internal fun decodeResponseBody(raw: String): String {
        var body = raw.substringAfter("\r\n\r\n", "")
        if (body.isBlank()) return ""
        if (raw.substringBefore("\r\n\r\n", "").contains("chunked", ignoreCase = true)) {
            val out = StringBuilder()
            var rest = body
            while (true) {
                val nl = rest.indexOf("\r\n")
                if (nl < 0) break
                val size = rest.substring(0, nl).trim().toIntOrNull(16) ?: break
                if (size == 0) break
                val start = nl + 2
                if (rest.length < start + size) break
                out.append(rest.substring(start, start + size))
                rest = rest.substring(start + size).removePrefix("\r\n")
            }
            body = out.toString()
        }
        return body
    }

    /** Decodes a subscription body (single-line base64
     *  or plain links) into links, the same way the
     *  panel client does — the pure (java.util.Base64)
     *  twin of CloudflareActivity.decodeSubLinks, for
     *  the JVM test. */
    fun decodeSubLinksPure(body: String): List<String> {
        var b = body.trim()
        if (!b.contains("://") && !b.startsWith("{") && !b.startsWith("[")) {
            try {
                var t = b.replace('-', '+').replace('_', '/')
                while (t.length % 4 != 0) t += "="
                b = String(
                    java.util.Base64.getDecoder().decode(t),
                    Charsets.UTF_8
                )
            } catch (_: Exception) {
                try {
                    var t = b.replace('-', '+').replace('_', '/')
                    while (t.length % 4 != 0) t += "="
                    b = String(
                        java.util.Base64.getUrlDecoder().decode(t),
                        Charsets.UTF_8
                    )
                } catch (_: Exception) {
                    return emptyList()
                }
            }
        }
        return b.lines().map { it.trim() }.filter { it.contains("://") }
    }
}
