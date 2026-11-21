package com.renamer

import java.io.File
import java.util.Base64

/** 1.52 — the aether rung's plan: pure Kotlin + java.* only,
 *  NO Android imports, so it is JVM-testable (see vm152
 *  TestMain152.kt). The Android code (WarpFree, AetherBridge)
 *  only executes the plan; the decisions live here.
 *
 *  NETWORK-ADAPTIVE RULE (user order 2026-11-20): nothing in
 *  the rung may depend on an IP, gateway or timing that only
 *  works on one network. The fixed 18-list is ONLY the seed.
 *  The rung's candidates come from the on-device scan at fetch
 *  time (seed scan, then — when the seeds yield too few — a
 *  wide sweep over aether's own MASQUE /24s below), ordered
 *  saved-first, and verified with a real CONNECT-IP before
 *  any tunnel is committed to. No gateway is ever hardcoded
 *  as "the winner" — the app finds the hole itself, on that
 *  network, at that moment. */
object AetherRungPlan {

    /** aether 2.4.0 prober.rs MASQUE_CIDRS_V4 — the public
     *  Cloudflare edge /24s the engine itself hunts in.
     *  The wide sweep walks these, never a single IP.
     *  1.59 — "162.159.199.0/24" added: the 2.3.0 list
     *  this was copied from had 14 ranges; the 2.4.0
     *  engine we ship hunts 15 (prober.rs:17-35), and
     *  199.0/24 is one of the two /24s its own scan
     *  tries first (198.0/24, then 199.0/24). */
    val MASQUE_CIDRS_V4 = listOf(
        "162.159.196.0/24", "162.159.195.0/24",
        "162.159.192.0/24", "162.159.193.0/24",
        "162.159.204.0/24", "162.159.197.0/24",
        "162.159.198.0/24", "162.159.199.0/24",
        "172.65.251.0/24",
        "188.114.96.0/24", "188.114.97.0/24",
        "188.114.98.0/24", "188.114.99.0/24",
        "162.159.36.0/24", "162.159.46.0/24"
    )

    /** Aether's H3 MASQUE port (UDP) and H2 port (TCP). */
    const val H3_PORT = 1701
    const val H2_PORT = 443
    const val MAX_CLEAN_LIST = 16

    /** One rung attempt: which IP, on which carrier, which
     *  try. 1.56 — PER-GATEWAY H3→H2, one try each: H3 on
     *  a gateway, and on failure H2 on the SAME gateway
     *  immediately, before moving to the next gateway.
     *  The 1.55 device run proved the old plan starves
     *  H2: eleven ~20s H3 data-plane timeouts ate the
     *  whole 240s fetch deadline ("H3 روی 11 دروازه
     *  (11 تلاش)، H2 روی 0 دروازه (0 تلاش)؛ توقف:
     *  مهلت"), while Asha's own PoW — which forces H2
     *  from the start — connected on this network, and
     *  Asha's MASQUE re-test (04:20) connected on H3
     *  too. H2 is proven here; it must never again
     *  wait behind a full H3 sweep. The first gateway
     *  in the plan (198.2) gets its H2 attempt within
     *  ~40s of the probe start. tryNo is always 1 now
     *  (the engine's own session loop already retries
     *  a failed peer internally — last_good_peer
     *  quick-verify + forced-peer re-dial — so a
     *  second try at our level would only burn the
     *  deadline on a gateway the engine just failed);
     *  the field stays so the log lines keep their
     *  shape. */
    data class Attempt(val ip: String, val carrier: String, val port: Int, val tryNo: Int)

    fun attempts(candidates: List<String>, firstCarrier: String? = null): List<Attempt> {
        val out = mutableListOf<Attempt>()
        // 1.58 — the fetch remembers the last winning
        // transport: when it was H2, the FIRST
        // candidate tries H2 before H3 (H2 is proven
        // on this network; the 14:09 device connect
        // wasted ~41s on H3 first). The rest keep
        // H3→H2 — the fetch still explores H3 on
        // every other gateway.
        val firstH2 = (firstCarrier ?: "").trim().equals("h2", ignoreCase = true)
        for ((idx, ip) in candidates.withIndex()) {
            if (idx == 0 && firstH2) {
                out += Attempt(ip, "h2", H2_PORT, 1)
                out += Attempt(ip, "h3", H3_PORT, 1)
            } else {
                out += Attempt(ip, "h3", H3_PORT, 1)
                out += Attempt(ip, "h2", H2_PORT, 1)
            }
        }
        return out
    }

    /** 1.58 — one session-connect attempt: which engine
     *  protocol (masque/gool), which carrier (h3/h2),
     *  which gateway, and the SOCKS wait budget for
     *  that leg. The connect path iterates this order
     *  and stops at the first success — a recorded H2
     *  winner connects via H2 directly, with NO H3
     *  attempt at all (the 14:09 device connect wasted
     *  ~41s on H3 first: 35s session timeout + retry). */
    data class SessionAttempt(val protocol: String, val carrier: String, val ip: String, val waitMs: Long)

    /** 1.58 — the session SOCKS wait for a winning-
     *  transport attempt (matches the probe's 20s
     *  budget — a winning H2 validated in ~12s on
     *  device 14:09) and for the legacy H3-first legs
     *  (the engine's own startup budget is 35s, so a
     *  40s wait never cuts a slow H3 dial — 1.54).
     *  1.62 — GOOL legs NEVER use the 20s winner
     *  wait: the engine's inner WireGuard validation
     *  takes 15s per attempt and retries once
     *  internally (AETHER_WG_VALIDATE_SECS=15), so a
     *  GOOL leg needs 30s+ before SOCKS can serve —
     *  the 1.60 device run died exactly this way
     *  ("verify timeout" at 15s, attempt 2 started,
     *  our 20s wait gone). 1.61 fixed only the FETCH
     *  probe wait (GOOL_PROBE_SOCKS_WAIT_MS=45s);
     *  the CONNECT path still gave a GOOL winner leg
     *  20s. GOOL_SESSION_WAIT_MS matches the probe
     *  wait, so a connect gets the same two inner
     *  attempts the fetch does. */
    const val SESSION_WIN_WAIT_MS = 20_000L
    const val SESSION_STD_WAIT_MS = 40_000L
    const val GOOL_SESSION_WAIT_MS = 45_000L

    /** 1.58 — the connect attempt order for one saved
     *  record. [rung] is the record's rung
     *  ("aether-masque" / "gool"), [preferCarrier] the
     *  transport that won the fetch ("h2" / "h3" /
     *  blank for legacy records). Winning transport
     *  first, exactly one attempt on the record
     *  gateway, tight budget — NO H3 attempt at all
     *  for an H2 winner. A legacy/unknown carrier
     *  keeps the old H3-first behaviour (record
     *  gateway + up to 3 saved-clean gateways on H3,
     *  then H2 on the record gateway). */
    fun sessionAttemptOrder(
        rung: String,
        preferCarrier: String?,
        gatewayIp: String,
        extraGateways: List<String>
    ): List<SessionAttempt> {
        val gw = gatewayIp.trim()
        val extras = extraGateways.map { it.trim() }
            .filter { it.isNotEmpty() && it != gw }.distinct().take(3)
        val first = (preferCarrier ?: "").trim().lowercase()
        return when {
            rung == "gool" && first == "h2" ->
                listOf(SessionAttempt("gool", "h2", gw, GOOL_SESSION_WAIT_MS))
            rung == "gool" && first == "h3" -> listOf(
                SessionAttempt("gool", "h3", gw, GOOL_SESSION_WAIT_MS),
                SessionAttempt("gool", "h2", gw, GOOL_SESSION_WAIT_MS))
            rung == "gool" -> listOf(
                SessionAttempt("gool", "h2", gw, GOOL_SESSION_WAIT_MS),
                SessionAttempt("gool", "h3", gw, GOOL_SESSION_WAIT_MS))
            first == "h2" ->
                listOf(SessionAttempt("masque", "h2", gw, SESSION_WIN_WAIT_MS))
            else -> {
                val out = mutableListOf(SessionAttempt("masque", "h3", gw, SESSION_STD_WAIT_MS))
                for (e in extras) out += SessionAttempt("masque", "h3", e, SESSION_STD_WAIT_MS)
                out += SessionAttempt("masque", "h2", gw, SESSION_STD_WAIT_MS)
                out
            }
        }
    }

    /** 1.58 — the fetch deadline is shared FAIRLY
     *  across rungs (real_traffic_tunnel_proof: no rung
     *  may starve the others). Shares of the remaining
     *  budget at Stage B start: AETHER 50%, Xray H3
     *  15%, Xray H2 15%, GOOL 20%. The shares are
     *  computed once, from the same remaining budget,
     *  so a slow AETHER can never eat the GOOL slice. */
    fun rungBudgetShares(): Map<String, Double> = mapOf(
        "aether-masque" to 0.50,
        "masque-h3" to 0.15,
        "masque-h2" to 0.15,
        "gool" to 0.20
    )

    /** 1.60 — a rung NEVER gets a 0s budget. The 1.59
     *  device run starved AETHER to 0s ("بودجهٔ تست
     *  تا مهلت دریافت: 0 ثانیه؛ توقف: مهلت") because
     *  the 240s clock covered registration too and
     *  the double registration ate it. Two fixes
     *  together: (a) the test clock now starts AFTER
     *  registration+scan (WarpFree.runFetch), so the
     *  shares below are computed from a full 240s;
     *  (b) this floor — even when the remaining
     *  budget is nearly exhausted, a rung keeps a
     *  minimum guaranteed share, and the caller
     *  EXTENDS the effective deadline by it instead
     *  of skipping the rung. The floor is per rung
     *  because a 20s AETHER attempt proves nothing
     *  (its own SOCKS wait alone is 20s), while 20s
     *  is a whole Xray/GOOL probe. */
    fun minRungBudgetMs(): Map<String, Long> = mapOf(
        "aether-masque" to 60_000L,
        "masque-h3" to 20_000L,
        "masque-h2" to 20_000L,
        "gool" to 20_000L
    )

    fun rungBudgetMs(remainingMs: Long, rung: String): Long {
        val share = rungBudgetShares()[rung] ?: 0.0
        val floor = minRungBudgetMs()[rung] ?: 0L
        return maxOf((remainingMs * share).toLong(), floor).coerceAtLeast(0L)
    }

    /** 1.60 — one rung's absolute deadline: now +
     *  its (floored) share. Pure, so the JVM test
     *  asserts the slow-registration case directly:
     *  even with remainingMs = 0 (deadline already
     *  eaten), the AETHER rung still gets now +
     *  60s — never a 0s budget. */
    fun rungDeadlineMs(nowMs: Long, stageBRemainingMs: Long, rung: String): Long =
        nowMs + rungBudgetMs(stageBRemainingMs, rung)

    /** 1.58 — the fetch FAST PATH (user order: Asha
     *  finds its working path in seconds, our fetch
     *  takes minutes): when the last saved winner is
     *  fresh and the identity is present, try that
     *  single gateway+transport first. ONE real-
     *  traffic attempt — the same gate as the full
     *  fetch, never weaker. A pass ends the fetch in
     *  ~20–30s with no scan; a fail falls back to
     *  the full per-protocol scan. */
    const val FAST_PATH_MAX_AGE_MS = 24L * 60 * 60 * 1000

    fun fastPathEligible(
        identityPresent: Boolean,
        winnerRung: String,
        winnerAgeMs: Long,
        winnerGateway: String
    ): Boolean {
        if (!identityPresent) return false
        if (winnerGateway.isBlank()) return false
        if (winnerRung != "aether-masque" && winnerRung != "masque-h3" &&
            winnerRung != "masque-h2" && winnerRung != "gool") return false
        if (winnerAgeMs < 0 || winnerAgeMs > FAST_PATH_MAX_AGE_MS) return false
        return true
    }

    /** The fast path's single attempt carrier: the
     *  Xray rungs are their own carrier; aether/gool
     *  use the winning transport from the record. */
    fun fastPathCarrier(winnerRung: String, winnerAlpn: String): String {
        return when (winnerRung) {
            "masque-h3" -> "h3"
            "masque-h2" -> "h2"
            else -> if (winnerAlpn.trim().equals("h3", ignoreCase = true)) "h3" else "h2"
        }
    }

    /** 1.55 — previously-PROVEN gateways, ORDER ONLY
     *  (never a hardcoded winner — the scan still
     *  decides; these are only tried first when the
     *  scan saw them alive). 162.159.198.2 = Asha's
     *  proven winner on this network (Asha MASQUE log
     *  2026-11-20 00:33: "MASQUE accepted
     *  162.159.198.2:1701"); 162.159.198.1 = our winner
     *  with a VPN (1.53 device run 02:05, real traffic
     *  5469ms). In the 1.54 no-VPN run both were killed
     *  by the scan's TLS gate before any probe — the
     *  gate is gone in 1.55, and trying them first
     *  means the fetch wins in seconds when they
     *  answer, instead of burning the deadline on
     *  dead gateways. */
    val PRIORITY_GATEWAYS = listOf("162.159.198.2", "162.159.198.1")

    /** 1.55 — the scan's verdict on one gateway.
     *  ONLY TCP decides candidacy. The SPKI (TLS
     *  handshake + cert validation) is DIAGNOSTIC
     *  ONLY: masqueOutbound uses the ENROLLED client
     *  key (idn.masquePublicSpkiB64), never the
     *  scanned leaf SPKI, and the aether FFI verify /
     *  tunnel needs no SPKI at all — so a gateway
     *  whose TLS handshake fails cert validation
     *  (device 1.54: CertPathValidatorException on
     *  198.2, 199.238, 197.250, 199.2, 204.2 without
     *  a VPN, while the same IPs pass through one)
     *  MUST still reach the H3 verify — the verify
     *  itself is the verdict. TCP silence is the
     *  only drop. */
    fun scanKeepsGateway(tcpOk: Boolean, spkiOk: Boolean): Boolean = tcpOk

    /** One scanned candidate: the IP plus the scan's
     *  ordering signal (TCP/TLS time; -1 when unknown). */
    data class ScannedIp(val ip: String, val pingMs: Long = -1L)

    /** Expand one /24 into its usable hosts (.1–.254;
     *  .0/.255 skipped). 14 CIDRs -> 14*254 = 3556 IPs. */
    fun expandCidr24(cidr: String): List<String> {
        val base = cidr.substringBefore("/").split(".")
        if (base.size != 4) return emptyList()
        val p0 = base[0].toIntOrNull() ?: return emptyList()
        val p1 = base[1].toIntOrNull() ?: return emptyList()
        val p2 = base[2].toIntOrNull() ?: return emptyList()
        if (p0 !in 0..255 || p1 !in 0..255 || p2 !in 0..255) return emptyList()
        return (1..254).map { "$p0.$p1.$p2.$it" }
    }

    fun wideSweepTargets(): List<String> =
        MASQUE_CIDRS_V4.flatMap { expandCidr24(it) }

    /** Candidate order for the rung: previously-working
     *  clean IPs first (this device, verified before),
     *  then this fetch's scan survivors (fastest first),
     *  then seed defaults not yet covered. Deduped, and
     *  NEVER a single hardcoded winner — the order is
     *  evidence (saved + scanned), not a guess. */
    fun orderCandidates(
        savedClean: List<String>,
        scanned: List<ScannedIp>,
        defaults: List<String>,
        priority: List<String> = emptyList()
    ): List<String> {
        val out = mutableListOf<String>()
        fun add(ip: String) {
            val t = ip.trim()
            if (t.isNotEmpty() && t !in out) out += t
        }
        // 1.55 — priority IPs first, but ONLY if the
        // pool already contains them (a priority IP
        // the scan never saw alive is never dialled).
        val pool = (savedClean + scanned.map { it.ip } + defaults)
            .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        for (ip in priority) if (ip.trim() in pool) add(ip)
        for (ip in savedClean) add(ip)
        val sortedScanned = scanned.sortedWith(
            compareBy<ScannedIp> { it.pingMs < 0 }.thenBy { it.pingMs.coerceAtLeast(0L) }
        )
        for (s in sortedScanned) add(s.ip)
        for (ip in defaults) add(ip)
        return out
    }

    /** Merge a newly-working gateway into the persistent
     *  clean list: most-recent first, deduped, capped. */
    fun mergeCleanList(saved: List<String>, workingIp: String): List<String> {
        val out = mutableListOf<String>()
        val w = workingIp.trim()
        if (w.isNotEmpty()) out += w
        for (ip in saved) {
            val t = ip.trim()
            if (t.isNotEmpty() && t !in out) out += t
        }
        return out.take(MAX_CLEAN_LIST)
    }

    /** Write the file ONLY when its bytes would change
     *  (atomic temp + rename). The 1.51 race was 18
     *  parallel probes rewriting the SAME identity file
     *  and racing the rename/delete; a no-op write is
     *  the fix at the file level — the caller must NOT
     *  treat "already identical" as a failure. Returns
     *  true when bytes were written, false when the file
     *  already had exactly this content. Throws only on
     *  a real I/O failure. */
    fun writeIfChanged(file: File, text: String): Boolean {
        val bytes = text.toByteArray(Charsets.UTF_8)
        try {
            if (file.exists() && file.readBytes().contentEquals(bytes)) return false
        } catch (_: Exception) { /* fall through and rewrite */ }
        val dir = file.parentFile
        if (dir != null) dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeBytes(bytes)
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) {
            // The rename lost a race with another writer
            // of the SAME bytes: accept the file if it
            // now holds what we wanted, else fail loudly.
            try { tmp.delete() } catch (_: Exception) {}
            if (file.exists() && file.readBytes().contentEquals(bytes)) return false
            throw java.io.IOException("rename failed for ${file.name}")
        }
        return true
    }

    // ---- ECH config list via DNS (HTTPS record, RFC 9460) ----
    // The core's own lookup (aether dns.rs) asks the HTTPS
    // record of cloudflare-ech.com of udp://1.1.1.1. Our
    // Kotlin fetch asks the SAME record over DoH to a clean
    // IP, so the key request never depends on the local
    // resolver. Only the parser lives here (pure bytes);
    // the socket lives in WarpIdentity.

    const val ECH_DOMAIN = "cloudflare-ech.com"
    const val ECH_DOH_HOST = "cloudflare-dns.com"

    /** Build a DNS query (id 0, RD=1) for <domain> type
     *  HTTPS (65), class IN. */
    fun buildDnsHttpsQuery(domain: String): ByteArray {
        val labels = domain.trimEnd('.').split(".")
        val q = mutableListOf<Byte>()
        fun u16(v: Int) {
            q += ((v ushr 8) and 0xff).toByte()
            q += (v and 0xff).toByte()
        }
        u16(0) // id 0 (RFC 8484: cacheable)
        u16(0x0100) // RD
        u16(1) // QDCOUNT
        u16(0); u16(0); u16(0) // AN/NS/ARCOUNT
        for (label in labels) {
            val b = label.toByteArray(Charsets.US_ASCII)
            q += b.size.toByte()
            for (x in b) q += x
        }
        q += 0.toByte() // root
        u16(65) // QTYPE HTTPS
        u16(1) // QCLASS IN
        return q.toByteArray()
    }

    /** Parse a DNS response; return the ECHConfigList bytes
     *  from the first HTTPS answer's ech SvcParam (key 5),
     *  or null when there is none. Handles name compression.
     *  Returns the RAW list bytes (base64 STANDARD is the
     *  AETHER_ECH wire form; the caller encodes). */
    fun parseEchFromDnsResponse(resp: ByteArray): ByteArray? {
        if (resp.size < 12) return null
        fun u16At(o: Int): Int =
            ((resp[o].toInt() and 0xff) shl 8) or (resp[o + 1].toInt() and 0xff)
        fun u32At(o: Int): Long =
            ((resp[o].toLong() and 0xff) shl 24) or
                ((resp[o + 1].toLong() and 0xff) shl 16) or
                ((resp[o + 2].toLong() and 0xff) shl 8) or
                (resp[o + 3].toLong() and 0xff)
        val qd = u16At(4)
        val an = u16At(6)
        // Skip the question section.
        var off = 12
        fun skipName(o: Int): Int {
            var p = o
            while (p < resp.size) {
                val len = resp[p].toInt() and 0xff
                if (len == 0) return p + 1
                if ((len and 0xc0) == 0xc0) return p + 2 // pointer
                p += 1 + len
            }
            return p
        }
        repeat(qd) {
            if (off >= resp.size) return null
            off = skipName(off)
            off += 4 // QTYPE + QCLASS
        }
        repeat(an) {
            if (off + 10 > resp.size) return null
            off = skipName(off)
            if (off + 10 > resp.size) return null
            val type = u16At(off)
            val rdLen = u16At(off + 8)
            val rdata = off + 10
            if (rdata + rdLen > resp.size) return null
            if (type == 65) {
                val ech = parseEchFromHttpsRdata(resp, rdata, rdLen)
                if (ech != null) return ech
            }
            off = rdata + rdLen
        }
        return null
    }

    /** HTTPS RDATA: u16 priority, target name (possibly
     *  compressed), then SvcParams (u16 key, u16 len, value).
     *  ech is key 5, value = base64url ECHConfigList. */
    fun parseEchFromHttpsRdata(resp: ByteArray, rdata: Int, rdLen: Int): ByteArray? {
        if (rdLen < 2) return null
        var off = rdata + 2
        val end = rdata + rdLen
        // Skip the target name (labels or a pointer).
        while (off < end) {
            val len = resp[off].toInt() and 0xff
            if (len == 0) { off += 1; break }
            if ((len and 0xc0) == 0xc0) { off += 2; break }
            off += 1 + len
        }
        while (off + 4 <= end) {
            val key = ((resp[off].toInt() and 0xff) shl 8) or (resp[off + 1].toInt() and 0xff)
            val len = ((resp[off + 2].toInt() and 0xff) shl 8) or (resp[off + 3].toInt() and 0xff)
            val vOff = off + 4
            if (vOff + len > end) return null
            if (key == 5) {
                val b64url = String(resp, vOff, len, Charsets.US_ASCII).trim()
                return try {
                    Base64.getUrlDecoder().decode(b64url)
                } catch (_: Exception) { null }
            }
            off = vOff + len
        }
        return null
    }
}
