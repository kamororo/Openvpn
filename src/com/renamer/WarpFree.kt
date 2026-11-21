package com.renamer

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CompletionService
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** 1.45 — free WARP (MASQUE) in the WireGuard &
 *  Amnezia page: gateway scan, adaptive ladder,
 *  delivered config, country picker.
 *
 *  WHAT IS REPLICATED — and what is NOT:
 *  - Registration + enrolment: Asha's flow, with
 *    our own keys (WarpIdentity). No aether core
 *    (AGPL-3.0), no Psiphon inner layer (someone
 *    else's keys), no wom (closed binary engine),
 *    no Tor.
 *  - GOOL: masque-outer + wireguard-inner with
 *    the SHIPPED Xray core only (wireguard
 *    outbound + sockopt.dialerProxy, both present
 *    in v26.10.21). The inner identity is a second
 *    registration of our own; it is registered
 *    directly (the API does not care where the
 *    registration comes from — Asha's "from inside
 *    the tunnel" is only a reachability fallback).
 *  - The ladder's bar is REAL traffic: a rung
 *    counts as passed only when generate_204
 *    answers through the tunnel (the same Xray
 *    speedtest path as the v2ray ping test). A
 *    handshake alone is never proof.
 *
 *  LADDER (attempt order depends on the stored
 *  start rung — see attemptOrder; the stored index
 *  is REALLY used as the start, and discarded when
 *  the ladder shape changes):
 *    0 masque-h3 → 1 masque-h2 → 2 gool → 3 wireguard-udp
 *  H3→H2 fallback logs the exact line
 *  "HTTP/2 carried the tunnel where HTTP/3 could
 *  not; staying on h2 for this session".
 *
 *  GATEWAYS: the 18 hardcoded clean peers from
 *  Asha's CoreConfig.kt:170-188 (public Cloudflare
 *  IPs). The 297-gateway Rust list is NOT pasted
 *  in — 18 is enough for v1.45.
 *
 *  COUNTRY: per gateway, GET /cdn-cgi/trace
 *  directly (normal HTTPS, TCP 443 to Cloudflare
 *  is open) and read loc=/colo=. Country is a
 *  FIRST-ATTEMPT preference, never a hard filter:
 *  no healthy gateway of the picked country means
 *  the fastest healthy gateway, said in the log.
 *  The REAL exit country is read from trace
 *  THROUGH the tunnel after connect.
 *
 *  PROBE: per candidate, the Xray speedtest path
 *  (build a masque config for that gateway, run
 *  measureOutboundDelay to generate_204). Budget:
 *  6s per probe (the speedtest core's own
 *  timeouts), 120s cap, target 6 successes,
 *  20s silence window, 16 concurrent for the
 *  TCP/TLS stage, 8 for the Xray stage (a MASQUE
 *  core per probe is heavier than a vless one).
 *  The in-memory registry (records + temp probe
 *  records + identities) is filled by
 *  WireGuardActivity on open — XraySpeedtest's
 *  outboundFor has no Context, so the parser
 *  reads from here, never from prefs. */
object WarpFree {

    // ---- ladder ----
    // 1.50: aether (Asha's own engine) is the first rung — the Xray
    // MASQUE dials are exhausted (1.49: UA removed + fragment off,
    // the server still answers 400 on every gateway). The Xray
    // rungs stay as fallbacks, unchanged.
    // 1.52: the aether rung is now sequential H3-first with
    // verify+retry (see probeAetherSequential); the sig bump
    // resets every stored ladder start to the aether rung.
    const val LADDER_SIG = "1.52:aether-masque,masque-h3,masque-h2,gool,wireguard-udp"
    const val RUNG_AETHER = "aether-masque"
    const val RUNG_H3 = "masque-h3"
    const val RUNG_H2 = "masque-h2"
    const val RUNG_GOOL = "gool"
    const val RUNG_WG = "wireguard-udp"
    private val RUNGS = listOf(RUNG_AETHER, RUNG_H3, RUNG_H2, RUNG_GOOL, RUNG_WG)

    // ---- prefs ----
    private const val PREFS = "renamer_warp_free"
    private const val KEY_RECORDS = "warp_free_configs"
    private const val KEY_ACTIVE = "warp_active_id"
    private const val KEY_HEALTHY = "warp_healthy_gateways"
    private const val KEY_LASTCONN = "warp_lastconn"
    private const val KEY_LADDER_IDX = "warp_ladder_index"
    private const val KEY_LADDER_SIG = "warp_ladder_sig"
    // 1.58 — the transport that won the last fetch
    // ("h2" / "h3"): the fetch tries it first on
    // the next fetch, and the connect path starts
    // with it (never a 35s H3 wait for an H2 winner).
    private const val KEY_LAST_WIN_CARRIER = "warp_last_win_carrier"
    // 1.52 — persistent clean list (Asha's clean_h2_peer /
    // uac-pow-clean-h2.json, same order rule: manual > saved
    // > scanned > defaults). The FFI never reads the JSON
    // file — Kotlin owns the order; the file is written for
    // parity/debugging only. NEVER a hardcoded winner: the
    // list only ever holds IPs this device verified.
    private const val KEY_CLEAN_LIST = "warp_clean_list"
    private const val KEY_SELECTED_CC = "warp_selected_country"
    // 1.58 — one card per working protocol
    // (aether, Xray H3, Xray H2, GOOL, WG).
    const val MAX_RECORDS = 5
    const val MAX_LASTCONN = 8
    // 1.59 — PART 3: the most IP replacements a
    // single connect attempt tries (the original
    // gateway + 3 swaps). Bounded so a connect on
    // a dead network cannot run forever.
    const val MAX_WARP_REPLACEMENTS = 3

    // ---- gateways: 18 hardcoded clean peers ----
    val GATEWAYS = listOf(
        "162.159.199.238", "162.159.197.250",
        "162.159.192.1", "162.159.193.1", "162.159.195.1", "162.159.204.1",
        "162.159.198.1", "162.159.199.1",
        "162.159.192.2", "162.159.193.2", "162.159.195.2", "162.159.204.2",
        "162.159.198.2", "162.159.199.2",
        "2606:4700:d0::a29f:c001", "2606:4700:d1::a29f:c101",
        "188.114.97.1", "188.114.96.1"
    )
    const val GATEWAY_PORT = 443

    // ---- in-memory registry (no Context in the parser) ----
    @Volatile var identity: WarpIdentity.Identity? = null
    @Volatile var goolIdentity: WarpIdentity.Identity? = null
    private val registry = mutableMapOf<String, WarpRecord>()
    private val tempRegistry = mutableMapOf<String, WarpRecord>()
    /** 1.47: record ids of the no-fragment retry
     *  pass. 1.49: the fragment is OFF for every
     *  dial now, so this set no longer changes
     *  the config — it only tags the retry pass's
     *  record ids (kept so the ladder is
     *  untouched). Cleared in unregisterTemp so
     *  a later fetch never inherits a stale entry. */
    private val noFragIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    data class WarpRecord(
        val id: String,
        val name: String,
        val rung: String,
        val alpn: String,
        val gatewayIp: String,
        val gatewayPort: Int,
        val countryCode: String,
        val colo: String,
        val serverSpkiB64: String,
        val serverSpkiSha256: String,
        val pingMs: Long,
        val exitCountry: String,
        val exitColo: String,
        val createdAt: Long,
        val wgTunnelName: String,
        // 1.59 — the network key the config was built
        // on (NetworkProfile.currentKey at fetch time:
        // "mci" / "irancell" / "rightel" / "wifi" /
        // "unknown"). PART 3's connect-time replacement
        // compares it with the CURRENT network: a
        // config fetched on Irancell that fails on MCI
        // gets its IP swapped in place instead of a
        // plain failure. "" = a pre-1.59 record (any
        // network may use it).
        val buildNetwork: String = ""
    ) {
        /** The link VpnConfigBuilder builds from.
         *  Empty for the wireguard rung (it connects
         *  through the WG engine, not Xray). */
        fun link(): String = when (rung) {
            RUNG_GOOL -> "CHAIN::gool|||WGXRAY::gool|||WARP::$id"
            RUNG_WG -> ""
            else -> "WARP::$id"
        }

        fun rungLabel(ctx: Context): String = when (rung) {
            RUNG_AETHER -> "AETHER MASQUE"
            RUNG_H3 -> "MASQUE H3"
            RUNG_H2 -> "MASQUE H2"
            RUNG_GOOL -> "GOOL"
            RUNG_WG -> AppLanguage.t(ctx, "وایرگارد", "WireGuard")
            else -> rung
        }

        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id); put("name", name); put("rung", rung)
            put("alpn", alpn); put("gateway_ip", gatewayIp)
            put("gateway_port", gatewayPort)
            put("country_code", countryCode); put("colo", colo)
            put("server_spki", serverSpkiB64)
            put("server_spki_sha256", serverSpkiSha256)
            put("ping_ms", pingMs)
            put("exit_country", exitCountry); put("exit_colo", exitColo)
            put("created_at", createdAt)
            put("wg_tunnel_name", wgTunnelName)
            put("build_network", buildNetwork)
        }

        companion object {
            fun fromJson(j: JSONObject): WarpRecord? = try {
                WarpRecord(
                    id = j.optString("id", ""),
                    name = j.optString("name", ""),
                    rung = j.optString("rung", ""),
                    alpn = j.optString("alpn", ""),
                    gatewayIp = j.optString("gateway_ip", ""),
                    gatewayPort = j.optInt("gateway_port", GATEWAY_PORT),
                    countryCode = j.optString("country_code", ""),
                    colo = j.optString("colo", ""),
                    serverSpkiB64 = j.optString("server_spki", ""),
                    serverSpkiSha256 = j.optString("server_spki_sha256", ""),
                    pingMs = j.optLong("ping_ms", -1L),
                    exitCountry = j.optString("exit_country", ""),
                    exitColo = j.optString("exit_colo", ""),
                    createdAt = j.optLong("created_at", 0L),
                    wgTunnelName = j.optString("wg_tunnel_name", ""),
                    buildNetwork = j.optString("build_network", "")
                ).takeIf { it.id.isNotBlank() && it.rung.isNotBlank() }
            } catch (_: Exception) { null }
        }
    }

    data class HealthyGateway(
        val ip: String,
        val countryCode: String,
        val colo: String,
        val spkiB64: String,
        val spkiSha256: String,
        val pingMs: Long,
        val at: Long
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("ip", ip); put("cc", countryCode); put("colo", colo)
            put("spki", spkiB64); put("sha", spkiSha256)
            put("ms", pingMs); put("at", at)
        }

        companion object {
            fun fromJson(j: JSONObject): HealthyGateway? = try {
                HealthyGateway(
                    ip = j.optString("ip", ""),
                    countryCode = j.optString("cc", ""),
                    colo = j.optString("colo", ""),
                    spkiB64 = j.optString("spki", ""),
                    spkiSha256 = j.optString("sha", ""),
                    pingMs = j.optLong("ms", -1L),
                    at = j.optLong("at", 0L)
                ).takeIf { it.ip.isNotBlank() }
            } catch (_: Exception) { null }
        }
    }

    data class LastConn(val ip: String, val carrier: String, val ms: Long, val at: Long) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("ip", ip); put("carrier", carrier); put("ms", ms); put("at", at)
        }

        companion object {
            fun fromJson(j: JSONObject): LastConn? = try {
                LastConn(
                    ip = j.optString("ip", ""),
                    carrier = j.optString("carrier", ""),
                    ms = j.optLong("ms", -1L),
                    at = j.optLong("at", 0L)
                ).takeIf { it.ip.isNotBlank() && it.carrier.isNotBlank() }
            } catch (_: Exception) { null }
        }
    }

    // ---- country names (compact table, common codes) ----
    private val COUNTRY_NAMES_FA = mapOf(
        "US" to "آمریکا", "DE" to "آلمان", "NL" to "هلند",
        "GB" to "بریتانیا", "FR" to "فرانسه", "FI" to "فنلاند",
        "SE" to "سوئد", "NO" to "نروژ", "DK" to "دانمارک",
        "PL" to "لهستان", "CZ" to "چک", "AT" to "اتریش",
        "CH" to "سوئیس", "IT" to "ایتالیا", "ES" to "اسپانیا",
        "PT" to "پرتغال", "TR" to "ترکیه", "IR" to "ایران",
        "AE" to "امارات", "SA" to "عربستان", "QA" to "قطر",
        "IN" to "هند", "SG" to "سنگاپور", "JP" to "ژاپن",
        "KR" to "کره جنوبی", "HK" to "هنگ‌کنگ", "TW" to "تایوان",
        "AU" to "استرالیا", "NZ" to "نیوزیلند", "CA" to "کانادا",
        "MX" to "مکزیک", "BR" to "برزیل", "AR" to "آرژانتین",
        "CL" to "شیلی", "IL" to "اسرائیل", "EG" to "مصر",
        "ZA" to "آفریقای جنوبی", "NG" to "نیجریه", "KE" to "کنیا",
        "RU" to "روسیه", "UA" to "اوکراین", "RO" to "رومانی",
        "HU" to "مجارستان", "GR" to "یونان", "BG" to "بلغارستان",
        "HR" to "کرواسی", "RS" to "صربستان", "IS" to "ایسلند",
        "IE" to "ایرلند", "BE" to "بلژیک", "LU" to "لوکزامبورگ",
        "SK" to "اسلواکی", "SI" to "اسلوونی", "LT" to "لیتوانی",
        "LV" to "لتونی", "EE" to "استونی", "MD" to "مولداوی",
        "KZ" to "قزاقستان", "AM" to "ارمنستان", "GE" to "گرجستان",
        "AZ" to "آذربایجان", "IQ" to "عراق", "JO" to "اردن",
        "LB" to "لبنان", "KW" to "کویت", "BH" to "بحرین",
        "OM" to "عمان", "PK" to "پاکستان", "BD" to "بنگلادش",
        "TH" to "تایلند", "VN" to "ویتنام", "MY" to "مالزی",
        "ID" to "اندونزی", "PH" to "فیلیپین"
    )
    private val COUNTRY_NAMES_EN = mapOf(
        "US" to "United States", "DE" to "Germany", "NL" to "Netherlands",
        "GB" to "United Kingdom", "FR" to "France", "FI" to "Finland",
        "SE" to "Sweden", "NO" to "Norway", "DK" to "Denmark",
        "PL" to "Poland", "CZ" to "Czechia", "AT" to "Austria",
        "CH" to "Switzerland", "IT" to "Italy", "ES" to "Spain",
        "PT" to "Portugal", "TR" to "Turkey", "IR" to "Iran",
        "AE" to "UAE", "SA" to "Saudi Arabia", "QA" to "Qatar",
        "IN" to "India", "SG" to "Singapore", "JP" to "Japan",
        "KR" to "South Korea", "HK" to "Hong Kong", "TW" to "Taiwan",
        "AU" to "Australia", "NZ" to "New Zealand", "CA" to "Canada",
        "MX" to "Mexico", "BR" to "Brazil", "AR" to "Argentina",
        "CL" to "Chile", "IL" to "Israel", "EG" to "Egypt",
        "ZA" to "South Africa", "NG" to "Nigeria", "KE" to "Kenya",
        "RU" to "Russia", "UA" to "Ukraine", "RO" to "Romania",
        "HU" to "Hungary", "GR" to "Greece", "BG" to "Bulgaria",
        "HR" to "Croatia", "RS" to "Serbia", "IS" to "Iceland",
        "IE" to "Ireland", "BE" to "Belgium", "LU" to "Luxembourg",
        "SK" to "Slovakia", "SI" to "Slovenia", "LT" to "Lithuania",
        "LV" to "Latvia", "EE" to "Estonia", "MD" to "Moldova"
    )

    fun countryName(ctx: Context, cc: String): String {
        val code = cc.trim().uppercase()
        if (code.isBlank()) return AppLanguage.t(ctx, "نامشخص", "Unknown")
        return if (AppLanguage.isEnglish(ctx)) COUNTRY_NAMES_EN[code] ?: code
        else COUNTRY_NAMES_FA[code] ?: code
    }

    // ---- prefs store ----
    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadAll(ctx: Context): MutableList<WarpRecord> {
        val out = mutableListOf<WarpRecord>()
        try {
            val arr = JSONArray(prefs(ctx).getString(KEY_RECORDS, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { WarpRecord.fromJson(it)?.let { r -> out += r } }
            }
        } catch (_: Exception) {}
        synchronized(registry) {
            registry.clear()
            for (r in out) registry[r.id] = r
        }
        return out
    }

    fun saveAll(ctx: Context, records: List<WarpRecord>) {
        try {
            val arr = JSONArray()
            for (r in records.take(MAX_RECORDS)) arr.put(r.toJson())
            prefs(ctx).edit().putString(KEY_RECORDS, arr.toString()).apply()
        } catch (_: Exception) {}
        synchronized(registry) {
            registry.clear()
            for (r in records.take(MAX_RECORDS)) registry[r.id] = r
        }
    }

    fun updateRecord(ctx: Context, rec: WarpRecord) {
        val all = loadAll(ctx).toMutableList()
        val idx = all.indexOfFirst { it.id == rec.id }
        if (idx >= 0) all[idx] = rec else all.add(0, rec)
        saveAll(ctx, all)
    }

    fun deleteRecord(ctx: Context, id: String) {
        saveAll(ctx, loadAll(ctx).filter { it.id != id })
        synchronized(registry) { registry.remove(id) }
        if (activeId(ctx) == id) setActiveId(ctx, "")
    }

    fun activeId(ctx: Context): String = try {
        prefs(ctx).getString(KEY_ACTIVE, "") ?: ""
    } catch (_: Exception) { "" }

    fun setActiveId(ctx: Context, id: String) {
        try { prefs(ctx).edit().putString(KEY_ACTIVE, id).apply() } catch (_: Exception) {}
    }

    fun loadHealthy(ctx: Context): List<HealthyGateway> {
        val out = mutableListOf<HealthyGateway>()
        try {
            val arr = JSONArray(prefs(ctx).getString(KEY_HEALTHY, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { HealthyGateway.fromJson(it)?.let { g -> out += g } }
            }
        } catch (_: Exception) {}
        return out
    }

    private fun saveHealthy(ctx: Context, list: List<HealthyGateway>) {
        try {
            val arr = JSONArray()
            for (g in list) arr.put(g.toJson())
            prefs(ctx).edit().putString(KEY_HEALTHY, arr.toString()).apply()
        } catch (_: Exception) {}
    }

    fun loadLastconn(ctx: Context): List<LastConn> {
        val out = mutableListOf<LastConn>()
        try {
            val arr = JSONArray(prefs(ctx).getString(KEY_LASTCONN, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { LastConn.fromJson(it)?.let { l -> out += l } }
            }
        } catch (_: Exception) {}
        return out
    }

    private fun saveLastconn(ctx: Context, list: List<LastConn>) {
        try {
            val arr = JSONArray()
            for (l in list.take(MAX_LASTCONN)) arr.put(l.toJson())
            prefs(ctx).edit().putString(KEY_LASTCONN, arr.toString()).apply()
        } catch (_: Exception) {}
    }

    /** 1.52 — the persistent clean list: gateways this
     *  device verified with real traffic, most-recent
     *  first. The seed list is never the solution by
     *  itself — this is what the scan + verify grew.
     *  1.59 — the list is PER NETWORK now
     *  (NetworkProfile): this reads the CURRENT
     *  network's list, so a winner verified on MCI is
     *  never offered as a winner on Irancell. A
     *  failure here falls back to the pre-1.59 global
     *  list so the fetch never breaks on a prefs
     *  error. */
    fun loadCleanList(ctx: Context): List<String> {
        return try {
            NetworkProfile.warpCleanList(ctx, NetworkProfile.currentKey(ctx))
        } catch (_: Exception) {
            loadCleanListGlobal(ctx)
        }
    }

    /** 1.59 — the pre-1.59 GLOBAL clean list (the
     *  KEY_CLEAN_LIST pref). Read by the one-time
     *  migration (NetworkProfile.ensureMigrated)
     *  only; nothing else should call it. */
    fun loadCleanListGlobal(ctx: Context): List<String> {
        return try {
            val arr = JSONArray(prefs(ctx).getString(KEY_CLEAN_LIST, "[]") ?: "[]")
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val ip = arr.optString(i, "").trim()
                if (ip.isNotBlank() && ip !in out) out += ip
            }
            out
        } catch (_: Exception) { emptyList() }
    }

    /** 1.59 — a SPECIFIC network's clean list (the
     *  sweep works on the network it started on,
     *  which may no longer be the current one). */
    fun loadCleanListFor(ctx: Context, networkKey: String): List<String> =
        try { NetworkProfile.warpCleanList(ctx, networkKey) } catch (_: Exception) { emptyList() }

    fun saveCleanListFor(ctx: Context, networkKey: String, list: List<String>) {
        try { NetworkProfile.saveWarpCleanList(ctx, networkKey, list) } catch (_: Exception) {}
    }

    private fun saveCleanList(ctx: Context, list: List<String>) {
        try {
            // 1.59 — the list is stored per network
            // (current network here); the mirror file
            // keeps that network's list (inspection
            // only, as before — the engine never
            // reads it).
            NetworkProfile.saveWarpCleanList(ctx, NetworkProfile.currentKey(ctx), list)
            try {
                val f = java.io.File(ctx.filesDir, "uac-pow-clean-h2.json")
                val peers = JSONArray()
                for (ip in list.take(AetherRungPlan.MAX_CLEAN_LIST)) {
                    peers.put(if (ip.contains(":")) "[$ip]:443" else "$ip:443")
                }
                f.writeText(JSONObject().apply { put("peers", peers) }.toString())
            } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    /** 1.52 — a gateway just passed real traffic: save
     *  it at the head of the clean list, most-recent
     *  first, and say so in the log.
     *  1.54 — idempotent: the rung saves the winner and
     *  deliver() saves it again, which logged the line
     *  twice on device (02:05). When the IP is already
     *  at the head, there is nothing to save or say. */
    fun saveCleanGateway(ctx: Context, ip: String) {
        if (ip.isBlank()) return
        if (loadCleanList(ctx).firstOrNull() == ip.trim()) return
        saveCleanList(ctx, AetherRungPlan.mergeCleanList(loadCleanList(ctx), ip))
        wlog(ctx, "آی‌پی تمیز جدید ذخیره شد: $ip.",
            "New clean IP saved: $ip.")
    }

    fun ladderStartIndex(ctx: Context): Int {
        return try {
            val p = prefs(ctx)
            if (p.getString(KEY_LADDER_SIG, "") != LADDER_SIG) 0
            else p.getInt(KEY_LADDER_IDX, 0).coerceIn(0, RUNGS.size - 1)
        } catch (_: Exception) { 0 }
    }

    fun saveLadderWin(ctx: Context, rung: String) {
        val idx = RUNGS.indexOf(rung).coerceAtLeast(0)
        try {
            prefs(ctx).edit()
                .putInt(KEY_LADDER_IDX, idx)
                .putString(KEY_LADDER_SIG, LADDER_SIG)
                .apply()
        } catch (_: Exception) {}
    }

    /** 1.58 — the last winning transport ("h2" /
     *  "h3", "" when unknown). Written by deliver()
     *  on every win; read by the fetch (first-carrier
     *  + fast path) and stored on the record itself
     *  (alpn) for the connect path. */
    fun lastWinCarrier(ctx: Context): String = try {
        prefs(ctx).getString(KEY_LAST_WIN_CARRIER, "") ?: ""
    } catch (_: Exception) { "" }

    fun saveLastWinCarrier(ctx: Context, carrier: String) {
        try {
            prefs(ctx).edit().putString(KEY_LAST_WIN_CARRIER, carrier).apply()
        } catch (_: Exception) {}
    }

    /** 1.58 — the inner WireGuard endpoint for GOOL
     *  ("<ip>:2408"): the gool identity's assigned
     *  endpoint, else engage.cloudflareclient.com
     *  (the same value wireguardOutbound uses). */
    fun goolInnerPeer(ctx: Context): String? {
        val idn = try { goolIdentity ?: WarpIdentity.load(ctx, "gool") } catch (_: Exception) { null }
        val ep = idn?.wgPeerEndpointV4?.trim().orEmpty()
        val host = if (ep.isNotBlank()) ep else "engage.cloudflareclient.com"
        return "$host:2408"
    }

    /** Attempt order for this fetch: the stored winning rung is
     *  the start rung and the rest follow in ladder order. The
     *  wireguard rung is never in this list — the activity runs
     *  it directly (its index means "straight to wireguard"). */
    fun attemptOrder(ctx: Context): List<String> {
        val start = ladderStartIndex(ctx)
        if (start == RUNGS.indexOf(RUNG_WG)) return emptyList()
        val rotated = RUNGS.drop(start) + RUNGS.take(start)
        return rotated.filter { it != RUNG_WG }
    }

    // 1.71 (item 9): pinnedCountries / setPinned are deleted with the relay
    // feature — the country picker (whose pin sort they served) is gone and
    // they had no callers left. selectedCountry / setSelectedCountry STAY:
    // runFetchInner reads selectedCountry as the fetch's gateway preference.

    fun selectedCountry(ctx: Context): String = try {
        prefs(ctx).getString(KEY_SELECTED_CC, "") ?: ""
    } catch (_: Exception) { "" }

    fun setSelectedCountry(ctx: Context, cc: String) {
        try { prefs(ctx).edit().putString(KEY_SELECTED_CC, cc).apply() } catch (_: Exception) {}
    }

    // ---- in-memory registry for the parser ----
    fun registerTemp(rec: WarpRecord) {
        synchronized(tempRegistry) { tempRegistry[rec.id] = rec }
    }

    fun unregisterTemp(id: String) {
        synchronized(tempRegistry) { tempRegistry.remove(id) }
        noFragIds.remove(id)
    }

    private fun recordFor(id: String): WarpRecord? {
        synchronized(tempRegistry) { tempRegistry[id]?.let { return it } }
        synchronized(registry) { return registry[id] }
    }

    /** 1.46 — ROOT CAUSES of the 1.45 device failure
     *  (user 2026-11-19: registration 200, scan 18→10,
     *  but NO gateway passed real traffic; Asha connects
     *  on both networks). Read in the shipped core source
     *  (Xray v26.10.21, commit 1b1647c) and in Asha's
     *  aether — and PROVEN LIVE on the VM against a
     *  local H2 CONNECT-IP server with the same core
     *  binary (see WORKLOG-1.46.md for the full matrix):
     *
     *  (1) warp.address WITHOUT CIDR — RULED OUT live.
     *  dialer.go:239-253 uses netip.ParsePrefix, BUT
     *  the config layer normalizes first:
     *  parseWarpAddress (transport_method.go:907-930)
     *  turns a bare IP into /32 or /128 itself. The
     *  1.45-shape config (bare IPs) ESTABLISHED a tunnel
     *  on the VM. CIDR is still sent (canonical form,
     *  matches warp_test.go) but it was NOT the bug.
     *
     *  (2) warp.publicKey was the LIVE LEAF SPKI from a
     *  JDK handshake — THE PRIME SUSPECT, proven as a
     *  mechanism live: with a wrong pin the dial fails
     *  with exactly `the WARP endpoint's key doesn't
     *  match "publicKey"` (warp.go:60-73 compares the
     *  leaf's RawSubjectPublicKeyInfo byte-for-byte);
     *  with the correct pin the same config establishes.
     *  The leaf the JDK handshake sees can differ from
     *  the one the uTLS-Chrome dial sees (different cert
     *  selection per ClientHello) — one wrong pin kills
     *  EVERY gateway uniformly, exactly the observed
     *  symptom. Fixed: verifyPeerCertByName makes
     *  warp.go:57-59 return early WITHOUT the SPKI
     *  check (client cert still sent); publicKey is now
     *  the ENROLLED client SPKI, like Asha. Proven live:
     *  with verifyPeerCertByName the tunnel establishes
     *  even with a deliberately wrong-as-pin publicKey.
     *  NOTE: "allowInsecure" does NOT exist in this core
     *  — setting it fails config load (removed feature,
     *  migrated to pcs/vcn; proven by xray -test);
     *  verifyPeerCertByName is the working equivalent.
     *
     *  (3) SNI was consumer-masque.cloudflareclient.com
     *  (Xray test value, proven only against a MOCK
     *  server). Asha uses www.cloudflare.com against
     *  the REAL edge (consts.rs:8, quic.rs:799-820).
     *  Fixed: SNI = www.cloudflare.com. (Not isolated
     *  live — the VM cannot reach the real edge — but
     *  it is the only SNI proven against it.)
     *
     *  Also 1.46: H2 ClientHello fragmentation like
     *  Asha (FinalMask fragment, 8-16 byte pieces,
     *  2-10 ms — dialer.go uses FinalMask.DialTCP for
     *  the H2 dial; proven live: the fragmented hello
     *  completes a handshake); per-gateway dial errors
     *  are now LOGGED instead of discarded; GOOL tries
     *  ALL surviving gateways, not just the first; the
     *  scan is labelled a pre-filter, never "healthy";
     *  the WG rung needs a REAL generate_204 through the
     *  tunnel, not just a handshake.
     *  1.49: the fragment is REMOVED again (Asha's
     *  default is OFF; the 1.48 device log showed the
     *  fragmented dial never gets an answer) and the
     *  request carries NO User-Agent (masqueSettings
     *  headers {"User-Agent": ""} — the one field
     *  where we differed from Asha; VM-proven on
     *  mock149/fragprobe). SNI/key/ALPN/host/path/
     *  authority/capsules/ladder untouched.
     *  1.58 CORRECTION: "UA ruled out" was WRONG —
     *  1.49 tested ABSENT, never aether's
     *  present-but-empty `user-agent: ""`
     *  (masque_h2.rs:206); no config value makes
     *  Xray emit present-empty (the skip is
     *  hard-coded in masque/http2.go writeHeaders).
     *  The only config-side probe is a single space
     *  " " — HPACK permits it, writeHeaders emits
     *  it (only "" is skipped). Whether the edge
     *  treats " " as empty is UNKNOWN — device
     *  test only, NOT a fix claim. */
    /** The masque outbound for a WARP::id link.
     *  protocol "masque" (settings address/port) —
     *  NOT a vless outbound with masque transport
     *  (the core rejects that). streamSettings
     *  carries alpn [h2|h3], fingerprint chrome,
     *  serverName www.cloudflare.com (Asha-proven),
     *  verifyPeerCertByName for the warp early-return,
     *  and masqueSettings {host cloudflareaccess.com,
     *  path /, warp {privateKey, publicKey,
     *  address [local addrs WITH /32 and /128]}}. */
    fun masqueOutbound(id: String): JSONObject? {
        val rec = recordFor(id) ?: return null
        val idn = identity ?: return null
        if (idn.masquePrivateKeyB64.isBlank() || idn.masquePublicSpkiB64.isBlank()) return null
        val alpn = if (rec.alpn == "h3") "h3" else "h2"
        // 1.46: warp.address carries a prefix (the
        // canonical form from warp_test.go). NOTE: bare
        // IPs would ALSO work — parseWarpAddress
        // (transport_method.go:907-930) normalizes them
        // to /32 or /128 itself, proven live on the VM —
        // the missing CIDR was NOT the 1.45 bug.
        val addrs = JSONArray()
        if (idn.ipv4.isNotBlank()) addrs.put(if (idn.ipv4.contains("/")) idn.ipv4 else "${idn.ipv4}/32")
        if (idn.ipv6.isNotBlank()) addrs.put(if (idn.ipv6.contains("/")) idn.ipv6 else "${idn.ipv6}/128")
        if (addrs.length() == 0) return null
        return try {
            JSONObject().apply {
                put("protocol", "masque")
                put("tag", "proxy")
                put("settings", JSONObject().apply {
                    put("address", rec.gatewayIp)
                    put("port", rec.gatewayPort)
                })
                put("streamSettings", JSONObject().apply {
                    put("network", "masque")
                    put("security", "tls")
                    put("tlsSettings", JSONObject().apply {
                        put("serverName", WarpIdentity.MASQUE_SNI)
                        put("alpn", JSONArray().put(alpn))
                        put("fingerprint", "chrome")
                        // 1.46 (Q3): "allowInsecure" is a
                        // REMOVED feature in the shipped core
                        // (transport_security.go:362 — setting
                        // it fails config load). This is the
                        // working equivalent: it sets
                        // InsecureSkipVerify=true in
                        // GetTLSConfig, so warp.go:57-59
                        // returns early WITHOUT the SPKI check
                        // — the client cert is still sent
                        // (warp.go:54, before the return) —
                        // while the leaf is still chain-checked
                        // for this DNS name against system
                        // roots. Rotation-proof and independent
                        // of which cert the edge picks per
                        // ClientHello.
                        put("verifyPeerCertByName", WarpIdentity.MASQUE_SNI)
                    })
                    put("masqueSettings", JSONObject().apply {
                        put("host", WarpIdentity.MASQUE_HOST)
                        put("path", WarpIdentity.MASQUE_PATH)
                        // 1.58 PROBE (Xray-400 finding): the
                        // ONLY remaining HTTP difference vs
                        // aether's working request is the
                        // user-agent — aether sends it
                        // PRESENT-BUT-EMPTY (masque_h2.rs:206)
                        // while "" makes Xray send NO header
                        // at all (writeHeaders skips v == "";
                        // the 1.49 "UA ruled out" verdict was
                        // wrong — it tested absent, never
                        // present-empty). No config value can
                        // emit present-empty; the ONLY
                        // config-side probe is a single space
                        // " " — HPACK permits it, writeHeaders
                        // emits it. Whether the edge treats
                        // " " as empty is UNKNOWN; if the
                        // device still 400s, the real fix is
                        // a core patch (remove the v == ""
                        // skip) — NOT a config change. This
                        // rung still saves nothing unless
                        // real traffic passes.
                        put("headers", JSONObject().apply {
                            put("User-Agent", " ")
                        })
                        put("warp", JSONObject().apply {
                            put("privateKey", idn.masquePrivateKeyB64)
                            // 1.46: the ENROLLED client SPKI
                            // (Asha semantics — the same local
                            // P-256 key registered via PATCH
                            // /reg/{id}). The live leaf SPKI
                            // from the scan is NOT the pin any
                            // more (diagnostic only now): the
                            // leaf the JDK handshake sees can
                            // differ from the one uTLS sees.
                            // With verifyPeerCertByName above,
                            // the core never compares this value.
                            put("publicKey", idn.masquePublicSpkiB64)
                            put("address", addrs)
                        })
                    })
                    // 1.49: NO fragment on the H2 dial.
                    // 1.46 added the FinalMask fragment
                    // believing Asha fragments; the code
                    // says otherwise (aether fragment.rs:
                    // enabled defaults to FALSE, env-gated)
                    // and the 1.48 device log agrees: WITH
                    // fragment the server never answers the
                    // CONNECT-IP (context deadline exceeded)
                    // while WITHOUT it the server answers
                    // (HTTP 400 — a request-level rejection,
                    // not a network block). VM-PROVEN against
                    // fragprobe: no-finalmask dials send the
                    // whole ClientHello in ONE 1783 B TCP
                    // segment; with fragment it was 99
                    // pieces of 13-20 B over ~600 ms. The
                    // 1.47 no-fragment retry pass is kept as
                    // a plain second attempt (ladder
                    // unchanged); noFragIds now only tags
                    // its record ids.
                })
            }
        } catch (_: Exception) { null }
    }

    /** The wireguard-inner outbound for GOOL
     *  (WGXRAY::gool). The chain code in
     *  outboundsFor puts dialerProxy on it, so it
     *  dials through the masque outer. */
    fun wireguardOutbound(id: String): JSONObject? {
        if (id != "gool") return null
        val idn = goolIdentity ?: return null
        if (idn.wgPrivateKeyB64.isBlank() || idn.wgPeerPublicKeyB64.isBlank()) return null
        val endpoint = if (idn.wgPeerEndpointV4.isNotBlank())
            "${idn.wgPeerEndpointV4}:2408" else "engage.cloudflareclient.com:2408"
        // 1.46: CIDR form (the WG outbound accepts bare
        // IPs too — client.go tries ParseAddr first —
        // but the canonical form is with a prefix).
        val addrs = JSONArray()
        if (idn.ipv4.isNotBlank()) addrs.put(if (idn.ipv4.contains("/")) idn.ipv4 else "${idn.ipv4}/32")
        if (idn.ipv6.isNotBlank()) addrs.put(if (idn.ipv6.contains("/")) idn.ipv6 else "${idn.ipv6}/128")
        if (addrs.length() == 0) return null
        return try {
            JSONObject().apply {
                put("protocol", "wireguard")
                put("tag", "proxy")
                put("settings", JSONObject().apply {
                    put("secretKey", idn.wgPrivateKeyB64)
                    put("address", addrs)
                    put("peers", JSONArray().put(JSONObject().apply {
                        put("publicKey", idn.wgPeerPublicKeyB64)
                        put("endpoint", endpoint)
                        put("allowedIPs", JSONArray().put("0.0.0.0/0").put("::/0"))
                        put("keepAlive", 25)
                    }))
                })
            }
        } catch (_: Exception) { null }
    }

    /** The delivered WireGuard .conf for the
     *  wireguard-UDP rung (a REAL .conf, saved via
     *  the existing WG path and connected by the
     *  Amnezia engine — never forced into the
     *  Xray masque shape). */
    fun buildWgConf(idn: WarpIdentity.Identity): String {
        val sb = StringBuilder()
        sb.append("[Interface]\n")
        sb.append("PrivateKey = ").append(idn.wgPrivateKeyB64).append("\n")
        // 1.47: the stored addresses may ALREADY
        // carry a prefix (registration stores them
        // with one) — appending /32 again would
        // produce "172.16.0.2/32/32", which no WG
        // parser accepts. Same contains("/") guard
        // as masqueOutbound.
        val addrs = listOfNotNull(
            idn.ipv4.takeIf { it.isNotBlank() }?.let { if (it.contains("/")) it else "$it/32" },
            idn.ipv6.takeIf { it.isNotBlank() }?.let { if (it.contains("/")) it else "$it/128" }
        )
        sb.append("Address = ").append(addrs.joinToString(", ")).append("\n")
        sb.append("DNS = 1.1.1.1, 8.8.8.8\n")
        sb.append("MTU = 1280\n")
        sb.append("\n[Peer]\n")
        sb.append("PublicKey = ").append(idn.wgPeerPublicKeyB64).append("\n")
        sb.append("Endpoint = engage.cloudflareclient.com:2408\n")
        sb.append("AllowedIPs = 0.0.0.0/0, ::/0\n")
        sb.append("PersistentKeepalive = 25\n")
        return sb.toString()
    }

    /** 1.54 — the REAL config for a MASQUE record (P1):
     *  a masque:// link the app's own parser round-trips
     *  into the exact masque outbound masqueOutbound()
     *  builds (same SNI, ALPN, fingerprint, host, path,
     *  warp keys and tunnel addresses). The old link()
     *  ("WARP::<id>") is an internal registry pointer,
     *  not a config — the user rightly called it "not a
     *  config at all". The link carries the ENROLLED
     *  CLIENT keys (Asha semantics); the parser sets
     *  verifyPeerCertByName for links carrying warp_priv,
     *  so the core never compares the client SPKI against
     *  the server leaf. Returns null when the identity or
     *  the keys are missing (the caller then says so
     *  honestly, instead of copying a stub). The link
     *  text itself is built by WarpReg.buildMasqueLink
     *  (pure — the JVM test asserts the exact bytes). */
    fun buildMasqueLink(ctx: Context, rec: WarpRecord): String? {
        val idn = identity ?: WarpIdentity.load(ctx, "main") ?: return null
        return WarpReg.buildMasqueLink(
            rec.gatewayIp, rec.gatewayPort, rec.alpn, rec.name,
            idn.masquePrivateKeyB64, idn.masquePublicSpkiB64,
            idn.ipv4, idn.ipv6
        )
    }

    // ---- fetch ----
    /** 1.58 — one rung's outcome in a fetch: did it
     *  pass real traffic, on which gateway, how fast.
     *  The fetch summary reports every rung, including
     *  the ones that failed (never a silent skip). */
    data class RungOutcome(
        val rung: String,
        val won: Boolean,
        val gatewayIp: String,
        val pingMs: Long,
        val note: String
    )

    sealed class FetchResult {
        /** 1.58 — one card per working protocol (the
         *  list may hold several records now); outcomes
         *  carry the per-rung verdicts for the summary. */
        data class Success(val records: List<WarpRecord>, val outcomes: List<RungOutcome>) : FetchResult()
        /** No masque/gool rung passed real traffic —
         *  the activity tries the wireguard rung. */
        data class NeedWireGuard(val identity: WarpIdentity.Identity) : FetchResult()
        data class Failed(val fa: String, val en: String) : FetchResult()
    }

    /** 1.62 — the GOOL rung's last real failure
     *  (the engine's own error, e.g. its inner
     *  WireGuard "verify timeout"), so the fetch
     *  summary states the exact reason instead of
     *  a bare ✗. Set by probeGoolRung on every
     *  failed attempt. */
    @Volatile var lastGoolError: String? = null
        private set

    /** 1.62 — how many gateways of the last Xray
     *  masque probe answered "connect-ip status:
     *  400" (refused MASQUE outright). Set by
     *  probeRung; the fetch summary reports it. */
    @Volatile var lastProbe400Count: Int = 0
        private set

    /** 1.62 — the per-rung fetch summary, pure:
     *  EVERY rung reports, winners and failures
     *  alike, and every failure carries its real
     *  reason (never a bare ✗). */
    fun formatRungSummary(outcomes: List<RungOutcome>): Pair<String, String> {
        fun labelFa(rung: String) = when (rung) {
            RUNG_AETHER -> "AETHER MASQUE"
            RUNG_H3 -> "MASQUE H3"
            RUNG_H2 -> "MASQUE H2"
            RUNG_GOOL -> "GOOL"
            else -> rung
        }
        val fa = outcomes.joinToString("؛ ") { o ->
            if (o.won) "${labelFa(o.rung)} ✓ (${o.gatewayIp}، ${o.pingMs}ms)"
            else {
                val note = o.note.trim().take(160)
                if (note.isNotEmpty()) "${labelFa(o.rung)} ✗ — $note" else "${labelFa(o.rung)} ✗"
            }
        }
        val en = outcomes.joinToString("; ") { o ->
            if (o.won) "${o.rung} ✓ (${o.gatewayIp}, ${o.pingMs}ms)"
            else {
                val note = o.note.trim().take(160)
                if (note.isNotEmpty()) "${o.rung} ✗ — $note" else "${o.rung} ✗"
            }
        }
        return fa to en
    }

    /** 1.60 — a fetch is in flight (the background
     *  sweep's warpScanOnce must never register an
     *  identity or steal the CLI while this is true).
     *  Set for the whole runFetch, cleared in finally. */
    @Volatile var fetchActive = false
        private set

    /** 1.60 — REAL fetch progress (0..100), from stage
     *  weights, never a timer. The % moves only when
     *  the work moves: each stage owns a slice of the
     *  bar and reports its within-stage fraction
     *  (attempts done / attempts planned, futures done
     *  / futures planned). 100 is reached ONLY on
     *  deliver. Pure (the JVM test asserts the
     *  mapping, including monotonicity). */
    object FetchProgress {
        // Stage slices (percent): registration 0–15,
        // scan 15–30, wide sweep 30–35, AETHER 35–60,
        // Xray H3 60–70, Xray H2 70–80, GOOL 80–90,
        // WireGuard 90–98, deliver 100.
        fun percent(stage: String, within: Float): Int {
            val (lo, hi) = when (stage) {
                "register" -> 0 to 15
                "scan" -> 15 to 30
                "sweep" -> 30 to 35
                "aether-masque" -> 35 to 60
                "masque-h3" -> 60 to 70
                "masque-h2" -> 70 to 80
                "gool" -> 80 to 90
                "wireguard-udp" -> 90 to 98
                "deliver" -> 100 to 100
                else -> 0 to 0
            }
            val f = within.coerceIn(0f, 1f)
            return (lo + (hi - lo) * f).toInt().coerceIn(0, 100)
        }
    }

    /** 1.60 — the delivered card's name is the literal
     *  "free warp" in both languages; a second winning
     *  protocol is "free warp 2", then "free warp 3"
     *  — the existing dedup numbering, so cards stay
     *  distinguishable. Pure. */
    fun nextFreeWarpName(existingNames: List<String>): String {
        val taken = existingNames.map { it.trim() }.toSet()
        if ("free warp" !in taken) return "free warp"
        var n = 2
        while ("free warp $n" in taken) n++
        return "free warp $n"
    }

    /** 1.60 — GOOL's enroll IP: the IP the MAIN
     *  registration already succeeded on (the verified
     *  one), never the freshest scanned survivor — the
     *  1.59 GOOL registration used 162.159.198.2, which
     *  fails TLS cert validation, and died on it while
     *  162.159.192.1 had worked minutes earlier. Pure. */
    fun goolEnrollIpFor(verifiedIp: String?, bridgeIp: String?): String {
        val v = verifiedIp?.trim().orEmpty()
        if (v.isNotBlank()) return v
        val b = bridgeIp?.trim().orEmpty()
        if (b.isNotBlank()) return b
        return "162.159.192.1"
    }

    private fun wlog(ctx: Context, fa: String, en: String) {
        AppLog.append(ctx, "warp", fa, en)
    }

    private fun tcpOk(host: String, timeoutMs: Int): Boolean {
        return try {
            Socket().apply {
                connect(InetSocketAddress(host, GATEWAY_PORT), timeoutMs)
                close()
            }
            true
        } catch (_: Exception) { false }
    }

    /** 1.48: one probe's outcome, INCLUDING the
     *  core's own log lines (see measureWarpProbe).
     *  ms >= 0 means real traffic passed; err is
     *  the wrapper error (usually just -1's cause);
     *  coreLines/logCaptured are the decision-making
     *  diagnostic — the exact establish error.
     *  1.49: logLines is the file's total line
     *  count (0 when no file) — it tells "the
     *  core wrote nothing at all" apart from "it
     *  wrote lines, none about MASQUE". */
    data class ProbeOutcome(
        val ms: Long,
        val err: String?,
        val coreLines: List<String>,
        val logCaptured: Boolean,
        val logLines: Int = 0,
        /** 1.50: an aether probe has no Xray core log at all — the
         *  engine's own lines reach the [warp] log live (see
         *  AetherBridge.logListener). The suffix stays empty. */
        val aether: Boolean = false
    )

    /** 1.48: the [warp]-log suffix carrying the
     *  core's own verdict for one probe. Three
     *  honest states, never a fake: lines found,
     *  file never written, or file written but
     *  with no MASQUE line in it. (No trailing
     *  period — the caller adds it.)
     *  1.49: the third state now says HOW MANY
     *  lines the file had, so a really-empty
     *  file and a filtered-out file read
     *  differently. */
    private fun coreLogSuffix(o: ProbeOutcome): Pair<String, String> {
        // 1.50: aether probes carry no Xray core log — the engine
        // logs straight into the [warp] log; nothing to append.
        if (o.aether) return "" to ""
        return when {
            o.coreLines.isNotEmpty() ->
                (" — لاگ هسته: " + o.coreLines.joinToString(" | ")) to
                (" — core log: " + o.coreLines.joinToString(" | "))
            !o.logCaptured ->
                (" — لاگ هسته نوشته نشد (فایل لاگ ساخته نشد؛ روی گوشی باید بررسی شود)") to
                (" — core log was not written (no log file; needs a phone check)")
            o.logLines > 0 ->
                (" — لاگ هسته خالی بود (فایل ${o.logLines} خط داشت، ولی هیچ‌کدام دربارهٔ MASQUE یا خطای تماس نبود)") to
                (" — core log empty (the file had ${o.logLines} lines, none about MASQUE or the dial error)")
            else ->
                (" — لاگ هسته خالی بود (هسته چیزی دربارهٔ MASQUE ننوشت)") to
                (" — core log empty (the core wrote nothing about MASQUE)")
        }
    }

    /** One Xray real-traffic probe for a candidate
     *  (temp record in the registry, then the same
     *  speedtest path as the v2ray ping test).
     *  1.48: the probe WITH the core error log
     *  captured (measureWarpProbe) — the record
     *  id is the log-file tag, unique per probe. */
    private fun probeMasque(ctx: Context, rec: WarpRecord): ProbeOutcome {
        registerTemp(rec)
        return try {
            val r = XraySpeedtest.measureWarpProbe(ctx, "WARP::${rec.id}", rec.id)
            ProbeOutcome(r.ms, r.err, r.coreLines, r.logCaptured, r.logLines)
        } catch (t: Throwable) {
            ProbeOutcome(-1L, t.message ?: t.javaClass.simpleName, emptyList(), false, 0)
        } finally {
            unregisterTemp(rec.id)
        }
    }

    /** 1.55 — ONE aether attempt for ONE candidate on ONE
     *  carrier, through the CLI session loop (Asha's
     *  scanner MASQUE surface): the loop quick-verifies
     *  the forced peer and establishes, and SOCKS
     *  serving (AetherCli.socksReady) means the gateway
     *  answered MASQUE on this carrier — that IS the
     *  verify, there is no separate FFI verify any
     *  more. Then generate_204 through its SOCKS port:
     *  the real-traffic verdict. The CLI process is
     *  stopped before returning — a probe never leaves
     *  a tunnel behind. 1.58 (Part B): a WINNING probe
     *  keeps its CLI session alive as the warm standby
     *  (AetherBridge.holdWarmSession) so the connect
     *  attaches to it; a losing probe still tears its
     *  session down. The engine identity file is
     *  written ONCE per fetch (runFetch, under the
     *  identity lock) — this function NEVER writes it,
     *  so the 1.51 race cannot recur.
     *  Returns (outcome, verified, winnerIp): verified
     *  tells the caller whether the candidate answered
     *  at all; winnerIp is the gateway the session loop
     *  ACTUALLY used — usually the candidate, but the
     *  loop hunts when the forced peer is dead, so it
     *  can differ (the SOCKS port is the contract, not
     *  the requested IP). */
    private fun probeAetherOnce(
        ctx: Context,
        rec: WarpRecord,
        socksPort: Int,
        port: Int
    ): Triple<ProbeOutcome, Boolean, String?> {
        val t0 = System.currentTimeMillis()
        val carrier = if (port == AetherRungPlan.H3_PORT) "h3" else "h2"
        val h2 = port != AetherRungPlan.H3_PORT
        // 1.58 (Part B) — a new CLI start kills whatever
        // process runs (AetherCli.start stops it first),
        // so any warm record from an earlier probe is
        // dead from here on: drop it before starting.
        AetherBridge.clearWarmSession()
        // 1.56 — no --ech: Asha's scanner passes none
        // (AetherCliConfig has no ech field; the PoW's
        // ECH is the Guard/FFI path). The CLI args are
        // exactly Asha's scanner invocation now.
        val started = AetherCli.start(ctx,
            AetherCli.buildMasqueArgs(socksPort, rec.gatewayIp, port, h2),
            socksPort, AetherCli.PROBE_SOCKS_WAIT_MS, AetherBridge.enrollIp) { line ->
            AetherBridge.noteCliLine(line)
            wlog(ctx, line, line)
        }
        if (!started) {
            // Asha's wording: "no answer" — the rung moves on.
            return Triple(ProbeOutcome(-1L,
                "پاسخی نیامد (${AetherCli.lastError})",
                emptyList(), false, 0, aether = true), false, null)
        }
        // SOCKS is serving. The loop may have hunted to
        // a different gateway — the winner is the one the
        // engine selected, not the one asked for.
        val selected = AetherCli.lastSelectedGatewayIp
        val winnerIp = if (selected.isNotBlank()) selected else rec.gatewayIp
        val result: Triple<ProbeOutcome, Boolean, String?> = try {
            // 1.61 — the probe retries (3 attempts, 20s
            // first, 10s after): the first request
            // through a fresh tunnel can exceed 10s
            // while the engine already validated it.
            // A final failure carries the REAL
            // exception text, never a bare -1.
            val res = WarpIdentity.generate204ThroughSocksWithRetry(socksPort)
            val ms = System.currentTimeMillis() - t0
            if (res.code == 200 || res.code == 204) Triple(
                ProbeOutcome(ms, null, emptyList(), true, 0, aether = true), true, winnerIp)
            else Triple(ProbeOutcome(-1L, "generate_204 از SOCKS aether: ${res.error ?: "کد ${res.code}"} (تلاش ${res.attempts} بار)",
                emptyList(), true, 0, aether = true), true, winnerIp)
        } catch (t: Throwable) {
            Triple(ProbeOutcome(-1L, t.message ?: t.javaClass.simpleName,
                emptyList(), true, 0, aether = true), true, winnerIp)
        }
        // 1.58 (Part B) — a WIN keeps its CLI session
        // alive as the warm standby (the connect attaches
        // to it with only a health probe); a loss still
        // tears the session down, so a probe never leaves
        // a dead tunnel behind.
        if (result.first.ms >= 0) {
            AetherBridge.holdWarmSession(socksPort, winnerIp, carrier, RUNG_AETHER)
            wlog(ctx, "جلسهٔ aether روی $winnerIp گرم نگه داشته شد (اتصال سریع).",
                "Aether session on $winnerIp kept warm (fast connect).")
        } else {
            AetherCli.stop()
        }
        return result
    }

    /** 1.52 — THE aether rung (replaces the 1.50/1.51
     *  parallel probeRung for this rung): SEQUENTIAL, one
     *  candidate at a time, PER-GATEWAY H3→H2 (1.56):
     *  H3/:1701 on a gateway, and on failure H2/TCP:443
     *  on the SAME gateway immediately, before moving
     *  on — exactly Asha's MASQUE section ("Trying
     *  UDP/1701" … "MASQUE accepted <ip>:1701"). The FIRST
     *  candidate that passes real traffic wins (Asha
     *  connects to the first accepted gateway; there is no
     *  "collect 6 winners" on this rung). A win also saves
     *  the gateway to the persistent clean list. Returns
     *  the winning record, or null. The candidate list
     *  comes from the scan (saved-clean first, then this
     *  fetch's survivors) — never from a hardcoded winner. */
    private fun probeAetherSequential(
        ctx: Context,
        candidates: List<HealthyGateway>,
        deadlineMs: Long,
        isCancelled: () -> Boolean,
        // 1.58 — the transport that won the last fetch:
        // when it was H2, the first candidate tries H2
        // before H3 (see AetherRungPlan.attempts).
        firstCarrier: String? = null,
        // 1.60 — real-stage progress: (attempts done,
        // attempts planned) after each attempt.
        onAttempt: ((Int, Int) -> Unit)? = null
    ): WarpRecord? {
        // Identity file ONCE, under the lock, before any
        // probe — the CLI loads it from disk and never
        // provisions over the network.
        if (!AetherBridge.ensureIdentityFile(ctx)) {
            wlog(ctx, "AETHER شروع نشد: ${AetherBridge.lastIdentityError ?: "?"}",
                "AETHER did not start: ${AetherBridge.lastIdentityError ?: "?"}")
            return null
        }
        // 1.56 — NO ECH fetch any more: Asha's scanner
        // passes no --ech (see AetherCli.buildMasqueArgs),
        // and the Kotlin DoH fetch never returned a key
        // on device (1.55 ran with ECH off). The PoW's
        // ECH belongs to the Guard/FFI path; P2 if H2
        // still fails is --ech auto on H2 runs.
        val ips = AetherRungPlan.orderCandidates(
            loadCleanList(ctx),
            candidates.map { AetherRungPlan.ScannedIp(it.ip, it.pingMs) },
            emptyList(),
            AetherRungPlan.PRIORITY_GATEWAYS)
        // The ordered candidates are exactly the scanned
        // survivors (saved-clean first, then this fetch's
        // scan): an IP is attempted only if it is in byIp
        // below, i.e. only if the scan saw it alive. Nothing
        // unscanned is ever dialled.
        val byIp = candidates.associateBy { it.ip }
        val plan = AetherRungPlan.attempts(ips, firstCarrier)
        val firstNoteFa = if ((firstCarrier ?: "").equals("h2", ignoreCase = true))
            " (برندهٔ قبلی H2 بود؛ اولین دروازه اول H2 را امتحان می‌کند)" else ""
        val firstNoteEn = if ((firstCarrier ?: "").equals("h2", ignoreCase = true))
            " (last winner was H2; the first gateway tries H2 first)" else ""
        wlog(ctx, "AETHER: تست ترتیبی ${ips.size} دروازه — هر دروازه: اول H3 روی UDP/1701، بعد بلافاصله H2 روی همان دروازه$firstNoteFa.",
            "AETHER: sequential test of ${ips.size} gateways — per gateway: H3 on UDP/1701 first, then H2 on the same gateway right away$firstNoteEn.")
        // 1.55 — the probe order is the diagnosis: the
        // proven gateways go first (see PRIORITY_GATEWAYS).
        wlog(ctx, "AETHER: ترتیب تست: ${ips.joinToString("، ")}.",
            "AETHER: probe order: ${ips.joinToString(", ")}.")
        val budgetSec = ((deadlineMs - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
        wlog(ctx, "AETHER: بودجهٔ تست تا مهلت دریافت: ${budgetSec} ثانیه؛ هر تلاش حداکثر ۲۰ ثانیه.",
            "AETHER: probe budget to the fetch deadline: ${budgetSec}s; each attempt max 20s.")
        var tried = 0
        // 1.55 — per-carrier counts + the stop reason:
        // the 1.54 device log showed 7 H3 gateways and
        // no H2 lines at all, with no way to tell the
        // probe had starved on the deadline.
        val h3Ips = mutableSetOf<String>()
        val h2Ips = mutableSetOf<String>()
        var h3Attempts = 0
        var h2Attempts = 0
        var stopWhy: String? = null
        for (attempt in plan) {
            if (isCancelled()) { stopWhy = "لغو"; break }
            if (System.currentTimeMillis() > deadlineMs) { stopWhy = "مهلت"; break }
            val g = byIp[attempt.ip] ?: continue
            tried++
            if (attempt.carrier == "h3") { h3Ips += g.ip; h3Attempts++ }
            else { h2Ips += g.ip; h2Attempts++ }
            val rec = WarpRecord(
                id = "tmp-aether-${attempt.carrier}-${g.ip}", name = "",
                rung = RUNG_AETHER, alpn = attempt.carrier,
                gatewayIp = g.ip, gatewayPort = attempt.port,
                countryCode = g.countryCode, colo = g.colo,
                serverSpkiB64 = g.spkiB64, serverSpkiSha256 = g.spkiSha256,
                pingMs = -1L, exitCountry = "", exitColo = "",
                createdAt = 0L, wgTunnelName = ""
            )
            val (outcome, _, winnerIp) = probeAetherOnce(ctx, rec,
                AetherBridge.PROBE_SOCKS_BASE, attempt.port)
            val ms = outcome.ms
            if (ms >= 0) {
                val winIp = winnerIp ?: g.ip
                if (winIp != g.ip) {
                    wlog(ctx, "AETHER: موتور دروازهٔ دیگری را انتخاب کرد: $winIp (به‌جای ${g.ip}).",
                        "AETHER: the engine selected a different gateway: $winIp (instead of ${g.ip}).")
                }
                wlog(ctx, "[✓] AETHER ${attempt.carrier.uppercase()} روی $winIp ترافیک واقعی را رد کرد (${ms}ms) — تلاش ${attempt.tryNo}.",
                    "[✓] AETHER ${attempt.carrier.uppercase()} passed real traffic on $winIp (${ms}ms) — try ${attempt.tryNo}.")
                saveCleanGateway(ctx, winIp)
                onAttempt?.invoke(tried, plan.size)
                return rec.copy(gatewayIp = winIp, pingMs = ms)
            }
            wlog(ctx, "[✗] AETHER ${attempt.carrier.uppercase()} روی ${g.ip} (تلاش ${attempt.tryNo}): ${outcome.err ?: "نامشخص"}.",
                "[✗] AETHER ${attempt.carrier.uppercase()} on ${g.ip} (try ${attempt.tryNo}): ${outcome.err ?: "unknown"}.")
            onAttempt?.invoke(tried, plan.size)
        }
        // 1.55 — the probe summary (this only runs on
        // failure; a win returns inside the loop above).
        wlog(ctx, "AETHER: تست تمام شد — H3 روی ${h3Ips.size} دروازه ($h3Attempts تلاش)، H2 روی ${h2Ips.size} دروازه ($h2Attempts تلاش)${if (stopWhy != null) "؛ توقف: $stopWhy" else ""}.",
            "AETHER: probe done — H3 on ${h3Ips.size} gateways ($h3Attempts attempts), H2 on ${h2Ips.size} gateways ($h2Attempts attempts)${if (stopWhy != null) "; stopped: $stopWhy" else ""}.")
        if (tried == 0) {
            wlog(ctx, "AETHER: هیچ دروازه‌ای برای تست نبود.",
                "AETHER: no gateway to test.")
        }
        return null
    }

    private fun probeGool(ctx: Context, outer: WarpRecord): ProbeOutcome {
        registerTemp(outer)
        return try {
            // outer.link() is the chain link itself:
            // CHAIN::gool|||WGXRAY::gool|||WARP::<id>
            val r = XraySpeedtest.measureWarpProbe(ctx, outer.link(), "gool-${outer.gatewayIp}")
            ProbeOutcome(r.ms, r.err, r.coreLines, r.logCaptured, r.logLines)
        } catch (t: Throwable) {
            ProbeOutcome(-1L, t.message ?: t.javaClass.simpleName, emptyList(), false, 0)
        } finally {
            unregisterTemp(outer.id)
        }
    }

    /** 1.52 — the wide sweep: TCP-connect aether's MASQUE
     *  /24s (3556 IPs), 64 at a time, 1200ms each, 60s
     *  budget, stop at 20 TCP-open; then TLS/SPKI + trace
     *  as INFO on the open ones (1.55: SPKI is not a
     *  gate — see scanKeepsGateway). Returns the new
     *  survivors (TCP answered — still NOT "healthy" until
     *  a rung passes real traffic through them). */
    private fun wideSweep(
        ctx: Context,
        isCancelled: () -> Boolean,
        deadlineMs: Long
    ): List<HealthyGateway> {
        val budgetEnd = minOf(deadlineMs, System.currentTimeMillis() + 60_000L)
        val targets = AetherRungPlan.wideSweepTargets()
        wlog(ctx, "جست‌وجوی گسترده شروع شد (${targets.size} آی‌پی در ${AetherRungPlan.MASQUE_CIDRS_V4.size} بازهٔ کلودفلر؛ حداکثر ۶۰ ثانیه).",
            "Wide sweep started (${targets.size} IPs in ${AetherRungPlan.MASQUE_CIDRS_V4.size} Cloudflare ranges; 60s max).")
        // 1.59 — the TCP phase is the shared scanner's
        // tcpSweep (same 1200ms timeout, same stop-at-20,
        // same 60s budget, 64 threads); the TLS/SPKI +
        // trace enrichment below is unchanged (info only).
        val open = SharedScanner.tcpSweep(
            targets, port = 443, timeoutMs = 1200,
            maxOpen = 20,
            budgetMs = (budgetEnd - System.currentTimeMillis()).coerceAtLeast(0L),
            concurrency = 64
        ).map { it.ip }.toMutableList()
        if (open.isEmpty() || isCancelled()) return emptyList()
        wlog(ctx, "جست‌وجوی گسترده: ${open.size} آی‌پی به TCP جواب دادند؛ بررسی TLS (فقط اطلاعات)…",
            "Wide sweep: ${open.size} IPs answered TCP; checking TLS (info only)…")
        val out = mutableListOf<HealthyGateway>()
        val pool2 = Executors.newFixedThreadPool(8)
        try {
            val futures = open.map { ip ->
                pool2.submit(Callable {
                    // 1.55 — same rule as Stage A (see
                    // scanKeepsGateway): SPKI is info only.
                    val (spki, _) = WarpIdentity.fetchServerSpkiWithError(ip, 6000)
                    if (!AetherRungPlan.scanKeepsGateway(true, spki != null)) return@Callable null
                    val trace = WarpIdentity.fetchTrace(ip, 6000)
                    HealthyGateway(ip, trace?.first ?: "", trace?.second ?: "",
                        spki?.first ?: "", spki?.second ?: "", -1L, System.currentTimeMillis())
                })
            }
            for (f in futures) {
                if (isCancelled()) break
                try { f.get(9000, TimeUnit.MILLISECONDS)?.let { out += it } } catch (_: Exception) {
                    try { f.cancel(true) } catch (_: Exception) {}
                }
            }
        } finally {
            pool2.shutdownNow()
        }
        return out
    }

    /** 1.48: log the EXACT streamSettings the core
     *  will dial with — read BACK from the outbound
     *  masqueOutbound builds (not re-typed), for the
     *  first ordered gateway — plus warp.address vs
     *  the registered identity's addresses, plus
     *  the warp_test.go fixture comparison (Host
     *  cloudflareaccess.com, Path /, ServerName
     *  consumer-masque.cloudflareclient.com).
     *  LOG ONLY: SNI is Asha's www.cloudflare.com
     *  (deliberately different from the fixture —
     *  flagged, NOT changed), keys/fragment/ALPN
     *  untouched. Only public facts — never private
     *  keys or tokens. */
    private fun logExactStreamSettings(ctx: Context, g: HealthyGateway) {
        val rec = WarpRecord(
            id = "tmp-diag-${g.ip}", name = "", rung = RUNG_H2, alpn = "h2",
            gatewayIp = g.ip, gatewayPort = GATEWAY_PORT,
            countryCode = g.countryCode, colo = g.colo,
            serverSpkiB64 = g.spkiB64, serverSpkiSha256 = g.spkiSha256,
            pingMs = -1L, exitCountry = "", exitColo = "",
            createdAt = 0L, wgTunnelName = ""
        )
        registerTemp(rec)
        try {
            val ob = masqueOutbound(rec.id) ?: run {
                wlog(ctx, "تنظیمات استریم خوانده نشد (masqueOutbound خالی برگشت).",
                    "Stream settings unreadable (masqueOutbound returned null).")
                return
            }
            val ss = ob.optJSONObject("streamSettings")
            val tls = ss?.optJSONObject("tlsSettings")
            val mq = ss?.optJSONObject("masqueSettings")
            val warp = mq?.optJSONObject("warp")
            val sni = tls?.optString("serverName", "") ?: ""
            val alpn = tls?.optJSONArray("alpn")?.optString(0, "") ?: ""
            val host = mq?.optString("host", "") ?: ""
            val path = mq?.optString("path", "") ?: ""
            val addrs = warp?.optJSONArray("address")?.let { a ->
                (0 until a.length()).joinToString(", ") { a.optString(it) }
            } ?: ""
            val idn = identity
            val regAddrs = listOfNotNull(
                idn?.ipv4?.takeIf { it.isNotBlank() },
                idn?.ipv6?.takeIf { it.isNotBlank() }
            ).joinToString(", ")
            val frag = ss?.has("finalmask") == true
            val ua = mq?.optJSONObject("headers")?.optString("User-Agent", null)
            val uaFa = when {
                ua == "" -> "خالی (حذف‌شده)"
                ua == " " -> "« » (یک فاصله — آزمایش ۱٫۵۸)"
                ua == null -> "تنظیم نشده (ChromeUA فرستاده می‌شود!)"
                else -> ua
            }
            wlog(ctx, "تنظیمات استریم (دقیق، از کانفیگ ساخته‌شده): serverName=$sni؛ ALPN=$alpn؛ masque host=$host؛ path=$path؛ warp.address=[$addrs]؛ آدرس‌های ثبت‌شده=[$regAddrs]؛ فرگمنت H2=${if (frag) "روشن" else "خاموش"}؛ User-Agent=$uaFa.",
                "Stream settings (exact, from the built config): serverName=$sni; ALPN=$alpn; masque host=$host; path=$path; warp.address=[$addrs]; registered addresses=[$regAddrs]; H2 fragment=${if (frag) "on" else "off"}; User-Agent=$uaFa.")
            val mismatches = mutableListOf<String>()
            if (host != "cloudflareaccess.com") mismatches += "host=$host (فیکسچر: cloudflareaccess.com)"
            if (path != "/") mismatches += "path=$path (فیکسچر: /)"
            if (ua != " ") mismatches += "User-Agent=${ua ?: "تنظیم نشده"} (باید « » باشد — آزمایش ۱٫۵۸)"
            if (frag) mismatches += "فرگمنت H2 روشن است (اشا: خاموش)"
            if (sni != "consumer-masque.cloudflareclient.com") mismatches += "SNI=$sni (فیکسچر: consumer-masque.cloudflareclient.com — عمدی، مقدار اشا)"
            if (mismatches.isEmpty()) {
                wlog(ctx, "مقایسه با فیکسچر warp_test.go: همه مطابق.",
                    "warp_test.go fixture comparison: all match.")
            } else {
                wlog(ctx, "مقایسه با فیکسچر warp_test.go — تفاوت‌ها: ${mismatches.joinToString("؛ ")}.",
                    "warp_test.go fixture comparison — differences: ${mismatches.joinToString("; ")}.")
            }
            val normReg = listOfNotNull(
                idn?.ipv4?.takeIf { it.isNotBlank() }?.let { if (it.contains("/")) it else "$it/32" },
                idn?.ipv6?.takeIf { it.isNotBlank() }?.let { if (it.contains("/")) it else "$it/128" }
            ).joinToString(", ")
            if (addrs != normReg) {
                wlog(ctx, "هشدار: warp.address با آدرس‌های ثبت‌شده فرق دارد ([$addrs] در برابر [$normReg]).",
                    "Warning: warp.address differs from the registered addresses ([$addrs] vs [$normReg]).")
            }
        } finally {
            unregisterTemp(rec.id)
        }
    }

    /** 1.54 — THE ENROLL PROBE (replaces the 1.53 TCP+TLS
     *  check, which is PROVEN insufficient on device:
     *  162.159.192.1 passed TCP+TLS with SNI
     *  www.cloudflare.com and then the POST with SNI
     *  api.cloudflareclient.com read-timed-out on BOTH
     *  legs). The probe is REGISTRATION-SHAPED: the same
     *  SNI and the same request as registration (an empty
     *  POST to /v0a4471/reg — the edge answers 400 in
     *  ~0.11s and creates nothing; any HTTP status line
     *  is a PASS). 1.58 — FLAT waves (ip x sni,
     *  first-answer-wins): the old code ran SNI-major
     *  and paid a whole 8s-timeout REG_HOST seeds wave
     *  before even trying the DEVICES SNI that answers
     *  on this network (1.57 paid ~25s of that). Wave 1:
     *  last-winner gateway + saved-clean, both SNIs,
     *  3s attempts. Wave 2: seeds, both SNIs, 3s.
     *  Wave 3 (fallback, slow networks): everything,
     *  both SNIs, 8s attempts. The probe SHAPE is
     *  unchanged — only the scheduling changed. If no
     *  wave answers and no VPN is on,
     *  a bounded wide sweep (45s) finds fresh TCP-open
     *  IPs and probes THOSE — the app finds the hole
     *  itself instead of settling for the unverified
     *  default 162.159.192.1. Returns (ip, sni) — the
     *  SNI the IP actually answered with — or null.
     *  "آی‌پی ثبت‌نام تأیید شد" is logged ONLY after
     *  that real answer, never after TCP+TLS. */
    private fun verifyEnrollIpRegShaped(
        ctx: Context,
        isCancelled: () -> Boolean,
        deadlineMs: Long
    ): Pair<String, String>? {
        val saved = loadCleanList(ctx).filter { it.isNotBlank() }
        // 1.58 (Part A) — the last winner's gateway leads
        // the priority wave: it is proven to answer on
        // this network (deliver() also saves it to the
        // clean list, so this is a belt-and-braces order).
        val winnerGw = loadAll(ctx).firstOrNull()?.gatewayIp.orEmpty()
        val priority = (listOf(winnerGw) + saved)
            .map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val seeds = GATEWAYS.filter { it.isNotBlank() && it !in priority }
        val snis = listOf(WarpReg.REG_HOST, WarpReg.DEVICES_HOST)
        val probeEnd = minOf(deadlineMs, System.currentTimeMillis() + 40_000L)
        // 1.58 — FLAT waves (ip x sni, first-answer-wins):
        // the old code ran SNI-major — a whole 8s-timeout
        // REG_HOST seeds wave before even trying the
        // DEVICES SNI that answers on this network (1.57
        // paid ~25s of that). Wave 1: priority IPs x both
        // SNIs, 3s attempts. Wave 2: seeds x both SNIs, 3s.
        // Wave 3 (fallback, slow networks): everything x
        // both SNIs, 8s attempts. The probe SHAPE is
        // unchanged (same SNI + same empty POST) — only
        // the scheduling changed, so the verdict ("this
        // IP answers the exact registration shape") is
        // exactly as accurate as before.
        fun pairs(ips: List<String>) = ips.flatMap { ip -> snis.map { sni -> ip to sni } }
        val waves = listOf(
            Triple(pairs(priority), WarpReg.PROBE_FAST_TIMEOUT_MS, 10_000L),
            Triple(pairs(seeds), WarpReg.PROBE_FAST_TIMEOUT_MS, 20_000L),
            Triple(pairs(priority + seeds), WarpReg.PROBE_SLOW_TIMEOUT_MS, 25_000L)
        )
        for ((cands, timeoutMs, budgetMs) in waves) {
            if (cands.isEmpty() || isCancelled()) continue
            val left = probeEnd - System.currentTimeMillis()
            if (left <= 0) break
            val hit = WarpReg.pickFirstPair(cands, { ip, sni ->
                WarpReg.probeRegShaped(ip, sni, timeoutMs).first >= 100
            }, 8, minOf(left, budgetMs))
            if (hit != null) return hit
        }
        // No seed answered — the real scan (only when no
        // VPN can carry the direct leg; with a VPN on,
        // the direct leg already proved 200 at 21:20).
        if (isCancelled() || System.currentTimeMillis() > deadlineMs) return null
        val swept = try {
            wideSweep(ctx, isCancelled, minOf(deadlineMs, System.currentTimeMillis() + 45_000L))
        } catch (_: Exception) { emptyList() }
        if (swept.isEmpty()) return null
        val sweptPairs = swept.map { it.ip }.flatMap { ip -> snis.map { sni -> ip to sni } }
        val left = deadlineMs - System.currentTimeMillis()
        if (left <= 0 || isCancelled()) return null
        return WarpReg.pickFirstPair(sweptPairs, { ip, sni ->
            WarpReg.probeRegShaped(ip, sni, WarpReg.PROBE_SLOW_TIMEOUT_MS).first >= 100
        }, 8, left)
    }

    /** Run the whole free fetch on a worker thread.
     *  onProgress posts status lines; isCancelled
     *  stops between stages. Every stage is
     *  guarded — one bad gateway never kills the
     *  fetch (defensive parsing rule). */
    fun runFetch(
        ctx: Context,
        onProgress: (String, String) -> Unit,
        isCancelled: () -> Boolean,
        // 1.60 — real-stage progress (0..100, see
        // FetchProgress); null keeps the old shape.
        onProgressPct: ((Int) -> Unit)? = null
    ): FetchResult {
        fetchActive = true
        return try {
            runFetchInner(ctx, onProgress, isCancelled, onProgressPct)
        } finally {
            fetchActive = false
        }
    }

    private fun runFetchInner(
        ctx: Context,
        onProgress: (String, String) -> Unit,
        isCancelled: () -> Boolean,
        onProgressPct: ((Int) -> Unit)? = null
    ): FetchResult {
        val startMs = System.currentTimeMillis()
        // 1.60 — the 240s clock covers the TEST phase
        // only (see testDeadlineMs at Stage B): the
        // 1.59 run burned the single 240s clock on a
        // double registration and left AETHER a 0s
        // budget. Registration + scan run under their
        // own caps below (probe 40s, scan/sweep 60s
        // each, registration legs 15s each).
        val deadlineMs = startMs + 240_000L
        try { CoreInit.init(ctx) } catch (_: Exception) {}
        // 1.59 — the shared clean-IP scanner runs ONLY
        // in the background and its results are cached
        // per network; the fetch NEVER waits for it
        // (it reads the cached per-network lists — the
        // 1.58 fast path — and the sweep fills them).
        // The network-change listener is started once
        // per process (idempotent); a switch to a new
        // network triggers a background re-scan for it.
        try {
            NetworkMonitor.ensureStarted(ctx.applicationContext)
            SharedScanner.sweepIfNeeded(ctx)
        } catch (_: Exception) {}
        // 1.50: engine log lines go straight into the [warp] log.
        // applicationContext — the listener outlives the activity.
        try {
            val appCtx = ctx.applicationContext
            AetherBridge.logListener = { line -> wlog(appCtx, line, line) }
        } catch (_: Exception) {}
        identity = WarpIdentity.load(ctx, "main")
        goolIdentity = WarpIdentity.load(ctx, "gool")
        val socksPort = try {
            if (VpnClientService.isRunning) VpnClientService.sessionSocksPort else -1
        } catch (_: Exception) { -1 }
        // 1.53: the VPN state is the SYSTEM state (a foreign
        // VPN counts — 1.52 only asked our own service).
        val vpnOn = WarpIdentity.isAnyVpnOn(ctx)
        val startIdx = ladderStartIndex(ctx)
        wlog(ctx, "دریافت رایگان وارپ شروع شد؛ نردبان از پلهٔ ${startIdx + 1} شروع می‌شود (ذخیره‌شده).",
            "Free WARP fetch started; ladder starts at rung ${startIdx + 1} (stored).")
        // 1.58 (Part B) — a new fetch invalidates the
        // previous fetch's warm session: its gateway was
        // validated for the OLD fetch, and this fetch's
        // own probes stop the process anyway. A fresh
        // warm session is held if this fetch wins.
        AetherBridge.stopWarmSession()
        // 1.52 — enroll to a CLEAN IP (saved-clean first,
        // else Asha's default 162.159.192.1): the Kotlin
        // registration dials the IP directly, and the
        // engine's own calls get the same address.
        // 1.54 — the enroll IP must be REGISTRATION-
        // VERIFIED (same SNI + same request shape as the
        // real POST — see verifyEnrollIpRegShaped):
        // never the first TCP+TLS answer, never the
        // unverified default. With a VPN on, the direct
        // leg routes through it, so a missing clean IP
        // does NOT abort — only the no-VPN case fails
        // plainly here.
        var enrollIp = loadCleanList(ctx).firstOrNull() ?: "162.159.192.1"
        var enrollSni = WarpReg.REG_HOST
        if (identity == null) {
            onProgress("در حال بررسی آی‌پی ثبت‌نام…", "Verifying the registration IP…")
            onProgressPct?.invoke(FetchProgress.percent("register", 0f))
            val verified = verifyEnrollIpRegShaped(ctx, isCancelled, deadlineMs)
            if (verified != null) {
                enrollIp = verified.first
                enrollSni = verified.second
                val sniNote = if (enrollSni != WarpReg.REG_HOST) " (SNI $enrollSni)" else ""
                wlog(ctx, "آی‌پی ثبت‌نام تأیید شد: $enrollIp$sniNote.",
                    "Registration IP verified: $enrollIp$sniNote.")
            } else if (!vpnOn) {
                return FetchResult.Failed(
                    "هیچ آی‌پی تمیزی برای ثبت‌نام پیدا نشد؛ بعداً دوباره بزن.",
                    "No clean IP found for registration; retry later.")
            } else {
                wlog(ctx, "هیچ آی‌پی تمیزی تأیید نشد؛ ثبت‌نام از مسیر VPN ادامه پیدا می‌کند.",
                    "No clean IP verified; registration continues via the VPN.")
            }
        }
        AetherBridge.setEnrollAddress(enrollIp)
        // 1.60 — the verified SNI goes along: a later
        // renewal through ensureIdentityFile must use
        // the SNI that answered, not the default (the
        // 1.59 second registration paid the slow legs
        // on every call for exactly this).
        AetherBridge.setEnrollSni(enrollSni)
        onProgress("در حال ثبت‌نام وارپ…", "Registering WARP…")
        if (identity == null && !vpnOn) {
            // Step zero, said upfront: no identity and no
            // VPN on — trying direct registration from a
            // verified clean IP. Never claim it is
            // impossible while trying.
            wlog(ctx, "هویت وارپ وجود ندارد و هیچ VPN فعالی نیست؛ ثبت‌نام مستقیم از آی‌پی تمیز را امتحان می‌کنم.",
                "No WARP identity and no active VPN; trying direct registration from a clean IP.")
        } else if (identity == null) {
            wlog(ctx, "VPN فعال است؛ ثبت‌نام وارپ از آی‌پی تمیز را امتحان می‌کنم.",
                "A VPN is on; trying WARP registration from a clean IP.")
        }
        val idn = WarpIdentity.ensure(ctx, "main", socksPort, enrollIp, enrollSni)
        if (idn == null) {
            // 1.53: the screen message is the SAME why the
            // log line carries — never "؟" — and the VPN
            // state is the system state.
            val why = WarpIdentity.lastRegisterError ?: "نامشخص"
            val (fa, en) = WarpReg.registrationFailureMessage(vpnOn, socksPort > 0, why)
            return FetchResult.Failed(fa, en)
        }
        identity = idn
        // 1.60 — registration done: the bar leaves the
        // registration slice (a returning fetch with a
        // stored identity reports the same 15% here —
        // no registration was needed, so the bar starts
        // past it instead of sitting at 0 through the
        // scan).
        onProgressPct?.invoke(FetchProgress.percent("register", 1f))
        if (isCancelled()) return FetchResult.Failed("لغو شد.", "Cancelled.")

        // 1.58 — FAST PATH (user order: Asha finds its
        // working path in seconds, our fetch takes
        // minutes): when the last saved winner is fresh
        // and the identity is present, try that single
        // gateway+transport first — ONE real-traffic
        // attempt, the same gate as the full fetch. A
        // pass ends the fetch here (~20–30s, no scan);
        // a fail falls through to the full per-protocol
        // scan below. Registration already ran above
        // when the identity was missing, so the first-
        // ever fetch (or one after a data wipe) still
        // pays it — honest limit, not a shortcut.
        val order = attemptOrder(ctx)
        val firstRung = order.firstOrNull()
        var fastWin: WarpRecord? = null
        run {
            val last = loadAll(ctx).firstOrNull() ?: return@run
            val ageMs = System.currentTimeMillis() - last.createdAt
            if (!AetherRungPlan.fastPathEligible(identity != null, last.rung, ageMs, last.gatewayIp)) return@run
            if (isCancelled()) return@run
            val carrier = AetherRungPlan.fastPathCarrier(last.rung, last.alpn)
            wlog(ctx, "مسیر سریع: برندهٔ قبلی (${last.rungLabel(ctx)} ${carrier.uppercase()} روی ${last.gatewayIp}) را اول امتحان می‌کنم…",
                "Fast path: trying the last winner first (${last.rungLabel(ctx)} ${carrier.uppercase()} on ${last.gatewayIp})…")
            val tmp = last.copy(id = "tmp-fastpath", pingMs = -1L)
            var winIp: String? = null
            onProgressPct?.invoke(FetchProgress.percent(last.rung, 0.5f))
            val outcome: ProbeOutcome = when (last.rung) {
                RUNG_AETHER -> {
                    if (!AetherBridge.ensureIdentityFile(ctx)) {
                        ProbeOutcome(-1L,
                            AetherBridge.lastIdentityError ?: "aether identity failed",
                            emptyList(), false, 0, aether = true)
                    } else {
                        val port = if (carrier == "h3") AetherRungPlan.H3_PORT else AetherRungPlan.H2_PORT
                        val (o, _, w) = probeAetherOnce(ctx, tmp, AetherBridge.PROBE_SOCKS_BASE, port)
                        if (o.ms >= 0) winIp = w ?: tmp.gatewayIp
                        o
                    }
                }
                RUNG_GOOL -> {
                    if (!AetherBridge.ensureIdentityFile(ctx)) {
                        ProbeOutcome(-1L,
                            AetherBridge.lastIdentityError ?: "aether identity failed",
                            emptyList(), false, 0, aether = true)
                    } else {
                        // Best-effort: the engine provisions
                        // the inner identity in-tunnel
                        // without the file.
                        AetherBridge.ensureGoolIdentityFile(ctx)
                        val port = if (carrier == "h3") AetherRungPlan.H3_PORT else AetherRungPlan.H2_PORT
                        val (o, _, w) = probeGoolAetherOnce(ctx, tmp, AetherBridge.PROBE_SOCKS_BASE, port, goolInnerPeer(ctx))
                        if (o.ms >= 0) winIp = w ?: tmp.gatewayIp
                        o
                    }
                }
                else -> probeMasque(ctx, tmp)
            }
            if (outcome.ms >= 0) {
                val trace = WarpIdentity.fetchTrace(winIp ?: tmp.gatewayIp, 6000)
                val won = tmp.copy(
                    gatewayIp = winIp ?: tmp.gatewayIp,
                    pingMs = outcome.ms,
                    countryCode = trace?.first ?: tmp.countryCode,
                    colo = trace?.second ?: tmp.colo)
                fastWin = won
                wlog(ctx, "[✓] مسیر سریع جواب داد: ${last.rungLabel(ctx)} ${carrier.uppercase()} روی ${won.gatewayIp} (${outcome.ms}ms)؛ اسکن کامل اجرا نمی‌شود.",
                    "[✓] Fast path worked: ${last.rungLabel(ctx)} ${carrier.uppercase()} on ${won.gatewayIp} (${outcome.ms}ms); skipping the full scan.")
            } else {
                val coreFa = coreLogSuffix(outcome).first
                val coreEn = coreLogSuffix(outcome).second
                wlog(ctx, "برندهٔ قبلی ترافیک واقعی را رد نکرد${if (outcome.err != null) ": ${outcome.err}" else ""}$coreFa؛ اسکن کامل…",
                    "Last winner did not pass real traffic${if (outcome.err != null) ": ${outcome.err}" else ""}$coreEn; full scan…")
            }
        }
        val fastRecord = fastWin
        if (fastRecord != null) {
            val rec = deliver(ctx, fastRecord, fastRecord.pingMs)
            onProgressPct?.invoke(FetchProgress.percent("deliver", 1f))
            return FetchResult.Success(
                listOf(rec),
                listOf(RungOutcome(rec.rung, true, rec.gatewayIp, rec.pingMs, "fast-path")))
        }
        if (isCancelled()) return FetchResult.Failed("لغو شد.", "Cancelled.")

        // Stage A: TCP (+ TLS/SPKI + trace as INFO),
        // 16 at a time. 1.55: the SPKI is diagnostic
        // only — a TLS failure never drops a gateway
        // (see AetherRungPlan.scanKeepsGateway).
        // 1.52 — the scan targets are the SAVED clean list
        // first, then the 18 seeds: the app re-verifies what
        // worked before on THIS network, it never trusts the
        // seed list alone.
        val scanTargets = (loadCleanList(ctx) + GATEWAYS).distinct().filter { it.isNotBlank() }
        onProgress("در حال اسکن دروازه‌ها…", "Scanning gateways…")
        wlog(ctx, "اسکن دروازه‌ها شروع شد (${scanTargets.size} دروازه: ${loadCleanList(ctx).size} از لیست تمیز ذخیره‌شده + ${GATEWAYS.size} پیش‌فرض).",
            "Gateway scan started (${scanTargets.size} gateways: ${loadCleanList(ctx).size} from the saved clean list + ${GATEWAYS.size} defaults).")
        // 1.47: gateways that answered TCP but failed
        // TLS (with the reason) — the scan used to
        // drop them silently.
        val scanErrors = java.util.Collections.synchronizedList(mutableListOf<String>())
        // 1.55 — TLS failures are INFO only (see
        // AetherRungPlan.scanKeepsGateway): the SPKI is
        // diagnostic, no dial needs it, so a gateway
        // whose TLS handshake fails cert validation
        // still reaches the H3 verify.
        val tlsInfo = java.util.Collections.synchronizedList(mutableListOf<String>())
        val poolA = Executors.newFixedThreadPool(16)
        val survivors = mutableListOf<HealthyGateway>()
        try {
            val futures = scanTargets.map { ip ->
                poolA.submit(Callable {
                    if (!tcpOk(ip, 2000)) { scanErrors.add("$ip: TCP"); return@Callable null }
                    // 1.55 — SPKI is DIAGNOSTIC ONLY: the
                    // verdict is TCP (scanKeepsGateway); a
                    // TLS failure never drops the gateway.
                    // spki stays "" then; the H3 verify
                    // decides whether it works.
                    val (spki, tlsErr) = WarpIdentity.fetchServerSpkiWithError(ip, 6000)
                    if (!AetherRungPlan.scanKeepsGateway(true, spki != null)) return@Callable null
                    if (spki == null) tlsInfo.add("$ip: TLS: ${tlsErr ?: "?"}")
                    val trace = WarpIdentity.fetchTrace(ip, 6000)
                    HealthyGateway(ip, trace?.first ?: "", trace?.second ?: "",
                        spki?.first ?: "", spki?.second ?: "", -1L, System.currentTimeMillis())
                })
            }
            var scanned = 0
            for (f in futures) {
                if (isCancelled()) break
                try {
                    f.get(9000, TimeUnit.MILLISECONDS)?.let { survivors += it }
                } catch (_: Exception) {
                    try { f.cancel(true) } catch (_: Exception) {}
                }
                scanned++
                onProgressPct?.invoke(FetchProgress.percent("scan",
                    scanned.toFloat() / futures.size.coerceAtLeast(1)))
            }
        } finally {
            poolA.shutdownNow()
        }
        // 1.46: the scan is a PRE-FILTER only — these
        // gateways answered TCP (+TLS+trace when
        // available), they are NOT "healthy" yet; only
        // the real-traffic probe below can say that.
        // The log must not mislead.
        // 1.55 — survivors are TCP-answering gateways;
        // the TLS/SPKI result is info only (see
        // scanKeepsGateway).
        wlog(ctx, "اسکن اولیه تمام شد؛ ${survivors.size} دروازه به TCP جواب دادند — این‌ها هنوز «سالم» نیستند؛ تست ترافیک واقعی باقی است.",
            "Pre-filter scan done; ${survivors.size} gateways answered TCP — these are NOT healthy yet; the real-traffic test is still to come.")
        if (tlsInfo.isNotEmpty()) {
            wlog(ctx, "اسکن: ${tlsInfo.size} دروازه گواهی TLS معتبر ندادند (برای H3/aether مهم نیست؛ تست ترافیک تصمیم می‌گیرد) — ${tlsInfo.take(8).joinToString("؛ ")}.",
                "Scan: ${tlsInfo.size} gateways gave no valid TLS certificate (irrelevant for H3/aether; the traffic test decides) — ${tlsInfo.take(8).joinToString("; ")}.")
        }
        if (scanErrors.isNotEmpty()) {
            wlog(ctx, "اسکن: ${scanErrors.size} دروازه به TCP جواب نداد — ${scanErrors.take(8).joinToString("؛ ")}.",
                "Scan: ${scanErrors.size} gateways did not answer TCP — ${scanErrors.take(8).joinToString("; ")}.")
        }
        // 1.47: dial facts, logged once per fetch so
        // the next failure can be judged against the
        // exact config the core dialled with. Only
        // public facts — never private keys or tokens.
        // The client SPKI fingerprint is the first 16
        // hex chars of its SHA-256.
        val clientSpkiSha = try {
            MessageDigest.getInstance("SHA-256")
                .digest(Base64.decode(idn.masquePublicSpkiB64, Base64.DEFAULT))
                .joinToString("") { "%02x".format(it) }
        } catch (_: Exception) { "" }
        val tunnelAddrs = listOfNotNull(
            idn.ipv4.takeIf { it.isNotBlank() },
            idn.ipv6.takeIf { it.isNotBlank() }
        ).joinToString(", ")
        wlog(ctx, "مشخصات اتصال: SNI=${WarpIdentity.MASQUE_SNI}؛ ALPN=h3→h2؛ میزبان=${WarpIdentity.MASQUE_HOST}؛ مسیر=${WarpIdentity.MASQUE_PATH}؛ آدرس تونل=$tunnelAddrs؛ اثر انگشت کلید کلاینت=${clientSpkiSha.take(16)}؛ فرگمنت H2=خاموش؛ User-Agent=« » (یک فاصله — آزمایش ۱٫۵۸).",
            "Dial facts: SNI=${WarpIdentity.MASQUE_SNI}; ALPN=h3→h2; host=${WarpIdentity.MASQUE_HOST}; path=${WarpIdentity.MASQUE_PATH}; tunnel address=$tunnelAddrs; client key fingerprint=${clientSpkiSha.take(16)}; H2 fragment=off; User-Agent=\" \" (single space — 1.58 probe).")
        // 1.52 — STAGE A2, THE WIDE SWEEP (user order:
        // the app must FIND the hole itself): when the seed
        // scan yields fewer than 3 survivors, walk aether's
        // own MASQUE /24s (13 ranges, 3556 IPs) with a fast
        // TCP sweep and TLS+trace only the ones that answer.
        // On a network where the 18 seeds are filtered, this
        // is what finds working gateways. Bounded: 60s wall
        // clock, stops at 20 TCP-open, never blocks the fetch.
        if (survivors.size < 3 && !isCancelled()) {
            onProgressPct?.invoke(FetchProgress.percent("sweep", 0f))
            val swept = wideSweep(ctx, isCancelled, deadlineMs)
            onProgressPct?.invoke(FetchProgress.percent("sweep", 1f))
            val have = survivors.map { it.ip }.toSet()
            val fresh = swept.filter { it.ip !in have }
            if (fresh.isNotEmpty()) {
                survivors += fresh
                wlog(ctx, "جست‌وجوی گسترده ${fresh.size} دروازهٔ تازه پیدا کرد؛ جمعاً ${survivors.size} دروازه برای تست ترافیک.",
                    "Wide sweep found ${fresh.size} new gateways; ${survivors.size} total for the traffic test.")
            } else {
                wlog(ctx, "جست‌وجوی گسترده دروازهٔ تازه‌ای پیدا نکرد.",
                    "Wide sweep found no new gateway.")
            }
        }
        if (survivors.isEmpty()) {
            return FetchResult.Failed(
                "هیچ دروازه‌ای جواب نداد؛ اینترنت را چک کن و دوباره بزن.",
                "No gateway answered; check your connection and try again.")
        }
        // Country preference: gateways of the picked
        // country first (preference, not a filter).
        // 1.55 — proven gateways first (see
        // PRIORITY_GATEWAYS), then the picked country,
        // then IP order.
        val pickedCc = selectedCountry(ctx)
        val ordered = survivors.sortedWith(
            compareBy<HealthyGateway> {
                AetherRungPlan.PRIORITY_GATEWAYS.indexOf(it.ip)
                    .let { idx -> if (idx < 0) Int.MAX_VALUE else idx }
            }.thenByDescending {
                pickedCc.isNotBlank() && it.countryCode.equals(pickedCc, ignoreCase = true)
            }.thenBy { it.ip })
        saveHealthy(ctx, survivors.sortedBy { it.ip })
        // 1.52: the freshest scanned survivor is the enroll
        // address from here on (aether provisioning + the
        // next registration both go to a verified-clean IP).
        ordered.firstOrNull()?.let { AetherBridge.setEnrollAddress(it.ip) }
        // 1.48: log the EXACT streamSettings the core
        // will dial with (read back from the built
        // outbound) + warp.address vs the registered
        // addresses + the warp_test.go fixture
        // comparison. Log only — nothing changes.
        logExactStreamSettings(ctx, ordered.first())
        // 1.50: say which engine the first rung uses.
        // 1.55: the engine is the 2.4.0 CLI binary
        // (libaether_v24.so), driven like Asha drives it.
        if (firstRung == RUNG_AETHER) {
            if (AetherBridge.cliAvailable(ctx)) {
                wlog(ctx, "پلهٔ اول: AETHER — موتور MASQUE آشا (aether ${AetherBridge.version()}).",
                    "First rung: AETHER — Asha's MASQUE engine (aether ${AetherBridge.version()}).")
            } else {
                wlog(ctx, "پلهٔ اول: AETHER — باینری موتور پیدا نشد؛ نردبان به Xray برمی‌گردد.",
                    "First rung: AETHER — engine binary missing; falling back to Xray.")
            }
        }
        if (isCancelled()) return FetchResult.Failed("لغو شد.", "Cancelled.")

        // Stage B: real-traffic probes per rung — 1.58
        // collects a winner PER PROTOCOL (one card
        // each), never stopping at the first winner.
        // The remaining budget is shared FAIRLY
        // (AetherRungPlan.rungBudgetShares): AETHER
        // 50%, Xray H3 15%, Xray H2 15%, GOOL 20% —
        // a slow rung can never starve the later ones.
        // 1.60 — the TEST clock starts HERE, after
        // registration + scan: the test phase gets its
        // own full 240s, whatever the pre-test phases
        // cost. The 1.59 run measured the remaining
        // budget against the fetch-start clock, so a
        // ~3 min double registration left AETHER 0s.
        val testDeadlineMs = System.currentTimeMillis() + 240_000L
        val stageBRemaining = (testDeadlineMs - System.currentTimeMillis()).coerceAtLeast(0L)
        val aetherShareSec = AetherRungPlan.rungBudgetMs(stageBRemaining, RUNG_AETHER) / 1000
        val h3ShareSec = AetherRungPlan.rungBudgetMs(stageBRemaining, RUNG_H3) / 1000
        val h2ShareSec = AetherRungPlan.rungBudgetMs(stageBRemaining, RUNG_H2) / 1000
        val goolShareSec = AetherRungPlan.rungBudgetMs(stageBRemaining, RUNG_GOOL) / 1000
        wlog(ctx, "بودجهٔ مرحلهٔ تست: ${stageBRemaining / 1000} ثانیه باقی‌مانده — AETHER ${aetherShareSec} ثانیه، MASQUE H3 ${h3ShareSec} ثانیه، MASQUE H2 ${h2ShareSec} ثانیه، GOOL ${goolShareSec} ثانیه.",
            "Probe-stage budget: ${stageBRemaining / 1000}s left — AETHER ${aetherShareSec}s, MASQUE H3 ${h3ShareSec}s, MASQUE H2 ${h2ShareSec}s, GOOL ${goolShareSec}s.")
        val wins = mutableListOf<WarpRecord>()
        val outcomes = mutableListOf<RungOutcome>()
        val poolB = Executors.newFixedThreadPool(8)
        try {
            for ((rungIdx, rung) in order.withIndex()) {
                if (isCancelled()) return FetchResult.Failed("لغو شد.", "Cancelled.")
                // 1.60 — a rung's deadline is its own
                // (floored) share from the TEST clock —
                // never capped by the pre-test deadline
                // and never 0s: the floor extends the
                // effective deadline when the test clock
                // is nearly exhausted (see
                // AetherRungPlan.rungBudgetMs). A rung is
                // skipped only when its own floored
                // deadline is already past, which cannot
                // happen on entry (the floor is >= 20s).
                val rungDeadline = AetherRungPlan.rungDeadlineMs(
                    System.currentTimeMillis(), stageBRemaining, rung)
                if (System.currentTimeMillis() > rungDeadline) {
                    // 1.55 — a deadline break used to be
                    // silent, so the log jumped from
                    // "AETHER failed" to "ladder gave
                    // nothing" with no H2/GOOL lines (1.54
                    // device run). Say what was skipped.
                    val skipped = order.drop(rungIdx)
                    wlog(ctx, "مهلت دریافت تمام شد؛ پله‌های ${skipped.joinToString("، ")} اجرا نشدند.",
                        "Fetch deadline reached; rungs ${skipped.joinToString(", ")} were skipped.")
                    for (s in skipped) outcomes += RungOutcome(s, false, "", -1L, "skipped: deadline")
                    break
                }
                onProgressPct?.invoke(FetchProgress.percent(rung, 0f))
                when (rung) {
                    RUNG_AETHER -> {
                        onProgress("در حال تست AETHER…", "Testing AETHER…")
                        val win = probeAetherSequential(ctx, ordered, rungDeadline, isCancelled, lastWinCarrier(ctx)) { done, total ->
                            onProgressPct?.invoke(FetchProgress.percent(RUNG_AETHER,
                                done.toFloat() / total.coerceAtLeast(1)))
                        }
                        if (win != null) {
                            val rec = deliver(ctx, win, win.pingMs)
                            wins += rec
                            outcomes += RungOutcome(rung, true, rec.gatewayIp, rec.pingMs, "")
                        } else {
                            outcomes += RungOutcome(rung, false, "", -1L, "no gateway passed real traffic")
                            wlog(ctx, "هیچ دروازه‌ای روی AETHER ترافیک واقعی را رد نکرد.",
                                "No gateway passed real traffic on AETHER.")
                        }
                    }
                    RUNG_H3, RUNG_H2 -> {
                        val alpn = if (rung == RUNG_H3) "h3" else "h2"
                        onProgress(
                            if (rung == RUNG_H3) "در حال تست MASQUE H3…" else "در حال تست MASQUE H2…",
                            if (rung == RUNG_H3) "Testing MASQUE H3…" else "Testing MASQUE H2…")
                        val rungWins = run {
                            // 1.62 — the 400 count for the
                            // summary (reset per probe pass).
                            lastProbe400Count = 0
                            probeRung(ctx, poolB, ordered, rung, alpn,
                            rungDeadline, isCancelled, onAttempt = { done, total ->
                                onProgressPct?.invoke(FetchProgress.percent(rung,
                                    done.toFloat() / total.coerceAtLeast(1)))
                            })
                        }
                        var n400 = lastProbe400Count
                        var bestWin: WarpRecord? = rungWins.minByOrNull { it.pingMs }
                        var retryNote = ""
                        if (bestWin == null && rung == RUNG_H2) {
                            // 1.47: H2 passed no gateway; one
                            // second attempt over the same
                            // gateways before giving up on H2.
                            // Own 30s budget (capped by the
                            // rung deadline) so the fetch does
                            // not stall. (1.49: the fragment
                            // is off for both passes now, so
                            // this is a plain retry.)
                            wlog(ctx, "H2 روی هیچ دروازه‌ای رد نشد؛ تست دوبارهٔ همان دروازه‌ها…",
                                "H2 passed no gateway; retrying the same gateways…")
                            val nfDeadline = minOf(rungDeadline, System.currentTimeMillis() + 30_000L)
                            // 1.62 — the retry pass's 400s add
                            // to the same summary count.
                            lastProbe400Count = 0
                            val winsNf = probeRung(ctx, poolB, ordered, rung, alpn,
                                nfDeadline, isCancelled, noFrag = true, label = "masque-h2-nofrag",
                                onAttempt = { done, total ->
                                    onProgressPct?.invoke(FetchProgress.percent(rung,
                                        done.toFloat() / total.coerceAtLeast(1)))
                                })
                            n400 += lastProbe400Count
                            bestWin = winsNf.minByOrNull { it.pingMs }
                            if (bestWin != null) retryNote = "retry"
                        }
                        if (bestWin != null) {
                            val rec = deliver(ctx, bestWin, bestWin.pingMs)
                            wins += rec
                            outcomes += RungOutcome(rung, true, rec.gatewayIp, rec.pingMs, retryNote)
                        } else {
                            // 1.62 — the failure carries its
                            // real reason into the summary:
                            // how many gateways refused
                            // MASQUE with 400 outright.
                            val failNote = if (n400 > 0)
                                "no gateway passed real traffic ($n400 gateway(s) answered 400)"
                            else "no gateway passed real traffic"
                            outcomes += RungOutcome(rung, false, "", -1L, failNote)
                            if (rung == RUNG_H3) {
                                wlog(ctx, "هیچ دروازه‌ای روی H3 ترافیک واقعی را رد نکرد؛ برگشت به H2 روی همان دروازه‌ها.",
                                    "No gateway passed real traffic on H3; falling back to H2 on the same gateways.")
                                wlog(ctx, "HTTP/2 carried the tunnel where HTTP/3 could not; staying on h2 for this session",
                                    "HTTP/2 carried the tunnel where HTTP/3 could not; staying on h2 for this session")
                            } else {
                                wlog(ctx, "هیچ دروازه‌ای روی H2 هم ترافیک واقعی را رد نکرد.",
                                    "No gateway passed real traffic on H2 either.")
                            }
                        }
                    }
                    RUNG_GOOL -> {
                        onProgress("در حال تست GOOL…", "Testing GOOL…")
                        // 1.60 — GOOL registers its inner
                        // identity against the enroll IP the
                        // MAIN registration already succeeded
                        // on (goolEnrollIpFor), never the
                        // freshest scanned survivor: 1.59
                        // used 162.159.198.2, which fails
                        // TLS cert validation, and died on
                        // it while 162.159.192.1 worked.
                        val win = probeGoolRung(ctx, ordered, rungDeadline, isCancelled, enrollSni,
                            goolEnrollIp = goolEnrollIpFor(enrollIp, AetherBridge.enrollIp),
                            onAttempt = { done, total ->
                                onProgressPct?.invoke(FetchProgress.percent(RUNG_GOOL,
                                    done.toFloat() / total.coerceAtLeast(1)))
                            })
                        if (win != null) {
                            val rec = deliver(ctx, win, win.pingMs)
                            wins += rec
                            outcomes += RungOutcome(rung, true, rec.gatewayIp, rec.pingMs, "")
                        } else {
                            // 1.62 — the failure carries the
                            // engine's exact reason (lastGoolError:
                            // e.g. the inner WireGuard "verify
                            // timeout" on the gool peer) into
                            // the summary. GOOL is NOT fixed
                            // by guessing: the outer MASQUE
                            // passes real traffic on the same
                            // gateway in ~1.4s, so the path is
                            // fast — the inner WG handshake to
                            // the gool peer never completes
                            // inside the engine's own 15s×2
                            // validation, inside the closed
                            // aether binary. No Kotlin-side
                            // cause was found (identity file
                            // + --gool-peer both come from the
                            // same registered "gool" identity;
                            // the connect-path wait IS fixed —
                            // see AetherRungPlan GOOL wait).
                            val goolNote = lastGoolError?.take(160)
                                ?: "no gateway passed real traffic"
                            outcomes += RungOutcome(rung, false, "", -1L, goolNote)
                            wlog(ctx, "GOOL هم ترافیک واقعی را رد نکرد.",
                                "GOOL did not pass real traffic either.")
                        }
                    }
                }
            }
        } finally {
            poolB.shutdownNow()
        }
        // 1.58 — the per-rung summary: every rung
        // reports, winners and failures alike.
        // 1.62 — with the real reason on every
        // failure (formatRungSummary).
        val (summaryFa, summaryEn) = formatRungSummary(outcomes)
        wlog(ctx, "دریافت تمام شد: $summaryFa.",
            "Fetch done: $summaryEn.")
        if (wins.isNotEmpty()) {
            // 1.58 — the WG rung is the last resort and
            // is proven dead on this network (13:38:
            // connects, no traffic); it runs only when
            // NO masque/gool rung won, so a fetch that
            // already has a working path does not burn
            // 15s+ on it. Said plainly in the log.
            wlog(ctx, "وایرگارد مستقیم در این دریافت تست نشد؛ یک مسیر وصل شد.",
                "Direct WireGuard was not tested in this fetch; a path already connected.")
            onProgressPct?.invoke(FetchProgress.percent("deliver", 1f))
            return FetchResult.Success(wins, outcomes)
        }
        // Nothing masque/gool passed — the activity
        // tries the wireguard-UDP rung.
        wlog(ctx, "نردبان MASQUE/GOOL نتیجه نداد؛ تلاش با وایرگارد مستقیم…",
            "MASQUE/GOOL ladder gave nothing; trying direct WireGuard…")
        onProgressPct?.invoke(FetchProgress.percent("wireguard-udp", 0f))
        return FetchResult.NeedWireGuard(idn)
    }

    /** Probe one masque rung over the survivors with
     *  smart-stop: target 6 successes, 20s silence
     *  window, 120s cap. Returns the winners. */
    private fun probeRung(
        ctx: Context,
        pool: java.util.concurrent.ExecutorService,
        candidates: List<HealthyGateway>,
        rung: String,
        alpn: String,
        deadlineMs: Long,
        isCancelled: () -> Boolean,
        // 1.47: noFrag retries the H2 dial as a
        // second pass; label is only the log text
        // (the record rung stays the RUNG_H2
        // constant, so the connect path still
        // matches it). 1.49: the fragment is OFF
        // for both passes now, so the retry is a
        // plain second attempt, not a fragment
        // toggle — the ladder is unchanged.
        noFrag: Boolean = false,
        label: String = rung,
        // 1.52: the aether rung no longer uses this parallel
        // prober — it runs sequentially in probeAetherSequential
        // (verify + retry, H3 first). This function is Xray-only now.
        // 1.60 — real-stage progress: (futures done,
        // futures submitted) after each probe completes.
        onAttempt: ((Int, Int) -> Unit)? = null
    ): List<WarpRecord> {
        val wins = mutableListOf<WarpRecord>()
        val completion: CompletionService<Triple<HealthyGateway, Long, ProbeOutcome>> =
            ExecutorCompletionService(pool)
        var submitted = 0
        for (g in candidates) {
            if (submitted >= 18) break
            val recId = if (noFrag) "tmp-nf-$rung-${g.ip}" else "tmp-$rung-${g.ip}"
            if (noFrag) noFragIds.add(recId)
            val rec = WarpRecord(
                id = recId, name = "", rung = rung, alpn = alpn,
                gatewayIp = g.ip, gatewayPort = GATEWAY_PORT,
                countryCode = g.countryCode, colo = g.colo,
                serverSpkiB64 = g.spkiB64, serverSpkiSha256 = g.spkiSha256,
                pingMs = -1L, exitCountry = "", exitColo = "",
                createdAt = 0L, wgTunnelName = ""
            )
            completion.submit(Callable {
                val outcome = probeMasque(ctx, rec)
                Triple(g, outcome.ms, outcome)
            })
            submitted++
        }
        var lastSuccessAt = System.currentTimeMillis()
        var taken = 0
        // 1.62 — gateways that refused MASQUE with
        // 400 outright (for the summary).
        var n400 = 0
        while (taken < submitted) {
            if (isCancelled()) break
            val now = System.currentTimeMillis()
            if (now > deadlineMs) break
            if (wins.size >= 6) break
            if (wins.isNotEmpty() && now - lastSuccessAt > 20_000L) break
            val remaining = (deadlineMs - now).coerceAtLeast(0L)
            val fut: Future<Triple<HealthyGateway, Long, ProbeOutcome>>? = try {
                completion.poll(1000, TimeUnit.MILLISECONDS)
            } catch (_: Exception) { null }
            if (fut == null) {
                if (remaining <= 0L) break
                continue
            }
            taken++
            val (g, ms, outcome) = try { fut.get() } catch (_: Exception) { continue }
            onAttempt?.invoke(taken, submitted)
            val err = outcome.err
            if (ms >= 0) {
                lastSuccessAt = System.currentTimeMillis()
                wins += WarpRecord(
                    id = if (noFrag) "tmp-nf-$rung-${g.ip}" else "tmp-$rung-${g.ip}", name = "", rung = rung, alpn = alpn,
                    gatewayIp = g.ip, gatewayPort = GATEWAY_PORT,
                    countryCode = g.countryCode, colo = g.colo,
                    serverSpkiB64 = g.spkiB64, serverSpkiSha256 = g.spkiSha256,
                    pingMs = ms, exitCountry = "", exitColo = "",
                    createdAt = 0L, wgTunnelName = ""
                )
                wlog(ctx, "[✓] $label روی ${g.ip} ترافیک واقعی را رد کرد (${ms}ms).",
                    "[✓] $label passed real traffic on ${g.ip} (${ms}ms).")
            } else {
                // 1.46: the per-gateway dial error is the
                // diagnosis — 1.45 discarded it, so every
                // gateway failed silently and identically.
                // 1.47: the FULL error — the 220-char
                // cap could hide the wrapped dial cause.
                // 1.48: + the core log lines (the exact
                // establish error, when the core wrote one).
                val coreFa = coreLogSuffix(outcome).first
                val coreEn = coreLogSuffix(outcome).second
                // 1.62 — count the outright-400 refusals.
                if ((err?.contains("400") == true) ||
                    outcome.coreLines.any { it.contains("400") }) n400++
                wlog(ctx, "[✗] $label روی ${g.ip} رد شد: ${err ?: "نامشخص"}$coreFa.",
                    "[✗] $label failed on ${g.ip}: ${err ?: "unknown"}$coreEn.")
            }
        }
        // 1.62 — publish the 400 count for the summary.
        lastProbe400Count = n400
        return wins
    }

    /** 1.58 — one GOOL (WOM) attempt on the aether
     *  CLI: --protocol gool carries the inner
     *  WireGuard inside the outer MASQUE stream, so
     *  no raw UDP ever touches the user network (the
     *  Xray-chain GOOL it replaces inherited the
     *  broken Xray H2 outer — 400 on every gateway).
     *  A clone of probeAetherOnce: SOCKS serving +
     *  generate_204 200/204 wins. */
    private fun probeGoolAetherOnce(
        ctx: Context,
        rec: WarpRecord,
        socksPort: Int,
        port: Int,
        goolPeer: String?
    ): Triple<ProbeOutcome, Boolean, String?> {
        val t0 = System.currentTimeMillis()
        val carrier = if (port == AetherRungPlan.H3_PORT) "h3" else "h2"
        val h2 = port != AetherRungPlan.H3_PORT
        // 1.58 (Part B) — a new CLI start kills whatever
        // process runs, so any warm record from an
        // earlier probe is dead from here on: drop it.
        AetherBridge.clearWarmSession()
        val started = AetherCli.start(ctx,
            AetherCli.buildGoolArgs(socksPort, rec.gatewayIp, port, h2, goolPeer),
            socksPort, AetherCli.GOOL_PROBE_SOCKS_WAIT_MS, AetherBridge.enrollIp) { line ->
            AetherBridge.noteCliLine(line)
            wlog(ctx, line, line)
        }
        if (!started) {
            return Triple(ProbeOutcome(-1L,
                "پاسخی نیامد (${AetherCli.lastError})",
                emptyList(), false, 0, aether = true), false, null)
        }
        val selected = AetherCli.lastSelectedGatewayIp
        val winnerIp = if (selected.isNotBlank()) selected else rec.gatewayIp
        val result: Triple<ProbeOutcome, Boolean, String?> = try {
            // 1.61 — the probe retries (3 attempts, 20s
            // first, 10s after): the first request
            // through a fresh tunnel can exceed 10s
            // while the engine already validated it.
            // A final failure carries the REAL
            // exception text, never a bare -1.
            val res = WarpIdentity.generate204ThroughSocksWithRetry(socksPort)
            val ms = System.currentTimeMillis() - t0
            if (res.code == 200 || res.code == 204) Triple(
                ProbeOutcome(ms, null, emptyList(), true, 0, aether = true), true, winnerIp)
            else Triple(ProbeOutcome(-1L, "generate_204 از SOCKS gool: ${res.error ?: "کد ${res.code}"} (تلاش ${res.attempts} بار)",
                emptyList(), true, 0, aether = true), true, winnerIp)
        } catch (t: Throwable) {
            Triple(ProbeOutcome(-1L, t.message ?: t.javaClass.simpleName,
                emptyList(), true, 0, aether = true), true, winnerIp)
        }
        // 1.58 (Part B) — a WIN keeps its CLI session
        // alive as the warm standby; a loss still tears
        // the session down.
        if (result.first.ms >= 0) {
            AetherBridge.holdWarmSession(socksPort, winnerIp, carrier, RUNG_GOOL)
            wlog(ctx, "جلسهٔ GOOL روی $winnerIp گرم نگه داشته شد (اتصال سریع).",
                "GOOL session on $winnerIp kept warm (fast connect).")
        } else {
            AetherCli.stop()
        }
        return result
    }

    /** GOOL rung (WOM): outer MASQUE (last winning
     *  transport first, then the other carrier)
     *  carrying an inner WireGuard, driven by the
     *  aether 2.4.0 CLI — the only H2 stack proven
     *  on this network (1.57). 1.58 replaces the Xray
     *  chain (its outer was the broken Xray H2 — 400
     *  on every gateway); the Xray chain survives
     *  only when the CLI binary is absent. One probe
     *  per gateway per carrier — real traffic or
     *  nothing — each failure logged with its error. */
    private fun probeGoolRung(
        ctx: Context,
        candidates: List<HealthyGateway>,
        deadlineMs: Long,
        isCancelled: () -> Boolean,
        cleanIpSni: String = WarpReg.REG_HOST,
        // 1.60 — the enroll IP the MAIN registration
        // already succeeded on (runFetch passes the
        // verified one); "" falls back to the bridge
        // enroll IP (see goolEnrollIpFor).
        goolEnrollIp: String = "",
        // 1.60 — real-stage progress: (attempts done,
        // attempts planned) after each carrier attempt.
        onAttempt: ((Int, Int) -> Unit)? = null
    ): WarpRecord? {
        if (candidates.isEmpty()) return null
        val socksPort = try {
            if (VpnClientService.isRunning) VpnClientService.sessionSocksPort else -1
        } catch (_: Exception) { -1 }
        // 1.60 — GOOL's registration is NOT free: three
        // routed API calls that can each burn ~45s on a
        // hostile network, and the 1.59 run paid for it
        // AFTER the deadline was already gone. Never
        // start it with under 30s of rung budget left —
        // a registration that cannot finish before the
        // rung ends is skipped honestly.
        if (deadlineMs - System.currentTimeMillis() < 30_000L) {
            wlog(ctx, "GOOL رد شد: بودجهٔ پله (کمتر از ۳۰ ثانیه) برای ثبت‌نام دوم کافی نیست.",
                "GOOL skipped: the rung budget (under 30s) is not enough for the second registration.")
            return null
        }
        val enrollForGool = goolEnrollIpFor(goolEnrollIp, AetherBridge.enrollIp)
        val gidn = WarpIdentity.ensure(ctx, "gool", socksPort, enrollForGool, cleanIpSni)
        if (gidn == null) {
            wlog(ctx, "GOOL ناموفق بود: ثبت‌نام دوم وارپ انجام نشد.",
                "GOOL failed: the second WARP registration did not complete.")
            return null
        }
        goolIdentity = gidn
        val useCli = AetherBridge.cliAvailable(ctx)
        if (!useCli) {
            wlog(ctx, "GOOL: باینری aether پیدا نشد؛ زنجیرهٔ Xray (قدیمی) امتحان می‌شود.",
                "GOOL: aether binary missing; trying the legacy Xray chain.")
        } else if (!AetherBridge.ensureIdentityFile(ctx)) {
            wlog(ctx, "GOOL شروع نشد: ${AetherBridge.lastIdentityError ?: "?"}",
                "GOOL did not start: ${AetherBridge.lastIdentityError ?: "?"}")
            return null
        } else {
            // Best-effort: the engine provisions the
            // inner identity in-tunnel without it.
            AetherBridge.ensureGoolIdentityFile(ctx)
        }
        // The last winning transport first (H2 on this
        // network), then the other carrier — per
        // gateway, like the AETHER rung.
        val lastCarrier = lastWinCarrier(ctx)
        val carriers = if (lastCarrier.equals("h3", ignoreCase = true)) listOf("h3", "h2") else listOf("h2", "h3")
        var goolDone = 0
        val goolTotal = candidates.size * carriers.size
        for (outer0 in candidates) {
            if (isCancelled() || System.currentTimeMillis() > deadlineMs) return null
            for (carrier in carriers) {
                if (isCancelled() || System.currentTimeMillis() > deadlineMs) return null
                val port = if (carrier == "h3") AetherRungPlan.H3_PORT else AetherRungPlan.H2_PORT
                val outer = WarpRecord(
                    id = "tmp-gool-$carrier-${outer0.ip}", name = "", rung = RUNG_GOOL, alpn = carrier,
                    gatewayIp = outer0.ip, gatewayPort = GATEWAY_PORT,
                    countryCode = outer0.countryCode, colo = outer0.colo,
                    serverSpkiB64 = outer0.spkiB64, serverSpkiSha256 = outer0.spkiSha256,
                    pingMs = -1L, exitCountry = "", exitColo = "",
                    createdAt = 0L, wgTunnelName = ""
                )
                val outcome: ProbeOutcome
                var winIp: String? = null
                if (useCli) {
                    val (o, _, w) = probeGoolAetherOnce(ctx, outer, AetherBridge.PROBE_SOCKS_BASE, port, goolInnerPeer(ctx))
                    outcome = o
                    winIp = w
                } else {
                    outcome = probeGool(ctx, outer)
                }
                val ms = outcome.ms
                val err = outcome.err
                goolDone++
                onAttempt?.invoke(goolDone, goolTotal)
                if (ms < 0) {
                    // 1.62 — keep the exact reason for
                    // the fetch summary (the engine's
                    // own error, e.g. inner WG
                    // "verify timeout").
                    lastGoolError = err?.takeIf { it.isNotBlank() }
                        ?: AetherCli.lastError.takeIf { it.isNotBlank() }
                    val coreFa = coreLogSuffix(outcome).first
                    val coreEn = coreLogSuffix(outcome).second
                    wlog(ctx, "GOOL ${carrier.uppercase()} روی ${outer0.ip} ترافیک واقعی را رد نکرد${if (err != null) ": $err" else ""}$coreFa.",
                        "GOOL ${carrier.uppercase()} did not pass real traffic on ${outer0.ip}${if (err != null) ": $err" else ""}$coreEn.")
                    continue
                }
                val winGateway = winIp ?: outer0.ip
                wlog(ctx, "[✓] GOOL ${carrier.uppercase()} روی $winGateway ترافیک واقعی را رد کرد (${ms}ms).",
                    "[✓] GOOL ${carrier.uppercase()} passed real traffic on $winGateway (${ms}ms).")
                return outer.copy(gatewayIp = winGateway, pingMs = ms)
            }
        }
        return null
    }

    /** 1.58 — save one winning record and return it
     *  (the fetch assembles the multi-record Success
     *  itself). One card per protocol: an older card
     *  of the same rung is replaced, never duplicated.
     *  Also records the winning transport for the next
     *  fetch and the connect path.
     *  1.60 — the card name is the literal "free warp"
     *  in both languages ("free warp 2" for a second
     *  winning protocol); the protocol, gateway
     *  country, exit and ping live ONLY in the tap
     *  detail dialog, never on the card. */
    private fun deliver(ctx: Context, win: WarpRecord, pingMs: Long): WarpRecord {
        val all = loadAll(ctx).toMutableList()
        all.removeAll { it.rung == win.rung }
        val name = nextFreeWarpName(all.map { it.name })
        val rec = win.copy(
            id = "warp-${System.currentTimeMillis()}",
            name = name,
            pingMs = pingMs,
            createdAt = System.currentTimeMillis(),
            // 1.59 — stamp the network this config was
            // built on (PART 3 compares it at connect
            // time and swaps the IP in place when the
            // network changed).
            buildNetwork = NetworkProfile.currentKey(ctx)
        )
        all.add(0, rec)
        saveAll(ctx, all)
        saveLadderWin(ctx, rec.rung)
        saveLastWinCarrier(ctx, rec.alpn)
        saveCleanGateway(ctx, rec.gatewayIp)
        val lc = loadLastconn(ctx).toMutableList()
        lc.removeAll { it.ip == rec.gatewayIp && it.carrier == rec.rung }
        lc.add(0, LastConn(rec.gatewayIp, rec.rung, pingMs, System.currentTimeMillis()))
        saveLastconn(ctx, lc)
        wlog(ctx, "[✓] برندهٔ نردبان: ${rec.rungLabel(ctx)} روی ${rec.gatewayIp} (${pingMs}ms)؛ کانفیگ «$name» ذخیره شد.",
            "[✓] Ladder winner: ${rec.rungLabel(ctx)} on ${rec.gatewayIp} (${pingMs}ms); config “$name” saved.")
        return rec
    }

    /** Switch a record's gateway to a healthy one
     *  of the picked country (first-attempt
     *  preference). Falls back to the fastest
     *  healthy gateway, said in the log. */
    fun switchCountry(
        ctx: Context,
        rec: WarpRecord,
        cc: String,
        isCancelled: () -> Boolean = { false }
    ): Pair<WarpRecord?, Boolean> {
        // 1.52: the aether identity file is written once
        // for the whole switch, never per gateway.
        val aetherReady = if (rec.rung == RUNG_AETHER) AetherBridge.ensureIdentityFile(ctx) else false
        val healthy = loadHealthy(ctx)
        if (healthy.isEmpty()) return null to false
        val picked = healthy.filter { it.countryCode.equals(cc, ignoreCase = true) }
            .sortedBy { it.pingMs.coerceAtLeast(0L) }
        val ordered = (picked + healthy.filter { it !in picked }
            .sortedBy { it.pingMs.coerceAtLeast(0L) })
        var fallback = picked.isEmpty()
        for (g in ordered) {
            if (isCancelled()) return null to false
            val tmp = rec.copy(
                id = "tmp-switch-${g.ip}",
                gatewayIp = g.ip, countryCode = g.countryCode, colo = g.colo,
                serverSpkiB64 = g.spkiB64, serverSpkiSha256 = g.spkiSha256
            )
            // 1.46: the probe error is logged, not discarded.
            // 1.48: + the core log lines.
            // 1.52: aether goes through the single
            // CLI attempt (H3, then H2).
            var switchWinIp: String? = null
            var switchCarrier: String? = null
            val outcome = if (rec.rung == RUNG_AETHER) {
                if (!aetherReady) ProbeOutcome(-1L,
                    AetherBridge.lastIdentityError ?: "aether identity failed",
                    emptyList(), false, 0, aether = true)
                else {
                    val (o3, _, w3) = probeAetherOnce(ctx, tmp,
                        AetherBridge.PROBE_SOCKS_BASE,
                        AetherRungPlan.H3_PORT)
                    if (o3.ms >= 0) {
                        switchWinIp = w3 ?: tmp.gatewayIp
                        switchCarrier = "h3"
                        o3
                    } else {
                        val (o2, _, w2) = probeAetherOnce(ctx, tmp,
                            AetherBridge.PROBE_SOCKS_BASE,
                            AetherRungPlan.H2_PORT)
                        if (o2.ms >= 0) {
                            switchWinIp = w2 ?: tmp.gatewayIp
                            switchCarrier = "h2"
                        }
                        o2
                    }
                }
            } else if (rec.rung == RUNG_GOOL) {
                // 1.58 — GOOL switches through the CLI
                // gool path (like the rung), never the
                // bare outer MASQUE probe: a "verified"
                // switch must have tested the chain.
                if (!AetherBridge.ensureIdentityFile(ctx)) ProbeOutcome(-1L,
                    AetherBridge.lastIdentityError ?: "aether identity failed",
                    emptyList(), false, 0, aether = true)
                else {
                    AetherBridge.ensureGoolIdentityFile(ctx)
                    val lastCarrier = lastWinCarrier(ctx)
                    val ports = if (lastCarrier.equals("h3", ignoreCase = true))
                        listOf(AetherRungPlan.H3_PORT, AetherRungPlan.H2_PORT)
                    else listOf(AetherRungPlan.H2_PORT, AetherRungPlan.H3_PORT)
                    var found: ProbeOutcome? = null
                    for (p in ports) {
                        val (o, _, w) = probeGoolAetherOnce(ctx, tmp,
                            AetherBridge.PROBE_SOCKS_BASE, p, goolInnerPeer(ctx))
                        if (o.ms >= 0) {
                            switchWinIp = w ?: tmp.gatewayIp
                            switchCarrier = if (p == AetherRungPlan.H3_PORT) "h3" else "h2"
                            found = o
                            break
                        }
                        found = o
                    }
                    found ?: ProbeOutcome(-1L, "GOOL: no carrier answered",
                        emptyList(), false, 0, aether = true)
                }
            } else probeMasque(ctx, tmp)
            val ms = outcome.ms
            val switchErr = outcome.err
            if (ms >= 0) {
                val winIp = switchWinIp ?: g.ip
                val updated = rec.copy(
                    gatewayIp = winIp, countryCode = g.countryCode, colo = g.colo,
                    serverSpkiB64 = g.spkiB64, serverSpkiSha256 = g.spkiSha256,
                    pingMs = ms, alpn = switchCarrier ?: rec.alpn
                )
                updateRecord(ctx, updated)
                if (fallback) {
                    wlog(ctx, "کشور $cc دروازهٔ سالمی نداشت؛ سریع‌ترین دروازهٔ سالم انتخاب شد.",
                        "Country $cc had no healthy gateway; the fastest healthy gateway was picked.")
                }
                wlog(ctx, "[✓] دروازهٔ «${updated.name}» عوض شد: $winIp (${ms}ms).",
                    "[✓] Gateway for “${updated.name}” switched: $winIp (${ms}ms).")
                return updated to fallback
            }
            val coreFa = coreLogSuffix(outcome).first
            val coreEn = coreLogSuffix(outcome).second
            wlog(ctx, "[✗] عوض کردن دروازه به ${g.ip} نشد: ${switchErr ?: "نامشخص"}$coreFa.",
                "[✗] Gateway switch to ${g.ip} failed: ${switchErr ?: "unknown"}$coreEn.")
        }
        return null to false
    }
}
