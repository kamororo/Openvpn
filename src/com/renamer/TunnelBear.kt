package com.renamer

import android.content.Context
import org.json.JSONArray
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom

/**
 * TunnelBear in the OpenVPN section (3.01, redesigned 3.02).
 *
 * The 47 bundled profiles live in assets/openvpn/
 * (copied from the published MLM source: every profile
 * is identical except its `remote` line, one
 * <cc>.lazerpenguin.com:443 UDP host per country).
 * A profile is TunnelBear when its host ends with
 * `.lazerpenguin.com`.
 *
 * 3.02 — ONE entry, not 47 tunnels: the stored list
 * holds a single "TunnelBear" profile whose country
 * follows the user's pick in the box on the
 * OpenVPN page
 * (the 47 profiles stay as DATA — country list +
 * endpoints — not as 47 tunnels). Upgrading from
 * 3.01 collapses the 47 imported profiles into that
 * one entry, keeping the country the user already
 * had; deleting the single entry removes TunnelBear
 * until the auto-account flow restores it — it is never
 * silently re-added from the OpenVPN page.
 *
 * Mechanism (verified from MLM's published source,
 * reimplemented here in our own code):
 * - The stored profile is never changed. At connect
 *   time the service rewrites it fresh: `cipher
 *   AES-256-CBC` is appended (the core ignores
 *   `data-ciphers` and would otherwise fall back to
 *   BF-CBC, which the servers refuse), `proto` becomes
 *   `tcp-client`, and `remote` points at the loopback
 *   split relay instead of the server.
 * - The relay (TunnelBearRelay) dials the server
 *   itself on TCP 7011 and cuts the first 6 KB into
 *   small misaligned segments; after that it is a
 *   plain copy. The core never dials the server
 *   directly: from Iran the handshake is cut at TLS
 *   on UDP and TCP alike.
 * - One hostname is a pool of ~20 servers and whole
 *   subnets stay silent, so the connect path probes
 *   the resolved IPs first and tries the answering
 *   ones first — never just the first DNS answer.
 *   3.02: the probe is a TLS-handshake probe (reset
 *   + ClientHello, waiting for the server's
 *   certificate flight), not a bare TCP connect: a
 *   TCP connect alone showed green for servers that
 *   never connected, because the cut happens at TLS.
 *   Servers that answer TCP but not TLS still fall
 *   back into the order behind the TLS-proven ones,
 *   and the relay's own skip-on-silent-handshake
 *   rotation stays as the backstop.
 * - The CA file the profiles reference
 *   (`ca openvpn-server-ca.crt`) is inlined as a
 *   <ca> block at import time: the core only ever
 *   receives config TEXT, so a file reference would
 *   resolve to nothing.
 *
 * Credentials: the account is created automatically
 * by the app (TbAutoAccount, 3.03) — a fresh temporary
 * email + a random password, verified through the
 * confirmation mail. They live in the same
 * "renamer_ovpn" prefs the app already uses for
 * OpenVPN credentials (username + password, like
 * OvpnStore.save) — never in the profile text, never
 * hardcoded, never logged. The password is passed to
 * the core only through provideCreds, like every
 * other auth-user-pass profile.
 */
object TunnelBear {
    const val TCP_PORT = 7011
    const val SIGNUP_URL = "https://www.tunnelbear.com/account/signup"
    const val ACCOUNT_URL = "https://www.tunnelbear.com/account"

    /** The single stored TunnelBear entry's name. */
    const val SINGLE_NAME = "TunnelBear"
    /** Fallback country when the user never picked one. */
    const val DEFAULT_COUNTRY = "Germany"

    /** 3.06: the ONLY user-visible name for the
     *  single entry — the stored name ("TunnelBear")
     *  never reaches the UI, the log, the home
     *  quick-connect card, Monitoring or Chain.
     *  The stored name/prefs stay as-is (invisible). */
    const val DISPLAY_NAME = "Free OpenVpn config"

    /** Display name for a stored profile. */
    fun displayProfileName(p: OvpnProfile): String =
        if (isTunnelBear(p)) DISPLAY_NAME else p.name

    /** Display name for a stored profile NAME
     *  (status lines, Monitoring, Chain hops,
     *  QuickConnect) — the single entry's stored
     *  name maps to the neutral display name. */
    fun displayNameForStored(name: String?): String =
        if (name == SINGLE_NAME) DISPLAY_NAME else (name ?: "")

    private const val PREFS = "renamer_ovpn"
    private const val KEY_USER = "tb_username"
    private const val KEY_PASS = "tb_password"
    private const val KEY_HEALTHY = "tb_healthy_"
    /** 3.06: per-country TLS-probe cache (host ->
     *  "code|millis", TTL 10 min) and per-country
     *  REAL-dial outcomes (last 7 days only). */
    private const val KEY_PROBE = "tb_probe_"
    private const val KEY_DIAL_OK = "tb_dial_ok_"
    private const val KEY_DIAL_TIME = "tb_dial_time_"
    const val PROBE_TTL_MS = 10 * 60 * 1000L
    const val DIAL_WINDOW_MS = 7L * 24 * 60 * 60 * 1000
    private const val KEY_COUNTRY = "tb_country"
    private const val KEY_REMOVED = "tb_removed"
    private const val ASSET_DIR = "openvpn"
    private const val CA_ASSET = "openvpn/openvpn-server-ca.crt"

    /** True when this profile is a TunnelBear one:
     *  its host ends with .lazerpenguin.com. The
     *  stored hostname is checked first; the remote
     *  lines in the text are the fallback (an edited
     *  profile keeps its hostname, but the text is
     *  the ground truth). */
    fun isTunnelBear(p: OvpnProfile): Boolean {
        if (p.hostname.endsWith(".lazerpenguin.com", ignoreCase = true)) return true
        for (line in p.ovpn.lines()) {
            val t = line.trim()
            if (t.startsWith("remote ", ignoreCase = true)) {
                val parts = t.split(Regex("\\s+"))
                if (parts.size >= 2 && parts[1].endsWith(".lazerpenguin.com", ignoreCase = true)) return true
            }
        }
        return false
    }

    /** 3.04: flag emoji for every bundled country,
     *  from the ISO code (regional indicators).
     *  "United States Central/East/West" all share
     *  the US flag. Unknown names get no flag. */
    private val COUNTRY_ISO = mapOf(
        "Argentina" to "AR", "Australia" to "AU", "Austria" to "AT",
        "Belgium" to "BE", "Brazil" to "BR", "Bulgaria" to "BG",
        "Canada" to "CA", "Chile" to "CL", "Colombia" to "CO",
        "Cyprus" to "CY", "Czech Republic" to "CZ", "Denmark" to "DK",
        "Finland" to "FI", "France" to "FR", "Germany" to "DE",
        "Greece" to "GR", "Hungary" to "HU", "Ireland" to "IE",
        "Italy" to "IT", "Japan" to "JP", "Kenya" to "KE",
        "Latvia" to "LV", "Lithuania" to "LT", "Malaysia" to "MY",
        "Mexico" to "MX", "Moldova" to "MD", "Netherlands" to "NL",
        "New Zealand" to "NZ", "Nigeria" to "NG", "Norway" to "NO",
        "Peru" to "PE", "Poland" to "PL", "Portugal" to "PT",
        "Romania" to "RO", "Serbia" to "RS", "Singapore" to "SG",
        "Slovenia" to "SI", "South Africa" to "ZA", "South Korea" to "KR",
        "Spain" to "ES", "Sweden" to "SE", "Switzerland" to "CH",
        "Taiwan" to "TW", "United Kingdom" to "GB",
        "United States Central" to "US", "United States East" to "US",
        "United States West" to "US"
    )

    fun flagEmoji(country: String): String {
        val iso = COUNTRY_ISO[country.trim()] ?: return ""
        val sb = StringBuilder()
        for (ch in iso) sb.appendCodePoint(0x1F1E6 + (ch - 'A'))
        return sb.toString()
    }

    /** Flag + English name, e.g. "🇬🇧 United Kingdom". */
    fun displayCountry(country: String): String {
        val flag = flagEmoji(country)
        return if (flag.isEmpty()) country else "$flag $country"
    }

    /** "TunnelBear Germany.ovpn" -> "Germany";
     *  "TunnelBear Latvia.ovpn.txt" -> "Latvia". */
    fun countryName(fileName: String): String {
        var n = fileName
        if (n.startsWith("TunnelBear ", ignoreCase = true)) n = n.substring("TunnelBear ".length)
        n = n.removeSuffix(".ovpn").removeSuffix(".OVPN")
        n = n.removeSuffix(".txt").removeSuffix(".TXT")
        return n.trim()
    }

    // ---------- account (user-entered, local only) ----------

    fun hasAccount(ctx: Context): Boolean = getAccount(ctx) != null

    /** v1.1: the ACTIVE dual account's credentials
     *  (FreeAccounts); falls back to the legacy
     *  single-account keys (v1.0 users, migrated on
     *  next launch). The password is returned only
     *  to callers that hand it straight to the core
     *  (provideCreds) — it is never logged and never
     *  shown in the UI. */
    fun getAccount(ctx: Context): Pair<String, String>? {
        return try {
            FreeAccounts.active(ctx)?.let { it.email to it.password }
                ?: getLegacyAccount(ctx)
        } catch (_: Exception) { null }
    }

    /** The legacy single-account keys (v1.0). Kept
     *  as a mirror of the active account so every
     *  existing caller keeps working. */
    fun getLegacyAccount(ctx: Context): Pair<String, String>? {
        return try {
            val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val u = sp.getString(KEY_USER, null)?.trim().orEmpty()
            val p = sp.getString(KEY_PASS, null).orEmpty()
            if (u.isBlank() || p.isBlank()) null else u to p
        } catch (_: Exception) { null }
    }

    fun saveLegacyAccount(ctx: Context, username: String, password: String) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_USER, username.trim())
                .putString(KEY_PASS, password)
                .apply()
        } catch (_: Exception) {}
    }

    fun saveAccount(ctx: Context, username: String, password: String) {
        saveLegacyAccount(ctx, username, password)
    }

    fun clearAccount(ctx: Context) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_USER).remove(KEY_PASS).apply()
        } catch (_: Exception) {}
    }

    // ---------- countries (the 47 profiles as data) ----------

    /** All bundled countries, sorted (Argentina to the
     *  United States). The profiles stay data, not
     *  tunnels: only the picked country's endpoint is
     *  ever stored as a profile. */
    fun countries(ctx: Context): List<String> {
        return try {
            val files = ctx.assets.list(ASSET_DIR) ?: return emptyList()
            files.filter { it.startsWith("TunnelBear ") &&
                (it.endsWith(".ovpn", ignoreCase = true) || it.endsWith(".ovpn.txt", ignoreCase = true)) }
                .map { countryName(it) }.filter { it.isNotBlank() }.sorted()
        } catch (_: Exception) { emptyList() }
    }

    private fun countryFile(ctx: Context, country: String): String? {
        return try {
            val files = ctx.assets.list(ASSET_DIR) ?: return null
            files.firstOrNull { countryName(it).equals(country, ignoreCase = true) }
        } catch (_: Exception) { null }
    }

    /** The user's picked country, persisted. */
    fun selectedCountry(ctx: Context): String {
        return try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_COUNTRY, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_COUNTRY
        } catch (_: Exception) { DEFAULT_COUNTRY }
    }

    /** True only when the stored value is a REAL
     *  country from the 47-country list (v1.5: the
     *  old upgrade adoption saved the raw entry name
     *  "TunnelBear" as tb_country, so a bare
     *  non-blank check is not enough — anything not
     *  in the list counts as unpicked, and the chip
     *  reads «انتخاب کشور»). */
    fun isKnownCountry(ctx: Context, country: String): Boolean {
        return try {
            countries(ctx).any { it.equals(country.trim(), ignoreCase = true) }
        } catch (_: Exception) { false }
    }

    /** True when the user has explicitly picked a
     *  country (v1.2: until then the chip reads
     *  «انتخاب کشور» and the log uses the bare
     *  display name). */
    fun hasSelectedCountry(ctx: Context): Boolean {
        return try {
            val stored = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_COUNTRY, null)
            stored?.isNotBlank() == true && isKnownCountry(ctx, stored)
        } catch (_: Exception) { false }
    }

    fun saveSelectedCountry(ctx: Context, country: String) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_COUNTRY, country).apply()
        } catch (_: Exception) {}
    }

    /** Marks the single entry as user-deleted, so the
     *  OpenVPN page never re-adds it silently. Only
     *  the auto-account flow (restoreDeleted = true)
     *  brings it back. */
    fun markRemoved(ctx: Context) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_REMOVED, true).apply()
        } catch (_: Exception) {}
    }

    // ---------- the single entry ----------

    /** Builds the single "TunnelBear" profile for one
     *  country (CA inlined, auth-user-pass + inline
     *  CA, no <cert> — covered by the content-based
     *  disableClientCert rule in Ovpn3VpnService).
     *  Null when the country's asset is missing. */
    fun profileForCountry(ctx: Context, country: String): OvpnProfile? {
        return try {
            val file = countryFile(ctx, country) ?: return null
            val ca = try {
                ctx.assets.open(CA_ASSET).bufferedReader().use { it.readText() }.trim()
            } catch (_: Exception) { return null }
            val text = try {
                ctx.assets.open("$ASSET_DIR/$file").bufferedReader().use { it.readText() }
            } catch (_: Exception) { return null }
            val inlined = inlineCa(text, ca) ?: return null
            val host = remoteHost(inlined) ?: return null
            OvpnProfile(name = SINGLE_NAME, hostname = host, ovpn = inlined.trim(),
                username = "", password = "", savePassword = false, tunnelBear = true)
        } catch (_: Exception) { null }
    }

    /** Creates the single entry when missing, collapses
     *  several stored TunnelBear profiles into one,
     *  and rebuilds the entry when the picked country
     *  changed. 3.01 users arrive with 47 imported
     *  profiles: on the first 3.02 run the country the
     *  user already had (last-connected, else first)
     *  is adopted as the pick, so nothing they used
     *  is silently switched. A user-deleted entry is
     *  NOT re-added unless restoreDeleted is true
     *  (the auto-account flow). Returns 1 when a new
     *  entry was created, 0 otherwise. */
    fun ensureSingleEntry(ctx: Context, restoreDeleted: Boolean = false): Int {
        return try {
            val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val stored = OvpnStore.load(ctx)
            val tb = stored.filter { it.tunnelBear }
            // v1.5 HEAL: a stored tb_country that is not
            // a real country (the old adoption saved the
            // raw entry name "TunnelBear") is removed, so
            // existing installs show «انتخاب کشور» again
            // with no reinstall. Only real 47-list
            // countries survive.
            val storedCountry = sp.getString(KEY_COUNTRY, null)
            if (storedCountry != null && !isKnownCountry(ctx, storedCountry)) {
                sp.edit().remove(KEY_COUNTRY).apply()
            }
            // Upgrade adoption: no country picked yet —
            // keep the country the user already had.
            if (sp.getString(KEY_COUNTRY, null) == null && tb.isNotEmpty()) {
                val last = sp.getString("ovpn_last", null)
                val pick = tb.firstOrNull { it.name == last } ?: tb.sortedBy { it.name }.first()
                // v1.5: adopt ONLY a real country.
                // countryName() of the bare single-entry
                // name "TunnelBear" is "TunnelBear" itself
                // — saving it made the chip show the raw
                // entry name. Anything not in the 47-list
                // is left unpicked.
                val adopted = countryName(pick.name)
                if (isKnownCountry(ctx, adopted)) saveSelectedCountry(ctx, adopted)
                if (last != null && last.startsWith("TunnelBear ")) {
                    sp.edit().putString("ovpn_last", SINGLE_NAME).apply()
                }
            }
            if (tb.isEmpty()) {
                if (!restoreDeleted && sp.getBoolean(KEY_REMOVED, false)) return 0
                val p = profileForCountry(ctx, selectedCountry(ctx)) ?: return 0
                stored += p
                OvpnStore.save(ctx, stored)
                sp.edit().remove(KEY_REMOVED).apply()
                return 1
            }
            val want = profileForCountry(ctx, selectedCountry(ctx)) ?: return 0
            val first = tb.first()
            if (tb.size == 1 && first.hostname == want.hostname &&
                first.ovpn.trim() == want.ovpn.trim()) return 0
            // Collapse to one, at the first TunnelBear
            // entry's position, keeping any credentials
            // stored on it (normally blank — the
            // account fills them at connect time).
            val idx = stored.indexOfFirst { it.tunnelBear }
            val rebuilt = want.copy(username = first.username, password = first.password)
            stored.removeAll { it.tunnelBear }
            stored.add(idx.coerceAtLeast(0), rebuilt)
            OvpnStore.save(ctx, stored)
            0
        } catch (_: Exception) { 0 }
    }

    /** Replaces the `ca openvpn-server-ca.crt` file
     *  reference with the certificate text inline.
     *  Null when the profile has no such line (then
     *  the core would have nothing to verify with). */
    private fun inlineCa(text: String, ca: String): String? {
        var replaced = false
        val out = text.lines().map { line ->
            val t = line.trim()
            if (!replaced && (t == "ca openvpn-server-ca.crt" || t.startsWith("ca openvpn-server-ca.crt "))) {
                replaced = true
                "<ca>\n$ca\n</ca>"
            } else line
        }
        return if (replaced) out.joinToString("\n") else null
    }

    private fun remoteHost(text: String): String? {
        for (line in text.lines()) {
            val t = line.trim()
            if (t.startsWith("remote ", ignoreCase = true)) {
                val parts = t.split(Regex("\\s+"))
                if (parts.size >= 2) return parts[1]
            }
        }
        return null
    }

    // ---------- connect-time rewrite ----------

    /** Fresh on every connect; the stored profile is
     *  never mutated. Appends `cipher AES-256-CBC`
     *  when no cipher line exists, moves the profile
     *  to TCP, and points the core at the loopback
     *  relay instead of the server. For cert-less
     *  profiles it also appends `setenv CLIENT_CERT 0`
     *  (belt-and-braces next to the API-level
     *  disableClientCert in Ovpn3VpnService: the
     *  documented OpenVPN Connect workaround for the
     *  "Missing External PKI alias" eval failure). */
    fun effectiveConfig(text: String, relayPort: Int): String {
        val lines = text.replace("\r\n", "\n").split("\n").toMutableList()
        fun directive(l: String): String = l.trim().substringBefore(' ').lowercase()
        if (lines.none { directive(it) == "cipher" }) {
            val first = lines.firstOrNull { directive(it) == "data-ciphers" }
                ?.trim()?.substringAfter(' ')?.split(':')?.firstOrNull()?.trim()
            if (!first.isNullOrBlank()) lines += "cipher $first"
        }
        for (i in lines.indices) {
            val t = lines[i].trim()
            when (directive(t)) {
                "proto" -> lines[i] = "proto tcp-client"
                "remote" -> lines[i] = "remote 127.0.0.1 $relayPort tcp-client"
                "explicit-exit-notify" -> lines[i] = ""
            }
        }
        if (!text.contains("<cert>") &&
            lines.none { it.trim().startsWith("setenv CLIENT_CERT", ignoreCase = true) }) {
            lines += "setenv CLIENT_CERT 0"
        }
        return lines.joinToString("\n")
    }

    // ---------- candidate IPs ----------

    /** IPs that passed the TLS probe last time, best
     *  first (at most 6). Host-keyed, so one
     *  country's winners never leak into another's. */
    fun healthyIps(ctx: Context, host: String): List<String> {
        return try {
            val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_HEALTHY + host.lowercase(), null) ?: return emptyList()
            JSONArray(raw).let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
            }
        } catch (_: Exception) { emptyList() }
    }

    fun rememberHealthy(ctx: Context, host: String, ips: List<String>) {
        try {
            val arr = JSONArray()
            for (ip in ips.distinct().take(6)) arr.put(ip)
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_HEALTHY + host.lowercase(), arr.toString()).apply()
        } catch (_: Exception) {}
    }

    /** Resolves the hostname and probes the addresses
     *  on TCP 7011 in parallel: a server passes only
     *  when it gets through the step real connections
     *  die on — the reset, then the TLS ClientHello
     *  inside the control channel, waiting for the
     *  server's certificate flight (see probeTls).
     *  TLS-proven servers come first; servers that
     *  answer TCP but not TLS follow (the relay's
     *  skip-on-silent-handshake rotation is the
     *  backstop for those); the rest, then the
     *  hostname itself as the last resort. Whole
     *  subnets stay silent from Iran, so taking the
     *  first DNS answer and hanging on it is exactly
     *  what this avoids. Background threads only. */
    fun probeCandidates(ctx: Context, host: String): List<String> {
        val fresh = try {
            InetAddress.getAllByName(host)
                .filterIsInstance<java.net.Inet4Address>()
                .mapNotNull { it.hostAddress }
        } catch (_: Exception) { emptyList() }
        val ordered = (fresh + healthyIps(ctx, host)).distinct().take(6)
        if (ordered.isEmpty()) return listOf(host)
        val tlsOk = java.util.Collections.synchronizedList(ArrayList<String>())
        val tcpOk = java.util.Collections.synchronizedList(ArrayList<String>())
        val pool = java.util.concurrent.Executors.newFixedThreadPool(ordered.size.coerceAtMost(6))
        val done = java.util.concurrent.CountDownLatch(ordered.size)
        for (ip in ordered) {
            pool.submit {
                try {
                    when (probeTls(ip)) {
                        2 -> tlsOk += ip
                        1 -> tcpOk += ip
                    }
                } catch (_: Exception) {} finally { done.countDown() }
            }
        }
        try { done.await(9500, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) {}
        try { pool.shutdownNow() } catch (_: Exception) {}
        if (tlsOk.isNotEmpty()) {
            try { rememberHealthy(ctx, host, tlsOk) } catch (_: Exception) {}
        }
        return (tlsOk + tcpOk + ordered.filter { it !in tlsOk && it !in tcpOk } + host).distinct()
    }

    // 0 = no TCP answer, 1 = TCP answered but no TLS
    // flight, 2 = TLS flight arrived (or a TLS alert:
    // the server read our ClientHello, so the path
    // is open). The reset goes out whole (the filter
    // lets a bare reset through); the ClientHello is
    // cut into the same small segments the relay uses.
    private fun probeTls(ip: String): Int {
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            try {
                socket.connect(InetSocketAddress(ip, TCP_PORT), 3500)
            } catch (_: Exception) {
                return 0
            }
            clampWindow(socket, 1024)
            socket.soTimeout = 3500
            val out = socket.getOutputStream()
            val input = DataInputStream(socket.getInputStream())
            val session = ByteArray(8).also { SecureRandom().nextBytes(it) }
            out.write(frame(OvpnProbePacket.request(session)))
            out.flush()
            val reply = try { readFrame(input) } catch (_: Exception) { return 1 }
            if (!OvpnProbePacket.accepts(reply, session)) return 1
            val hello = clientHello() ?: return 1
            val control = OvpnProbePacket.control(
                session, OvpnProbePacket.packetId(reply),
                OvpnProbePacket.serverSession(reply), 1, hello)
            writeSplit(out, frame(control))
            // The server's answer: its TLS records inside
            // control packets, until a flight's worth.
            val deadline = System.currentTimeMillis() + 5000
            var got = 0
            while (System.currentTimeMillis() < deadline) {
                socket.soTimeout = (deadline - System.currentTimeMillis()).toInt().coerceIn(1, 5000)
                val pkt = try { readFrame(input) } catch (_: Exception) { break }
                val tls = OvpnProbePacket.controlPayload(pkt) ?: continue
                // An alert is an answer too: the server
                // read our TLS, so the path is open.
                if (tls.isNotEmpty() && tls[0].toInt() == 0x15) return 2
                got += tls.size
                if (got >= 2048) return 2
            }
            return 1
        } catch (_: Exception) {
            return 0
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun frame(packet: ByteArray) =
        byteArrayOf((packet.size ushr 8).toByte(), packet.size.toByte()) + packet

    private fun readFrame(input: DataInputStream): ByteArray {
        val length = input.readUnsignedShort()
        check(length in 1..4096)
        return ByteArray(length).also { input.readFully(it) }
    }

    /** The relay's cutting: the first byte alone, then
     *  pieces of 3–29 bytes a few milliseconds apart,
     *  so no segment starts on a record boundary a
     *  matcher can key on. */
    private fun writeSplit(out: java.io.OutputStream, bytes: ByteArray) {
        val rng = SecureRandom()
        var at = 0
        var first = true
        while (at < bytes.size) {
            val size = if (first) 1 else 3 + rng.nextInt(27)
            first = false
            val end = minOf(bytes.size, at + size)
            out.write(bytes, at, end - at)
            out.flush()
            at = end
            if (at < bytes.size) Thread.sleep(2L + rng.nextInt(6))
        }
    }

    /** TCP_WINDOW_CLAMP on the live socket, so the
     *  server's certificate flight arrives in small
     *  segments. False where the platform refuses it. */
    private fun clampWindow(socket: Socket, bytes: Int): Boolean = try {
        android.os.ParcelFileDescriptor.fromSocket(socket).use { pfd ->
            android.system.Os.setsockoptInt(pfd.fileDescriptor, 6 /* IPPROTO_TCP */, 10 /* TCP_WINDOW_CLAMP */, bytes)
        }
        true
    } catch (_: Exception) {
        false
    }

    private val tlsContext by lazy { javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, null, null) } }

    /** A real TLS ClientHello from the platform's own stack: a fresh one every time. */
    private fun clientHello(): ByteArray? = try {
        val engine = tlsContext.createSSLEngine().apply { useClientMode = true }
        val out = ByteBuffer.allocate(engine.session.packetBufferSize)
        engine.beginHandshake()
        engine.wrap(ByteBuffer.allocate(0), out)
        out.flip()
        ByteArray(out.remaining()).also { out.get(it) }
    } catch (_: Exception) { null }

    // ---------- 3.06: country health (list circles) ----------

    /** The `remote` hostname of one bundled
     *  country's asset — reads ONLY that line, so
     *  opening the country list never rebuilds
     *  all 47 full profiles with the inlined CA. */
    fun remoteHostForCountry(ctx: Context, country: String): String? {
        return try {
            val file = countryFile(ctx, country) ?: return null
            ctx.assets.open("$ASSET_DIR/$file").bufferedReader().useLines { lines ->
                for (line in lines) {
                    val t = line.trim()
                    if (t.startsWith("remote ", ignoreCase = true)) {
                        val parts = t.split(Regex("\\s+"))
                        if (parts.size >= 2) return parts[1]
                    }
                }
                null
            }
        } catch (_: Exception) { null }
    }

    /** 3.06: the LIGHT per-country probe for the
     *  country list — the same TLS-handshake probe
     *  as probeTls (reset + ClientHello, waiting
     *  for the server's certificate flight or a
     *  TLS alert), against the country's own
     *  hostname, in parallel over its resolved
     *  IPs (at most 6). Returns 2 when >=1 IP
     *  returns a TLS flight/alert (healthy),
     *  1 when >=1 IP answers TCP but no TLS
     *  flight, 0 when nothing answers. Plain
     *  sockets only — never touches the VPN.
     *  Background threads only. */
    fun probeCountryHealth(ctx: Context, host: String): Int {
        val fresh = try {
            InetAddress.getAllByName(host)
                .filterIsInstance<java.net.Inet4Address>()
                .mapNotNull { it.hostAddress }
        } catch (_: Exception) { emptyList() }
        val ordered = (fresh + healthyIps(ctx, host)).distinct().take(6)
        if (ordered.isEmpty()) return 0
        val best = java.util.concurrent.atomic.AtomicInteger(0)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(ordered.size.coerceAtMost(6))
        val done = java.util.concurrent.CountDownLatch(ordered.size)
        for (ip in ordered) {
            pool.submit {
                try {
                    val r = probeTls(ip)
                    while (true) {
                        val cur = best.get()
                        if (r <= cur || best.compareAndSet(cur, r)) break
                    }
                } catch (_: Exception) {} finally { done.countDown() }
            }
        }
        try { done.await(9500, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) {}
        try { pool.shutdownNow() } catch (_: Exception) {}
        return best.get()
    }

    /** Cached probe result for a host (2/1/0),
     *  or null when unknown/stale (>10 min). */
    fun cachedProbe(ctx: Context, host: String): Int? {
        return try {
            val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PROBE + host.lowercase(), null) ?: return null
            val parts = raw.split("|")
            if (parts.size != 2) return null
            val code = parts[0].toIntOrNull() ?: return null
            val at = parts[1].toLongOrNull() ?: return null
            if (System.currentTimeMillis() - at > PROBE_TTL_MS) null else code
        } catch (_: Exception) { null }
    }

    fun storeProbe(ctx: Context, host: String, code: Int) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PROBE + host.lowercase(), "$code|${System.currentTimeMillis()}").apply()
        } catch (_: Exception) {}
    }

    /** 3.06: record a REAL dial outcome for one
     *  country (called from OpenVpnActivity's
     *  recordDial when the active profile is the
     *  single free entry). Only real ovpn3 dials
     *  are recorded here — never a probe. */
    fun recordDialOutcome(ctx: Context, country: String, ok: Boolean) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_DIAL_OK + country, ok)
                .putLong(KEY_DIAL_TIME + country, System.currentTimeMillis()).apply()
        } catch (_: Exception) {}
    }

    /** A country's real-dial outcome from the
     *  last 7 days: 1 = a real dial succeeded,
     *  -1 = a real dial failed, 0 = never dialled
     *  (or older than 7 days = unknown again). */
    fun dialOutcome(ctx: Context, country: String): Int {
        return try {
            val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val t = sp.getLong(KEY_DIAL_TIME + country, 0)
            if (t <= 0 || System.currentTimeMillis() - t > DIAL_WINDOW_MS) 0
            else if (sp.getBoolean(KEY_DIAL_OK + country, false)) 1 else -1
        } catch (_: Exception) { 0 }
    }

    // ---------- 3.07: session probe per country ----------

    /** Session-probe cache (host -> "tls|session|millis|ip|detail",
     *  TTL 10 min, same as the TLS-probe cache). Never the dial keys. */
    private const val KEY_SESSION = "tb_session_"

    /** 3.07: the full session probe for one country's host — TLS
     *  flight + AUTH with the saved account + PUSH_REPLY, over TCP
     *  7011 with the relay-style split write (the same transport the
     *  connect path uses). The CA comes from the stored free entry's
     *  inlined <ca> block when there is one. No account yet means
     *  no credentials, so the probe stops after the TLS flight
     *  (session 0, "no_creds"). Never logs credentials. */
    fun probeCountrySession(ctx: Context, host: String): OvpnSessionProbe.SessionVerdict {
        val account = getAccount(ctx)
        val caPem = try {
            OvpnStore.load(ctx).firstOrNull { it.tunnelBear }
                ?.let { OvpnSessionProbe.extractBlock(it.ovpn, "ca") }
        } catch (_: Exception) { null }
        val ips = try {
            InetAddress.getAllByName(host)
                .filterIsInstance<java.net.Inet4Address>()
                .mapNotNull { it.hostAddress }
        } catch (_: Exception) { emptyList() }
        val ordered = (ips + healthyIps(ctx, host)).distinct().take(2)
        if (ordered.isEmpty()) return OvpnSessionProbe.SessionVerdict(0, 0, null, "unresolved")
        val user = account?.first ?: ""
        val pass = account?.second ?: ""
        for (ip in ordered) {
            val v = OvpnSessionProbe.probeTcp(ip, TCP_PORT, user, pass,
                needsAuth = true, caPem = caPem, certPem = null, keyPem = null,
                cipher = "AES-256-CBC", auth = "SHA256",
                splitWrite = true, deadlineMs = 9000)
            if (v.tls >= 1) {
                storeSession(ctx, host, v)
                storeProbe(ctx, host, v.tls)
                return v
            }
        }
        val v = OvpnSessionProbe.SessionVerdict(0, 0, null, "tcp_dead")
        storeSession(ctx, host, v)
        storeProbe(ctx, host, 0)
        return v
    }

    fun storeSession(ctx: Context, host: String, v: OvpnSessionProbe.SessionVerdict) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_SESSION + host.lowercase(),
                    "${v.tls}|${v.session}|${System.currentTimeMillis()}|${v.assignedIp ?: ""}|${v.detail}")
                .apply()
        } catch (_: Exception) {}
    }

    /** Cached session verdict for a host, or null when never probed or
     *  older than the 10-minute TTL. */
    fun cachedSession(ctx: Context, host: String): OvpnSessionProbe.SessionVerdict? {
        return try {
            val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SESSION + host.lowercase(), null) ?: return null
            val parts = raw.split("|")
            if (parts.size < 3) return null
            val tls = parts[0].toIntOrNull() ?: return null
            val session = parts[1].toIntOrNull() ?: return null
            val at = parts[2].toLongOrNull() ?: return null
            if (System.currentTimeMillis() - at > PROBE_TTL_MS) return null
            OvpnSessionProbe.SessionVerdict(tls, session,
                parts.getOrNull(3)?.takeIf { it.isNotBlank() },
                parts.getOrNull(4) ?: "")
        } catch (_: Exception) { null }
    }

    /** A country's session outcome from the cache: 1 = a session was
     *  proven (AUTH + PUSH_REPLY with an IP), -1 = the session failed
     *  (AUTH_FAILED / no PUSH), 0 = untested or TLS-only. */
    fun sessionOutcome(ctx: Context, host: String): Int {
        val v = cachedSession(ctx, host) ?: return 0
        return v.session
    }
}
