package com.renamer

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.TrustManagerFactory

/**
 * 3.07: a REAL OpenVPN session probe over TCP — no tun, no ovpn3 core.
 *
 * What the old TLS-flight probe (TunnelBear.probeTls) could say was only
 * "the server answered a ClientHello". That is 1 green circle. What it
 * could NOT say is whether the server will let THIS profile in — wrong
 * password, expired account, revoked cert all die AFTER the flight, so
 * the list showed green for profiles that never connect. This probe
 * goes the whole way a real connect goes, minus the tun:
 *
 *   TCP connect -> OpenVPN reset -> TLS handshake (retained SSLEngine,
 *   driven to FINISHED, TLS records reassembled across control packets)
 *   -> key-method-2 exchange (random key material + the profile's options
 *   string + username/password when the profile has them) -> the server's
 *   key exchange -> PUSH_REQUEST -> PUSH_REPLY.
 *
 * A PUSH_REPLY carrying an ifconfig (an assigned IP) IS the session:
 * the server accepted the credentials and handed out an address, which
 * is exactly what a real connect needs before the tun is even built.
 * AUTH_FAILED, no PUSH_REPLY, or a TLS cut after the flight are all
 * distinct from a timeout, and are reported as such — never as a guess.
 *
 * The AUTH wire format is OpenVPN's documented key-method-2 plaintext
 * control message (openvpn.net protocol doc, "Structure of plaintext
 * control channel messages" + the key_exchange struct in the OpenVPN
 * wire-protocol draft): 4 zero bytes, method byte 0x02, key_random[48],
 * random1[32], random2[32], u16 options-string length (incl. NUL) +
 * options string + NUL, then (optional, lengths incl. NUL) username +
 * NUL, password + NUL. The record may end after the options string.
 * The options string is built from the profile itself (cipher/auth
 * from its own lines), so the server's options check sees the same
 * values a real connect would send.
 *
 * Control-channel reliability: our packets carry incrementing packet
 * ids and ack the server's last packet id (the same framing as
 * OvpnProbePacket); server packets are deduped by packet id, and
 * unacked packets are retransmitted up to twice after 1.2s of silence.
 * TLS records may arrive split across control packets (or several in
 * one) — incoming TLS bytes accumulate in a buffer and SSLEngine
 * unwraps whatever is complete.
 *
 * Classes that can NEVER be session-proven, by design:
 *  - tls-auth / tls-crypt profiles: the reset itself is HMAC'd /
 *    encrypted with a key this probe does not use, so the server stays
 *    silent by design. probeTcp returns tls=0 detail=tls_auth_silent
 *    and the caller must paint GREY (inconclusive), never red.
 *  - UDP profiles: this probe is TCP-only (UDP AUTH+PUSH is phase 2).
 *  - profiles with auth-user-pass but blank stored credentials: the
 *    probe stops after the TLS flight (session=0 detail=no_creds).
 * Cert profiles (a <cert>/<key> block, no username/password) ARE
 * session-proven: the client cert is offered from an in-memory
 * KeyStore and the flow skips AUTH straight to PUSH_REQUEST.
 *
 * Plain sockets only — never touches the VPN, never the ovpn3 core.
 * Background threads only. No credential is ever logged.
 */
object OvpnSessionProbe {

    /** tls: 0 = TCP dead / silent-by-design, 1 = TCP answered but no TLS
     *  flight, 2 = TLS flight arrived (server alive, path open).
     *  session: 1 = AUTH accepted + PUSH_REPLY with an assigned IP,
     *  -1 = AUTH_FAILED / no PUSH / TLS cut after the flight,
     *  0 = untested for a session (no creds, tls-auth, UDP, TLS failed). */
    data class SessionVerdict(
        val tls: Int,
        val session: Int,
        val assignedIp: String?,
        val detail: String
    )

    private const val CONNECT_TIMEOUT_MS = 3000
    private const val RETRANSMIT_MS = 1200L
    private const val MAX_RETRANSMITS = 2
    private const val CTL_CHUNK = 1024

    // ---------- profile text helpers ----------

    fun extractBlock(text: String, tag: String): String? {
        return try {
            Regex("<$tag>(.*?)</$tag>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
    }

    /** True when the profile needs the tls-auth/tls-crypt key to even
     *  answer a reset (inline block or a key-file reference). */
    fun hasTlsAuthOrCrypt(text: String): Boolean {
        for (line in text.lines()) {
            val t = line.trim().lowercase()
            if (t.startsWith("tls-auth") || t.startsWith("tls-crypt") ||
                t.startsWith("<tls-auth>") || t.startsWith("<tls-crypt>")) return true
        }
        return false
    }

    fun hasInlineCert(text: String): Boolean =
        extractBlock(text, "cert") != null && extractBlock(text, "key") != null

    private fun directiveValue(text: String, name: String): String? {
        for (line in text.lines()) {
            val t = line.trim()
            if (t.startsWith(name, ignoreCase = true) &&
                (t.length == name.length || t[name.length].isWhitespace())) {
                val rest = t.substring(name.length).trim()
                if (rest.isNotBlank()) return rest.split(Regex("\\s+"))[0]
            }
        }
        return null
    }

    fun cipherFrom(text: String): String {
        directiveValue(text, "cipher")?.let { return it.uppercase() }
        val dc = directiveValue(text, "data-ciphers")
        if (!dc.isNullOrBlank()) {
            // data-ciphers is a colon list ("AES-256-GCM:AES-128-GCM");
            // directiveValue took the first whitespace token, which is
            // the whole list — take its first entry.
            return dc.split(":").firstOrNull()?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
                ?: "AES-256-GCM"
        }
        return "AES-256-GCM"
    }

    fun authFrom(text: String): String =
        directiveValue(text, "auth")?.uppercase() ?: "SHA256"

    // ---------- per-profile storage (renamer_ovpn; NEVER ovpn_dial_*) ----------

    private const val KEY_TLS = "ovpn_probe_tls_"
    private const val KEY_SESSION = "ovpn_probe_session_"
    private const val KEY_TIME = "ovpn_probe_time_"
    private const val KEY_IP = "ovpn_probe_ip_"
    private const val KEY_DETAIL = "ovpn_probe_detail_"
    const val PROBE_TTL_MS = 10 * 60 * 1000L

    fun storeProbe(ctx: Context, name: String, v: SessionVerdict) {
        try {
            ctx.getSharedPreferences("renamer_ovpn", Context.MODE_PRIVATE).edit()
                .putInt(KEY_TLS + name, v.tls)
                .putInt(KEY_SESSION + name, v.session)
                .putLong(KEY_TIME + name, System.currentTimeMillis())
                .putString(KEY_IP + name, v.assignedIp ?: "")
                .putString(KEY_DETAIL + name, v.detail)
                .apply()
        } catch (_: Exception) {}
    }

    /** Cached probe (tls, session, assignedIp, detail), or null when
     *  never probed or older than the 10-minute TTL. */
    fun cachedProbe(ctx: Context, name: String): SessionVerdict? {
        return try {
            val sp = ctx.getSharedPreferences("renamer_ovpn", Context.MODE_PRIVATE)
            val at = sp.getLong(KEY_TIME + name, 0L)
            if (at == 0L || System.currentTimeMillis() - at > PROBE_TTL_MS) return null
            if (!sp.contains(KEY_TLS + name)) return null
            SessionVerdict(
                sp.getInt(KEY_TLS + name, 0),
                sp.getInt(KEY_SESSION + name, 0),
                sp.getString(KEY_IP + name, null)?.takeIf { it.isNotBlank() },
                sp.getString(KEY_DETAIL + name, "") ?: ""
            )
        } catch (_: Exception) { null }
    }

    /** Session-proven profile names among the given names (fresh cache
     *  only), for Monitoring. */
    fun sessionProvenNames(ctx: Context, names: List<String>): List<String> {
        val out = ArrayList<String>()
        for (n in names) {
            val v = cachedProbe(ctx, n)
            if (v != null && v.session == 1) out += n
        }
        return out
    }

    // ---------- top-level entry: one stored profile ----------

    /** Full probe for one stored profile. Returns quickly for classes
     *  that cannot be session-proven (see the file header). The caller
     *  stores the verdict with storeProbe. */
    fun probeProfile(ctx: Context, profile: OvpnProfile): SessionVerdict {
        val text = profile.ovpn
        if (text.isBlank()) return SessionVerdict(0, 0, null, "no_profile")
        if (hasTlsAuthOrCrypt(text)) {
            // The reset itself needs the key: the server stays silent
            // by design. Do not burn a TCP probe pretending otherwise.
            return SessionVerdict(0, 0, null, "tls_auth_silent")
        }
        // Endpoints, TCP first (the core falls back across remotes too).
        val eps = endpointsOf(text)
        if (eps.isEmpty()) return SessionVerdict(0, 0, null, "no_endpoint")
        val tcpEps = eps.filter { it.proto == "tcp" }
        if (tcpEps.isEmpty()) return SessionVerdict(0, 0, null, "udp_only")
        val user = profile.username.trim()
        val pass = profile.password
        val needsAuth = profile.needsAuth()
        val caPem = extractBlock(text, "ca")
        val certPem = extractBlock(text, "cert")
        val keyPem = extractBlock(text, "key")
        val cipher = if (profile.tunnelBear) "AES-256-CBC" else cipherFrom(text)
        val auth = authFrom(text)
        for (ep in tcpEps.take(2)) {
            val ips = resolveBest(ep.host, 2)
            if (ips.isEmpty()) continue
            for (ip in ips) {
                val v = probeTcp(ip, ep.port, user, pass, needsAuth,
                    caPem, certPem, keyPem, cipher, auth,
                    splitWrite = profile.tunnelBear, deadlineMs = 9000)
                // A TLS flight (or better) is an answer from THIS
                // endpoint — no need to try the next IP. Only a dead
                // TCP connect falls through to the next address.
                if (v.tls >= 1) return v
            }
        }
        return SessionVerdict(0, 0, null, "tcp_dead")
    }

    data class Endpoint(val host: String, val port: Int, val proto: String)

    fun endpointsOf(text: String): List<Endpoint> {
        val out = ArrayList<Endpoint>()
        var directiveProto: String? = null
        for (line in text.lines()) {
            val t = line.trim()
            if (t == "proto udp" || t.startsWith("proto udp ")) directiveProto = "udp"
            else if (t.startsWith("proto tcp")) directiveProto = "tcp"
        }
        for (line in text.lines()) {
            val t = line.trim()
            if (!t.startsWith("remote ")) continue
            val parts = t.split(Regex("\\s+"))
            if (parts.size < 2) continue
            val host = parts[1]
            val port = if (parts.size >= 3) parts[2].toIntOrNull() ?: 1194 else 1194
            var proto: String? = null
            if (parts.size >= 4) {
                val pr = parts[3].lowercase()
                if (pr.startsWith("tcp")) proto = "tcp" else if (pr.startsWith("udp")) proto = "udp"
            }
            out += Endpoint(host, port, proto ?: directiveProto ?: "udp")
        }
        return out
    }

    private fun resolveBest(host: String, max: Int): List<String> {
        if (host.matches(Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$")) || host.contains(":")) return listOf(host)
        return try {
            InetAddress.getAllByName(host)
                .filterIsInstance<java.net.Inet4Address>()
                .mapNotNull { it.hostAddress }.distinct().take(max)
        } catch (_: Exception) { emptyList() }
    }

    // ---------- the TCP session probe ----------

    /**
     * One TCP endpoint, whole flow. [username]/[password] are the stored
     * credentials (blank when the profile has none); [needsAuth] is the
     * profile's auth-user-pass flag. When needsAuth is true but the
     * username is blank the probe stops after the TLS flight (session 0,
     * "no_creds") — it never invents credentials. [splitWrite] cuts the
     * first 6 KB into small misaligned segments (the free-entry relay
     * style, TCP 7011); plain writes otherwise.
     */
    fun probeTcp(
        ip: String, port: Int,
        username: String, password: String, needsAuth: Boolean,
        caPem: String?, certPem: String?, keyPem: String?,
        cipher: String, auth: String,
        splitWrite: Boolean, deadlineMs: Long = 9000
    ): SessionVerdict {
        val deadline = System.currentTimeMillis() + deadlineMs
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.tcpNoDelay = true
            try {
                socket.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
            } catch (_: Exception) {
                return SessionVerdict(0, 0, null, "tcp_dead")
            }
            val ctl = CtlChannel(socket, splitWrite)
            val session = ByteArray(8).also { SecureRandom().nextBytes(it) }
            ctl.setClientSession(session)
            ctl.sendRaw(frame(OvpnProbePacket.request(session)))
            // Server reset reply.
            val reply = ctl.readFrame(deadline) ?: return SessionVerdict(1, 0, null, "no_reset")
            if (!OvpnProbePacket.accepts(reply, session)) return SessionVerdict(1, 0, null, "bad_reset")
            ctl.serverSession = OvpnProbePacket.serverSession(reply)
            ctl.ackServer(OvpnProbePacket.packetId(reply))
            ctl.noteServerPacket(0L)

            // TLS handshake, driven to FINISHED.
            val tlsRes = runTls(ctl, caPem, certPem, keyPem, deadline)
            if (!tlsRes.handshakeDone) {
                // A flight arrived but the handshake never finished:
                // the path is open (1st circle green) but no session.
                val tls = if (tlsRes.flightSeen) 2 else 1
                return SessionVerdict(tls, 0, null, tlsRes.detail)
            }
            // Handshake done = TLS flight proven. Now the session.
            if (needsAuth && username.isBlank()) {
                return SessionVerdict(2, 0, null, "no_creds")
            }
            val engine = tlsRes.engine ?: return SessionVerdict(2, 0, null, "tls_engine_gone")
            val keyEx = buildKeyExchange(username, password, needsAuth, cipher, auth)
            ctl.sendTlsApp(engine, keyEx, deadline)
            // Read app data until the server's key exchange, an
            // AUTH_FAILED, or the deadline. The server's key exchange
            // starts with 4 zero bytes + 0x02.
            val app = AppReader(ctl, engine, deadline)
            var sawServerKeyEx = false
            var verdict: SessionVerdict? = null
            // v1.12: the key exchange gets the FULL remaining
            // deadline, not a fixed 3s — on a slow line the
            // server's key exchange can take longer than 3s,
            // and the old cap called a working location
            // "no_keyex" (red) when it only needed more time
            // (item 9 probe check).
            val keyExDeadline = deadline
            while (System.currentTimeMillis() < keyExDeadline && verdict == null) {
                val chunk = app.readMore() ?: break
                val text = app.textSoFar()
                if (text.contains("AUTH_FAILED")) {
                    verdict = SessionVerdict(2, -1, null, "auth_failed")
                } else if (!sawServerKeyEx && chunkLooksLikeKeyEx(app)) {
                    sawServerKeyEx = true
                }
                if (sawServerKeyEx) break
            }
            if (verdict != null) return verdict
            if (!sawServerKeyEx) {
                return SessionVerdict(2, -1, null, "no_keyex")
            }
            // PUSH_REQUEST, then wait for the reply.
            ctl.sendTlsApp(engine, "PUSH_REQUEST\u0000".toByteArray(Charsets.UTF_8), deadline)
            // v1.12: same — the PUSH_REPLY gets the full
            // remaining deadline (item 9 probe check).
            val pushDeadline = deadline
            while (System.currentTimeMillis() < pushDeadline) {
                app.readMore() ?: break
                val text = app.textSoFar()
                if (text.contains("AUTH_FAILED")) {
                    return SessionVerdict(2, -1, null, "auth_failed")
                }
                val idx = text.indexOf("PUSH_REPLY")
                if (idx >= 0) {
                    val ipAssigned = parseIfconfig(text.substring(idx))
                    return if (ipAssigned != null) {
                        SessionVerdict(2, 1, ipAssigned, "session_ok")
                    } else {
                        SessionVerdict(2, -1, null, "push_no_ip")
                    }
                }
            }
            return SessionVerdict(2, -1, null, "no_push")
        } catch (_: Exception) {
            return SessionVerdict(1, 0, null, "error")
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    /** The assigned IP in a PUSH_REPLY ("ifconfig 10.x.x.x ..."). */
    fun parseIfconfig(pushReply: String): String? {
        val m = Regex("ifconfig\\s+([0-9]{1,3}(?:\\.[0-9]{1,3}){3})").find(pushReply)
        return m?.groupValues?.getOrNull(1)
    }

    private fun chunkLooksLikeKeyEx(app: AppReader): Boolean {
        val b = app.bytesSoFar()
        return b.size >= 5 && b[0] == 0.toByte() && b[1] == 0.toByte() &&
            b[2] == 0.toByte() && b[3] == 0.toByte() && b[4] == 0x02.toByte()
    }

    // ---------- key exchange ----------

    /** The client's key-method-2 message (see the file header). Random
     *  key material is fresh every probe; the options string is built
     *  from the profile's own cipher/auth. Credentials are appended
     *  only when the profile needs auth AND has a username. */
    fun buildKeyExchange(
        username: String, password: String, needsAuth: Boolean,
        cipher: String, auth: String
    ): ByteArray {
        val rnd = SecureRandom()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, 0))
        out.write(0x02)
        val preMaster = ByteArray(48).also { rnd.nextBytes(it) }
        val random1 = ByteArray(32).also { rnd.nextBytes(it) }
        val random2 = ByteArray(32).also { rnd.nextBytes(it) }
        out.write(preMaster)
        out.write(random1)
        out.write(random2)
        val options = ("V4,dev-type tun,link-mtu 1550,tun-mtu 1500,proto tcp-client" +
            ",cipher $cipher,auth $auth,keysize 256,key-method 2,tls-client")
        val optBytes = (options + "\u0000").toByteArray(Charsets.UTF_8)
        out.write(byteArrayOf((optBytes.size ushr 8).toByte(), optBytes.size.toByte()))
        out.write(optBytes)
        if (needsAuth && username.isNotBlank()) {
            val u = (username + "\u0000").toByteArray(Charsets.UTF_8)
            out.write(byteArrayOf((u.size ushr 8).toByte(), u.size.toByte()))
            out.write(u)
            val p = (password + "\u0000").toByteArray(Charsets.UTF_8)
            out.write(byteArrayOf((p.size ushr 8).toByte(), p.size.toByte()))
            out.write(p)
        }
        return out.toByteArray()
    }

    // ---------- TLS driver ----------

    private data class TlsResult(
        val handshakeDone: Boolean,
        val flightSeen: Boolean,
        val detail: String,
        val engine: SSLEngine?
    )

    private fun buildSslContext(caPem: String?, certPem: String?, keyPem: String?): SSLContext {
        val trustManagers = try {
            if (!caPem.isNullOrBlank()) {
                val cf = CertificateFactory.getInstance("X.509")
                val cert = cf.generateCertificate(
                    java.io.ByteArrayInputStream(caPem.toByteArray(Charsets.UTF_8))) as X509Certificate
                val ks = KeyStore.getInstance(KeyStore.getDefaultType())
                ks.load(null, null)
                ks.setCertificateEntry("ca", cert)
                val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                tmf.init(ks)
                tmf.trustManagers
            } else null
        } catch (_: Exception) { null }
        val keyManagers = try {
            if (!certPem.isNullOrBlank() && !keyPem.isNullOrBlank() && !keyPem.contains("ENCRYPTED")) {
                val cf = CertificateFactory.getInstance("X.509")
                val cert = cf.generateCertificate(
                    java.io.ByteArrayInputStream(certPem.toByteArray(Charsets.UTF_8))) as X509Certificate
                val keyBytes = decodePemKey(keyPem) ?: throw IllegalArgumentException("bad key")
                val key = try {
                    KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
                } catch (_: Exception) {
                    KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
                }
                val ks = KeyStore.getInstance(KeyStore.getDefaultType())
                ks.load(null, null)
                ks.setKeyEntry("client", key, CharArray(0),
                    arrayOf<java.security.cert.Certificate>(cert))
                val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                kmf.init(ks, CharArray(0))
                kmf.keyManagers
            } else null
        } catch (_: Exception) { null }
        return SSLContext.getInstance("TLS").apply { init(keyManagers, trustManagers, SecureRandom()) }
    }

    private fun decodePemKey(pem: String): ByteArray? {
        return try {
            val body = pem.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("-----") }
                .joinToString("")
            android.util.Base64.decode(body, android.util.Base64.DEFAULT)
        } catch (_: Exception) { null }
    }

    /** Drive the SSLEngine to FINISHED over the control channel. Returns
     *  the live engine (kept for the key exchange + PUSH) on success. */
    private fun runTls(
        ctl: CtlChannel, caPem: String?, certPem: String?, keyPem: String?,
        deadline: Long
    ): TlsResult {
        var flightSeen = false
        try {
            val ctx = buildSslContext(caPem, certPem, keyPem)
            val engine = ctx.createSSLEngine().apply { useClientMode = true }
            engine.beginHandshake()
            val netIn = ByteArrayOutputStream() // encrypted bytes waiting to unwrap
            var lastProgress = System.currentTimeMillis()
            var retransmits = 0
            while (System.currentTimeMillis() < deadline) {
                when (engine.handshakeStatus) {
                    SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                        var task = engine.delegatedTask
                        while (task != null) {
                            try { task.run() } catch (_: Exception) {}
                            task = engine.delegatedTask
                        }
                        lastProgress = System.currentTimeMillis()
                    }
                    SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                        val out = ByteBuffer.allocate(engine.session.packetBufferSize)
                        val res = try {
                            engine.wrap(ByteBuffer.allocate(0), out)
                        } catch (e: Exception) {
                            return TlsResult(false, flightSeen, "tls_wrap_failed", null)
                        }
                        out.flip()
                        if (out.hasRemaining()) {
                            val bytes = ByteArray(out.remaining()).also { out.get(it) }
                            ctl.sendTlsRecords(bytes, deadline)
                            lastProgress = System.currentTimeMillis()
                        }
                        if (res.handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED ||
                            res.handshakeStatus == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                            return TlsResult(true, true, "tls_ok", engine)
                        }
                        if (res.status == SSLEngineResult.Status.CLOSED) {
                            return TlsResult(false, flightSeen, "tls_closed", null)
                        }
                    }
                    SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                        if (netIn.size() > 0) {
                            val data = netIn.toByteArray()
                            netIn.reset()
                            val res = try {
                                engine.unwrap(ByteBuffer.wrap(data), ByteBuffer.allocate(engine.session.applicationBufferSize))
                            } catch (e: Exception) {
                                return TlsResult(false, flightSeen, "tls_failed", null)
                            }
                            if (res.status == SSLEngineResult.Status.OK ||
                                res.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                                flightSeen = true
                                lastProgress = System.currentTimeMillis()
                            }
                            if (res.status == SSLEngineResult.Status.CLOSED) {
                                return TlsResult(false, flightSeen, "tls_closed", null)
                            }
                            if (res.handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED ||
                                res.handshakeStatus == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                                return TlsResult(true, true, "tls_ok", engine)
                            }
                            // BUFFER_UNDERFLOW with no bytes left: read more below.
                            if (res.bytesConsumed() == 0 && res.bytesProduced() == 0) {
                                // fall through to read
                            } else {
                                continue
                            }
                        }
                        val pkt = ctl.readFrame(deadline)
                        if (pkt == null) {
                            // Silence: retransmit unacked packets, then give up.
                            if (System.currentTimeMillis() - lastProgress > RETRANSMIT_MS &&
                                retransmits < MAX_RETRANSMITS) {
                                retransmits++
                                ctl.resendUnacked()
                                lastProgress = System.currentTimeMillis()
                                continue
                            }
                            return TlsResult(false, flightSeen, "tls_timeout", null)
                        }
                        val tls = ctl.tlsBytesOf(pkt)
                        if (tls != null && tls.isNotEmpty()) {
                            flightSeen = true
                            netIn.write(tls)
                            lastProgress = System.currentTimeMillis()
                        }
                    }
                    SSLEngineResult.HandshakeStatus.FINISHED,
                    SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING -> {
                        return TlsResult(true, true, "tls_ok", engine)
                    }
                    else -> return TlsResult(false, flightSeen, "tls_state", null)
                }
            }
            return TlsResult(false, flightSeen, "tls_timeout", null)
        } catch (_: Exception) {
            return TlsResult(false, flightSeen, "tls_error", null)
        }
    }

    // ---------- app-data reader (post-handshake) ----------

    /** Accumulates decrypted TLS application data (key exchange reply,
     *  PUSH_REPLY, AUTH_FAILED) across control packets / TLS records. */
    private class AppReader(
        private val ctl: CtlChannel,
        private val engine: SSLEngine,
        private val deadline: Long
    ) {
        private val netIn = ByteArrayOutputStream()
        private val appOut = ByteArrayOutputStream()
        private var lastProgress = System.currentTimeMillis()
        private var retransmits = 0

        fun bytesSoFar(): ByteArray = appOut.toByteArray()
        fun textSoFar(): String = try {
            String(appOut.toByteArray(), Charsets.UTF_8)
        } catch (_: Exception) { "" }

        /** Read until some app data arrives (or the deadline / silence).
         *  Returns the newly arrived app bytes, or null when nothing
         *  more will come. */
        fun readMore(): ByteArray? {
            while (System.currentTimeMillis() < deadline) {
                if (netIn.size() > 0) {
                    val data = netIn.toByteArray()
                    netIn.reset()
                    val before = appOut.size()
                    try {
                        var buf = ByteBuffer.wrap(data)
                        while (buf.hasRemaining()) {
                            val out = ByteBuffer.allocate(engine.session.applicationBufferSize)
                            val res = engine.unwrap(buf, out)
                            out.flip()
                            if (out.hasRemaining()) {
                                val b = ByteArray(out.remaining()).also { out.get(it) }
                                appOut.write(b)
                            }
                            if (res.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) break
                            if (res.status != SSLEngineResult.Status.OK) break
                            if (res.bytesConsumed() == 0 && res.bytesProduced() == 0) break
                        }
                    } catch (_: Exception) {
                        return null
                    }
                    if (appOut.size() > before) {
                        lastProgress = System.currentTimeMillis()
                        return appOut.toByteArray().copyOfRange(before, appOut.size())
                    }
                }
                val pkt = ctl.readFrame(deadline) ?: return null
                val tls = ctl.tlsBytesOf(pkt)
                if (tls != null && tls.isNotEmpty()) {
                    netIn.write(tls)
                    lastProgress = System.currentTimeMillis()
                    continue
                }
                if (System.currentTimeMillis() - lastProgress > RETRANSMIT_MS &&
                    retransmits < MAX_RETRANSMITS) {
                    retransmits++
                    ctl.resendUnacked()
                    lastProgress = System.currentTimeMillis()
                }
            }
            return null
        }
    }

    // ---------- control channel ----------

    /** One OpenVPN-over-TCP control channel: framing, packet ids, acks,
     *  retransmit store. The reset is sent raw by the caller; everything
     *  after it goes through here. */
    private class CtlChannel(
        private val socket: Socket,
        private val splitWrite: Boolean
    ) {
        private val out = socket.getOutputStream()
        private val input = DataInputStream(socket.getInputStream())
        var serverSession: ByteArray = ByteArray(8)
        private var nextId = 1L
        private var lastServerId = -1L
        private val seenServerIds = HashSet<Long>()
        private val unacked = LinkedHashMap<Long, ByteArray>()
        private var bytesWritten = 0L
        private val rng = SecureRandom()

        /** The server packet id our next control packet acknowledges
         *  (-1 = none yet). */
        private var ackId: Long = -1L

        fun ackServer(id: Long) { ackId = id }

        fun noteServerPacket(id: Long) {
            lastServerId = maxOf(lastServerId, id)
        }

        /** Send a control packet (chunked): P_CONTROL_V1 carrying
         *  [payload], acking the server's last packet id. */
        private fun sendControl(payload: ByteArray) {
            val id = nextId++
            val pkt = OvpnProbePacket.control(
                lastClientSession.copyOf(),
                ackId, serverSession, id, payload)
            val framed = frame(pkt)
            unacked[id] = framed
            writeBytes(framed)
        }

        // Our session id, set by the caller before the first control
        // packet (the reset's session).
        var lastClientSession: ByteArray = ByteArray(8)

        fun setClientSession(s: ByteArray) { lastClientSession = s.copyOf() }

        fun sendRaw(bytes: ByteArray) = writeBytes(bytes)

        private fun writeBytes(bytes: ByteArray) {
            if (splitWrite && bytesWritten < 6144) {
                // The relay's cutting: first byte alone, then pieces
                // of 3–29 bytes a few ms apart, until 6 KB are out.
                var at = 0
                var first = true
                while (at < bytes.size) {
                    val remaining = 6144 - bytesWritten
                    if (remaining <= 0) break
                    val size = if (first) 1 else minOf(3 + rng.nextInt(27), remaining.toInt())
                    first = false
                    val end = minOf(bytes.size, at + size)
                    out.write(bytes, at, end - at)
                    out.flush()
                    bytesWritten += (end - at)
                    at = end
                    if (at < bytes.size && bytesWritten < 6144) Thread.sleep(2L + rng.nextInt(6))
                }
                if (at < bytes.size) {
                    out.write(bytes, at, bytes.size - at)
                    out.flush()
                    bytesWritten += (bytes.size - at)
                }
            } else {
                out.write(bytes)
                out.flush()
                bytesWritten += bytes.size
            }
        }

        /** Send TLS records as control packets (chunked to CTL_CHUNK). */
        fun sendTlsRecords(records: ByteArray, deadline: Long) {
            var at = 0
            while (at < records.size) {
                val end = minOf(records.size, at + CTL_CHUNK)
                sendControl(records.copyOfRange(at, end))
                at = end
            }
        }

        /** Wrap TLS application data and send it as control packets. */
        fun sendTlsApp(engine: SSLEngine, plain: ByteArray, deadline: Long) {
            val outBuf = ByteBuffer.allocate(engine.session.packetBufferSize)
            val res = engine.wrap(ByteBuffer.wrap(plain), outBuf)
            outBuf.flip()
            if (outBuf.hasRemaining()) {
                val bytes = ByteArray(outBuf.remaining()).also { outBuf.get(it) }
                sendTlsRecords(bytes, deadline)
            }
            // A post-handshake wrap can itself need a second flight
            // (e.g. a session ticket); drain NEED_WRAP once.
            var guard = 0
            while (res.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP && guard++ < 3) {
                val b2 = ByteBuffer.allocate(engine.session.packetBufferSize)
                engine.wrap(ByteBuffer.allocate(0), b2)
                b2.flip()
                if (!b2.hasRemaining()) break
                val bytes = ByteArray(b2.remaining()).also { b2.get(it) }
                sendTlsRecords(bytes, deadline)
            }
        }

        fun resendUnacked() {
            for ((_, framed) in unacked) {
                try { writeBytes(framed) } catch (_: Exception) {}
            }
        }

        /** Read one framed packet (2-byte length + payload), or null on
         *  timeout / EOF / deadline. The socket timeout is set from the
         *  remaining time on every call. */
        fun readFrame(deadline: Long): ByteArray? {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return null
            return try {
                socket.soTimeout = remaining.coerceIn(1, 30000).toInt()
                val length = input.readUnsignedShort()
                if (length !in 1..4096) return null
                ByteArray(length).also { input.readFully(it) }
            } catch (_: Exception) {
                null
            }
        }

        /** The TLS bytes a server packet carries (null when it carries
         *  none / is not a control packet). Updates ack + dedup state:
         *  duplicate server packets (retransmits) return their payload
         *  once only — the caller must still ACK them, which the next
         *  control packet does via ackId. */
        fun tlsBytesOf(packet: ByteArray): ByteArray? {
            if (packet.isEmpty()) return null
            val op = OvpnProbePacket.opcode(packet)
            if (op == 4) {
                val count = if (packet.size > 9) packet[9].toInt() and 0xff else 0
                val at = 10 + count * 4 + (if (count > 0) 8 else 0)
                if (packet.size < at + 4) return null
                val pid = (0 until 4).fold(0L) { acc, i ->
                    (acc shl 8) or (packet[at + i].toLong() and 0xff)
                }
                // Ack array: ids the server has seen from us.
                for (i in 0 until count) {
                    var ack = 0L
                    for (j in 0 until 4) ack = (ack shl 8) or (packet[10 + i * 4 + j].toLong() and 0xff)
                    unacked.remove(ack)
                }
                ackServer(pid)
                noteServerPacket(pid)
                if (!seenServerIds.add(pid)) return ByteArray(0)
                return OvpnProbePacket.controlPayload(packet)
            }
            if (op == 8) {
                // Server reset reply (also arrives here when the caller
                // reads it through readFrame instead of the first read).
                val count = if (packet.size > 9) packet[9].toInt() and 0xff else 0
                val at = 10 + count * 4 + 8
                if (packet.size >= at + 4) {
                    val pid = (0 until 4).fold(0L) { acc, i ->
                        (acc shl 8) or (packet[at + i].toLong() and 0xff)
                    }
                    ackServer(pid)
                    noteServerPacket(pid)
                }
                return null
            }
            if (op == 9) {
                // P_ACK_V1: ack array only.
                val count = if (packet.size > 9) packet[9].toInt() and 0xff else 0
                for (i in 0 until count) {
                    var ack = 0L
                    for (j in 0 until 4) ack = (ack shl 8) or (packet[10 + i * 4 + j].toLong() and 0xff)
                    unacked.remove(ack)
                }
                return null
            }
            return null
        }
    }

    private fun frame(packet: ByteArray) =
        byteArrayOf((packet.size ushr 8).toByte(), packet.size.toByte()) + packet
}
