package com.renamer

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * v2rayNG's real-ping, reproduced exactly:
 *
 * 1. TCP pre-check (RealPingWorkerService.startRealPing): for simple
 *    types (VLESS/VMess/Trojan/Shadowsocks, not WireGuard, not custom,
 *    not h3), a 1000 ms socket connect to server:port. Fail -> -1.
 * 2. Build a speedtest Xray config (CoreConfigManager
 *    .getV2rayConfig4Speedtest + postProcessForSpeedtest): no
 *    inbounds, no DNS, no routing rules, mux removed. The main
 *    outbound is the config under test.
 * 3. CoreNativeManager.measureOutboundDelay -> libv2ray
 *    MeasureOutboundDelay(config, delayTestUrl): starts an Xray
 *    instance with only outbound/dispatcher/log apps, then does
 *    an HTTP GET *through that instance* to the delay test URL.
 *    Default URL (AppConfig.DELAY_TEST_URL):
 *    https://www.gstatic.com/generate_204
 *    (2.75: all three ping-test routes use this same
 *    URL, so a config green here is green in v2ray.)
 *    Inside libv2ray: 12 s client timeout, 6 s TLS handshake
 *    timeout, 2 attempts, minimum duration wins, success only
 *    on HTTP 200 or 204. Anything else -> -1.
 *
 * Concurrency default in v2rayNG (SettingsManager
 * .getRealPingConcurrency) is 16, so tests run 16 at a time.
 */
object XraySpeedtest {
    const val TEST_URL = "https://www.gstatic.com/generate_204"
    // 2.75: TEST_URL2/TEST_URL3 removed — all routes
    // default to TEST_URL (gstatic), like v2ray.
    const val CONCURRENCY = 16
    @Volatile var testUrlOverride: String? = null
    @Volatile var concurrencyOverride: Int? = null
    fun testUrl(ctx: android.content.Context): String =
        ctx.getSharedPreferences("renamer_vpn", android.content.Context.MODE_PRIVATE)
            .getString("delay_test_url", null)?.takeIf { it.isNotBlank() }
            ?: (testUrlOverride ?: TEST_URL)
    fun poolSize(): Int = (concurrencyOverride ?: CONCURRENCY).coerceIn(1, 64)
    const val TCP_TIMEOUT_MS = 1000

    data class Server(val host: String, val port: Int)

    /** 1.31 (free-button crash fix): decode a vmess link's JSON,
     *  accepting BOTH base64 alphabets. Real vmess links use
     *  STANDARD base64 ('+' and '/'); decoding those with
     *  Base64.URL_SAFE throws IllegalArgumentException
     *  ("bad base-64") — and fetchAll called outboundFor
     *  unprotected, so one such line killed the whole app.
     *  Returns null when the payload is not decodable JSON. */
    fun vmessJson(cfg: String): JSONObject? {
        var raw = cfg.removePrefix("vmess://").substringBefore("#").trim()
        while (raw.length % 4 != 0) raw += "="
        for (flags in intArrayOf(android.util.Base64.URL_SAFE, android.util.Base64.DEFAULT)) {
            try {
                return JSONObject(String(android.util.Base64.decode(raw, flags), Charsets.UTF_8))
            } catch (_: Exception) {}
        }
        return null
    }

    fun parseServer(cfg: String): Server? {
        if (isGroup(cfg)) return null
        if (isCustom(cfg)) return customServer(cfg)
        if (isChain(cfg)) {
            val first = chainParts(cfg).firstOrNull() ?: return null
            return parseServer(first)
        }
        return try {
            if (cfg.startsWith("vmess://")) {
                val j = vmessJson(cfg) ?: return null
                val host = j.optString("add")
                val port = j.optString("port").toIntOrNull() ?: return null
                if (host.isBlank()) null else Server(host, port)
            } else {
                val uri = URI(cfg.substringBefore("#"))
                val host = uri.host ?: return null
                val port = if (uri.port != -1) uri.port else 443
                Server(host, port)
            }
        } catch (_: Exception) { null }
    }

    fun tcpConnectTime(host: String, port: Int, timeoutMs: Int = TCP_TIMEOUT_MS): Long {
        val start = System.currentTimeMillis()
        return try {
            Socket().apply {
                connect(InetSocketAddress(host, port), timeoutMs)
                close()
            }
            System.currentTimeMillis() - start
        } catch (_: Throwable) { -1L }
    }

    fun isSimpleType(cfg: String): Boolean {
        return cfg.startsWith("vless://") || cfg.startsWith("vmess://") ||
            cfg.startsWith("trojan://") || cfg.startsWith("ss://") ||
            cfg.startsWith("socks://") || cfg.startsWith("socks5://") || cfg.startsWith("socks4://") ||
            cfg.startsWith("http://")
    }

    /** A v2rayNG CUSTOM config: a full Xray JSON config,
     *  not a URI link. v2rayNG detects it by the
     *  presence of inbounds + outbounds + routing in
     *  one JSON object (or an array of them in a
     *  subscription). It must be labelled "Custom",
     *  never mistaken for a vmess link. */
    fun isCustom(cfg: String): Boolean {
        val s = cfg.trim()
        if (!s.startsWith("{")) return false
        return s.contains("\"outbounds\"") && s.contains("\"routing\"")
    }

    /** Split pasted/fetched text into configs. A
     *  pretty-printed custom JSON spans many lines,
     *  so it must NOT be split line by line: if the
     *  whole text is one JSON object or an array of
     *  them, each object is one config. Otherwise the
     *  old line-by-line behaviour applies. */
    fun splitConfigs(text: String): List<String> {
        val s = text.trim()
        if (s.isEmpty()) return emptyList()
        try {
            if (s.startsWith("[")) {
                val arr = org.json.JSONArray(s)
                val out = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val el = arr.get(i)
                    if (el is org.json.JSONObject) out.add(el.toString())
                    else if (el is String && el.isNotBlank()) out.add(el.trim())
                }
                if (out.isNotEmpty()) return out
            } else if (s.startsWith("{") && isCustom(s)) {
                return listOf(s)
            }
        } catch (_: Exception) {}
        return s.lines().map { it.trim() }.filter { it.isNotBlank() }
    }

    /** Remarks of a custom config (its JSON
     *  "remarks" field), or "" when absent. */
    fun customRemarks(cfg: String): String {
        return try { org.json.JSONObject(cfg).optString("remarks", "") } catch (_: Exception) { "" }
    }

    /** The proxy outbound of a custom config: the
     *  first outbound whose protocol is a real proxy
     *  protocol (v2rayNG's getProxyOutbound), skipping
     *  freedom / blackhole / dns. Used for ping and
     *  for the speedtest config. */
    fun customProxyOutbound(cfg: String): org.json.JSONObject? {
        return try {
            val arr = org.json.JSONObject(cfg).optJSONArray("outbounds") ?: return null
            val proxyProtos = setOf("vmess", "vless", "trojan", "shadowsocks",
                "socks", "http", "wireguard", "hysteria2", "hysteria", "tuic")
            for (i in 0 until arr.length()) {
                val ob = arr.getJSONObject(i)
                if (proxyProtos.contains(ob.optString("protocol", "").lowercase())) {
                    return org.json.JSONObject(ob.toString()).apply { put("tag", "proxy") }
                }
            }
            null
        } catch (_: Exception) { null }
    }

    /** Server address/port of a custom config, from
     *  its proxy outbound (vnext or servers form). */
    fun customServer(cfg: String): Server? {
        return try {
            val ob = customProxyOutbound(cfg) ?: return null
            val st = ob.optJSONObject("settings") ?: return null
            val vnext = st.optJSONArray("vnext")
            if (vnext != null && vnext.length() > 0) {
                val v = vnext.getJSONObject(0)
                val host = v.optString("address", "")
                val port = v.optInt("port", -1)
                if (host.isNotBlank() && port > 0) return Server(host, port)
            }
            val servers = st.optJSONArray("servers")
            if (servers != null && servers.length() > 0) {
                val v = servers.getJSONObject(0)
                val host = v.optString("address", "")
                val port = v.optInt("port", -1)
                if (host.isNotBlank() && port > 0) return Server(host, port)
            }
            null
        } catch (_: Exception) { null }
    }

    fun isGroup(cfg: String) = cfg.startsWith("GROUP::")
    fun isChain(cfg: String) = cfg.startsWith("CHAIN::")
    // Group headers may carry a strategy suffix
    // ("GROUP::name|||leastLoad"); the name is the
    // part before it, the strategy after it.
    fun groupName(cfg: String) = cfg.removePrefix("GROUP::").substringBefore("|||")
    fun groupStrategy(cfg: String): String {
        val s = cfg.removePrefix("GROUP::").substringAfter("|||", "")
        return if (s == "leastLoad") "leastLoad" else "leastPing"
    }
    fun chainName(cfg: String): String {
        return cfg.removePrefix("CHAIN::").substringBefore("|||")
    }
    fun chainParts(cfg: String): List<String> {
        val rest = cfg.removePrefix("CHAIN::").substringAfter("|||", "")
        if (rest.isBlank()) return emptyList()
        return rest.split("|||").filter { it.isNotBlank() }
    }

    /** Outbounds for a config: a single proxy outbound, or
     *  a chain (first outbound dials through the next via
     *  streamSettings.sockopt.dialerProxy, Xray's chaining
     *  mechanism). Tags: proxy, chain1, chain2, ... */
    fun outboundsFor(cfg: String): JSONArray? {
        if (isChain(cfg)) {
            val parts = chainParts(cfg)
            if (parts.isEmpty()) return null
            val arr = JSONArray()
            for ((i, part) in parts.withIndex()) {
                val ob = outboundFor(part) ?: return null
                val tag = if (i == 0) "proxy" else "chain$i"
                ob.put("tag", tag)
                if (i < parts.size - 1) {
                    val nextTag = "chain${i + 1}"
                    var ss = ob.optJSONObject("streamSettings")
                    if (ss == null) {
                        ss = JSONObject()
                        ob.put("streamSettings", ss)
                    }
                    var sockopt = ss.optJSONObject("sockopt")
                    if (sockopt == null) {
                        sockopt = JSONObject()
                        ss.put("sockopt", sockopt)
                    }
                    sockopt.put("dialerProxy", nextTag)
                }
                arr.put(ob)
            }
            return arr
        }
        val ob = outboundFor(cfg) ?: return null
        return JSONArray().put(ob)
    }

    /** v2rayNG real ping for one config: TCP pre-check, then
     *  Xray measureOutboundDelay with the primary test URL only
     *  (batch mode in v2rayNG uses only DELAY_TEST_URL). */
    fun measureOne(cfg: String): Long = measureOneWithError(cfg).first

    /** Same as measureOne, but keeps the real error message
     *  instead of swallowing it into -1, so a failing test
     *  can actually be diagnosed on the device. */
    fun measureOneWithError(cfg: String): Pair<Long, String?> {
        if (isSimpleType(cfg)) {
            val srv = parseServer(cfg) ?: return -1L to "parseServer failed"
            if (tcpConnectTime(srv.host, srv.port) < 0) return -1L to "TCP connect to ${srv.host}:${srv.port} failed (1000 ms)"
        }
        val config = buildSpeedtestConfig(cfg) ?: return -1L to "buildSpeedtestConfig failed (unsupported protocol?)"
        return try {
            libv2ray.Libv2ray.measureOutboundDelay(config, testUrlOverride ?: TEST_URL) to null
        } catch (t: Throwable) {
            -1L to (t.message ?: t.javaClass.simpleName)
        }
    }

    /** 1.29: moved here from the removed engine, unchanged.
     *  Download-speed probe for one config (2.77): builds an
     *  Xray speedtest config for the link, starts a local
     *  SOCKS core, downloads a fixed 2 MB from Cloudflare's
     *  speed test and returns MB/s, or -1 on any failure.
     *  Runs on a pool worker; bounded by timeoutMs. */
    fun measureDownloadMbps(ctx: android.content.Context, link: String, timeoutMs: Long = 15000): Double {
        var controller: libv2ray.CoreController? = null
        return try {
            if (!CoreInit.init(ctx)) return -1.0
            val base = buildSpeedtestConfig(link) ?: return -1.0
            val port = try {
                java.net.ServerSocket(0).use { it.localPort }
            } catch (_: Exception) { return -1.0 }
            val root = org.json.JSONObject(base)
            val inbounds = org.json.JSONArray()
            inbounds.put(org.json.JSONObject().apply {
                put("protocol", "socks")
                put("tag", "test-speed-in")
                put("port", port)
                put("listen", "127.0.0.1")
                put("settings", org.json.JSONObject().apply {
                    put("auth", "noauth")
                    put("udp", false)
                })
            })
            root.put("inbounds", inbounds)
            val c = libv2ray.Libv2ray.newCoreController(object : libv2ray.CoreCallbackHandler {
                override fun onEmitStatus(code: Long, message: String?): Long = 0
                override fun shutdown(): Long = 0
                override fun startup(): Long = 0
            })
            controller = c
            c.startLoop(root.toString(), -1)
            Thread.sleep(1200)
            val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS,
                java.net.InetSocketAddress("127.0.0.1", port))
            val conn = java.net.URL("https://speed.cloudflare.com/__down?bytes=2000000")
                .openConnection(proxy) as java.net.HttpURLConnection
            conn.connectTimeout = timeoutMs.toInt()
            conn.readTimeout = timeoutMs.toInt()
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            val start = System.nanoTime()
            var bytes = 0L
            conn.inputStream.use { inp ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    bytes += n
                    if (bytes >= 2_000_000L) break
                    if (System.nanoTime() - start > timeoutMs * 1_000_000L) return -1.0
                }
            }
            val secs = (System.nanoTime() - start) / 1e9
            if (bytes <= 0 || secs <= 0) -1.0 else (bytes / 1024.0 / 1024.0) / secs
        } catch (_: Throwable) { -1.0 } finally {
            try { controller?.stopLoop() } catch (_: Exception) {}
        }
    }

    /** Same v2rayNG real-ping measurement, but against the
     *  caller's test URL. Used by the multi-route ping test
     *  (2.74): every route measures exactly like the primary
     *  one — same speedtest config, same TCP pre-check, same
     *  200/204 rule — only the delay URL differs. */    fun measureOneWithUrl(cfg: String, testUrl: String): Long {
        if (isSimpleType(cfg)) {
            val srv = parseServer(cfg) ?: return -1L
            if (tcpConnectTime(srv.host, srv.port) < 0) return -1L
        }
        val config = buildSpeedtestConfig(cfg) ?: return -1L
        return try {
            libv2ray.Libv2ray.measureOutboundDelay(config, testUrl)
        } catch (_: Throwable) { -1L }
    }

    /** Dedup key: a config ignoring only its name.
     *  Why the old key missed duplicates: for vmess,
     *  JSONObject.toString() order is hash order, and for
     *  URI configs the query-parameter order matters, so
     *  the same server delivered twice (different param
     *  order, or a different JSON key order) got two
     *  different keys and delete-duplicate did nothing.
     *  Now both are normalized: vmess keys are sorted,
     *  URI query params are sorted, host is lowercased,
     *  and a trailing "/" path is dropped. */
    fun dedupKey(cfg: String): String {
        return try {
            if (cfg.startsWith("vmess://")) {
                // 1.33: parse through vmessJson (both base64
                // alphabets) instead of a URL_SAFE-only decode,
                // so standard-base64 vmess links dedup by
                // their real fields instead of the raw string.
                val j = vmessJson(cfg) ?: return cfg.substringBefore("#")
                j.remove("ps")
                val keys = j.keys().asSequence().sorted().toList()
                keys.joinToString("|") { k -> "$k=${j.optString(k)}" }
            } else {
                val base = cfg.substringBefore("#")
                val scheme = base.substringBefore("://")
                val rest = base.substringAfter("://")
                val query = rest.substringAfter("?", "")
                val noQuery = rest.substringBefore("?")
                // Split userinfo@host:port from the path.
                val atIdx = noQuery.lastIndexOf("@")
                val hostPort = if (atIdx >= 0) noQuery.substring(atIdx + 1) else noQuery
                val userinfo = if (atIdx >= 0) noQuery.substring(0, atIdx + 1) else ""
                val path = hostPort.substringAfter("/", "")
                val hostPortOnly = hostPort.substringBefore("/")
                val sortedQuery = if (query.isBlank()) "" else "?" +
                    query.split("&").filter { it.isNotBlank() }.sorted().joinToString("&")
                val normPath = if (path.isBlank() || path == "/") "" else "/$path"
                "$scheme://$userinfo${hostPortOnly.lowercase()}$normPath$sortedQuery"
            }
        } catch (_: Exception) { cfg.substringBefore("#") }
    }

    /** Human name of a config: vmess ps, or the #fragment.
     *  1.33 (item 4): vmess is read through vmessJson,
     *  which tries BOTH base64 alphabets. The old code
     *  decoded with URL_SAFE only, so a fetched vmess
     *  link in standard base64 ('+'/'/') threw here and
     *  the row fell back to the scheme name "vmess" —
     *  even after renameFree had (also) failed to
     *  rewrite its "ps" for the same reason. */
    fun displayName(cfg: String): String {
        if (isCustom(cfg)) {
            val r = customRemarks(cfg)
            return if (r.isNotBlank()) r else "Custom"
        }
        if (isGroup(cfg)) return groupName(cfg)
        if (isChain(cfg)) return chainName(cfg)
        if (cfg.startsWith("WARP::")) return "WARP"
        if (cfg.startsWith("WGXRAY::")) return "WireGuard"
        return try {
            if (cfg.startsWith("vmess://")) {
                val ps = vmessJson(cfg)?.optString("ps", "") ?: ""
                if (ps.isNotBlank()) ps else cfg.substringBefore("://")
            } else {
                val frag = cfg.substringAfter("#", "")
                if (frag.isNotBlank()) java.net.URLDecoder.decode(frag, "UTF-8") else cfg.substringBefore("://")
            }
        } catch (_: Exception) { cfg.substringBefore("://") }
    }

    fun protocolLabel(cfg: String): String {
        return when {
            isCustom(cfg) -> "Custom"
            isGroup(cfg) -> "GROUP"
            isChain(cfg) -> "CHAIN"
            cfg.startsWith("vless://") -> "VLESS"
            cfg.startsWith("vmess://") -> "VMess"
            cfg.startsWith("trojan://") -> "Trojan"
            cfg.startsWith("ss://") -> "Shadowsocks"
            cfg.startsWith("wireguard://") -> "WireGuard"
            cfg.startsWith("WARP::") -> "WARP"
            cfg.startsWith("masque://") -> "MASQUE"
            else -> cfg.substringBefore("://").uppercase()
        }
    }

    /** Full type exactly like v2rayNG's server list
     *  (MainServerRowModels.serverProtocolDescription):
     *  PROTOCOL / network / security, joined with " / ",
     *  protocol ALL CAPS, network/security lowercase, tcp
     *  omitted, security kept only when tls or reality,
     *  insecure TLS shown as "tls insecure". Examples:
     *  VLESS / ws / tls, VMESS / grpc / tls, TROJAN / tls,
     *  SHADOWSOCKS, VLESS / ws, VLESS / ws / reality,
     *  HYSTERIA2 / hysteria / tls, WIREGUARD, SOCKS, HTTP,
     *  CUSTOM. (The user's quoted "Vless/tls/ws" does not
     *  match this checkout's format — order, casing and
     *  separator all differ — so this follows the source.) */
    fun fullType(cfg: String): String {
        if (isCustom(cfg)) return "CUSTOM"
        if (isGroup(cfg)) return "GROUP"
        if (isChain(cfg)) return "CHAIN"
        if (cfg.startsWith("WARP::")) return "WARP"
        if (cfg.startsWith("WGXRAY::")) return "WIREGUARD"
        val scheme = cfg.substringBefore("://").lowercase()
        val proto = when (scheme) {
            "vless" -> "VLESS"
            "vmess" -> "VMESS"
            "trojan" -> "TROJAN"
            "ss" -> "SHADOWSOCKS"
            "socks", "socks5", "socks4" -> "SOCKS"
            "http" -> "HTTP"
            "wireguard" -> "WIREGUARD"
            "hysteria2", "hy2" -> "HYSTERIA2"
            "hysteria" -> "HYSTERIA"
            "masque" -> "MASQUE"
            "tuic" -> "TUIC"
            else -> scheme.uppercase()
        }
        var net = ""
        var sec = ""
        var insecure = false
        try {
            when {
                cfg.startsWith("vmess://") -> {
                    // 1.33: vmessJson (both base64 alphabets),
                    // so standard-base64 links keep net/tls.
                    val j = vmessJson(cfg) ?: JSONObject()
                    net = j.optString("net", "tcp")
                    sec = j.optString("tls", "")
                }
                cfg.startsWith("vless://") || cfg.startsWith("trojan://") -> {
                    val p = parseProxy(cfg)
                    net = p.params["type"] ?: "tcp"
                    sec = if (scheme == "trojan") (p.params["security"] ?: "tls") else (p.params["security"] ?: "")
                    insecure = p.params["insecure"] == "1" || p.params["insecure"].equals("true", ignoreCase = true) ||
                        p.params["allowInsecure"] == "1" || p.params["allow_insecure"] == "1"
                }
                cfg.startsWith("hysteria2://") || cfg.startsWith("hy2://") -> {
                    val p = parseProxy(cfg)
                    net = "hysteria"
                    sec = p.params["security"] ?: "tls"
                }
            }
        } catch (_: Exception) {}
        if (sec != "tls" && sec != "reality") sec = ""
        val parts = mutableListOf(proto)
        if (net.isNotBlank() && !net.equals("tcp", ignoreCase = true)) parts.add(net.lowercase())
        if (sec.isNotBlank()) parts.add(if (insecure && sec == "tls") "tls insecure" else sec.lowercase())
        return parts.joinToString(" / ")
    }

    /** Speedtest Xray config for one profile, as v2rayNG builds it. */
    fun buildSpeedtestConfig(cfg: String): String? =
        buildSpeedtestConfigWithLog(cfg, null)

    /** Same speedtest config, but with the caller's
     *  log section (1.48: the warp probe passes
     *  {loglevel: "debug", error: <file>} so the
     *  core's MASQUE establish error lands in a
     *  file the app can read back — the error
     *  measureOutboundDelay returns is only -1). */
    private fun buildSpeedtestConfigWithLog(cfg: String, logObj: JSONObject?): String? {
        val outbounds = try { outboundsFor(cfg) } catch (_: Exception) { return null } ?: return null

        outbounds.put(JSONObject().apply { put("protocol", "freedom"); put("tag", "direct") })
        outbounds.put(JSONObject().apply { put("protocol", "blackhole"); put("tag", "block") })
        return JSONObject().apply {
            put("log", logObj ?: JSONObject().apply { put("loglevel", "warning") })
            put("outbounds", outbounds)
            put("routing", JSONObject().apply {
                put("domainStrategy", "IPIfNonMatch")
                put("rules", JSONArray())
                put("final", "proxy")
            })
        }.toString()
    }

    /** 1.48: result of one WARP probe WITH the core
     *  error log captured. coreLines are the
     *  MASQUE/CONNECT-IP lines the core wrote (the
     *  establish error, or "tunnel established");
     *  logCaptured is false when the core never
     *  wrote the file at all (libv2ray ignoring
     *  the log section, or an unwritable path) —
     *  the caller must SAY that, never fake lines.
     *  1.49: logLines is the TOTAL non-empty line
     *  count of the file, so "no keyword line"
     *  can be told apart from "the file was
     *  really empty" (see measureWarpProbe). */
    data class WarpProbeResult(
        val ms: Long,
        val err: String?,
        val coreLines: List<String>,
        val logCaptured: Boolean,
        val logLines: Int = 0
    )

    /** Substrings the shipped core (26.10.21)
     *  writes for a MASQUE dial — every one
     *  verified present in the xray binary with
     *  `strings` (1.48 VM check). The probe log
     *  keeps only lines containing one of these,
     *  so the [warp] log gets the verdict, not a
     *  dump of the whole debug log. No private
     *  keys or tokens can appear: these lines
     *  carry only the dial outcome. */
    private val WARP_CORE_KEYWORDS = listOf(
        "MASQUE:", "connect-ip:", "failed to establish",
        "tunnel closed", "tunnel established",
        "server responded with", "didn't enable datagrams",
        "tunnel can only carry", "WARP needs an address",
        "no address assigned"
    )

    /** 1.49: the generic dial-failure substrings.
     *  WHY: the 1.48 device test showed probes
     *  whose core log said «لاگ هسته خالی بود»
     *  even though the dial HAD failed with a
     *  concrete error — the file was per-probe
     *  and unique (not shared/overwritten), but
     *  the filter above kept only MASQUE lines,
     *  so a dial that died BEFORE the MASQUE
     *  layer (TLS, deadline, reset) left zero
     *  kept lines: H2-frag "context deadline
     *  exceeded", H3 "timeout: no recent network
     *  activity", GOOL "context deadline
     *  exceeded" all matched NOTHING. These
     *  second-priority lines are kept only when
     *  no MASQUE line exists for the probe (max
     *  2), so the verdict line still comes
     *  first and the log never floods. */
    private val WARP_CORE_GENERIC_KEYWORDS = listOf(
        "context deadline exceeded",
        "no recent network activity",
        "connection reset", "connection refused",
        "i/o timeout", "no such host",
        "network is unreachable",
        "TLS handshake", "certificate",
        "unexpected EOF"
    )

    /** 1.48: the WARP probe — the same real-traffic
     *  measurement as measureOneWithError, but the
     *  probe core is started with a per-probe
     *  error-log file (loglevel debug), and the
     *  file is read back afterwards. VM-PROVEN
     *  against the shipped core binary (1.48):
     *  a 503 mock writes "failed to establish
     *  CONNECT-IP tunnel > ... > connect-ip:
     *  server responded with 503" into the file;
     *  an accepting mock writes "MASQUE: tunnel
     *  established from [...]"; a closing mock
     *  writes the reset reason. What the VM could
     *  NOT prove is the libv2ray wrapper honouring
     *  the log section on Android — if the file
     *  is never written on the phone, logCaptured
     *  is false and the [warp] log says so
     *  plainly. One file per probe (unique tag):
     *  probes run 8 at a time and must never
     *  share a file. The file is deleted after
     *  reading; a crash leaves at most a few KB.
     *  1.49: generic dial-failure keywords are
     *  kept as a fallback (see WARP_CORE_GENERIC_
     *  KEYWORDS) and the file's total line count
     *  is returned, so "empty" vs "filtered out"
     *  can be told apart on the phone. */
    fun measureWarpProbe(
        ctx: android.content.Context,
        cfg: String,
        tag: String
    ): WarpProbeResult {
        val dir = java.io.File(ctx.filesDir, "warp_probe_logs").apply { mkdirs() }
        val safeTag = tag.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        val logFile = java.io.File(dir, "warp_probe_$safeTag.log")
        try { if (logFile.exists()) logFile.delete() } catch (_: Exception) {}
        val config = try {
            buildSpeedtestConfigWithLog(cfg, JSONObject().apply {
                put("loglevel", "debug")
                put("error", logFile.absolutePath)
            })
        } catch (_: Exception) { null }
            ?: return WarpProbeResult(-1L,
                "buildSpeedtestConfig failed (unsupported protocol?)",
                emptyList(), false)
        val (ms, err) = try {
            libv2ray.Libv2ray.measureOutboundDelay(config, testUrlOverride ?: TEST_URL) to null
        } catch (t: Throwable) {
            -1L to (t.message ?: t.javaClass.simpleName)
        }
        val lines = mutableListOf<String>()
        var captured = false
        var totalLines = 0
        try {
            if (logFile.exists()) {
                captured = true
                // 1.49: two priorities — MASQUE lines
                // first (max 4), generic dial-failure
                // lines only as a fallback (max 2), so
                // a probe that died before the MASQUE
                // layer still reports its real error
                // instead of «لاگ هسته خالی بود».
                // totalLines tells the caller whether
                // the file was really empty or only
                // filtered out.
                val masque = mutableListOf<String>()
                val generic = mutableListOf<String>()
                for (raw in logFile.readLines()) {
                    val line = raw.trim()
                    if (line.isEmpty()) continue
                    totalLines++
                    // The establish error is a wrapped
                    // chain whose cause sits at the END —
                    // keep the tail, not the head, when
                    // a line is overlong.
                    val kept = if (line.length > 400) "…" + line.takeLast(400) else line
                    when {
                        WARP_CORE_KEYWORDS.any { line.contains(it) } -> {
                            if (kept !in masque && masque.size < 4) masque.add(kept)
                        }
                        WARP_CORE_GENERIC_KEYWORDS.any { line.contains(it) } -> {
                            if (kept !in generic && generic.size < 2) generic.add(kept)
                        }
                    }
                }
                lines.addAll(masque)
                if (lines.isEmpty()) lines.addAll(generic)
            }
        } catch (_: Exception) {}
        try { logFile.delete() } catch (_: Exception) {}
        return WarpProbeResult(ms, err, lines, captured, totalLines)
    }

    private fun parseProxy(cfg: String): ProxyParts {
        val uri = URI(cfg.substringBefore("#"))
        val q = uri.rawQuery ?: ""
        val params = q.split("&").mapNotNull {
            val kv = it.split("=", limit = 2)
            if (kv.size == 2) kv[0] to java.net.URLDecoder.decode(kv[1], "UTF-8") else null
        }.toMap()
        val userInfo = try { java.net.URLDecoder.decode(uri.rawUserInfo ?: "", "UTF-8") } catch (_: Exception) { uri.rawUserInfo ?: "" }
        return ProxyParts(
            scheme = uri.scheme ?: "",
            // 1.54: URI.getHost() returns an IPv6 literal WITH
            // brackets ("[2606:...]") on this JDK — the core
            // wants the bare address (it adds its own brackets
            // when joining host+port). Strip them here, once,
            // for every link type.
            server = (uri.host ?: "").removeSurrounding("[", "]"),
            port = if (uri.port != -1) uri.port else 443,
            id = userInfo.substringBefore(":").ifBlank { userInfo },
            // a userinfo with no ":" carries the password
            // itself (standard Trojan links) — it must not become empty.
            password = if (userInfo.contains(":")) userInfo.substringAfter(":") else userInfo,
            params = params,
            path = uri.path ?: ""
        )
    }

    private data class ProxyParts(
        val scheme: String, val server: String, val port: Int,
        val id: String, val password: String,
        val params: Map<String, String>, val path: String
    )

    private fun streamSettings(p: ProxyParts): JSONObject {
        val s = JSONObject()
        val net = p.params["type"] ?: "tcp"
        s.put("network", net)
        val sec = p.params["security"] ?: ""
        if (sec == "reality" || sec == "tls") {
            s.put("security", sec)
            if (sec == "reality") {
                s.put("realitySettings", JSONObject().apply {
                    put("show", false)
                    put("fingerprint", p.params["fp"] ?: "chrome")
                    if (p.params["sni"] != null) put("serverName", p.params["sni"])
                    if (p.params["pbk"] != null) put("publicKey", p.params["pbk"])
                    put("shortId", p.params["sid"] ?: "")
                    put("spiderX", p.params["spx"] ?: "")
                })
            } else {
                s.put("tlsSettings", JSONObject().apply {
                    if (p.params["sni"] != null) put("serverName", p.params["sni"])
                    if (p.params["fp"] != null) put("fingerprint", p.params["fp"])
                    // 1.45: the winning MASQUE variant reaches
                    // streamSettings as alpn=h2|h3 on the link.
                    if (p.params["alpn"] != null) put("alpn", JSONArray().put(p.params["alpn"]))
                    // 1.54: a masque:// link carrying warp_priv
                    // holds the ENROLLED CLIENT keys (Asha
                    // semantics — the same keys
                    // WarpFree.masqueOutbound uses). Without
                    // this, the core compares warp.publicKey
                    // against the server leaf and the dial
                    // fails; with it, warp.go returns early
                    // without the SPKI check (the client cert
                    // is still sent), exactly like the app's
                    // own outbound. Only for warp_priv links —
                    // a generic server-key link is untouched.
                    if (net == "masque" && p.params["warp_priv"] != null) {
                        put("verifyPeerCertByName", p.params["sni"] ?: "www.cloudflare.com")
                    }
                })
            }
        }
        if (net == "masque") {
            // 1.45: host/path default to the WARP values
            // (cloudflareaccess.com, "/") when the link
            // does not carry them; warp keys come from
            // the link params — the app's own path uses
            // WARP:: registry lookups instead, so no
            // secret ever travels in a link string.
            s.put("masqueSettings", JSONObject().apply {
                put("host", p.params["masque_host"] ?: "cloudflareaccess.com")
                put("path", p.params["masque_path"] ?: "/")
                // 1.49: same as WarpFree.masqueOutbound —
                // NO User-Agent (aether sends it empty;
                // "" deletes the header in the shipped
                // core, VM-proven on mock149). No
                // fragment here either (never was).
                put("headers", JSONObject().apply {
                    put("User-Agent", "")
                })
                val warp = JSONObject()
                if (p.params["warp_priv"] != null) warp.put("privateKey", p.params["warp_priv"])
                if (p.params["warp_pub"] != null) warp.put("publicKey", p.params["warp_pub"])
                val addr = p.params["warp_addr"]
                if (addr != null) {
                    val arr = JSONArray()
                    for (a in addr.split(",")) if (a.isNotBlank()) arr.put(a.trim())
                    if (arr.length() > 0) warp.put("address", arr)
                }
                if (warp.length() > 0) put("warp", warp)
            })
        }
        if (net == "ws") {
            s.put("wsSettings", JSONObject().apply {
                // prefer the query path, else the link's own URI path, else "/".
                put("path", p.params["path"]?.takeIf { it.isNotBlank() } ?: p.path.ifBlank { "/" })
                if (p.params["host"] != null) put("headers", JSONObject().apply {
                    put("Host", p.params["host"])
                })
            })
        }
        if (net == "grpc") {
            s.put("grpcSettings", JSONObject().apply {
                put("serviceName", p.params["serviceName"] ?: "")
            })
        }
        return s
    }

    /** 1.31 (free-button crash fix): never throws. A malformed
     *  fetched/pasted line returns null ("unsupported") instead
     *  of killing the caller — every caller already treats
     *  null as unsupported. The vmess branch also accepts
     *  standard-base64 payloads now (see vmessJson). */
    fun outboundFor(cfg: String): JSONObject? {
        return try { outboundForUnsafe(cfg) } catch (_: Exception) { null }
    }

    /** 1.41 (F): decode an ss userinfo blob, trying
     *  both base64 alphabets (standard first when the
     *  blob carries '+' or '/', URL-safe first
     *  otherwise), with missing padding restored.
     *  Returns null when neither decodes to text. */
    private fun decodeSsUserInfo(blob: String): String? {
        val trimmed = blob.trim()
        if (trimmed.isEmpty()) return null
        val padded = trimmed + "=".repeat((4 - trimmed.length % 4) % 4)
        val order = if (trimmed.contains('+') || trimmed.contains('/'))
            intArrayOf(Base64.DEFAULT, Base64.URL_SAFE)
        else
            intArrayOf(Base64.URL_SAFE, Base64.DEFAULT)
        for (flags in order) {
            try {
                val s = String(Base64.decode(padded, flags), Charsets.UTF_8)
                if (s.isNotBlank()) return s
            } catch (_: Exception) {}
        }
        return null
    }

    private fun outboundForUnsafe(cfg: String): JSONObject? {
        if (isCustom(cfg)) return customProxyOutbound(cfg)
        return when {
            // 1.45: free WARP. WARP::id resolves the
            // delivered record from the in-memory
            // registry (WarpFree); WGXRAY::gool is the
            // GOOL inner WireGuard identity. The
            // outbound is protocol "masque" (settings
            // address/port) — NOT a vless outbound
            // with masque transport (the core rejects
            // that: "the masque transport can only be
            // used by the masque outbound").
            cfg.startsWith("WARP::") -> WarpFree.masqueOutbound(cfg.removePrefix("WARP::"))
            cfg.startsWith("WGXRAY::") -> WarpFree.wireguardOutbound(cfg.removePrefix("WGXRAY::"))
            cfg.startsWith("masque://") -> {
                val p = parseProxy(cfg)
                JSONObject().apply {
                    put("protocol", "masque"); put("tag", "proxy")
                    put("settings", JSONObject().apply {
                        put("address", p.server); put("port", p.port)
                    })
                    put("streamSettings", streamSettings(p))
                }
            }
            cfg.startsWith("http://") -> {
                val p = parseProxy(cfg)
                JSONObject().apply {
                    put("protocol", "http"); put("tag", "proxy")
                    put("settings", JSONObject().apply {
                        put("servers", JSONArray().put(JSONObject().apply {
                            put("address", p.server); put("port", p.port)
                            if (p.id.isNotBlank()) {
                                put("users", JSONArray().put(JSONObject().apply {
                                    put("user", p.id)
                                    put("pass", p.password)
                                }))
                            }
                        }))
                    })
                }
            }
            cfg.startsWith("socks://") || cfg.startsWith("socks5://") || cfg.startsWith("socks4://") -> {
                val p = parseProxy(cfg)
                JSONObject().apply {
                    put("protocol", "socks"); put("tag", "proxy")
                    put("settings", JSONObject().apply {
                        put("servers", JSONArray().put(JSONObject().apply {
                            put("address", p.server); put("port", p.port)
                            if (p.id.isNotBlank()) {
                                put("users", JSONArray().put(JSONObject().apply {
                                    put("user", p.id)
                                    put("pass", p.password)
                                }))
                            }
                        }))
                    })
                }
            }
            cfg.startsWith("vless://") -> {
                val p = parseProxy(cfg)
                JSONObject().apply {
                    put("protocol", "vless"); put("tag", "proxy")
                    put("settings", JSONObject().apply {
                        put("vnext", JSONArray().put(JSONObject().apply {
                            put("address", p.server); put("port", p.port)
                            put("users", JSONArray().put(JSONObject().apply {
                                put("id", p.id)
                                put("encryption", p.params["encryption"] ?: "none")
                                if (!p.params["flow"].isNullOrBlank()) put("flow", p.params["flow"])
                            }))
                        }))
                    })
                    put("streamSettings", streamSettings(p))
                }
            }
            cfg.startsWith("vmess://") -> {
                val j = vmessJson(cfg) ?: return null
                val net = j.optString("net", "tcp")
                val tls = j.optString("tls", "")
                val ss = JSONObject().apply {
                    put("network", net)
                    if (tls == "tls" || tls == "reality") {
                        put("security", tls)
                        if (tls == "tls") {
                            put("tlsSettings", JSONObject().apply {
                                if (j.optString("sni").isNotBlank()) put("serverName", j.optString("sni"))
                            })
                        }
                        if (tls == "reality") {
                            put("realitySettings", JSONObject().apply {
                                put("show", false)
                                put("fingerprint", j.optString("fp").ifBlank { "chrome" })
                                if (j.optString("sni").isNotBlank()) put("serverName", j.optString("sni"))
                                if (j.optString("pbk").isNotBlank()) put("publicKey", j.optString("pbk"))
                                put("shortId", j.optString("sid", ""))
                                put("spiderX", j.optString("spx", ""))
                            })
                        }
                    }
                    if (net == "ws") {
                        put("wsSettings", JSONObject().apply {
                            put("path", j.optString("path", "/"))
                            if (j.optString("host").isNotBlank()) put("headers", JSONObject().apply {
                                put("Host", j.optString("host"))
                            })
                        })
                    }
                }
                JSONObject().apply {
                    put("protocol", "vmess"); put("tag", "proxy")
                    put("settings", JSONObject().apply {
                        put("vnext", JSONArray().put(JSONObject().apply {
                            put("address", j.optString("add"))
                            put("port", j.optString("port").toIntOrNull() ?: 443)
                            put("users", JSONArray().put(JSONObject().apply {
                                put("id", j.optString("id"))
                                put("alterId", j.optString("aid").toIntOrNull() ?: 0)
                                put("security", "auto")
                            }))
                        }))
                    })
                    put("streamSettings", ss)
                }
            }
            cfg.startsWith("trojan://") -> {
                val p = parseProxy(cfg)
                JSONObject().apply {
                    put("protocol", "trojan"); put("tag", "proxy")
                    put("settings", JSONObject().apply {
                        put("servers", JSONArray().put(JSONObject().apply {
                            put("address", p.server); put("port", p.port)
                            put("password", p.password)
                        }))
                    })
                    put("streamSettings", streamSettings(p))
                }
            }
            cfg.startsWith("ss://") -> {
                val p = parseProxy(cfg)
                var method = ""
                var pass = p.password
                var host = p.server
                var port = p.port
                // ss://base64(method:password@host:port) form
                if (p.id.isBlank() || !p.id.contains(":")) {
                    try {
                        // 1.41 (F): decode ONLY the userinfo
                        // (before @) for the
                        // ss://BASE64(method:password)@host:port
                        // form — the old code decoded the
                        // WHOLE "BASE64@host:port" string, so
                        // the decoded text never contained
                        // "@", the branch was skipped, and
                        // the method fell back to aes-128-gcm
                        // with the raw base64 blob as the
                        // password (real method aes-256-gcm).
                        // No literal @ means the whole
                        // string is the base64
                        // (method:password@host:port) form —
                        // decode that instead.
                        val raw = cfg.removePrefix("ss://").substringBefore("#").substringBefore("/")
                        val toDecode = if (raw.contains("@")) raw.substringBefore("@") else raw
                        val decoded = decodeSsUserInfo(toDecode) ?: ""
                        val at = decoded.lastIndexOf("@")
                        if (at > 0) {
                            method = decoded.substring(0, at).substringBefore(":")
                            pass = decoded.substring(0, at).substringAfter(":")
                            val hp = decoded.substring(at + 1)
                            host = hp.substringBeforeLast(":")
                            port = hp.substringAfterLast(":").toIntOrNull() ?: port
                        } else if (decoded.contains(":")) {
                            // Userinfo-only form: the decoded
                            // text is "method:password"; the
                            // host:port come from the URI
                            // (parseProxy already filled them).
                            method = decoded.substringBefore(":")
                            pass = decoded.substringAfter(":")
                        }
                    } catch (_: Exception) {}
                } else {
                    method = p.id.substringBefore(":")
                    pass = p.password
                }
                JSONObject().apply {
                    put("protocol", "shadowsocks"); put("tag", "proxy")
                    put("settings", JSONObject().apply {
                        put("servers", JSONArray().put(JSONObject().apply {
                            put("address", host); put("port", port)
                            put("method", method.ifBlank { "aes-128-gcm" })
                            put("password", pass)
                            put("uot", true)
                        }))
                    })
                }
            }
            cfg.startsWith("wireguard://") -> {
                // WireGuard: v2rayNG skips the TCP pre-check for it
                // and measures through the Xray wireguard outbound.
                val p = parseProxy(cfg)
                JSONObject().apply {
                    put("protocol", "wireguard"); put("tag", "proxy")
                    put("settings", JSONObject().apply {
                        put("secretKey", p.params["privateKey"] ?: "")
                        put("address", JSONArray().put("10.0.0.2/32"))
                        put("peers", JSONArray().put(JSONObject().apply {
                            put("publicKey", p.params["publicKey"] ?: "")
                            put("endpoint", "${p.server}:${p.port}")
                            put("allowedIPs", JSONArray().put("0.0.0.0/0").put("::/0"))
                            put("keepAlive", 25)
                        }))
                    })
                }
            }
            else -> null
        }
    }
}
