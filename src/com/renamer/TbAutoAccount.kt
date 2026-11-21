package com.renamer

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom

/**
 * Automatic free-OpenVpn account creation (3.03), all
 * on-device, plain HTTP, no backend, no Muse at
 * runtime. The flow, in order:
 *
 *  (a) Temp email from a ROTATING list of providers
 *      (3.05): 10minutemail.net, then tempmail.lol,
 *      then mail.tm. Each provider is tried in turn:
 *      get an address, check it with TunnelBear's
 *      validateEmail FIRST (cheap, before any
 *      account is created), create the account only
 *      when the address is accepted, then poll THAT
 *      provider's inbox for the verification mail.
 *      A rejection or a failure moves to the next
 *      provider immediately; providers cycle until
 *      the overall ~2-minute watchdog expires.
 *      No domain is ever hard-coded (tempmail.lol
 *      and mail.tm rotate theirs); only the domain
 *      (never the full address or the password) is
 *      logged.
 *  (b) Signup via TunnelBear's own web API: GET
 *      .../core/web/xzrf for the tb-csrf-token
 *      header, POST .../core/web/validateEmail
 *      (form-urlencoded, NOT JSON), then POST
 *      .../core/web/createAccount with the same
 *      headers and encoding. The password is a
 *      random 16-char on-device secret, never
 *      logged and never shown. createAccount is
 *      NEVER called while probing — validateEmail
 *      only.
 *  (c) Verification: poll the temp mailbox until
 *      the TunnelBear confirmation mail arrives,
 *      open it, follow its "Verify my account"
 *      link through to tunnelbear.com/account/
 *      confirmed (HTTP 200 = activated).
 *
 *  3.05 ROOT CAUSE (why 3.04 failed on the phone):
 *  the 10minutemail.net homepage now 302-redirects
 *  to /m/?lang=en and the address is NO LONGER in
 *  the HTML — data-clipboard-text is empty and the
 *  address loads via JS. The old HTML scraper fell
 *  through to its generic regex and returned the
 *  bogus address "Icon-72@2x.png" (an icon
 *  filename), which TunnelBear rejects with "No MX
 *  records found for domain '2x.png'". The address
 *  is now fetched from the site's own API:
 *  GET address.api.php?sessionid=<TENMM_G1_SESSION>
 *  (the session id comes from the CookieJar after
 *  the homepage GET — the final /m/ response sets
 *  it). The old HTML parse stays ONLY as a
 *  fallback, guarded: any candidate whose domain
 *  ends in an image/asset extension is rejected.
 *
 *  VPN handling (3.06): the provider's site is
 *  filtered in Iran, so this flow NEVER starts
 *  on its own — the caller (OpenVpnActivity's
 *  VPN gate) only calls start() after the
 *  user's own VPN is already up, and the flow
 *  runs as-is through it. Emergency is NEVER
 *  started or stopped by this flow anymore
 *  (the 3.03/3.04 auto-Emergency is gone):
 *  with no VPN the gate shows a 3-second
 *  message and this flow never runs.
 *
 *  Progress (3.06): the Callback's onProgress
 *  reports the stage percentage and the
 *  seconds left (see run/tryProvider). Every
 *  stage is logged (AppLog, section
 *  "freeovpn") with provider name, domain and
 *  HTTP status, so the next failure is
 *  diagnosable from one screenshot. No email or
 *  password is ever logged.
 *
 *  Overall watchdog ~2 minutes (v1.9, was ~5);
 *  every request has its own timeout; on any failure the caller gets
 *  a plain-language message and nothing is left
 *  half-created (no account is stored until the
 *  mail is verified).
 *
 *  v1.10 — the device/IP limit + single-first model:
 *  the site sometimes refuses with "an account was already created
 *  from this device/phone". The signup sends NO device identity
 *  (no device id, no model, no Android id) — the only stable
 *  identifiers are the VPN exit IP and the User-Agent, which used
 *  to be ONE fixed "Pixel 7" string for every signup. So: each
 *  attempt now picks a fresh generic browser UA (no device model),
 *  a limit refusal rotates it, the two account creations are
 *  separate runs (never parallel, never sharing a cookie jar),
 *  and the limit is DETECTED (server message + HTTP 429/403) and
 *  named plainly in the failure message — including the fix the
 *  user can do (another VPN server/country = a new exit IP).
 *  The exact server refusal is logged to logcat only
 *  (never shown, never in the in-app log — v1.10).
 */
object TbAutoAccount {

    interface Callback {
        fun onSuccess()
        fun onFailure(fa: String, en: String, ru: String = "")
        /** 3.06: stage progress for the waiting
         *  screen — default no-op, so existing
         *  callers keep compiling. pct is the
         *  stage percentage (0-100); secondsLeft
         *  is the countdown estimate (never
         *  negative, 0 at success). Posted on
         *  the main thread. */
        fun onProgress(pct: Int, secondsLeft: Int) {}
        /** v1.9: the REAL current stage in plain words
         *  (index 0..4 = address, token, validate,
         *  create, verify-wait), posted on the main
         *  thread. The waiting screen shows this +
         *  elapsed time instead of a fake countdown
         *  during the mail wait. */
        fun onStage(fa: String, en: String, ru: String, index: Int) {}
    }

    /** v1.9: a running creation that can be stopped.
     *  cancel() sets the flag and interrupts the
     *  worker thread (it is sleeping between polls
     *  or inside a <=10 s request, so it exits
     *  within seconds); the callback is suppressed
     *  after a cancel. */
    class JobHandle internal constructor(private val state: RunState, private val thread: Thread) {
        fun cancel() {
            state.cancelled = true
            try { thread.interrupt() } catch (_: Exception) {}
        }
        /** v1.11: the run's last specific failure, device-limit
         *  first — the DualFree deadline path shows THIS instead
         *  of the generic timeout text. Null when no attempt has
         *  finished yet (the deadline then names the stage). */
        fun lastFailure(): Triple<String, String, String>? {
            val dl = state.lastDeviceLimitOutcome
            if (dl != null && !dl.ok) return Triple(dl.fa, dl.en, dl.ru)
            val o = state.lastOutcome
            if (o != null && !o.ok) return Triple(o.fa, o.en, o.ru)
            return null
        }
        /** v1.11: the last stage text the run reached (for the
         *  deadline fallback message). */
        fun lastStageText(): Triple<String, String, String>? = state.lastStageText
        fun currentStage(): Int = state.stage
    }

    /** v1.10: NO fixed device UA anymore. The old flow sent ONE fixed
     *  "Pixel 7" UA for every signup — together with the VPN exit IP
     *  the only stable fingerprint the site sees (the app sends no
     *  device id, no model, no Android id at all). Each creation
     *  attempt now picks one generic browser UA (no device model in
     *  any of them); a device-limit refusal rotates it for the next
     *  attempt. Held in a ThreadLocal: each start() worker thread
     *  carries its own UA, so concurrent jobs never share one. */
    private val UAS = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0",
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    )
    /** v1.13: the FULL fingerprint profile — not just the UA.
     *  The signup service sees the VPN exit IP + the TLS/client
     *  fingerprint (it gets NO device identity). Rotating the UA
     *  alone does not change the TLS handshake, which stays
     *  identical across attempts, so a refused exit IP keeps
     *  being recognised. Each attempt now rotates the whole
     *  header profile (UA, Accept-Language, Accept,
     *  Accept-Encoding, DNT) AND the TLS profile (cipher-suite
     *  order/subset + TLS 1.3 vs 1.2 — see TlsFingerprint).
     *  Held in a ThreadLocal: each start() worker thread
     *  carries its own profile, so concurrent jobs never
     *  share one. */
    private val ACCEPT_LANGS = listOf(
        "en-US,en;q=0.9", "en-GB,en;q=0.9", "en-US,en;q=0.8,fa;q=0.5", "en;q=0.9"
    )
    private val ACCEPTS = listOf(
        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    )
    private val ACCEPT_ENCODINGS = listOf("gzip, deflate", "gzip, deflate, br", "identity")
    private val DNT_VALUES: List<String?> = listOf("1", null, null)
    private data class Fingerprint(
        val ua: String, val lang: String, val accept: String,
        val encoding: String, val dnt: String?, val tlsProfile: Int
    )
    private val fpLocal = ThreadLocal<Fingerprint>()
    private fun currentFp(): Fingerprint = try {
        fpLocal.get() ?: Fingerprint(UAS[0], ACCEPT_LANGS[0], ACCEPTS[0], ACCEPT_ENCODINGS[0], null, 0)
    } catch (_: Exception) {
        Fingerprint(UAS[0], ACCEPT_LANGS[0], ACCEPTS[0], ACCEPT_ENCODINGS[0], null, 0)
    }
    private fun pickFingerprint(): Fingerprint {
        val r = SecureRandom()
        return try {
            Fingerprint(
                UAS[r.nextInt(UAS.size)],
                ACCEPT_LANGS[r.nextInt(ACCEPT_LANGS.size)],
                ACCEPTS[r.nextInt(ACCEPTS.size)],
                ACCEPT_ENCODINGS[r.nextInt(ACCEPT_ENCODINGS.size)],
                DNT_VALUES[r.nextInt(DNT_VALUES.size)],
                r.nextInt(3)
            )
        } catch (_: Exception) {
            Fingerprint(UAS[0], ACCEPT_LANGS[0], ACCEPTS[0], ACCEPT_ENCODINGS[0], null, 0)
        }
    }
    /** v1.13: rotate the whole fingerprint (called at the
     *  start of every run and after a device-limit refusal). */
    internal fun rotateFingerprint() { try { fpLocal.set(pickFingerprint()) } catch (_: Exception) {} }
    private fun currentUa(): String = currentFp().ua
    /** v1.10: rotate this run's UA (called after a device-limit refusal).
     *  v1.13: rotates the WHOLE fingerprint now, not just the UA. */
    internal fun rotateUa() { rotateFingerprint() }
    // v1.9: HARD per-job watchdog — 120 s, not 5 min.
    // A lone job can never spin longer than this.
    // v1.11: DualFree's global deadline is now LONGER
    // (130 s) on purpose — this watchdog fires FIRST
    // and run() returns the run's specific outcome
    // (last attempt / device limit), so the failure
    // dialog names the real reason. DualFree's deadline
    // is only a backstop, and it reads the last recorded
    // outcome first instead of the generic timeout text
    // (v1.10's 110 s deadline always pre-empted it —
    // that is why the user only ever saw the generic
    // "took too long" message). (ROOT CAUSE of the
    // phone's 10-minute hang: this was 5 min PER job
    // and the backup job retried 3x while the screen
    // waited for BOTH jobs — up to 15 min of a frozen 30%.)
    private const val OVERALL_TIMEOUT_MS = 120_000L
    // v1.7: fail fast. A request that takes >10 s on the
    // user's VPN is stuck, not slow — abort it and let
    // rotation move on (was 15 s).
    private const val REQ_TIMEOUT_MS = 10_000
    // v1.8: poll the inbox every 2 s, not 5 s — the
    // verification mail usually arrives within seconds
    // of createAccount, and every poll is one small
    // GET (a few KB); a 5 s poll added up to 5 s of
    // dead wait after the mail had already arrived.
    private const val POLL_INTERVAL_MS = 2_000L
    // 3.05: provider rotation — each provider attempt
    // gets its own verification window (v1.8: 30 s,
    // was 60 s in v1.7 — a mail that has not arrived
    // in 30 s almost never arrives later from that
    // provider, and burning the full 60 s was the
    // dominant wait on the phone: a failed attempt
    // cost 60 s of verify wait before rotating) inside
    // the overall watchdog; providers cycle until the
    // watchdog expires, 2 s backoff between cycles.
    private const val RETRY_BACKOFF_MS = 2_000L
    private const val ATTEMPT_VERIFY_MS = 30_000L

    /** v1.13: exit-IP awareness. The signup must go through
     *  the user's VPN (the provider's site does NOT open from
     *  Iranian internet on a non-VPN IP), so the bypass makes
     *  the site unable to recognise the user as the same person
     *  who created an account before: TLS + header rotation
     *  (above) + a fresh email domain per attempt + NEVER
     *  retrying on an exit IP that already refused. Refused
     *  exit IPs are remembered 24h; a refused provider/domain
     *  is remembered too, so the next job (and the next run)
     *  starts on a different path instead of burning the same
     *  one again. */
    private const val IPIFY_URL = "https://api.ipify.org"
    private const val PREFS_EXIT_IP = "tb_exit_ip"
    private const val REFUSED_IP_TTL_MS = 24 * 60 * 60 * 1000L
    private const val KEY_REFUSED_IPS = "tb_refused_ips"
    private const val KEY_LAST_DEVLIMIT_AT = "tb_last_devlimit_at"
    private const val KEY_REFUSED_PROVIDER = "tb_refused_provider"
    private const val KEY_REFUSED_DOMAIN = "tb_refused_domain"
    private const val KEY_LAST_KNOWN_EXIT_IP = "tb_last_exit_ip"

    /** One tiny HTTPS fetch through the tunnel (the VPN is
     *  up) that returns the current VPN exit IP, or null
     *  when it cannot be determined (then the caller
     *  proceeds as before — an ipify failure must never
     *  block account creation). 5 s timeouts. */
    fun fetchExitIp(): String? {
        return try {
            val conn = (URL(IPIFY_URL).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 5_000
                readTimeout = 5_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", currentUa())
            }
            val code = conn.responseCode
            if (code != 200) { try { conn.disconnect() } catch (_: Exception) {}; return null }
            val body = try { conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim() } catch (_: Exception) { "" }
            try { conn.disconnect() } catch (_: Exception) {}
            if (body.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")) || body.contains(":")) body else null
        } catch (_: Exception) { null }
    }

    private fun refusedExitIps(ctx: Context): MutableMap<String, Long> {
        val out = mutableMapOf<String, Long>()
        try {
            val raw = ctx.getSharedPreferences(PREFS_EXIT_IP, Context.MODE_PRIVATE)
                .getString(KEY_REFUSED_IPS, null) ?: return out
            val now = System.currentTimeMillis()
            for (part in raw.split(";")) {
                val kv = part.split("|")
                if (kv.size != 2) continue
                val at = kv[1].toLongOrNull() ?: continue
                if (now - at < REFUSED_IP_TTL_MS) out[kv[0]] = at
            }
        } catch (_: Exception) {}
        return out
    }

    fun isRefusedExitIp(ctx: Context, ip: String?): Boolean {
        if (ip.isNullOrBlank()) return false
        return refusedExitIps(ctx).containsKey(ip.trim())
    }

    private fun recordRefusedExitIp(ctx: Context, ip: String?) {
        if (ip.isNullOrBlank()) return
        try {
            val map = refusedExitIps(ctx)
            map[ip.trim()] = System.currentTimeMillis()
            val kept = map.entries.sortedByDescending { it.value }.take(20)
            ctx.getSharedPreferences(PREFS_EXIT_IP, Context.MODE_PRIVATE).edit()
                .putString(KEY_REFUSED_IPS, kept.joinToString(";") { "${it.key}|${it.value}" }).apply()
        } catch (_: Exception) {}
    }

    fun lastDeviceLimitAt(ctx: Context): Long {
        return try {
            ctx.getSharedPreferences(PREFS_EXIT_IP, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_DEVLIMIT_AT, 0L)
        } catch (_: Exception) { 0L }
    }

    fun lastRefusedProvider(ctx: Context): String? {
        return try {
            val at = lastDeviceLimitAt(ctx)
            if (at <= 0 || System.currentTimeMillis() - at > REFUSED_IP_TTL_MS) null
            else ctx.getSharedPreferences(PREFS_EXIT_IP, Context.MODE_PRIVATE)
                .getString(KEY_REFUSED_PROVIDER, null)
        } catch (_: Exception) { null }
    }

    fun lastRefusedDomain(ctx: Context): String? {
        return try {
            val at = lastDeviceLimitAt(ctx)
            if (at <= 0 || System.currentTimeMillis() - at > REFUSED_IP_TTL_MS) null
            else ctx.getSharedPreferences(PREFS_EXIT_IP, Context.MODE_PRIVATE)
                .getString(KEY_REFUSED_DOMAIN, null)
        } catch (_: Exception) { null }
    }

    /** v1.13: record a device-limit refusal — provider +
     *  domain + exit IP + time — so the next attempt, the
     *  next run and the background second-account job all
     *  start on a different path. Called from tryProvider
     *  at the two refusal sites (validateEmail, createAccount). */
    private fun recordDeviceLimit(ctx: Context, provider: String, domain: String, exitIp: String?) {
        try {
            recordRefusedExitIp(ctx, exitIp)
            ctx.getSharedPreferences(PREFS_EXIT_IP, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_DEVLIMIT_AT, System.currentTimeMillis())
                .putString(KEY_REFUSED_PROVIDER, provider)
                .putString(KEY_REFUSED_DOMAIN, domain.lowercase())
                .apply()
        } catch (_: Exception) {}
    }

    fun lastKnownExitIp(ctx: Context): String? {
        return try {
            ctx.getSharedPreferences(PREFS_EXIT_IP, Context.MODE_PRIVATE)
                .getString(KEY_LAST_KNOWN_EXIT_IP, null)
        } catch (_: Exception) { null }
    }

    /** v1.13: should the pending second-account job retry on
     *  the CURRENT exit IP? True when the IP is unseen (or
     *  cannot be determined — an ipify failure never blocks
     *  creation); false when this exact exit IP already
     *  refused with the device limit. Also remembers the
     *  current exit IP, so the app can tell when it changes. */
    fun exitIpAllowsRetry(ctx: Context): Boolean {
        val ip = fetchExitIp()
        if (ip.isNullOrBlank()) return true
        try {
            ctx.getSharedPreferences(PREFS_EXIT_IP, Context.MODE_PRIVATE).edit()
                .putString(KEY_LAST_KNOWN_EXIT_IP, ip).apply()
        } catch (_: Exception) {}
        return !isRefusedExitIp(ctx, ip)
    }

    private const val TEMP_MAIL_HOME = "https://10minutemail.net/"
    private const val TEMP_MAIL_API = "https://10minutemail.net/address.api.php"
    private const val TEMP_MAIL_BOX = "https://10minutemail.net/mailbox.ajax.php"
    private const val TEMP_MAIL_READ = "https://10minutemail.net/readmail.html?mid="
    private const val TEMP_LOL_GENERATE = "https://api.tempmail.lol/generate"
    private const val TEMP_LOL_INBOX = "https://api.tempmail.lol/v2/inbox?token="
    private const val MAILTM_DOMAINS = "https://api.mail.tm/domains"
    private const val MAILTM_ACCOUNTS = "https://api.mail.tm/accounts"
    private const val MAILTM_TOKEN = "https://api.mail.tm/token"
    private const val MAILTM_MESSAGES = "https://api.mail.tm/messages"
    private const val ONESEC_GEN = "https://www.1secmail.com/api/v1/?action=genRandomMailbox&count=1"
    private const val ONESEC_API = "https://www.1secmail.com/api/v1/"
    private const val GUERRILLA_API = "https://api.guerrillamail.com/ajax.php"
    private const val MAILDROP_API = "https://api.maildrop.cc/graphql"
    private const val TB_API = "https://prod-api-core.tunnelbear.com/core/web/"

    /** v1.7: the waiting screen's countdown is REAL
     *  now — no fixed ~60 s countdown holding at 3 s.
     *  Stages (address, token, validate, create,
     *  verify-wait) each carry a typical duration
     *  measured from real runs (exponential moving
     *  average, persisted per stage; defaults below
     *  until the first successful run). secondsLeft =
     *  what is left of the current stage + the
     *  typical durations of the stages still to come.
     *  The percentage is the stage weight (5/15/30/
     *  50/50-90/100 as before) — completed stages
     *  are done; the verify wait creeps with its own
     *  elapsed time against its typical length.
     *  v1.8: this per-job secondsLeft is still computed
     *  here, but the DISPLAYED countdown is derived in
     *  DualFree from the single monotonic percentage +
     *  estimatedTotalMs() below — a restarting job can
     *  never push the shown countdown back up. */
    private const val PREFS_STAGE_EMA = "tb_stage_ema"
    private val STAGE_KEYS = listOf("addr", "token", "validate", "create", "verify")
    private val STAGE_DEFAULT_MS = longArrayOf(8_000L, 5_000L, 5_000L, 8_000L, 40_000L)
    private const val STAGE_COUNT = 5

    /** v1.8: the expected length of ONE clean attempt =
     *  the sum of the per-stage typical durations
     *  (EMAs, or the defaults until the first successful
     *  run). DualFree derives seconds-left from this and
     *  the single monotonic percentage, so the countdown
     *  always agrees with the bar and never jumps up. */
    fun estimatedTotalMs(ctx: Context): Long {
        return try {
            val prefs = ctx.getSharedPreferences(PREFS_STAGE_EMA, Context.MODE_PRIVATE)
            var total = 0L
            for (i in 0 until STAGE_COUNT) total += prefs.getLong(STAGE_KEYS[i], STAGE_DEFAULT_MS[i])
            total
        } catch (_: Exception) { STAGE_DEFAULT_MS.sum() }
    }

    /** v1.8: providers ordered by MEASURED speed. Each
     *  provider's time-to-success is kept as an EMA in
     *  tb_provider_ema (keyed by display name); a
     *  provider with no success yet keeps its default
     *  place (stable sort, slowest last). The fastest
     *  provider is tried first, so a slow inbox does not
     *  burn the verify window on every run. Only
     *  successes are recorded — a failure says nothing
     *  about speed. */
    private const val PREFS_PROVIDER_EMA = "tb_provider_ema"
    private fun providerOrder(ctx: Context, startProviderIndex: Int): List<TempProvider> {
        val prefs = try { ctx.getSharedPreferences(PREFS_PROVIDER_EMA, Context.MODE_PRIVATE) } catch (_: Exception) { null }
        val sorted = PROVIDERS.sortedBy { p -> prefs?.getLong(p.displayName, Long.MAX_VALUE) ?: Long.MAX_VALUE }
        return (0 until sorted.size).map { sorted[(startProviderIndex + it) % sorted.size] }
    }

    private fun recordProviderEma(ctx: Context, providerName: String, measuredMs: Long) {
        try {
            if (measuredMs !in 1..300_000) return
            val prefs = ctx.getSharedPreferences(PREFS_PROVIDER_EMA, Context.MODE_PRIVATE)
            val old = prefs.getLong(providerName, -1L)
            val next = if (old < 0) measuredMs else (old + measuredMs) / 2
            prefs.edit().putLong(providerName, next).apply()
        } catch (_: Exception) {}
    }

    /** Per-run stage clock. One instance per start()
     *  call — the two DualFree jobs run in parallel,
     *  so this must never be object-global state.
     *  v1.9: internal (not private) so JobHandle's
     *  internal constructor may hold it. */
    internal class RunState(ctx: Context) {
        val typicalMs: LongArray = LongArray(STAGE_COUNT) { i ->
            try {
                ctx.getSharedPreferences(PREFS_STAGE_EMA, Context.MODE_PRIVATE)
                    .getLong(STAGE_KEYS[i], STAGE_DEFAULT_MS[i])
            } catch (_: Exception) { STAGE_DEFAULT_MS[i] }
        }
        @Volatile var stage: Int = 0
        /** v1.9: set by JobHandle.cancel() — the run
         *  loop, the mail-wait loop and the callback
         *  all stop as soon as they see it. */
        @Volatile var cancelled: Boolean = false
        /** v1.11: the last specific failure this run recorded
         *  (the most recent attempt's outcome; the device-limit
         *  one separately, since it wins at the end) + the last
         *  stage text the run reached. The DualFree deadline
         *  path reads these so the failure dialog can name the
         *  REAL reason instead of the generic timeout — written
         *  by the worker thread, read on the main thread.
         *  Logcat only; never the in-app log (v1.10). */
        @Volatile var lastOutcome: Outcome? = null
        @Volatile var lastDeviceLimitOutcome: Outcome? = null
        @Volatile var lastStageText: Triple<String, String, String>? = null
        /** v1.13: this run's exit IP, cached 30 s (an ipify
         *  fetch per provider attempt would add a round-trip
         *  to every attempt). */
        @Volatile var exitIp: String? = null
        @Volatile var exitIpAtMs: Long = 0
        val startedAtMs: Long = System.currentTimeMillis()
        val stageStartMs = LongArray(STAGE_COUNT) { 0L }
        val stageDurMs = LongArray(STAGE_COUNT) { -1L }
        init { stageStartMs[0] = System.currentTimeMillis() }

        fun noteStage(i: Int) {
            val now = System.currentTimeMillis()
            val prev = stage
            if (prev in 0 until STAGE_COUNT && stageStartMs[prev] > 0 && stageDurMs[prev] < 0) {
                stageDurMs[prev] = now - stageStartMs[prev]
            }
            if (i in 0 until STAGE_COUNT) {
                stage = i
                if (stageStartMs[i] <= 0) stageStartMs[i] = now
            } else {
                stage = STAGE_COUNT // done
            }
        }

        /** Seconds still to go, from measured stage
         *  lengths: what is left of the current stage
         *  plus the typical lengths of the rest. */
        fun secondsLeft(): Int {
            val now = System.currentTimeMillis()
            var remMs = 0L
            val s = stage
            if (s in 0 until STAGE_COUNT) {
                val spent = now - stageStartMs[s]
                remMs += maxOf(0L, typicalMs[s] - spent)
                for (j in s + 1 until STAGE_COUNT) remMs += typicalMs[j]
            }
            return maxOf(5, (remMs / 1000).toInt())
        }

        fun recordEma(ctx: Context) {
            try {
                val ed = ctx.getSharedPreferences(PREFS_STAGE_EMA, Context.MODE_PRIVATE).edit()
                for (i in 0 until STAGE_COUNT) {
                    val measured = stageDurMs[i]
                    if (measured in 1..600_000) {
                        ed.putLong(STAGE_KEYS[i], (typicalMs[i] + measured) / 2)
                    }
                }
                ed.apply()
            } catch (_: Exception) {}
        }
    }

    /** v1.1: saveHook receives each verified account
     *  (email, password) instead of the legacy
     *  single-account save — the dual-account
     *  orchestrator stores it in the accounts list.
     *  Null keeps the v1.0 behaviour (single
     *  account + single entry). */
    fun start(ctx: Context, callback: Callback, saveHook: ((String, String) -> Unit)? = null, startProviderIndex: Int = 0): JobHandle {
        val appCtx = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        val state = RunState(appCtx)
        val progress: (Int) -> Unit = { pct ->
            val left = state.secondsLeft()
            main.post { try { callback.onProgress(pct, left) } catch (_: Exception) {} }
        }
        val stage: (String, String, String, Int) -> Unit = { fa, en, ru, idx ->
            // v1.11: remember the last stage text in the run state —
            // the DualFree deadline path names the stalled stage when
            // no attempt ever finished (failure dialog + logcat only).
            state.lastStageText = Triple(fa, en, ru)
            main.post { try { callback.onStage(fa, en, ru, idx) } catch (_: Exception) {} }
        }
        val worker = Thread {
            // v1.13: this run's full fingerprint profile
            // (fresh per run; rotated again if the site
            // refuses with the device limit).
            try { fpLocal.set(pickFingerprint()) } catch (_: Exception) {}
            val result = try {
                run(appCtx, progress, stage, saveHook, state, startProviderIndex)
            } catch (e: Exception) {
                Outcome(false,
                    "ساخت حساب ناموفق بود: ${shortErr(e)}",
                    "Account creation failed: ${shortErr(e)}",
                    "Не удалось создать аккаунт: ${shortErr(e)}")
            }
            // v1.9: a cancelled job reports nothing —
            // the caller (DualFree timeout / user
            // cancel) already settled the screen.
            if (state.cancelled) return@Thread
            main.post {
                if (state.cancelled) return@post
                if (result.ok) {
                    try { callback.onProgress(100, 0) } catch (_: Exception) {}
                    callback.onSuccess()
                } else callback.onFailure(result.fa, result.en, result.ru)
            }
            try { fpLocal.remove() } catch (_: Exception) {}
        }
        worker.start()
        return JobHandle(state, worker)
    }

    // v1.11: internal (was private) — RunState (internal) must be
    // allowed to hold the last outcome for the DualFree deadline
    // path; the type is still used only inside this file.
    internal class Outcome(val ok: Boolean, val fa: String = "", val en: String = "", val ru: String = "", val deviceLimit: Boolean = false)

    /** Last HTTP status seen, for the stage log.
     *  Codes only — never an email or a password. */
    @Volatile private var lastHttpCode = -1

    /** v1.9: every stage line carries the seconds
     *  since this run started — if it hangs again,
     *  the log alone shows exactly where and for
     *  how long. AppLog already stamps [HH:mm]. */
    /** v1.10: stages go ONLY to logcat (android.util.Log) —
     *  never to the in-app log (user order 2026-11-17): the
     *  in-app log must contain nothing about how the account
     *  is made — no temp-mail provider names, no signup
     *  stages, no verification details. The failure MESSAGES
     *  shown to the user stay (plain words, no method
     *  details). The fa text is kept in the signature so
     *  call sites stay unchanged; logcat carries the
     *  English line + elapsed seconds. */
    private fun logStage(ctx: Context, state: RunState?, fa: String, en: String) {
        val prefix = if (state != null) "[${((System.currentTimeMillis() - state.startedAtMs) / 1000)}s] " else ""
        try { android.util.Log.d("TbAutoAccount", prefix + en) } catch (_: Exception) {}
    }

    private fun shortErr(e: Throwable): String {
        val m = e.message?.take(120)?.trim().orEmpty()
        return if (m.isBlank()) e.javaClass.simpleName else m
    }

    private fun domainOf(email: String): String = email.substringAfter("@", "")

    /** 3.06: the VPN gate already ran in the
     *  caller — this flow only ever runs with
     *  the user's own VPN up. No Emergency here
     *  anymore (see the header comment). */
    /** v1.13: this run's exit IP, cached 30 s. Null when it
     *  cannot be determined (the caller proceeds as before). */
    private fun freshExitIp(state: RunState): String? {
        val now = System.currentTimeMillis()
        if (state.exitIp != null && now - state.exitIpAtMs < 30_000L) return state.exitIp
        val ip = fetchExitIp()
        if (ip != null) {
            state.exitIp = ip
            state.exitIpAtMs = now
        }
        return state.exitIp
    }

    private fun run(ctx: Context, progress: (Int) -> Unit, stage: (String, String, String, Int) -> Unit, saveHook: ((String, String) -> Unit)? = null, state: RunState, startProviderIndex: Int = 0): Outcome {
        val deadline = System.currentTimeMillis() + OVERALL_TIMEOUT_MS
        // v1.13: a fresh fingerprint for this run (not the
        // previous run's profile).
        rotateFingerprint()
        progress(5)
            // 3.05: cycle the providers until the
            // overall watchdog expires. A rejection
            // or failure moves to the next provider
            // immediately; a full cycle costs a 2s
            // backoff, then fresh addresses again.
            // v1.7: the rotation starts at this
            // job's index (DualFree staggers its two
            // parallel jobs) so a dead first provider
            // does not stall both jobs at once.
            // v1.8: the order is by measured speed
            // (fastest provider first — see
            // providerOrder), so a slow inbox does
            // not burn the verify window every run.
            val order0 = providerOrder(ctx, startProviderIndex)
            // v1.13: the background second-account job (and any
            // next run) must not start on the provider that JUST
            // refused with the device limit — rotate it to the
            // end of the order for this run.
            val refusedProvider = lastRefusedProvider(ctx)
            val order = if (refusedProvider != null && order0.size > 1 && order0[0].displayName == refusedProvider)
                order0.drop(1) + order0[0] else order0
            var sawRejection = false
            var last: Outcome? = null
            // v1.10: the device/IP-limit refusal, if it happens —
            // it wins over the generic text at the end (it already
            // carries the plain explanation + the fix).
            var lastDeviceLimit: Outcome? = null
            // v1.14: once a limit-class refusal is seen, try at
            // most 2 providers total on THIS exit IP per run
            // (enough to test whether the refusal is per-email-
            // domain) — then end promptly instead of cycling
            // all providers until the deadline. The refused-
            // exit-IP early exit above usually stops the run
            // after the first refusal; this is the backstop
            // for when the exit IP cannot be determined.
            var limitProvidersTried = 0
            limitRun@ while (System.currentTimeMillis() < deadline && !state.cancelled) {
                for (provider in order) {
                    if (System.currentTimeMillis() >= deadline || state.cancelled) break
                    // v1.13: exit-IP awareness — NEVER retry the
                    // signup on an exit IP that already returned
                    // the device-limit refusal. The run ends
                    // promptly with the honest message instead of
                    // burning attempts on a burned IP.
                    val exitIp = freshExitIp(state)
                    if (exitIp != null && isRefusedExitIp(ctx, exitIp)) {
                        logStage(ctx, state, "آی‌پی خروجی فعلی قبلاً با محدودیت دستگاه رد شده است — تلاش متوقف شد",
                            "Current exit IP was already refused with the device limit — stopping the run")
                        return Outcome(false, DEVICE_LIMIT_FA, DEVICE_LIMIT_EN, DEVICE_LIMIT_RU, true)
                    }
                    val attemptDeadline = minOf(deadline, System.currentTimeMillis() + ATTEMPT_VERIFY_MS)
                    val res = try { tryProvider(ctx, provider, attemptDeadline, progress, stage, saveHook, state) } catch (e: Exception) {
                        AttemptResult(Outcome(false,
                            "ساخت حساب ناموفق بود: ${shortErr(e)}",
                            "Account creation failed: ${shortErr(e)}"), false)
                    }
                    if (res.outcome.ok) return res.outcome
                    if (res.emailRejected) sawRejection = true
                    // v1.11: mirror the outcome into the run state —
                    // the DualFree deadline reads it so the failure
                    // dialog names the real reason (see JobHandle).
                    state.lastOutcome = res.outcome
                    if (res.outcome.deviceLimit) {
                        lastDeviceLimit = res.outcome
                        state.lastDeviceLimitOutcome = res.outcome
                        // v1.13: rotate the WHOLE fingerprint
                        // (TLS + headers), not just the UA.
                        try { rotateFingerprint() } catch (_: Exception) {}
                        logStage(ctx, state, "سرویس می‌گوید از این اتصال قبلاً حساب ساخته شده است — اثر مرورگر عوض شد، سراغ ارائه‌دهنده بعدی",
                            "Service says an account was already created from this connection — rotated the browser fingerprint, moving to the next provider")
                        // v1.14: cap — 2 providers on this exit
                        // IP is enough; stop burning attempts.
                        limitProvidersTried++
                        if (limitProvidersTried >= 2) {
                            logStage(ctx, state, "دو ارائه‌دهنده روی این اتصال با محدودیت رد شدند — تلاش متوقف شد",
                                "Two providers refused with the limit on this connection — stopping the run")
                            break@limitRun
                        }
                    }
                    last = res.outcome
                    logStage(ctx, state, "تلاش با ${provider.displayName} ناموفق بود",
                        "Attempt with ${provider.displayName} failed")
                    // v1.13: small random jitter (300-800 ms)
                    // between attempts, so rapid identical retries
                    // do not look automated.
                    if (!res.outcome.ok && System.currentTimeMillis() < deadline && !state.cancelled) {
                        val jitter = 300L + SecureRandom().nextInt(500)
                        val left = deadline - System.currentTimeMillis()
                        if (left > 0) {
                            try { Thread.sleep(minOf(jitter, left)) } catch (_: Exception) {}
                        }
                    }
                }
                val remaining = deadline - System.currentTimeMillis()
                if (remaining > 0) {
                    try { Thread.sleep(minOf(RETRY_BACKOFF_MS, remaining)) } catch (_: Exception) {}
                }
            }
            // 3.05: honest final message — the temp
            // mails were not accepted; never promise
            // that a fresh email will fix it.
            // v1.10: the device/IP limit wins over the generic text —
            // it already carries the plain explanation + the fix
            // (switch VPN server/country for a new exit IP).
            if (lastDeviceLimit != null) return lastDeviceLimit
            if (sawRejection) {
                return Outcome(false,
                    "ساخت حساب ناموفق بود. ایمیل‌های موقت از طرف سرویس قبول نشدند. دوباره تلاش کن.",
                    "Account creation failed. The service did not accept the temporary emails. Try again.",
                    "Не удалось создать аккаунт. Сервис не принял временные адреса. Попробуйте снова.")
            }
            return last ?: Outcome(false,
                "ساخت حساب ناموفق بود. دوباره تلاش کن.",
                "Account creation failed. Try again.",
                "Не удалось создать аккаунт. Попробуйте снова.")
    }

    // ---------- providers (3.05) ----------

    private class AttemptResult(val outcome: Outcome, val emailRejected: Boolean)

    private data class TempAddress(val address: String, val provider: String, val state: Any?)
    private data class MailTmState(val token: String)

    /** v1.9: one inbox poll result. link = the verify
     *  link when the mail arrived; mailboxDead = the
     *  mailbox endpoint itself is failing (HTTP
     *  403/429/5xx) — NOT "mail not arrived yet".
     *  A dead mailbox fails the attempt FAST instead
     *  of burning the whole verify window. */
    private class PollResult(val link: String?, val mailboxDead: Boolean = false)

    private interface TempProvider {
        val displayName: String
        fun fetchAddress(): TempAddress?
        /** One inbox poll: the TunnelBear verify link, or a dead-mailbox flag. */
        fun pollVerifyLink(addr: TempAddress): PollResult
    }

    /** Rotation order, proved live 2026-11-16. v1.11: 1secmail
     *  + guerrillamail added (both plain JSON APIs, no key, no
     *  JS, no captcha — shapes verified against their public
     *  API docs) in case the first three domains are burned or
     *  blocked on the user's network. Excluded on purpose:
     *  getnada (domain rejected by validateEmail). (1secmail +
     *  guerrillamail were unreachable from the VM proxy — the
     *  phone's network differs, and a dead provider fails fast
     *  here, so rotation just moves on.) */
    private val PROVIDERS: List<TempProvider> = listOf(TenMinuteMail, TempMailLol, MailTm, OneSecMail, GuerrillaMail, Maildrop)

    /** 3.05: an address candidate is plausible only
     *  when its domain is a real mail domain — never
     *  an image/asset filename. This kills the
     *  "Icon-72@2x.png" class of false positives
     *  that broke 3.04 on the phone. */
    private val ASSET_EXTS = listOf(".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico", ".webp", ".css", ".js")

    private fun isPlausibleEmail(cand: String?): Boolean {
        if (cand.isNullOrBlank()) return false
        val c = cand.trim()
        if (!c.contains("@")) return false
        val local = c.substringBefore("@")
        val domain = c.substringAfter("@")
        if (local.isBlank() || domain.isBlank()) return false
        if (!domain.contains(".")) return false
        val low = domain.lowercase()
        if (ASSET_EXTS.any { low.endsWith(it) }) return false
        if (c.contains("tunnelbear", ignoreCase = true)) return false
        if (c.contains("10minutemail", ignoreCase = true)) return false
        return true
    }

    /** (1) 10minutemail.net — address from the site's
     *  own API (address.api.php, session id from the
     *  CookieJar after the homepage GET); the old HTML
     *  parse stays ONLY as a guarded fallback. Inbox
     *  via the existing mailbox.ajax.php + readmail
     *  code, unchanged. */
    private object TenMinuteMail : TempProvider {
        override val displayName = "10minutemail.net"
        override fun fetchAddress(): TempAddress? {
            val jar = CookieJar()
            val home = httpGet(TEMP_MAIL_HOME, jar, mapOf("Accept" to "text/html"))
            val session = jar.get("TENMM_G1_SESSION")
            if (!session.isNullOrBlank()) {
                val api = httpGet(
                    TEMP_MAIL_API + "?sessionid=" + URLEncoder.encode(session, "UTF-8"),
                    jar, mapOf("Accept" to "application/json"))
                if (api.code == 200) {
                    val cand = try { JSONObject(api.body).optString("mail_get_mail", "") } catch (_: Exception) { "" }
                    if (isPlausibleEmail(cand)) return TempAddress(cand.trim(), displayName, jar)
                }
            }
            // Guarded HTML fallback only.
            if (home.code == 200) {
                parseTempEmail(home.body)?.let { return TempAddress(it, displayName, jar) }
            }
            return null
        }
        override fun pollVerifyLink(addr: TempAddress): PollResult {
            val jar = addr.state as? CookieJar ?: return PollResult(null, true)
            val found = try { findVerificationMail(jar) } catch (_: Exception) { return PollResult(null) }
            if (found.dead) return PollResult(null, true)
            val mid = found.mid ?: return PollResult(null)
            val link = try { readVerifyLink(jar, mid) } catch (_: Exception) { null }
            return PollResult(link)
        }
    }

    /** (2) tempmail.lol — no key, no auth. generate
     *  returns {address, token}; inbox v2/inbox
     *  returns {"emails":[{from,subject,body,html}]}.
     *  Domains rotate (never hard-code one). */
    private object TempMailLol : TempProvider {
        override val displayName = "tempmail.lol"
        override fun fetchAddress(): TempAddress? {
            val resp = httpGet(TEMP_LOL_GENERATE, CookieJar(), mapOf("Accept" to "application/json"))
            if (resp.code != 200) return null
            val obj = try { JSONObject(resp.body) } catch (_: Exception) { return null }
            val address = obj.optString("address", "")
            val token = obj.optString("token", "")
            if (!isPlausibleEmail(address) || token.isBlank()) return null
            return TempAddress(address.trim(), displayName, token)
        }
        override fun pollVerifyLink(addr: TempAddress): PollResult {
            val token = addr.state as? String ?: return PollResult(null, true)
            val resp = httpGet(
                TEMP_LOL_INBOX + URLEncoder.encode(token, "UTF-8"),
                CookieJar(), mapOf("Accept" to "application/json"))
            if (resp.code != 200) return PollResult(null, true)
            val arr = try { JSONObject(resp.body).optJSONArray("emails") } catch (_: Exception) { null } ?: return PollResult(null)
            // v1.11: no "confirmation" in the subject is required
            // anymore — any mail from the service counts, and the
            // verify link itself is the real check. Pass 0 = mails
            // from the service; pass 1 = any mail at all (the link
            // host is definitive). The bodies ride inline here, so
            // the second pass costs no extra request.
            for (pass in 0..1) {
                for (i in 0 until arr.length()) {
                    val it = arr.optJSONObject(i) ?: continue
                    val from = it.optString("from", "")
                    val subject = it.optString("subject", "")
                    val fromService = from.contains("tunnelbear", ignoreCase = true) || subject.contains("tunnelbear", ignoreCase = true)
                    if (pass == 0 && !fromService) continue
                    val text = it.optString("body", "") + "\n" + it.optString("html", "")
                    extractVerifyLink(text)?.let { return PollResult(it) }
                }
            }
            return PollResult(null)
        }
    }

    /** (3) mail.tm — domains list, create account
     *  (201), token (Bearer), /messages list, then
     *  /messages/{id} for the body. */
    private object MailTm : TempProvider {
        override val displayName = "mail.tm"
        override fun fetchAddress(): TempAddress? {
            val jar = CookieJar()
            val domResp = httpGet(MAILTM_DOMAINS, jar, mapOf("Accept" to "application/json"))
            if (domResp.code != 200) return null
            val domain = try {
                val arr = JSONArray(domResp.body)
                var found = ""
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val d = o.optString("domain", "")
                    if (d.isBlank()) continue
                    if (found.isBlank()) found = d
                    if (o.optBoolean("isActive", true)) { found = d; break }
                }
                found
            } catch (_: Exception) { "" }
            if (domain.isBlank()) return null
            val local = randomLocal(12)
            val address = "$local@$domain"
            val password = randomPassword()
            val body = JSONObject().put("address", address).put("password", password).toString()
            val accResp = httpPostJson(MAILTM_ACCOUNTS, body, jar)
            if (accResp.code != 201 && accResp.code != 200) return null
            val tokResp = httpPostJson(MAILTM_TOKEN, body, jar)
            if (tokResp.code != 200) return null
            val token = try { JSONObject(tokResp.body).optString("token", "") } catch (_: Exception) { "" }
            if (token.isBlank()) return null
            return TempAddress(address, displayName, MailTmState(token))
        }
        override fun pollVerifyLink(addr: TempAddress): PollResult {
            val state = addr.state as? MailTmState ?: return PollResult(null, true)
            val auth = mapOf("Accept" to "application/json", "Authorization" to "Bearer ${state.token}")
            val resp = httpGet(MAILTM_MESSAGES, CookieJar(), auth)
            if (resp.code != 200) return PollResult(null, true)
            val items = try {
                val trimmed = resp.body.trim()
                when {
                    trimmed.startsWith("[") -> JSONArray(trimmed)
                    else -> JSONObject(trimmed).optJSONArray("hydra:member")
                        ?: JSONObject(trimmed).optJSONArray("member")
                        ?: JSONObject(trimmed).optJSONArray("messages")
                }
            } catch (_: Exception) { null } ?: return PollResult(null)
            // v1.11: no "confirmation" in the subject is required
            // anymore — any mail from the service counts, and the
            // verify link itself is the real check. Pass 0 = mails
            // from the service; pass 1 = any mail at all (the link
            // host is definitive); each mail is fetched at most once
            // per poll.
            val tried = HashSet<String>()
            for (pass in 0..1) {
                for (i in 0 until items.length()) {
                    val it = items.optJSONObject(i) ?: continue
                    val fromObj = it.optJSONObject("from")
                    val from = fromObj?.optString("address", "") ?: it.optString("from", "")
                    val subject = it.optString("subject", "")
                    val fromService = from.contains("tunnelbear", ignoreCase = true) || subject.contains("tunnelbear", ignoreCase = true)
                    if (pass == 0 && !fromService) continue
                    val id = it.optString("id", "")
                    if (id.isBlank() || !tried.add(id)) continue
                    val msg = httpGet("$MAILTM_MESSAGES/$id", CookieJar(), auth)
                    if (msg.code != 200) continue
                    val text = try {
                        val o = JSONObject(msg.body)
                        val t = o.optString("text", "")
                        val h = o.opt("html")
                        val htmlText = when (h) {
                            is JSONArray -> (0 until h.length()).joinToString("\n") { h.optString(it) }
                            is String -> h
                            else -> ""
                        }
                        t + "\n" + htmlText
                    } catch (_: Exception) { msg.body }
                    extractVerifyLink(text)?.let { return PollResult(it) }
                }
            }
            return PollResult(null)
        }
    }

    /** (4) 1secmail.com — plain JSON API, no key, no auth
     *  (verified against the public API docs, v1.11):
     *  genRandomMailbox -> ["local@domain"]; getMessages ->
     *  [{id, from, subject, date}]; readMessage -> {from,
     *  subject, textBody, htmlBody, body}. A mailbox with no
     *  mail answers "Message not found" as plain text, not
     *  JSON — the parse failure just means "nothing yet".
     *  The address itself is the state (login@domain). */
    private object OneSecMail : TempProvider {
        override val displayName = "1secmail.com"
        override fun fetchAddress(): TempAddress? {
            val resp = httpGet(ONESEC_GEN, CookieJar(), mapOf("Accept" to "application/json"))
            if (resp.code != 200) return null
            val address = try { JSONArray(resp.body).optString(0, "") } catch (_: Exception) { "" }
            if (!isPlausibleEmail(address)) return null
            return TempAddress(address.trim(), displayName, address.trim())
        }
        override fun pollVerifyLink(addr: TempAddress): PollResult {
            val address = addr.state as? String ?: return PollResult(null, true)
            val login = address.substringBefore("@")
            val domain = address.substringAfter("@")
            if (login.isBlank() || domain.isBlank()) return PollResult(null, true)
            val q = "login=" + URLEncoder.encode(login, "UTF-8") + "&domain=" + URLEncoder.encode(domain, "UTF-8")
            val resp = httpGet("$ONESEC_API?action=getMessages&$q", CookieJar(), mapOf("Accept" to "application/json"))
            if (resp.code != 200) return PollResult(null, true)
            val items = try { JSONArray(resp.body) } catch (_: Exception) { return PollResult(null) }
            val tried = HashSet<Int>()
            // v1.11 matcher (same as the other providers): pass 0 =
            // mails from the service (any subject); pass 1 = any
            // mail — the verify link itself is the real check.
            for (pass in 0..1) {
                for (i in 0 until items.length()) {
                    val it = items.optJSONObject(i) ?: continue
                    val from = it.optString("from", "")
                    val subject = it.optString("subject", "")
                    val fromService = from.contains("tunnelbear", ignoreCase = true) || subject.contains("tunnelbear", ignoreCase = true)
                    if (pass == 0 && !fromService) continue
                    val id = it.optInt("id", -1)
                    if (id < 0 || !tried.add(id)) continue
                    val msg = httpGet("$ONESEC_API?action=readMessage&$q&id=$id", CookieJar(), mapOf("Accept" to "application/json"))
                    if (msg.code != 200) continue
                    val text = try {
                        val o = JSONObject(msg.body)
                        o.optString("textBody", "") + "\n" + o.optString("htmlBody", "") + "\n" + o.optString("body", "")
                    } catch (_: Exception) { msg.body }
                    extractVerifyLink(text)?.let { return PollResult(it) }
                }
            }
            return PollResult(null)
        }
    }

    /** (5) guerrillamail.com — plain JSON API, no key
     *  (verified against the public API docs, v1.11):
     *  get_email_address -> {email_addr, sid_token};
     *  check_email (seq=0) -> {list: [{mail_id, mail_from,
     *  mail_subject, ...}]}; fetch_email -> {mail_body}.
     *  The sid_token is the session; the PHPSESSID cookie
     *  rides in the jar kept in the address state. */
    private data class GuerrillaState(val sid: String, val jar: CookieJar)
    private object GuerrillaMail : TempProvider {
        override val displayName = "guerrillamail.com"
        override fun fetchAddress(): TempAddress? {
            val jar = CookieJar()
            val resp = httpGet("$GUERRILLA_API?f=get_email_address", jar, mapOf("Accept" to "application/json"))
            if (resp.code != 200) return null
            val obj = try { JSONObject(resp.body) } catch (_: Exception) { return null }
            val address = obj.optString("email_addr", "")
            val sid = obj.optString("sid_token", "")
            if (!isPlausibleEmail(address) || sid.isBlank()) return null
            return TempAddress(address.trim(), displayName, GuerrillaState(sid, jar))
        }
        override fun pollVerifyLink(addr: TempAddress): PollResult {
            val st = addr.state as? GuerrillaState ?: return PollResult(null, true)
            val resp = httpGet("$GUERRILLA_API?f=check_email&seq=0&sid_token=" + URLEncoder.encode(st.sid, "UTF-8"), st.jar, mapOf("Accept" to "application/json"))
            if (resp.code != 200) return PollResult(null, true)
            val list = try { JSONObject(resp.body).optJSONArray("list") } catch (_: Exception) { null } ?: return PollResult(null)
            val tried = HashSet<String>()
            // v1.11 matcher: pass 0 = mails from the service (any
            // subject); pass 1 = any mail — the verify link itself
            // is the real check.
            for (pass in 0..1) {
                for (i in 0 until list.length()) {
                    val it = list.optJSONObject(i) ?: continue
                    val from = it.optString("mail_from", "")
                    val subject = it.optString("mail_subject", "")
                    val fromService = from.contains("tunnelbear", ignoreCase = true) || subject.contains("tunnelbear", ignoreCase = true)
                    if (pass == 0 && !fromService) continue
                    val id = it.optString("mail_id", "")
                    if (id.isBlank() || !tried.add(id)) continue
                    val msg = httpGet("$GUERRILLA_API?f=fetch_email&email_id=" + URLEncoder.encode(id, "UTF-8") + "&sid_token=" + URLEncoder.encode(st.sid, "UTF-8"), st.jar, mapOf("Accept" to "application/json"))
                    if (msg.code != 200) continue
                    val body = try { JSONObject(msg.body).optString("mail_body", "") } catch (_: Exception) { msg.body }
                    extractVerifyLink(body)?.let { return PollResult(it) }
                }
            }
            return PollResult(null)
        }
    }

    /** (6) maildrop.cc — plain GraphQL API, no key (verified
     *  live from the VM, v1.13): POST https://api.maildrop.cc/graphql
     *  {"query":"query { inbox(mailbox:\"<local>\") { id subject headerfrom } }"}
     *  -> {"data":{"inbox":[...]}}; the message body is the "data"
     *  field (raw RFC822) or "html". Any local@maildrop.cc works —
     *  no fetch needed for the address, just a random local part.
     *  Whether the signup service accepts @maildrop.cc at
     *  validateEmail is UNVERIFIED (a rejection fails fast there,
     *  before createAccount, so rotation just moves on). */
    private object Maildrop : TempProvider {
        override val displayName = "maildrop.cc"
        private const val DOMAIN = "maildrop.cc"
        override fun fetchAddress(): TempAddress? {
            val local = try {
                val r = SecureRandom()
                val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
                (1..12).map { chars[r.nextInt(chars.length)] }.joinToString("")
            } catch (_: Exception) { return null }
            val address = "$local@$DOMAIN"
            if (!isPlausibleEmail(address)) return null
            return TempAddress(address, displayName, local)
        }
        private fun gql(query: String): Resp {
            val json = "{\"query\":" + JSONObject.quote(query) + "}"
            return httpPostJson(MAILDROP_API, json, CookieJar())
        }
        override fun pollVerifyLink(addr: TempAddress): PollResult {
            val local = addr.state as? String ?: return PollResult(null, true)
            val inbox = gql("query { inbox(mailbox:\"$local\") { id subject headerfrom } }")
            if (inbox.code != 200) return PollResult(null, true)
            val items = try { JSONObject(inbox.body).optJSONObject("data")?.optJSONArray("inbox") } catch (_: Exception) { null }
                ?: return PollResult(null)
            val tried = HashSet<String>()
            // v1.11 matcher: pass 0 = mails from the service (any
            // subject); pass 1 = any mail — the verify link itself
            // is the real check.
            for (pass in 0..1) {
                for (i in 0 until items.length()) {
                    val it = items.optJSONObject(i) ?: continue
                    val from = it.optString("headerfrom", "")
                    val subject = it.optString("subject", "")
                    val fromService = from.contains("tunnelbear", ignoreCase = true) || subject.contains("tunnelbear", ignoreCase = true)
                    if (pass == 0 && !fromService) continue
                    val id = it.optString("id", "")
                    if (id.isBlank() || !tried.add(id)) continue
                    val msg = gql("query { message(mailbox:\"$local\", id:\"$id\") { data html } }")
                    if (msg.code != 200) continue
                    val text = try {
                        val o = JSONObject(msg.body).optJSONObject("data")?.optJSONObject("message")
                        (o?.optString("data", "") ?: "") + "\n" + (o?.optString("html", "") ?: "")
                    } catch (_: Exception) { msg.body }
                    extractVerifyLink(text)?.let { return PollResult(it) }
                }
            }
            return PollResult(null)
        }
    }

    /** One provider attempt: address -> validateEmail
     *  FIRST -> createAccount only when accepted ->
     *  poll THAT provider's inbox. A rejection moves
     *  to the next provider immediately. */
    private fun tryProvider(ctx: Context, provider: TempProvider, deadline: Long, progress: (Int) -> Unit, stage: (String, String, String, Int) -> Unit, saveHook: ((String, String) -> Unit)? = null, state: RunState): AttemptResult {
        val attemptStartMs = System.currentTimeMillis()
        state.noteStage(0)
        stage("در حال ساخت ایمیل موقت…", "Creating a temporary email…", "Создание временного адреса…", 0)
        // (a) Temp email from this provider.
        val addr = try { provider.fetchAddress() } catch (_: Exception) { null }
        if (addr == null) {
            logStage(ctx, state, "مرحله: گرفتن ایمیل موقت (${provider.displayName}) — ناموفق (HTTP $lastHttpCode)",
                "Stage: temp-mail fetch (${provider.displayName}) — failed (HTTP $lastHttpCode)")
            return AttemptResult(Outcome(false,
                "گرفتن ایمیل موقت ناموفق بود. دوباره تلاش کن.",
                "Getting a temporary email failed. Try again.",
                "Не удалось получить временный адрес. Попробуйте снова."), false)
        }
        val domain = domainOf(addr.address)
        // v1.13: a device-limit retry must NEVER reuse the
        // same temp-mail domain twice in a row — if this
        // domain is the one that JUST refused with the
        // limit, skip this provider at once.
        val refusedDomain = lastRefusedDomain(ctx)
        if (refusedDomain != null && domain.lowercase() == refusedDomain.lowercase()) {
            logStage(ctx, state, "دامنه $domain همین حالا با محدودیت دستگاه رد شده بود — سراغ ارائه‌دهنده بعدی",
                "Domain $domain was just refused with the device limit — moving to the next provider")
            return AttemptResult(Outcome(false,
                "ایمیل موقت از طرف سرویس قبول نشد. دوباره تلاش کن تا یک ایمیل تازه بگیری.",
                "The service did not accept the temporary email. Try again to get a fresh one.",
                "Сервис не принял временный адрес. Попробуйте снова, чтобы получить новый."), false)
        }
        progress(15)
        state.noteStage(1)
        stage("در حال اتصال به سرویس…", "Connecting to the service…", "Подключение к сервису…", 1)
        logStage(ctx, state, "مرحله: گرفتن ایمیل موقت (${provider.displayName}، دامنه $domain) — شد",
            "Stage: temp-mail fetch (${provider.displayName}, domain $domain) — ok")
        val password = randomPassword()
        // (b) Signup API.
        val tbJar = CookieJar()
        val csrf = try { fetchCsrf(tbJar) } catch (_: Exception) { null }
        if (csrf.isNullOrBlank()) {
            logStage(ctx, state, "مرحله: ارتباط با سرویس (گرفتن توکن) — ناموفق (HTTP $lastHttpCode)",
                "Stage: service reach (token) — failed (HTTP $lastHttpCode)")
            return AttemptResult(Outcome(false,
                "ارتباط با سرویس برقرار نشد. دوباره تلاش کن.",
                "Could not reach the service. Try again.",
                "Не удалось связаться с сервисом. Попробуйте снова."), false)
        }
        logStage(ctx, state, "مرحله: ارتباط با سرویس (گرفتن توکن) — شد",
            "Stage: service reach (token) — ok")
        state.noteStage(2)
        stage("در حال بررسی ایمیل…", "Checking the email…", "Проверка адреса…", 2)
        // v1.10: the check also reports the device/IP limit (the
        // site can refuse here too, not only at createAccount).
        val vr = try { validateEmail(tbJar, csrf, addr.address) } catch (_: Exception) { null }
        if (vr?.deviceLimit == true) {
            // v1.13: remember provider + domain + exit IP.
            recordDeviceLimit(ctx, provider.displayName, domain, state.exitIp)
            logStage(ctx, state, "مرحله: بررسی ایمیل (${provider.displayName}، دامنه $domain) — سرویس می‌گوید از این اتصال قبلاً حساب ساخته شده است (HTTP $lastHttpCode)",
                "Stage: email check (${provider.displayName}, domain $domain) — the service says an account was already created from this connection (HTTP $lastHttpCode)")
            return AttemptResult(Outcome(false, DEVICE_LIMIT_FA, DEVICE_LIMIT_EN, DEVICE_LIMIT_RU, true), false)
        }
        val valid = vr?.valid
        if (valid == false) {
            logStage(ctx, state, "مرحله: بررسی ایمیل (${provider.displayName}، دامنه $domain) — سرویس ایمیل را قبول نکرد (HTTP $lastHttpCode)",
                "Stage: email check (${provider.displayName}, domain $domain) — the service rejected the email (HTTP $lastHttpCode)")
            return AttemptResult(Outcome(false,
                "ایمیل موقت از طرف سرویس قبول نشد. دوباره تلاش کن تا یک ایمیل تازه بگیری.",
                "The service did not accept the temporary email. Try again to get a fresh one.",
                "Сервис не принял временный адрес. Попробуйте снова, чтобы получить новый."), true)
        }
        if (valid == null) {
            logStage(ctx, state, "مرحله: بررسی ایمیل (${provider.displayName}، دامنه $domain) — ناموفق (HTTP $lastHttpCode)",
                "Stage: email check (${provider.displayName}, domain $domain) — failed (HTTP $lastHttpCode)")
            return AttemptResult(Outcome(false,
                "بررسی ایمیل ناموفق بود. دوباره تلاش کن.",
                "Checking the email failed. Try again.",
                "Проверка адреса не удалась. Попробуйте снова."), false)
        }
        progress(30)
        state.noteStage(3)
        stage("در حال ساخت حساب…", "Creating the account…", "Создание аккаунта…", 3)
        logStage(ctx, state, "مرحله: بررسی ایمیل (${provider.displayName}، دامنه $domain) — قبول شد",
            "Stage: email check (${provider.displayName}, domain $domain) — accepted")
        val createFail = try { createAccount(tbJar, csrf, addr.address, password) } catch (e: Exception) {
            CreateFail("ساخت حساب ناموفق بود: ${shortErr(e)}",
                "Account creation failed: ${shortErr(e)}",
                "Не удалось создать аккаунт: ${shortErr(e)}", false)
        }
        if (createFail != null) {
            // v1.10: the site's exact refusal is logged (serverMsg),
            // so the log alone shows whether it was the device/IP
            // limit — the user-facing message already says so plainly.
            // v1.13: remember provider + domain + exit IP.
            if (createFail.deviceLimit) recordDeviceLimit(ctx, provider.displayName, domain, state.exitIp)
            val extra = if (createFail.deviceLimit && createFail.serverMsg.isNotBlank())
                " — پیام سرویس: ${createFail.serverMsg}" else ""
            logStage(ctx, state, "مرحله: ساخت حساب (${provider.displayName}، دامنه $domain) — ناموفق (HTTP $lastHttpCode)$extra",
                "Stage: account creation (${provider.displayName}, domain $domain) — failed (HTTP $lastHttpCode)$extra")
            return AttemptResult(Outcome(false, createFail.fa, createFail.en, createFail.ru, createFail.deviceLimit), false)
        }
        progress(50)
        state.noteStage(4)
        stage("در انتظار ایمیل تایید…", "Waiting for the confirmation email…", "Ожидание письма с подтверждением…", 4)
        logStage(ctx, state, "مرحله: ساخت حساب (${provider.displayName}، دامنه $domain) — شد؛ منتظر ایمیل تایید",
            "Stage: account creation (${provider.displayName}, domain $domain) — ok; waiting for the confirmation email")
        // (c) Verification mail from THIS provider's inbox.
        // 3.06: the wait creeps 50 -> 90% with the
        // elapsed time inside this attempt's window.
        val waitRes = try { waitForProviderLink(provider, addr, deadline, progress, state) } catch (_: Exception) { 0 }
        if (waitRes == 2) {
            logStage(ctx, state, "مرحله: ایمیل تایید (${provider.displayName}، دامنه $domain) — صندوق در دسترس نیست، سراغ بعدی",
                "Stage: confirmation email (${provider.displayName}, domain $domain) — mailbox unreachable, moving on")
            return AttemptResult(Outcome(false,
                "صندوق ایمیل موقت در دسترس نبود. دوباره تلاش کن.",
                "The temporary mailbox was unreachable. Try again.",
                "Временный почтовый ящик недоступен. Попробуйте снова."), false)
        }
        if (waitRes != 1) {
            logStage(ctx, state, "مرحله: ایمیل تایید (${provider.displayName}، دامنه $domain) — نرسید",
                "Stage: confirmation email (${provider.displayName}, domain $domain) — did not arrive")
            return AttemptResult(Outcome(false,
                "ایمیل تایید سرویس نرسید (شاید آدرس موقت منقضی شد). دوباره تلاش کن.",
                "The service's confirmation email did not arrive (the temporary address may have expired). Try again.",
                "Письмо с подтверждением не пришло (временный адрес мог истечь). Попробуйте снова."), false)
        }
        logStage(ctx, state, "مرحله: ایمیل تایید (${provider.displayName}، دامنه $domain) — تایید شد؛ حساب ذخیره شد",
            "Stage: confirmation email (${provider.displayName}, domain $domain) — verified; account saved")
        if (saveHook != null) {
            try { saveHook(addr.address, password) } catch (_: Exception) {}
        } else {
            TunnelBear.saveAccount(ctx, addr.address, password)
            try { TunnelBear.ensureSingleEntry(ctx, restoreDeleted = true) } catch (_: Exception) {}
        }
        // v1.7: this run's real stage durations feed
        // the next run's remaining-time estimate.
        state.noteStage(5)
        state.recordEma(ctx)
        // v1.8: this provider's time-to-success feeds
        // the next run's provider order (fastest first).
        recordProviderEma(ctx, provider.displayName, System.currentTimeMillis() - attemptStartMs)
        return AttemptResult(Outcome(true), false)
    }

    /** Polls ONE provider's inbox for the verify link
     *  until the attempt deadline; follows the link
     *  through to the confirmed page. Returns 1 when
     *  verified, 0 when the mail never arrived inside
     *  the window, 2 when the mailbox endpoint itself
     *  is dead (fail the attempt at once — v1.9). */
    private fun waitForProviderLink(provider: TempProvider, addr: TempAddress, deadline: Long, progress: (Int) -> Unit, state: RunState): Int {
        val windowStart = System.currentTimeMillis()
        val windowMs = maxOf(1L, deadline - windowStart)
        while (System.currentTimeMillis() < deadline && !state.cancelled) {
            val frac = ((System.currentTimeMillis() - windowStart).toDouble() / windowMs).coerceIn(0.0, 1.0)
            progress((50 + frac * 40).toInt().coerceIn(50, 90))
            val res = try { provider.pollVerifyLink(addr) } catch (_: Exception) { PollResult(null) }
            if (res.mailboxDead) return 2
            val link = res.link
            if (link != null) {
                try {
                    if (followVerifyLink(CookieJar(), link)) return 1
                } catch (_: Exception) {}
                // Link found but not followed through yet (slow redirect / transient error) — keep polling until the deadline.
            }
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            try { Thread.sleep(minOf(POLL_INTERVAL_MS, remaining)) } catch (_: Exception) {}
        }
        return 0
    }

    /** Guarded HTML fallback for 10minutemail only:
     *  every candidate must pass isPlausibleEmail,
     *  so an icon filename can never be returned. */
    private fun parseTempEmail(html: String): String? {
        val pats = listOf(
            Regex("""data-clipboard-text="([^"]+@[^"]+)""""),
            Regex("""value="([^"]+@[^"]+)""""),
            Regex("""([A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,})""")
        )
        for (re in pats) {
            val cand = re.find(html)?.groupValues?.getOrNull(1)?.trim() ?: continue
            if (isPlausibleEmail(cand)) return cand
        }
        return null
    }

    private fun fetchCsrf(jar: CookieJar): String? {
        val resp = httpGet(TB_API + "xzrf", jar, emptyMap())
        if (resp.code != 200) return null
        return resp.headers["tb-csrf-token"]?.firstOrNull()?.takeIf { it.isNotBlank() }
    }

    /** v1.13: the site's "an account was already created from this
     *  device/phone" refusal, in plain words + what happens next.
     *  v1.14: also covers the site's newer refusal — "we can't
     *  create any more accounts" (this exit network is exhausted).
     *  The site keys this on the exit IP + the TLS/client
     *  fingerprint — the app sends nothing about the real phone:
     *  no device id, no model, no Android id. Popular VPN exits
     *  are shared datacenter IPs many people already used, so
     *  switching to another server of the SAME VPN usually lands
     *  on another already-burned IP — the app already changed
     *  its fingerprint and retries on its own when the exit IP
     *  changes; what helps is a DIFFERENT VPN provider/app
     *  (not just another server) or waiting. */
    private const val DEVICE_LIMIT_FA = "سرویس می‌گوید از این اتصال دیگر نمی‌تواند حساب بسازد — این شبکه خروجی به سقف رسیده است. برنامه اثر انگشتش را عوض کرد و وقتی آی‌پی خروجی عوض شد خودش دوباره تلاش می‌کند؛ یا فیلترشکن را به یک سرویس دیگر عوض کن (نه فقط سرور دیگر همان فیلترشکن)، یا کمی صبر کن."
    private const val DEVICE_LIMIT_EN = "The service says it cannot create any more accounts from this connection — this exit network is exhausted. The app changed its fingerprint and will retry on its own when the exit IP changes; you can help by switching to a different VPN provider/app (not just another server of the same one), or by waiting."
    private const val DEVICE_LIMIT_RU = "Сервис сообщает, что с этого подключения больше нельзя создать аккаунтов — эта выходная сеть исчерпана. Приложение изменило свой отпечаток и попробует само, когда сменится исходящий IP; вы можете помочь, переключившись на другой VPN-сервис (а не просто на другой сервер того же), или подождать."

    /** v1.10: detect that refusal. Matched on the server's own
     *  message and on HTTP 429/403 at create/check time. A recycled
     *  temp address ("email already registered") is NOT this limit
     *  — that one just needs a fresh address, so it is excluded.
     *  v1.14: also the site's newer refusal — "Grrr? For security
     *  reasons we can't create any more accounts." — which matches
     *  none of the old markers and was treated as generic. */
    private fun isDeviceLimit(code: Int, serverMsg: String): Boolean {
        if (code == 429 || code == 403) return true
        val m = serverMsg.lowercase()
        if (m.isBlank()) return false
        if (m.contains("email") && m.contains("already")) return false
        // v1.14: "can't create any more accounts" / "security
        // reasons" — the exhausted-exit refusal.
        if (m.contains("security reasons") || m.contains("can't create") ||
            m.contains("cannot create") || m.contains("any more account")) return true
        return m.contains("device") || m.contains("phone") ||
            (m.contains("already") && (m.contains("creat") || m.contains("register") || m.contains("account"))) ||
            m.contains("too many") || m.contains("limit") || m.contains("exceed")
    }

    /** v1.10: a refused createAccount, with the server's exact words
     *  kept for the log (serverMsg is never user-visible). */
    private class CreateFail(val fa: String, val en: String, val ru: String, val deviceLimit: Boolean, val serverMsg: String = "")
    private class ValidateResult(val valid: Boolean?, val deviceLimit: Boolean)

    /** valid = the service accepted the address; null = network/parse
     *  failure. deviceLimit = the site refused with the
     *  already-created-from-this-connection limit (v1.10 — the site
     *  can refuse here too, not only at createAccount). */
    private fun validateEmail(jar: CookieJar, csrf: String, email: String): ValidateResult {
        val body = "email=" + URLEncoder.encode(email, "UTF-8")
        val resp = httpPostForm(TB_API + "validateEmail", body, jar, csrf)
        if (resp.code != 200) return ValidateResult(null, isDeviceLimit(resp.code, ""))
        val obj = try { JSONObject(resp.body) } catch (_: Exception) { return ValidateResult(null, false) }
        val msg = obj.optString("message", "").ifBlank { obj.optString("error", "") }
        if (!obj.has("is_valid")) return ValidateResult(null, isDeviceLimit(resp.code, msg))
        return ValidateResult(obj.optBoolean("is_valid", false), isDeviceLimit(resp.code, msg))
    }

    /** Null on success, else the failure (fa, en, ru + whether it
     *  was the device/IP limit). */
    private fun createAccount(jar: CookieJar, csrf: String, email: String, password: String): CreateFail? {
        val body = "email=" + URLEncoder.encode(email, "UTF-8") +
            "&password=" + URLEncoder.encode(password, "UTF-8") +
            "&json=1&v=web-1.0&referralKey="
        val resp = httpPostForm(TB_API + "createAccount", body, jar, csrf)
        if (resp.code != 200) {
            if (isDeviceLimit(resp.code, "")) {
                return CreateFail(DEVICE_LIMIT_FA, DEVICE_LIMIT_EN, DEVICE_LIMIT_RU, true)
            }
            return CreateFail(
                "ساخت حساب ناموفق بود (پاسخ ${resp.code}). دوباره تلاش کن.",
                "Account creation failed (response ${resp.code}). Try again.",
                "Не удалось создать аккаунт (ответ ${resp.code}). Попробуйте снова.", false)
        }
        val obj = try { JSONObject(resp.body) } catch (_: Exception) {
            return CreateFail(
                "ساخت حساب ناموفق بود (پاسخ خوانده نشد). دوباره تلاش کن.",
                "Account creation failed (the reply could not be read). Try again.",
                "Не удалось создать аккаунт (ответ не прочитан). Попробуйте снова.", false)
        }
        if (obj.optString("result", "") == "OK" && obj.optString("status", "") == "OK") return null
        val serverMsg = obj.optString("message", "").ifBlank { obj.optString("error", "") }.take(200)
        if (isDeviceLimit(resp.code, serverMsg)) {
            return CreateFail(DEVICE_LIMIT_FA, DEVICE_LIMIT_EN, DEVICE_LIMIT_RU, true, serverMsg)
        }
        return if (serverMsg.isNotBlank()) {
            CreateFail("ساخت حساب قبول نشد: $serverMsg",
                "Account creation was refused: $serverMsg",
                "Создание аккаунта отклонено: $serverMsg", false, serverMsg)
        } else {
            CreateFail("ساخت حساب قبول نشد. دوباره تلاش کن.",
                "Account creation was refused. Try again.",
                "Создание аккаунта отклонено. Попробуйте снова.", false)
        }
    }

    /** v1.9: tri-state — mid when the mail is there,
     *  dead when the mailbox endpoint itself fails
     *  (the attempt must fail fast, not wait out
     *  the window pretending the mail is late). */
    private class MailCheck(val mid: String?, val dead: Boolean = false)

    /** The mid of the TunnelBear confirmation mail, or null when it has not arrived yet. */
    private fun findVerificationMail(jar: CookieJar): MailCheck {
        val resp = httpGet(TEMP_MAIL_BOX, jar, mapOf("Accept" to "application/json, text/javascript, */*"))
        if (resp.code != 200) return MailCheck(null, true)
        val body = resp.body
        try {
            val items = ArrayList<JSONObject>()
            val trimmed = body.trim()
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { items += it }
            } else if (trimmed.startsWith("{")) {
                val obj = JSONObject(trimmed)
                for (key in listOf("mails", "mail", "messages", "data")) {
                    val arr = obj.optJSONArray(key) ?: continue
                    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { items += it }
                }
            }
            // v1.11: no "confirmation" in the subject is required
            // anymore — any mail from the service counts, and the
            // verify link inside its body is the real check
            // (readVerifyLink + extractVerifyLink). If nothing
            // matches by sender, let the caller read the newest
            // mail and extract the link from its body — the link
            // host (email.tunnelbear.com) is definitive.
            for (it in items) {
                val from = it.optString("from", "") + " " + it.optString("sender", "") + " " + it.optString("from_address", "")
                val subject = it.optString("subject", "")
                if (from.contains("tunnelbear", ignoreCase = true) || subject.contains("tunnelbear", ignoreCase = true)) {
                    val id = it.optString("id", "").ifBlank { it.optString("mid", "") }
                    if (id.isNotBlank()) return MailCheck(id)
                }
            }
            if (items.isNotEmpty()) {
                val first = items[0]
                val id = first.optString("id", "").ifBlank { first.optString("mid", "") }
                if (id.isNotBlank()) return MailCheck(id)
                return MailCheck(null)
            }
        } catch (_: Exception) {}
        // Fallback: raw text — a mid near a tunnelbear mention.
        val low = body.lowercase()
        var idx = low.indexOf("tunnelbear")
        while (idx >= 0) {
            val window = body.substring(maxOf(0, idx - 400), minOf(body.length, idx + 400))
            Regex("""mid=(\d+)""").find(window)?.let { return MailCheck(it.groupValues[1]) }
            Regex("""mid["'=: ]+(\d+)""").find(window)?.let { return MailCheck(it.groupValues[1]) }
            idx = low.indexOf("tunnelbear", idx + 1)
        }
        return MailCheck(null)
    }

    private fun readVerifyLink(jar: CookieJar, mid: String): String? {
        val resp = httpGet(TEMP_MAIL_READ + URLEncoder.encode(mid, "UTF-8"), jar, emptyMap())
        if (resp.code != 200) return null
        val body = resp.body.replace("&amp;", "&")
        return extractVerifyLink(body)
    }

    /** The TunnelBear verify link inside a mail body/html
     *  (shared by all three providers). */
    private fun extractVerifyLink(text: String): String? {
        val body = text.replace("&amp;", "&")
        Regex("""https?://email\.tunnelbear\.com/c/[A-Za-z0-9_\-/?=&.%]+""").find(body)?.let { return it.value }
        Regex("""https?://email\.tunnelbear\.com[^"'\s<>]+""").find(body)?.let { return it.value }
        // v1.11: if the service ever stops routing the verify link
        // through email.tunnelbear.com, any tunnelbear.com URL that
        // carries "verify" still counts — followVerifyLink only
        // accepts it when the confirmed page answers 200, so a
        // wrong link just keeps polling instead of breaking.
        Regex("""https?://[A-Za-z0-9.\-]*tunnelbear\.com[^"'\s<>]*verify[^"'\s<>]*""", RegexOption.IGNORE_CASE).find(body)?.let { return it.value }
        return null
    }

    /** Follows the verify link (email host -> verify API -> confirmed page). True only when the
     *  confirmed page answers 200. Redirects are followed by hand so the final URL is checked. */
    private fun followVerifyLink(jar: CookieJar, startUrl: String): Boolean {
        var url = startUrl
        var hops = 0
        while (hops < 8) {
            hops++
            val resp = httpGetNoFollow(url, jar)
            if (resp.code in 301..308) {
                val loc = resp.location ?: return false
                url = try { URL(URL(url), loc).toString() } catch (_: Exception) { return false }
                if (url.contains("tunnelbear.com/account/confirmed", ignoreCase = true)) return true
                continue
            }
            if (resp.code == 200) {
                if (url.contains("tunnelbear.com/account/confirmed", ignoreCase = true)) return true
                // Some verify endpoints answer 200 on their own host with a confirmed body.
                if (url.contains("tunnelbear.com", ignoreCase = true) &&
                    resp.body.contains("confirmed", ignoreCase = true)) return true
                return false
            }
            return false
        }
        return url.contains("tunnelbear.com/account/confirmed", ignoreCase = true)
    }

    /** On-device random password (16 chars, unambiguous alphabet). Never logged, never shown. */
    private fun randomPassword(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val rng = SecureRandom()
        return (1..16).map { chars[rng.nextInt(chars.length)] }.joinToString("")
    }

    /** On-device random local part for mail.tm addresses. */
    private fun randomLocal(n: Int): String {
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        val rng = SecureRandom()
        return (1..n).map { chars[rng.nextInt(chars.length)] }.joinToString("")
    }

    // ---------- minimal HTTP ----------

    private class Resp(val code: Int, val body: String, val headers: Map<String, List<String>>, val location: String?)

    /** Keeps every Set-Cookie (the temp-mail session and any TunnelBear session cookie) and sends them back. */
    private class CookieJar {
        private val map = LinkedHashMap<String, String>()
        fun collect(conn: HttpURLConnection) {
            try {
                val sets = conn.headerFields.entries
                    .filter { it.key != null && it.key.equals("Set-Cookie", ignoreCase = true) }
                    .flatMap { it.value }
                for (sc in sets) {
                    val pair = sc.substringBefore(";").trim()
                    val name = pair.substringBefore("=").trim()
                    val value = pair.substringAfter("=", "")
                    if (name.isNotBlank()) map[name] = value
                }
            } catch (_: Exception) {}
        }
        /** 3.05: read one cookie by name (the 10minutemail
         *  session id for address.api.php). */
        fun get(name: String): String? = map[name]
        fun header(): String? =
            if (map.isEmpty()) null else map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /** v1.13: applies this attempt's fingerprint profile to a
     *  connection — UA + Accept-Language + Accept +
     *  Accept-Encoding + DNT, and the rotating TLS factory
     *  (cipher order/subset + TLS 1.3 vs 1.2) for HTTPS.
     *  Called by every http* helper, so the whole attempt
     *  (temp-mail, token, validate, create, mailbox poll)
     *  carries ONE consistent fingerprint. */
    private fun applyFingerprint(conn: HttpURLConnection) {
        val fp = currentFp()
        conn.setRequestProperty("User-Agent", fp.ua)
        conn.setRequestProperty("Accept-Language", fp.lang)
        conn.setRequestProperty("Accept", fp.accept)
        conn.setRequestProperty("Accept-Encoding", fp.encoding)
        if (fp.dnt != null) conn.setRequestProperty("DNT", fp.dnt)
        if (conn is javax.net.ssl.HttpsURLConnection) {
            try { conn.sslSocketFactory = TlsFingerprint.factoryFor(fp.tlsProfile) } catch (_: Exception) {}
        }
    }

    private fun httpGet(url: String, jar: CookieJar, extra: Map<String, String>): Resp {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = REQ_TIMEOUT_MS
            readTimeout = REQ_TIMEOUT_MS
            requestMethod = "GET"
            applyFingerprint(this)
            jar.header()?.let { setRequestProperty("Cookie", it) }
            for ((k, v) in extra) setRequestProperty(k, v)
        }
        return readResp(conn, jar)
    }

    private fun httpGetNoFollow(url: String, jar: CookieJar): Resp {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = REQ_TIMEOUT_MS
            readTimeout = REQ_TIMEOUT_MS
            requestMethod = "GET"
            applyFingerprint(this)
            jar.header()?.let { setRequestProperty("Cookie", it) }
        }
        return readResp(conn, jar)
    }

    /** Form-urlencoded (NOT JSON) with the web client's headers — the proven route. */
    private fun httpPostForm(url: String, body: String, jar: CookieJar, csrf: String): Resp {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = REQ_TIMEOUT_MS
            readTimeout = REQ_TIMEOUT_MS
            requestMethod = "POST"
            applyFingerprint(this)
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("TB-CSRF-Token", csrf)
            setRequestProperty("tunnelbear-app-id", "com.tunnelbear.web")
            setRequestProperty("tunnelbear-app-version", "1.0.0")
            setRequestProperty("Origin", "https://www.tunnelbear.com")
            jar.header()?.let { setRequestProperty("Cookie", it) }
            doOutput = true
            setFixedLengthStreamingMode(bytes.size)
        }
        try {
            conn.outputStream.use { it.write(bytes) }
        } catch (e: Exception) {
            try { conn.disconnect() } catch (_: Exception) {}
            throw e
        }
        return readResp(conn, jar)
    }

    /** JSON POST (mail.tm accounts/token). */
    private fun httpPostJson(url: String, json: String, jar: CookieJar): Resp {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = REQ_TIMEOUT_MS
            readTimeout = REQ_TIMEOUT_MS
            requestMethod = "POST"
            applyFingerprint(this)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            jar.header()?.let { setRequestProperty("Cookie", it) }
            doOutput = true
            setFixedLengthStreamingMode(bytes.size)
        }
        try {
            conn.outputStream.use { it.write(bytes) }
        } catch (e: Exception) {
            try { conn.disconnect() } catch (_: Exception) {}
            throw e
        }
        return readResp(conn, jar)
    }

    private fun readResp(conn: HttpURLConnection, jar: CookieJar): Resp {
        try {
            val code = conn.responseCode
            lastHttpCode = code
            jar.collect(conn)
            val stream = try {
                if (code in 200..399) conn.inputStream else conn.errorStream
            } catch (_: Exception) {
                try { conn.errorStream } catch (_: Exception) { null }
            }
            val decoded = try {
                val enc = conn.getHeaderField("Content-Encoding") ?: ""
                if (enc.contains("gzip", ignoreCase = true) && stream != null)
                    java.util.zip.GZIPInputStream(stream) else stream
            } catch (_: Exception) { stream }
            val body = try { decoded?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "" } catch (_: Exception) { "" }
            val headers = conn.headerFields.filterKeys { it != null }.mapKeys { it.key!!.lowercase() }
            val loc = try { conn.getHeaderField("Location") } catch (_: Exception) { null }
            return Resp(code, body, headers, loc)
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }
}
