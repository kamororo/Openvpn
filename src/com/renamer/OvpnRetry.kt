package com.renamer

/**
 * v1.10: the retry ladder for IMPORTED (own) profiles.
 *
 * WHY: an imported config ("USA-Visa") connects in the main app
 * but not in the standalone. The direct connect path is
 * code-identical between the two apps (verified by diff on
 * OvpnEngine, Ovpn3VpnService, Ovpn3Client, OvpnStore,
 * OvpnSessionProbe, the import handler, connectProfile, the
 * orb tap and onActivityResult — the only differences are the
 * main app's chain plumbing and the removed engine's ladder).
 * The standalone's own probe agrees the DIRECT endpoint is
 * dead (red upper dot: TCP answered but the TLS flight was
 * cut, or no answer at all). The one mechanism in the main
 * app that can still connect such a config is the ladder:
 * alternate resolved IPs, direct-IP remotes, DoH IPs, a
 * proto swap and mssfix — tried before giving up.
 *
 * This is the small, self-contained part of that ladder, with
 * NO Smart engine, NO Xray, NO chain and NO learning: the
 * original config first (today's behaviour, unchanged), then
 * direct-IP remotes, DoH IPs, a proto swap and mssfix, each
 * with the main ladder's fast probe (dead TCP endpoints are
 * skipped at once) and its 40s/25s budget. The FIRST rung
 * that reaches CONNECTED and holds 3s wins; the saved profile
 * is NEVER rewritten (rungs run on in-memory copies).
 * The free entry never runs this ladder — it keeps its single
 * direct connect through the split relay, untouched.
 */
object OvpnRetry {

    data class Rung(val id: String, val nameFa: String, val nameEn: String, val nameRu: String, val text: String)
    data class Endpoint(val host: String, val port: Int, val proto: String)

    /** Rungs for one .ovpn text: the original first, then the
     *  fallbacks that differ from it (identical texts are
     *  dropped, so a config that is already an IP, already
     *  TCP-only or already has mssfix simply has fewer
     *  rungs). */
    fun buildRungs(ovpnText: String): List<Rung> {
        val out = ArrayList<Rung>()
        fun add(id: String, fa: String, en: String, ru: String, text: String?) {
            if (text == null) return
            if (out.any { it.text == text }) return
            out += Rung(id, fa, en, ru, text)
        }
        add("original", "اصلی", "Original", "Оригинал", ovpnText)
        val replaced = ovpnReplacedRemotes(ovpnText)
        add("replaced", "IP مستقیم", "Direct IP", "Прямой IP", replaced)
        val replacedDoh = ovpnReplacedRemotesDoh(ovpnText)
        add("replacedDoh", "IP از راه DoH", "IP via DoH", "IP через DoH", replacedDoh)
        val swapBase = replaced ?: ovpnText
        add("swapped", "پروتکل جایگزین", "Alternate protocol", "Другой протокол", ovpnProtoSwapped(swapBase))
        val mssBase = ovpnProtoSwapped(swapBase) ?: swapBase
        add("mssfix", "mssfix", "mssfix", "mssfix", ovpnWithMssfix(mssBase))
        return out
    }

    /** First `remote` line + effective proto of one .ovpn text.
     *  A `remote host port proto` token wins over the `proto`
     *  directive. Null when there is no remote line at all. */
    fun firstEndpoint(text: String): Endpoint? {
        var host: String? = null
        var port = 1194
        var remoteProto: String? = null
        var directiveProto: String? = null
        for (line in text.lines()) {
            val t = line.trim()
            if (t.startsWith("remote ") && host == null) {
                val parts = t.split(Regex("\\s+"))
                if (parts.size >= 2) {
                    host = parts[1]
                    if (parts.size >= 3) port = parts[2].toIntOrNull() ?: 1194
                    if (parts.size >= 4) {
                        val pr = parts[3].lowercase()
                        if (pr.startsWith("tcp")) remoteProto = "tcp" else if (pr == "udp") remoteProto = "udp"
                    }
                }
            }
            if (t == "proto udp" || t.startsWith("proto udp ")) directiveProto = "udp"
            else if (t.startsWith("proto tcp")) directiveProto = "tcp"
        }
        if (host == null) return null
        return Endpoint(host, port, remoteProto ?: directiveProto ?: "udp")
    }

    /** Fast liveness probe for one rung endpoint. 0 = the server
     *  answered, 1 = reliably dead (a TCP connect failed — the
     *  TLS handshake cannot succeed on a path where TCP itself
     *  fails), 2 = unknown (UDP silent — inconclusive, because
     *  tls-auth servers stay silent to unauthenticated resets
     *  by design; the rung still runs, with a shorter budget),
     *  -1 = the hostname could not be resolved by either
     *  resolver. Background threads only. */
    fun probeEndpoint(ep: Endpoint): Int {
        val isIp = ep.host.matches(Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$"))
        val hosts: List<String> = if (isIp) listOf(ep.host) else {
            val sys = try { resolveIps(ep.host) } catch (_: Exception) { emptyList() }
            if (sys.isNotEmpty()) sys.take(2)
            else try { resolveIpsDoh(ep.host) } catch (_: Exception) { emptyList() }.take(2)
        }
        if (hosts.isEmpty()) return -1
        return if (ep.proto == "tcp") {
            var ok = false
            for (h in hosts) {
                if (probeTcpAlive(h, ep.port)) { ok = true; break }
            }
            if (ok) 0 else 1
        } else {
            if (probeUdpReset(hosts[0], ep.port)) 0 else 2
        }
    }

    private fun probeTcpAlive(host: String, port: Int): Boolean {
        return try {
            val s = java.net.Socket()
            try {
                s.connect(java.net.InetSocketAddress(host, port), 2500)
                true
            } finally {
                try { s.close() } catch (_: Exception) {}
            }
        } catch (_: Exception) { false }
    }

    /** One unauthenticated client-reset packet: any reply at all
     *  proves the server is there (a tls-auth server stays
     *  silent by design, which is why silence is not death). */
    private fun probeUdpReset(host: String, port: Int): Boolean {
        return try {
            val sock = java.net.DatagramSocket()
            sock.soTimeout = 500
            val addr = java.net.InetAddress.getByName(host)
            val sess = ByteArray(8)
            java.util.Random().nextBytes(sess)
            val pkt = ByteArray(13)
            pkt[0] = 0x38.toByte()
            System.arraycopy(sess, 0, pkt, 1, 8)
            val start = System.currentTimeMillis()
            val deadline = start + 5000
            var sent = 0
            var alive = false
            while (System.currentTimeMillis() < deadline && !alive) {
                val now = System.currentTimeMillis()
                if (sent < 3 && now - start >= sent * 1500L) {
                    try { sock.send(java.net.DatagramPacket(pkt, pkt.size, addr, port)) } catch (_: Exception) {}
                    sent++
                }
                try {
                    val buf = ByteArray(512)
                    sock.receive(java.net.DatagramPacket(buf, buf.size))
                    alive = true
                } catch (_: java.net.SocketTimeoutException) {}
            }
            try { sock.close() } catch (_: Exception) {}
            alive
        } catch (_: Exception) { false }
    }

    /** Wait for one rung: 0 = connected, 1 = the core gave up,
     *  2 = budget expired with no answer, 3 = aborted (a newer
     *  connect superseded this ladder, or the user stopped it).
     *  Background threads only. */
    fun waitRung(name: String, timeoutMs: Long, abort: () -> Boolean): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (abort()) return 3
            val an = OvpnEngine.activeName
            if (an != null && an != name) return 3
            if (OvpnEngine.isConnected && an == name) return 0
            if (!OvpnEngine.isConnecting && !OvpnEngine.isConnected) return 1
            try { Thread.sleep(400) } catch (_: Exception) {}
        }
        return if (abort()) 3 else 2
    }

    // ---------- config-text transforms (ported from the
    // removed engine — pure text, no engine) ----------

    fun resolveIps(host: String, max: Int = 8): List<String> {
        return try {
            if (host.isBlank() || host.contains(":") || host.matches(Regex("^[0-9.]+$"))) return emptyList()
            val all = java.net.InetAddress.getAllByName(host)
            all.mapNotNull { it.hostAddress }.filter { it.contains(".") && !it.contains(":") }.distinct().take(max)
        } catch (_: Exception) { emptyList() }
    }

    /** A-record lookup over DNS-over-HTTPS (dns.google). IPv4
     *  only, max 8, empty for IP literals and on any failure.
     *  Background threads only. */
    fun resolveIpsDoh(host: String, max: Int = 8): List<String> {
        return try {
            if (host.isBlank() || host.contains(":") || host.matches(Regex("^[0-9.]+$"))) return emptyList()
            val enc = java.net.URLEncoder.encode(host, "UTF-8")
            val conn = java.net.URL("https://dns.google/resolve?name=$enc&type=A")
                .openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            if (conn.responseCode != 200) return emptyList()
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val ans = org.json.JSONObject(body).optJSONArray("Answer") ?: return emptyList()
            val out = ArrayList<String>()
            for (i in 0 until ans.length()) {
                val o = ans.optJSONObject(i) ?: continue
                if (o.optInt("type", -1) != 1) continue
                val ip = o.optString("data", "")
                if (ip.matches(Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$"))) out += ip
                if (out.size >= max) break
            }
            out.distinct()
        } catch (_: Exception) { emptyList() }
    }

    /** Replaced remotes: for every `remote host ...` line with
     *  a domain host, the line is REPLACED by one `remote ip
     *  ...` line per resolved IP (no hostname line left), so
     *  the core never falls back to a poisoned hostname
     *  resolution. Null when nothing changes. */
    fun ovpnReplacedRemotes(ovpnText: String): String? {
        return try {
            var changed = false
            val out = mutableListOf<String>()
            for (line in ovpnText.lines()) {
                val t = line.trim()
                if (t.startsWith("remote ") || t == "remote") {
                    val parts = t.split(Regex("\\s+"))
                    if (parts.size >= 2) {
                        val ips = resolveIps(parts[1])
                        if (ips.isNotEmpty()) {
                            changed = true
                            val rest = if (parts.size > 2) " " + parts.subList(2, parts.size).joinToString(" ") else ""
                            for (ip in ips) out += "remote $ip$rest"
                            continue
                        }
                    }
                }
                out += line
            }
            if (changed) out.joinToString("\n") else null
        } catch (_: Exception) { null }
    }

    /** Same replacement as ovpnReplacedRemotes, but the IPs
     *  come from DNS-over-HTTPS (dns.google) instead of the
     *  system resolver, so a poisoned system DNS cannot feed
     *  bad addresses. Null when DoH yields nothing or nothing
     *  changes. Background threads only. */
    fun ovpnReplacedRemotesDoh(ovpnText: String): String? {
        return try {
            var changed = false
            val out = mutableListOf<String>()
            for (line in ovpnText.lines()) {
                val t = line.trim()
                if (t.startsWith("remote ") || t == "remote") {
                    val parts = t.split(Regex("\\s+"))
                    if (parts.size >= 2) {
                        val ips = resolveIpsDoh(parts[1])
                        if (ips.isNotEmpty()) {
                            changed = true
                            val rest = if (parts.size > 2) " " + parts.subList(2, parts.size).joinToString(" ") else ""
                            for (ip in ips) out += "remote $ip$rest"
                            continue
                        }
                    }
                }
                out += line
            }
            if (changed) out.joinToString("\n") else null
        } catch (_: Exception) { null }
    }

    /** UDP <-> TCP swap of the `proto` line, or null when there
     *  is no proto line to swap. */
    fun ovpnProtoSwapped(ovpnText: String): String? {
        return try {
            var changed = false
            val out = ovpnText.lines().map { line ->
                val t = line.trim()
                when {
                    t == "proto udp" || t.startsWith("proto udp ") -> {
                        changed = true
                        line.replace("proto udp", "proto tcp-client")
                    }
                    t == "proto tcp-client" || t.startsWith("proto tcp-client ") -> {
                        changed = true
                        line.replace("proto tcp-client", "proto udp")
                    }
                    t == "proto tcp" || t.startsWith("proto tcp ") -> {
                        changed = true
                        line.replace("proto tcp", "proto udp")
                    }
                    else -> line
                }
            }
            if (changed) out.joinToString("\n") else null
        } catch (_: Exception) { null }
    }

    /** Forces TCP on an OpenVPN config (proto lines and
     *  remote proto fields rewritten to tcp-client;
     *  explicit-exit-notify dropped; a missing proto
     *  line is added). Null when nothing changed.
     *  Ported from the removed engine — pure text, no
     *  engine. Only used to decide a log line in the
     *  Chain section; the connect uses the original. */
    fun ovpnForceTcp(ovpnText: String): String? {
        return try {
            var changed = false
            val out = ArrayList<String>()
            var hasProto = false
            for (line in ovpnText.lines()) {
                val t = line.trim()
                if (t == "explicit-exit-notify" || t.startsWith("explicit-exit-notify ")) {
                    changed = true
                    continue
                }
                if (t == "proto" || t.startsWith("proto ")) {
                    hasProto = true
                    val nv = when {
                        t == "proto udp" || t.startsWith("proto udp ") -> line.replace("proto udp", "proto tcp-client")
                        t == "proto udp4" || t.startsWith("proto udp4 ") -> line.replace("proto udp4", "proto tcp4-client")
                        t == "proto udp6" || t.startsWith("proto udp6 ") -> line.replace("proto udp6", "proto tcp6-client")
                        t == "proto tcp-server" || t.startsWith("proto tcp-server ") -> line.replace("proto tcp-server", "proto tcp-client")
                        else -> line
                    }
                    if (nv != line) changed = true
                    out += nv
                    continue
                }
                if (t.startsWith("remote ") || t == "remote") {
                    val parts = t.split(Regex("\\s+"))
                    if (parts.size >= 4) {
                        val nv = when (parts[3]) {
                            "udp" -> "tcp-client"
                            "udp4" -> "tcp4-client"
                            "udp6" -> "tcp6-client"
                            "tcp-server" -> "tcp-client"
                            else -> null
                        }
                        if (nv != null) {
                            changed = true
                            out += (parts.subList(0, 3) + nv + parts.subList(4, parts.size)).joinToString(" ")
                            continue
                        }
                    }
                    out += line
                    continue
                }
                out += line
            }
            if (!hasProto) {
                changed = true
                out += "proto tcp-client"
            }
            if (changed) out.joinToString("\n") else null
        } catch (_: Exception) { null }
    }

    /** Appends `mssfix 1200` when the config has no mssfix
     *  line (helps on lines where large packets get
     *  black-holed). Null when already present. */
    fun ovpnWithMssfix(ovpnText: String): String? {
        return try {
            for (line in ovpnText.lines()) {
                if (line.trim().startsWith("mssfix")) return null
            }
            ovpnText + "\nmssfix 1200"
        } catch (_: Exception) { null }
    }
}
