package com.renamer

import android.util.Base64
import org.json.JSONObject

/**
 * Free configs for the v2ray page (1.30): one
 * «دریافت کانفیگ رایگان» button fetches from 4
 * fixed subscriptions, ping-tests them exactly
 * like the v2ray ping test, and adds the 10
 * lowest-ping into the SAME v2ray list, named
 * @internet_azad_2026(1)…(10) in ping order.
 * (1.33: was 30; only the 10 lowest-ping now.)
 *
 * Privacy rule (user order): the sub URLs and
 * any sub name are NEVER shown anywhere — no UI,
 * no row, no log line, no error message, no
 * toast. Only the fetch URL (the part before
 * any '#') is stored, and every failure surfaces
 * as one generic message chosen by the caller.
 * A failed sub never blocks the others.
 *
 * Unsupported schemes (hysteria2/hy2/tuic/
 * wireguard/hysteria) are skipped silently:
 * this tree predates the main app's hy2 support,
 * so those links can neither ping nor connect
 * here. Junk lines that carry "://" but build no
 * outbound (e.g. profile header URLs) are
 * dropped by the same outboundFor check.
 */
object FreeConfigs {
    // 1.33: only the TEN lowest-ping configs are added — not 30, not
    // every healthy config.
    const val FREE_COUNT = 10
    const val NAME_PREFIX = "@internet_azad_2026("

    // Fixed fetch URLs only — the part before '#'.
    // (None of these carries a fragment; the strip
    // below is defensive, so a name can never leak
    // in through a future edit of this list.)
    // 1.67: the free-WARP section's bundled relay
    // list (BundledRelays) no longer uses these
    // constants at all — the 1.66 pool that reused
    // them through subUrls() below is deleted.
    private val SUB_URLS = listOf(
        "https://example.com/subscription-1",
        "https://example.com/subscription-2",
        "https://example.com/subscription-3",
        "https://example.com/subscription-4"
    )

    /** The sub URLs for the free-config fetch —
     *  the same constants, never a copy, never
     *  shown anywhere. (1.67: the free-WARP
     *  section no longer uses them — its bundled
     *  relay list is static.) */
    fun subUrls(): List<String> = SUB_URLS

    private val SKIP_SCHEMES = setOf(
        "hysteria2", "hy2", "tuic", "wireguard", "hysteria"
    )

    fun isFreeConfig(cfg: String): Boolean {
        if (XraySpeedtest.isGroup(cfg) || XraySpeedtest.isChain(cfg)) return false
        // 1.33: also match the fragment marker. v1.30–1.32
        // vmess rows carry the free name only in the
        // #fragment — their "ps" rewrite failed on
        // standard base64, so displayName reads "vmess"
        // for them. Without this, a re-tap would never
        // remove those stale rows, and they would not
        // get the free-row treatment either.
        if (cfg.contains("#$NAME_PREFIX")) return true
        return XraySpeedtest.displayName(cfg).startsWith(NAME_PREFIX)
    }

    fun freeName(n: Int): String = "@internet_azad_2026($n)"

    /** 1.39 (item 5): highest @internet_azad_2026(N)
     *  number among the given configs, or 0.
     *  Numbering continues from the free COUNT, but
     *  if the user deleted a middle free row the
     *  count is lower than the highest number still
     *  in the list — starting from the count alone
     *  would then re-use a number that still exists.
     *  Callers start from max(count, this). */
    fun maxFreeNumber(cfgs: List<String>): Int {
        var max = 0
        for (cfg in cfgs) {
            if (!isFreeConfig(cfg)) continue
            val name = XraySpeedtest.displayName(cfg)
            if (!name.startsWith(NAME_PREFIX)) continue
            val num = name.removePrefix(NAME_PREFIX)
                .removeSuffix(")").toIntOrNull() ?: continue
            if (num > max) max = num
        }
        return max
    }

    /** Rename one config to its free name. vmess
     *  carries its name inside the base64 JSON
     *  ("ps"), not in the fragment, so the JSON
     *  is rewritten; every other URI link gets a
     *  fresh #fragment. The dedup key ignores the
     *  name, so a ping measured before renaming
     *  still matches the renamed config.
     *
     *  1.33 (item 4 root cause): the old code decoded
     *  the vmess JSON with Base64.URL_SAFE only, but
     *  real fetched vmess links use STANDARD base64
     *  ('+' and '/'), so the decode threw, the catch
     *  fell back to a fragment-only rename, and
     *  displayName — which reads "ps" for vmess and
     *  ignores the fragment — showed "vmess" (the
     *  scheme fallback) on the row. Now the JSON is
     *  parsed through XraySpeedtest.vmessJson, which
     *  tries both alphabets, so the "ps" rewrite
     *  actually lands and the row shows the free name. */
    fun renameFree(cfg: String, n: Int): String {
        val name = freeName(n)
        return try {
            if (cfg.startsWith("vmess://")) {
                val j = XraySpeedtest.vmessJson(cfg)
                    ?: return cfg.substringBefore("#") + "#$name"
                j.put("ps", name)
                val encoded = Base64.encodeToString(
                    j.toString().toByteArray(Charsets.UTF_8),
                    Base64.URL_SAFE or Base64.NO_WRAP
                )
                "vmess://$encoded#$name"
            } else {
                cfg.substringBefore("#") + "#$name"
            }
        } catch (_: Exception) {
            cfg.substringBefore("#") + "#$name"
        }
    }

    /** Parse one fetched body into raw config
     *  lines: a single-line base64 blob is decoded
     *  first (sub 2), profile header lines are
     *  skipped, and only lines that look like a
     *  config (a "://" link or a custom JSON) pass. */
    fun parseBody(rawBody: String): List<String> {
        var body = rawBody.trim()
        if (body.isBlank()) return emptyList()
        val lines = body.lines()
        if (lines.size == 1 && !body.contains("://") &&
            !body.startsWith("{") && !body.startsWith("[")
        ) {
            try {
                var b = body.replace('-', '+').replace('_', '/')
                while (b.length % 4 != 0) b += "="
                body = String(Base64.decode(b, Base64.DEFAULT), Charsets.UTF_8)
            } catch (_: Exception) { /* keep the raw body */ }
        }
        val out = mutableListOf<String>()
        for (line in body.lines()) {
            val c = line.trim()
            if (c.isBlank()) continue
            if (c.startsWith("#profile-")) continue
            if (!c.contains("://") && !XraySpeedtest.isCustom(c)) continue
            out.add(c)
        }
        return out
    }

    /** Fetch one sub on the caller's (background)
     *  thread. Any failure returns an empty list —
     *  the caller never learns which sub failed,
     *  and neither does the user. */
    private fun fetchOne(url: String): List<String> {
        return try {
            val fetchUrl = url.substringBefore("#")
            val conn = java.net.URL(fetchUrl).openConnection()
                as java.net.HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "v2rayNG")
            val code = conn.responseCode
            if (code !in 200..299) return emptyList()
            val body = conn.inputStream.bufferedReader().readText()
            parseBody(body)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Fetch all 4 subs, dedup across them, drop
     *  unsupported schemes and anything that builds
     *  no outbound. Returns the usable configs.
     *  `cancelled` is checked between subs so a
     *  cancel stops the fetch promptly.
     *  1.44 (F): `onSub` reports each finished sub
     *  (done, total) so the waiting line can show
     *  live fetch progress. */
    fun fetchAll(
        cancelled: () -> Boolean = { false },
        onSub: ((done: Int, total: Int) -> Unit)? = null
    ): List<String> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<String>()
        for ((urlIdx, url) in SUB_URLS.withIndex()) {
            if (cancelled()) break
            for (cfg in fetchOne(url)) {
                // 1.31 (free-button crash fix): one malformed
                // line must never kill the fetch — skip it.
                // (outboundFor itself no longer throws
                // either; this is the belt under braces.)
                try {
                    val c = cfg.trim()
                    if (c.isBlank()) continue
                    val scheme = c.substringBefore("://").lowercase()
                    if (scheme in SKIP_SCHEMES) continue
                    if (XraySpeedtest.isCustom(c)) {
                        if (XraySpeedtest.outboundFor(c) == null) continue
                    } else {
                        if (!c.contains("://")) continue
                        if (XraySpeedtest.outboundFor(c) == null) continue
                    }
                    if (seen.add(XraySpeedtest.dedupKey(c))) out += c
                } catch (_: Exception) {
                    continue
                }
            }
            // 1.44 (F): one finished sub.
            try { onSub?.invoke(urlIdx + 1, SUB_URLS.size) } catch (_: Throwable) {}
        }
        return out
    }

    /** 1.30 (spec change 2026-11-18 ~04:49):
     *  ROUTE_COUNT concurrent routes per config,
     *  modeled on the main app's 5-route ping test
     *  (1.35: back to 5, like the main app) — that
     *  many parallel through-tunnel requests to the
     *  test URL, and the MINIMUM duration is that
     *  config's ping, so the test finishes sooner
     *  and the lowest-ping configs reach the list. `measure` is one
     *  through-tunnel attempt (the caller passes
     *  XraySpeedtest.measureOneWithUrl with the
     *  gstatic URL, the same mechanism as the v2ray
     *  ping test). Returns -1 when all routes fail.
     *
     *  Concurrency cap: the page's normal ping test
     *  runs 16 measurements at a time; ROUTE_COUNT
     *  routes x 16 configs would be 160 Xray cores
     *  at once, so attempts queue on this semaphore
     *  (32 in flight at most) — each config still
     *  gets ROUTE_COUNT parallel attempts, they
     *  just start as slots free up.
     *
     *  1.34: measureRoutes is the same rule with a
     *  caller-chosen route count — the screen stage
     *  of pingTopFree uses 2 routes per config and
     *  only the top candidates get the full
     *  ROUTE_COUNT. 1.35: ROUTE_COUNT 10 -> 5, all
     *  five always on for the ping test — the full
     *  test is 50 x 5 = 250 Xray starts instead of
     *  500, and the minimum of the 5 still decides
     *  the ping. measureTenRoutes keeps its name
     *  (no other caller) and now measures
     *  ROUTE_COUNT = 5 routes. */
    const val ROUTE_COUNT = 5
    private val routePermits = java.util.concurrent.Semaphore(32)

    fun measureRoutes(cfg: String, measure: (String) -> Long, routes: Int): Long {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(routes)
        return try {
            val futures = (1..routes).map {
                pool.submit<Long> {
                    routePermits.acquire()
                    try {
                        measure(cfg)
                    } catch (_: Throwable) {
                        -1L
                    } finally {
                        routePermits.release()
                    }
                }
            }
            var best = -1L
            for (f in futures) {
                val d = try { f.get() } catch (_: Throwable) { -1L }
                if (d >= 0 && (best < 0 || d < best)) best = d
            }
            best
        } finally {
            pool.shutdown()
        }
    }

    fun measureTenRoutes(cfg: String, measure: (String) -> Long): Long =
        measureRoutes(cfg, measure, ROUTE_COUNT)

    // 1.34 (free ping test speed): the pre-check runs
    // one cheap TCP connect per config on this many
    // threads in parallel — a socket connect is
    // light, so 128 in flight is fine on the phone,
    // and it is NOT gated by the Xray semaphore.
    // 1.37: 64 -> 128 — measured on the VM harness
    // (70%-dead sample, cached pre-check): the
    // pre-check fell from ~3.1 s to ~2.1 s, the
    // whole test from ~12.2 s to ~11.2 s. Same
    // probes, same 1 s timeout — only more of them
    // in parallel. (A 500 ms timeout was also
    // measured — ~1.5 s faster still — but NOT
    // shipped: it would silently drop healthy
    // configs whose TCP connect takes 500–1000 ms
    // on the user's network, and the VM cannot
    // measure that loss.)
    const val TCP_PRECHECK_THREADS = 128
    // 1.34: the screen measures each survivor on
    // only this many routes to rank it roughly; the
    // FULL 5-route rule still decides the final
    // top 10 (see pingTopFree).
    const val SCREEN_ROUTES = 2
    // 1.34: only this many best-screened configs get
    // the full 5-route test (1.35: was 10 routes).
    // 50, not 40: the harness showed the worst true
    // top-10 config screening at rank 46 in the dense
    // 0%-dead sample (rank 23 in the phone-like
    // 70%-dead sample) — a 40 pool would have lost
    // one true top-10 config there, while 50 kept
    // 10/10 in both samples. The 50 extra Xray
    // starts (~1 s at 5 routes) buy that margin.
    const val SCREEN_TOP = 50

    // 1.38: the free fetch is STREAMING now — a config
    // is added the moment it is proven fast, and the
    // run stops the moment 10 are added (see
    // pingStreamingFree). A config is added ONLY when
    // its full ROUTE_COUNT-route minimum is UNDER this
    // limit. The screen gate below is deliberately a
    // little ABOVE it: the full minimum is taken over
    // MORE attempts than the screen minimum, so it can
    // only come out lower — a screen gate at exactly
    // 200 ms would miss configs whose full minimum is
    // under 200 ms.
    const val FREE_PING_LIMIT_MS = 200L
    const val SCREEN_GATE_MS = 250L

    /** 1.38: the result of one streaming run — the
     *  configs that were added (in add order, each
     *  with its full 5-route minimum, every one of
     *  them < FREE_PING_LIMIT_MS by construction),
     *  how many configs survived the TCP pre-check,
     *  how many were screen-tested and full-tested
     *  before the run stopped, and the phase timings
     *  (the VM harness reads all of this). */
    data class StreamingResult(
        val added: List<Pair<String, Long>>,
        val survivors: Int,
        val screened: Int,
        val fullTested: Int,
        val precheckMs: Long,
        val testMs: Long
    )

    /** 1.38 (free-fetch redesign): STREAMING early-
     *  stop. Same Stage 0 as pingTopFree — dedup,
     *  then the 1.37 TCP pre-check (one probe per
     *  distinct host:port, no retry) — then the
     *  survivors are tested IN FETCH ORDER, 10
     *  engines x 3 configs at once (the same shape
     *  as measureBatch, still bounded by the shared
     *  32-permit semaphore), each config screened
     *  on SCREEN_ROUTES routes and, only when its
     *  screen minimum is under SCREEN_GATE_MS,
     *  confirmed on the full ROUTE_COUNT routes.
     *  A config is added ONLY when its full minimum
     *  is under FREE_PING_LIMIT_MS, and `onAdded`
     *  fires for it at once (the caller adds it to
     *  the list right there). 1.44 (E): `pingLimitMs`
     *  / `screenGateMs` replace the fixed gates —
     *  ANY_GREEN passes Long.MAX_VALUE for both, so
     *  any config whose ping test returns a
     *  measurement is added. 1.44 (F): `onProgress`
     *  reports (phase, processed, total) live. The
     *  moment the 10th
     *  config is added, no further config is
     *  started — screens and full tests already in
     *  flight finish, everything else stops — so a
     *  run that finds 10 fast configs never tests
     *  the rest of the fetch. When the survivors
     *  run out with fewer than 10 passers, the run
     *  simply ends with what passed. `cancelled`
     *  stops new work the same way. pingTopFree is
     *  kept below (superseded, still used by the VM
     *  harness as the 1.37 baseline). */
    fun pingStreamingFree(
        configs: List<String>,
        measure: (String) -> Long,
        onAdded: (String, Long) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
        tcpCheck: (String, Int) -> Long = { h, p -> XraySpeedtest.tcpConnectTime(h, p) },
        // 1.44 (E): the accept gates — UNDER_200 uses
        // FREE_PING_LIMIT_MS / SCREEN_GATE_MS; ANY_GREEN
        // passes Long.MAX_VALUE for both, so any config
        // whose ping test returns a measurement is
        // added (the ping test itself still runs).
        pingLimitMs: Long = FREE_PING_LIMIT_MS,
        screenGateMs: Long = SCREEN_GATE_MS,
        // 1.44 (F): live progress — phase 0 = TCP
        // pre-check, phase 1 = ping test; (processed,
        // total) counts probe tasks / dispositioned
        // configs. Called from worker threads.
        onProgress: ((phase: Int, processed: Int, total: Int) -> Unit)? = null
    ): StreamingResult {
        val tP0 = System.currentTimeMillis()
        // Dedup BEFORE any ping test (same as pingTopFree).
        val seen = mutableSetOf<String>()
        val deduped = configs.filter { seen.add(XraySpeedtest.dedupKey(it)) }
        // Stage 0: TCP pre-check — the 1.37 rule, one
        // probe per distinct host:port, no retry.
        val survivors = java.util.Collections.synchronizedList(mutableListOf<String>())
        val prePool = java.util.concurrent.Executors.newFixedThreadPool(TCP_PRECHECK_THREADS)
        // 1.44 (F): pre-check progress — one report per
        // finished probe task (passthrough configs +
        // host groups), phase 0.
        val precheckDone = java.util.concurrent.atomic.AtomicInteger(0)
        var precheckTotal = 0
        try {
            val byHost = linkedMapOf<String, MutableList<String>>()
            val passthrough = mutableListOf<String>()
            for (cfg in deduped) {
                val srv = if (XraySpeedtest.isSimpleType(cfg)) XraySpeedtest.parseServer(cfg) else null
                if (srv == null) passthrough += cfg
                else byHost.getOrPut("${srv.host}:${srv.port}") { mutableListOf() } += cfg
            }
            val futures = mutableListOf<java.util.concurrent.Future<*>>()
            precheckTotal = passthrough.size + byHost.size
            for (cfg in passthrough) {
                futures += prePool.submit {
                    try {
                        if (!cancelled()) survivors += cfg
                    } finally {
                        // 1.44 (F)
                        try { onProgress?.invoke(0, precheckDone.incrementAndGet(), precheckTotal) } catch (_: Throwable) {}
                    }
                }
            }
            for ((_, cfgs) in byHost) {
                futures += prePool.submit {
                    try {
                        if (cancelled()) return@submit
                        val srv = XraySpeedtest.parseServer(cfgs[0])
                        val alive = if (srv == null) {
                            true
                        } else {
                            try { tcpCheck(srv.host, srv.port) >= 0 } catch (_: Throwable) { false }
                        }
                        if (alive) {
                            for (c in cfgs) {
                                if (!cancelled()) survivors += c
                            }
                        }
                    } finally {
                        // 1.44 (F)
                        try { onProgress?.invoke(0, precheckDone.incrementAndGet(), precheckTotal) } catch (_: Throwable) {}
                    }
                }
            }
            for (f in futures) {
                try { f.get() } catch (_: Throwable) {}
            }
        } finally {
            prePool.shutdown()
        }
        val tP1 = System.currentTimeMillis()
        // Streaming test: survivors in fetch order.
        val added = mutableListOf<Pair<String, Long>>()
        val addLock = Any()
        val addedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val screenedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val fullTestedCount = java.util.concurrent.atomic.AtomicInteger(0)
        // 1.44 (F): configs dispositioned by the ping
        // test so far (screen pass/fail alike), phase 1.
        val pingDone = java.util.concurrent.atomic.AtomicInteger(0)
        fun stopped(): Boolean = cancelled() || addedCount.get() >= FREE_COUNT
        val survivorList = survivors.toList()
        val next = java.util.concurrent.atomic.AtomicInteger(0)
        val engines = (1..PING_ENGINES).map {
            Thread {
                while (true) {
                    if (stopped()) break
                    val batch = mutableListOf<String>()
                    while (batch.size < PING_PER_ENGINE) {
                        val i = next.getAndIncrement()
                        if (i >= survivorList.size) break
                        batch += survivorList[i]
                    }
                    if (batch.isEmpty()) break
                    val inner = java.util.concurrent.Executors.newFixedThreadPool(PING_PER_ENGINE)
                    try {
                        val futures = batch.map { cfg ->
                            inner.submit {
                                try {
                                if (stopped()) return@submit
                                screenedCount.incrementAndGet()
                                val screenMin = try {
                                    measureRoutes(cfg, measure, SCREEN_ROUTES)
                                } catch (_: Throwable) {
                                    -1L
                                }
                                if (screenMin < 0 || screenMin >= screenGateMs) return@submit
                                if (stopped()) return@submit
                                fullTestedCount.incrementAndGet()
                                val fullMin = try {
                                    measureRoutes(cfg, measure, ROUTE_COUNT)
                                } catch (_: Throwable) {
                                    -1L
                                }
                                if (fullMin < 0 || fullMin >= pingLimitMs) return@submit
                                var claimed = false
                                synchronized(addLock) {
                                    if (added.size < FREE_COUNT) {
                                        added += cfg to fullMin
                                        claimed = true
                                    }
                                }
                                if (claimed) {
                                    addedCount.incrementAndGet()
                                    try { onAdded(cfg, fullMin) } catch (_: Throwable) {}
                                }
                                } finally {
                                    // 1.44 (F): one report per
                                    // dispositioned config, phase 1.
                                    try { onProgress?.invoke(1, pingDone.incrementAndGet(), survivorList.size) } catch (_: Throwable) {}
                                }
                            }
                        }
                        for (f in futures) {
                            try { f.get() } catch (_: Throwable) {}
                        }
                    } finally {
                        inner.shutdown()
                    }
                }
            }
        }
        engines.forEach { it.start() }
        engines.forEach { it.join() }
        // 1.44 (F): the ping stage is done — report
        // 100% (processed == total) before returning.
        try { onProgress?.invoke(1, survivorList.size, survivorList.size) } catch (_: Throwable) {}
        val tP2 = System.currentTimeMillis()
        val addedOut = synchronized(addLock) { added.toList() }
        return StreamingResult(
            addedOut, survivorList.size,
            screenedCount.get(), fullTestedCount.get(),
            tP1 - tP0, tP2 - tP1
        )
    }

    /** 1.38: SUPERSEDED by pingStreamingFree above
     *  (the app's free fetch streams now) — kept
     *  unchanged as the VM harness's 1.37 baseline.
     *  1.33 (item 3) ping phase: 10 engines, each
     *  testing 3 configs at once (30 configs in
     *  flight). Each config is still measured with
     *  the ROUTE_COUNT-route rule (measureTenRoutes,
     *  minimum wins) bounded by the shared 32-permit
     *  semaphore on Xray starts. The list is NOT
     *  touched here: this only measures and returns
     *  ALL healthy results ranked lowest-ping-first;
     *  the caller filters its own configs out and
     *  adds only the final top FREE_COUNT.
     *
     *  Dedup (item 2) runs first, inside this
     *  function as well as in fetchAll: the input is
     *  deduped by dedupKey before ANY ping test
     *  starts, so a duplicate is never measured.
     *  `measure` is one through-tunnel attempt (the
     *  caller passes XraySpeedtest.measureOneWithUrl
     *  with the gstatic URL). `onProgress(done,
     *  total)` fires once per finished config, on a
     *  worker thread — the caller posts the live ETA
     *  from it. Returns the ranked healthy results;
     *  an empty list means nothing passed.
     *
     *  1.34 (free ping test speed — measured on the
     *  VM harness against the 1.33 code on the same
     *  sample, see the 1.34 backup): three stages.
     *  (0) TCP PRE-CHECK, in parallel, outside the
     *  Xray semaphore: one cheap TCP connect per
     *  config (the SAME check measureOneWithUrl runs
     *  first inside every route attempt, so a config
     *  dropped here would have failed all ROUTE_COUNT
     *  of its route attempts anyway — each of which
     *  held a semaphore permit for the full ~1 s
     *  timeout). 1.37: ONE probe per distinct
     *  host:port and NO retry — configs are grouped
     *  by host:port first (see Stage 0), so a dead
     *  host costs one 1 s probe for the whole run,
     *  not one per config plus a retry. Simple-type
     *  configs only: custom JSON
     *  and chains skip the TCP check in
     *  measureOneWithUrl, so they skip it here too
     *  and pass straight to the screen. (1) SCREEN:
     *  each survivor is measured on SCREEN_ROUTES
     *  routes, to rank it roughly. (2) FULL: only
     *  the SCREEN_TOP best-screened configs get the
     *  full ROUTE_COUNT-route test, minimum wins —
     *  the final ranking still comes from the full
     *  ROUTE_COUNT-route rule, exactly as before.
     *  1.35: ROUTE_COUNT is 5, so the full test is
     *  50 x 5 = 250 Xray starts instead of 500. When there are no
     *  more survivors than SCREEN_TOP the screen is
     *  skipped and every survivor gets the full
     *  test directly. `onProgress` still fires once
     *  per resolved config (dropped by the
     *  pre-check, screened out, or fully measured),
     *  so the caller's live ETA keeps moving
     *  through all three stages. `tcpCheck` is the
     *  TCP probe (the real tcpConnectTime by
     *  default; the VM harness injects its own so
     *  the phone's dead-server mix can be modeled).
     *  The 32-permit Xray-start cap is UNCHANGED:
     *  the harness showed a higher cap helps only
     *  the sleep-bound stub, not real Xray starts,
     *  and the phone is weaker than the VM — the
     *  speedup comes from fewer starts, not more
     *  concurrent ones. */
    const val PING_ENGINES = 10
    const val PING_PER_ENGINE = 3

    /** One 10-engine x 3-config pass over `items`:
     *  each config is measured with `routes` routes
     *  and `onEach(cfg, ping)` is called once per
     *  finished config (ping -1 when it failed or
     *  the run was cancelled before measuring it). */
    private fun measureBatch(
        items: List<String>,
        routes: Int,
        measure: (String) -> Long,
        onEach: (String, Long) -> Unit,
        cancelled: () -> Boolean
    ) {
        val next = java.util.concurrent.atomic.AtomicInteger(0)
        val engines = (1..PING_ENGINES).map {
            Thread {
                while (true) {
                    if (cancelled()) break
                    val batch = mutableListOf<String>()
                    while (batch.size < PING_PER_ENGINE) {
                        val i = next.getAndIncrement()
                        if (i >= items.size) break
                        batch += items[i]
                    }
                    if (batch.isEmpty()) break
                    val inner = java.util.concurrent.Executors.newFixedThreadPool(PING_PER_ENGINE)
                    try {
                        val futures = batch.map { cfg ->
                            inner.submit {
                                val d = if (cancelled()) -1L else try {
                                    measureRoutes(cfg, measure, routes)
                                } catch (_: Throwable) {
                                    -1L
                                }
                                try { onEach(cfg, d) } catch (_: Throwable) {}
                            }
                        }
                        for (f in futures) {
                            try { f.get() } catch (_: Throwable) {}
                        }
                    } finally {
                        inner.shutdown()
                    }
                }
            }
        }
        engines.forEach { it.start() }
        engines.forEach { it.join() }
    }

    fun pingTopFree(
        configs: List<String>,
        measure: (String) -> Long,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
        tcpCheck: (String, Int) -> Long = { h, p -> XraySpeedtest.tcpConnectTime(h, p) }
    ): List<Pair<String, Long>> {
        // Item 2: dedup BEFORE any ping test.
        val seen = mutableSetOf<String>()
        val deduped = configs.filter { seen.add(XraySpeedtest.dedupKey(it)) }
        val total = deduped.size
        val results = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Long>>())
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        fun reportDone() {
            try { onProgress(done.incrementAndGet(), total) } catch (_: Throwable) {}
        }
        // Stage 0: TCP pre-check (parallel, no semaphore).
        // 1.37: ONE probe per distinct host:port, NO
        // retry. Measured on the VM harness (same
        // 321-config / 70%-dead sample as 1.34/1.35):
        // the pre-check was the DOMINANT phase — ~8.1 s
        // of a ~17.1 s test (47%) — because many
        // configs share a host (321 configs / 218
        // distinct host:ports, 154 dead) and the old
        // code probed the same dead host once per
        // config AND retried each failure: 227 dead
        // configs cost 454 x 1 s probes on 64 threads.
        // AFTER the fix (+ 128 pre-check threads):
        // pre-check ~2.1 s, whole test ~11.2 s — 1.53x
        // on the phone-like mix (see the 1.37 harness
        // summary). A host that refuses/times out once
        // does not revive inside the same run, and the
        // same host:port gives the same verdict for
        // every config on it — so the configs are
        // grouped by host:port first and each distinct
        // server is probed exactly once. Non-simple configs
        // (custom JSON / chains) have no host:port to
        // probe and pass straight through, as before.
        // `onProgress` still fires exactly once per
        // config (a dead group's configs report here;
        // survivors report when their screen/full test
        // ends). The 0%-dead sample is NOT helped by
        // this — its pre-check is already ~0 ms and
        // the 2-route screen dominates there (see the
        // 1.37 harness log) — but the phone-like
        // 70%-dead mix is, and that is the user's
        // network. ROUTE_COUNT, SCREEN_ROUTES,
        // SCREEN_TOP and the 32-permit cap are
        // unchanged.
        val survivors = java.util.Collections.synchronizedList(mutableListOf<String>())
        val prePool = java.util.concurrent.Executors.newFixedThreadPool(TCP_PRECHECK_THREADS)
        try {
            // Group by host:port so each distinct server
            // is probed once; configs with no parseable
            // server pass straight through.
            val byHost = linkedMapOf<String, MutableList<String>>()
            val passthrough = mutableListOf<String>()
            for (cfg in deduped) {
                val srv = if (XraySpeedtest.isSimpleType(cfg)) XraySpeedtest.parseServer(cfg) else null
                if (srv == null) passthrough += cfg
                else byHost.getOrPut("${srv.host}:${srv.port}") { mutableListOf() } += cfg
            }
            val futures = mutableListOf<java.util.concurrent.Future<*>>()
            for (cfg in passthrough) {
                futures += prePool.submit {
                    if (!cancelled()) survivors += cfg
                }
            }
            for ((_, cfgs) in byHost) {
                futures += prePool.submit {
                    if (cancelled()) return@submit
                    val srv = XraySpeedtest.parseServer(cfgs[0])
                    val alive = if (srv == null) {
                        true
                    } else {
                        try { tcpCheck(srv.host, srv.port) >= 0 } catch (_: Throwable) { false }
                    }
                    if (alive) {
                        for (c in cfgs) {
                            if (!cancelled()) survivors += c
                        }
                    } else {
                        for (c in cfgs) reportDone()
                    }
                }
            }
            for (f in futures) {
                try { f.get() } catch (_: Throwable) {}
            }
        } finally {
            prePool.shutdown()
        }
        if (cancelled()) return synchronized(results) { results.toList() }.sortedBy { it.second }
        // Stage 1: screen the survivors on few routes —
        // skipped outright when every survivor fits in
        // the full-test pool anyway.
        val candidates: List<String>
        if (survivors.size <= SCREEN_TOP) {
            candidates = survivors.toList()
        } else {
            val screened = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Long>>())
            measureBatch(survivors, SCREEN_ROUTES, measure, { cfg, d ->
                if (d >= 0) screened += cfg to d else reportDone()
            }, cancelled)
            if (cancelled()) return synchronized(results) { results.toList() }.sortedBy { it.second }
            candidates = synchronized(screened) { screened.toList() }
                .sortedBy { it.second }.take(SCREEN_TOP).map { it.first }
            // Screened-out configs are resolved now; the
            // candidates resolve when their full test ends.
            val candidateKeys = candidates.map { XraySpeedtest.dedupKey(it) }.toSet()
            for (pair in synchronized(screened) { screened.toList() }) {
                if (XraySpeedtest.dedupKey(pair.first) !in candidateKeys) reportDone()
            }
        }
        // Stage 2: full ROUTE_COUNT-route test,
        // minimum wins, on the candidates only. The
        // final ranking still comes from the full
        // ROUTE_COUNT-route rule.
        measureBatch(candidates, ROUTE_COUNT, measure, { cfg, d ->
            if (d >= 0) results += cfg to d
            reportDone()
        }, cancelled)
        return synchronized(results) { results.toList() }.sortedBy { it.second }
    }
}
