package com.renamer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocketFactory

/** 1.45 — free WARP (MASQUE) identity: the app's OWN
 *  Cloudflare WARP registration, built exactly the
 *  way Asha Guard does it, minus the parts we do
 *  not copy (no aether core, no Psiphon keys).
 *
 *  Formula (proven on the VM 2026-11-19, registration
 *  POST 200 in ~1.2s, enrolment PATCH 200 in ~1.4s):
 *  LOCAL KEY + ENROLMENT. The client generates the
 *  keys locally and enrols them — the server never
 *  generates a key for us:
 *  - WireGuard identity: local X25519 keypair; POST
 *    /v0a4471/reg {key: <pub b64>, key_type
 *    "curve25519", tunnel_type "wireguard"} returns
 *    id + token + config (interface addresses,
 *    peers[0].public_key = bmXOC+... , endpoint,
 *    client_id). The WG private key stays local.
 *  - MASQUE identity: local ECDSA P-256 keypair;
 *    PATCH /reg/{id} {key: <SPKI b64>, key_type
 *    "secp256r1", tunnel_type "masque"}.
 *  - Then PATCH /reg/{id} {"warp_enabled": true}.
 *
 *  Two corrections vs the first (wrong) assumption,
 *  both proven against the shipped core source
 *  (Xray v26.10.21, commit 1b1647c) — CORRECTED AGAIN
 *  in 1.46, after the 1.45 device failure:
 *  - warp.publicKey is the ENROLLED CLIENT SPKI (the
 *    same local P-256 key registered via PATCH
 *    /reg/{id}), exactly like Asha (account.rs:590:
 *    public_key = SPKI of the local key). It is NOT
 *    the bmXOC+... WireGuard peer key — and NOT the
 *    live leaf SPKI from a gateway handshake either:
 *    1.45 pinned the live leaf, but warp.go:60-73
 *    compares the leaf byte-for-byte, and the leaf the
 *    JDK handshake sees can differ from the one the
 *    uTLS-Chrome dial sees, which kills EVERY gateway
 *    uniformly. In 1.46 the SPKI check is skipped
 *    entirely via verifyPeerCertByName (it sets
 *    InsecureSkipVerify=true, so warp.go:57-59 returns
 *    early while the client cert is still sent); the
 *    leaf is still chain-validated for the SNI name.
 *    The live leaf SPKI is now diagnostic only.
 *  - serverName is www.cloudflare.com (Asha-proven
 *    against the real edge). The Xray test's
 *    consumer-masque.cloudflareclient.com is proven
 *    only against a mock server.
 *  - "allowInsecure" does NOT exist in this core:
 *    setting it fails config load (removed feature).
 *    Do NOT re-add it.
 *
 *  Storage: app-private file, MODE_PRIVATE (0600),
 *  atomic write (temp + rename). NEVER in a log:
 *  every log line here carries only stages and
 *  HTTP statuses — no id, token or key, ever.
 *  No device ID anywhere: the serial is a random
 *  8-byte hex string, like Asha's. */
object WarpIdentity {

    const val REG_URL = "https://api.cloudflareclient.com/v0a4471/reg"
    // 1.46: SNI is www.cloudflare.com — Asha-proven
    // against the REAL edge (aether consts.rs:8,
    // quic.rs:799-820; consumer-masque... is only noted
    // there as "the SNI the official WARP client sends",
    // NOT used). 1.45 used consumer-masque... from the
    // Xray test, which is proven only against a MOCK
    // server. Same value is the verifyPeerCertByName
    // below (chain validation for this DNS name).
    const val MASQUE_SNI = "www.cloudflare.com"
    const val TRACE_SNI = "www.cloudflare.com"
    const val MASQUE_HOST = "cloudflareaccess.com"
    const val MASQUE_PATH = "/"
    private const val FILE_MAIN = "warp_identity.json"
    private const val FILE_GOOL = "warp_identity_gool.json"
    /** Enrolled MASQUE key lifetime we assume (the
     *  registration account ttl is ~90 days); renew
     *  when under 7 days are left, like Asha. */
    private const val MASQUE_KEY_TTL_MS = 90L * 24 * 60 * 60 * 1000
    private const val RENEW_BEFORE_MS = 7L * 24 * 60 * 60 * 1000

    data class Identity(
        val deviceId: String,
        val token: String,
        val wgPrivateKeyB64: String,
        val wgPeerPublicKeyB64: String,
        val wgPeerEndpointV4: String,
        val clientId: String,
        val ipv4: String,
        val ipv6: String,
        val masquePrivateKeyB64: String,
        val masquePublicSpkiB64: String,
        val masqueEnrolledAt: Long,
        val masqueExpiresAt: Long
    ) {
        fun masqueKeyValid(now: Long = System.currentTimeMillis()): Boolean =
            masquePrivateKeyB64.isNotBlank() && masqueExpiresAt - now > RENEW_BEFORE_MS

        fun toJson(): JSONObject = JSONObject().apply {
            put("device_id", deviceId)
            put("access_token", token)
            put("wg_private_key", wgPrivateKeyB64)
            put("wg_peer_public_key", wgPeerPublicKeyB64)
            put("wg_peer_endpoint_v4", wgPeerEndpointV4)
            put("client_id", clientId)
            put("ipv4", ipv4)
            put("ipv6", ipv6)
            put("masque_private_key", masquePrivateKeyB64)
            put("masque_public_spki", masquePublicSpkiB64)
            put("masque_enrolled_at", masqueEnrolledAt)
            put("masque_expires_at", masqueExpiresAt)
        }

        companion object {
            fun fromJson(j: JSONObject): Identity? = try {
                Identity(
                    deviceId = j.optString("device_id", ""),
                    token = j.optString("access_token", ""),
                    wgPrivateKeyB64 = j.optString("wg_private_key", ""),
                    wgPeerPublicKeyB64 = j.optString("wg_peer_public_key", ""),
                    wgPeerEndpointV4 = j.optString("wg_peer_endpoint_v4", ""),
                    clientId = j.optString("client_id", ""),
                    ipv4 = j.optString("ipv4", ""),
                    ipv6 = j.optString("ipv6", ""),
                    masquePrivateKeyB64 = j.optString("masque_private_key", ""),
                    masquePublicSpkiB64 = j.optString("masque_public_spki", ""),
                    masqueEnrolledAt = j.optLong("masque_enrolled_at", 0L),
                    masqueExpiresAt = j.optLong("masque_expires_at", 0L)
                ).takeIf { it.deviceId.isNotBlank() && it.token.isNotBlank() }
            } catch (_: Exception) { null }
        }
    }

    private fun fileName(tag: String) = if (tag == "gool") FILE_GOOL else FILE_MAIN

    fun load(ctx: Context, tag: String = "main"): Identity? =
        loadWithReason(ctx, tag).first

    /** 1.52 — WHY is there no identity? The 00:23 open
     *  question (the user deleted "something", never said
     *  what): deleting a config row provably does NOT touch
     *  this file (nothing outside this file references it),
     *  so when load() returns null the log must now say
     *  WHY — file missing / parse broken / empty id+token —
     *  instead of failing silently. Second = the reason,
     *  null when an identity was loaded. */
    fun loadWithReason(ctx: Context, tag: String = "main"): Pair<Identity?, String?> {
        return try {
            val f = File(ctx.filesDir, fileName(tag))
            if (!f.exists()) return null to "file missing (${fileName(tag)})"
            val text = f.readText()
            val idn = Identity.fromJson(JSONObject(text))
            if (idn == null) {
                null to "parse broken: ${fileName(tag)} unreadable or empty id+token"
            } else {
                idn to null
            }
        } catch (e: Exception) {
            null to "parse broken: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** 1.52 — the REAL registration failure (never a bare
     *  -1): "DNS: ...", "TCP connect timed out", "TLS: ...",
     *  "HTTP 400", ... Set on every register/enroll failure;
     *  runFetch shows it when registration fails. */
    @Volatile var lastRegisterError: String? = null
        private set

    /** 1.60 — ONE registration per tag, ever, no
     *  matter how many threads ask at once. The 1.59
     *  device failure: the fetch registered account
     *  #1 at 15:33 while the background sweep's
     *  warpScanOnce raced it through
     *  AetherBridge.ensureIdentityFile and registered
     *  account #2 — two device ids, ~2 min of slow
     *  PATCH legs (the second caller used the
     *  default REG_HOST SNI, not the verified one),
     *  and the test phase starved to a 0s AETHER
     *  budget. The guard serializes the
     *  load→register check-then-act per tag and
     *  double-checks the load after acquiring the
     *  lock, so a later caller reuses the first
     *  caller's saved identity instead of
     *  registering again. Pure (no Android) so the
     *  JVM test races it with real threads. */
    object RegistrationGuard {
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
        fun <T> ensureOnce(key: String, load: () -> T?, register: () -> T?): T? {
            load()?.let { return it }
            val lock = locks.getOrPut(key) { Any() }
            synchronized(lock) {
                load()?.let { return it }
                return register()
            }
        }
    }

    private val saveLock = Any()

    private fun save(ctx: Context, tag: String, id: Identity) {
        // 1.60 — the save is atomic per tag even when
        // two threads reach it (the tmp file name is
        // per tag; a concurrent rename used to be
        // able to leave a half-written identity).
        synchronized(saveLock) {
            try {
                val dir = ctx.filesDir
                val tmp = File(dir, fileName(tag) + ".tmp")
                val dst = File(dir, fileName(tag))
                ctx.openFileOutput(tmp.name, Context.MODE_PRIVATE).use {
                    it.write(id.toJson().toString().toByteArray(Charsets.UTF_8))
                }
                if (dst.exists()) dst.delete()
                tmp.renameTo(dst)
            } catch (_: Exception) {}
        }
    }

    private fun wlog(ctx: Context, fa: String, en: String) {
        AppLog.append(ctx, "warp", fa, en)
    }

    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)

    private fun randomSerialHex(): String {
        val b = ByteArray(8)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02X".format(it) }
    }

    private fun nowIso(): String = try {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        f.format(Date())
    } catch (_: Exception) { "" }

    // ---- X25519 (RFC 7748), pure Kotlin/BigInteger ----
    // No XDH KeyAgreement below API 31 and no
    // Curve25519 in the tree, so the Montgomery
    // ladder runs on BigInteger (255-bit mods —
    // a few ms). Verified on the VM against the
    // RFC 7748 §5.2 test vector before shipping.
    @Suppress("UNUSED_VALUE")
    fun x25519ScalarMult(scalar: ByteArray, u: ByteArray): ByteArray {
        val p = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
        fun decode(b: ByteArray): BigInteger {
            val t = b.copyOf()
            t[31] = (t[31].toInt() and 0x7f).toByte()
            var v = BigInteger.ZERO
            for (i in 31 downTo 0) {
                v = v.shiftLeft(8).or(BigInteger.valueOf((t[i].toInt() and 0xff).toLong()))
            }
            return v
        }
        fun encode(v: BigInteger): ByteArray {
            val out = ByteArray(32)
            var x = v
            for (i in 0 until 32) {
                out[i] = x.and(BigInteger.valueOf(0xff)).toByte()
                x = x.shiftRight(8)
            }
            return out
        }
        val k = scalar.copyOf()
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = (k[31].toInt() and 127).toByte()
        k[31] = (k[31].toInt() or 64).toByte()
        var x1 = decode(u)
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0
        val a24 = BigInteger.valueOf(121665)
        for (t in 254 downTo 0) {
            val kt = (k[t / 8].toInt() shr (t % 8)) and 1
            swap = swap xor kt
            if (swap == 1) {
                var tmp = x2; x2 = x3; x3 = tmp
                tmp = z2; z2 = z3; z3 = tmp
            }
            swap = kt
            val a = x2.add(z2).mod(p)
            val aa = a.multiply(a).mod(p)
            val b = x2.subtract(z2).mod(p)
            val bb = b.multiply(b).mod(p)
            val e = aa.subtract(bb).mod(p)
            val c = x3.add(z3).mod(p)
            val d = x3.subtract(z3).mod(p)
            val da = d.multiply(a).mod(p)
            val cb = c.multiply(b).mod(p)
            val s1 = da.add(cb).mod(p)
            x3 = s1.multiply(s1).mod(p)
            val s2 = da.subtract(cb).mod(p)
            z3 = x1.multiply(s2.multiply(s2).mod(p)).mod(p)
            x2 = aa.multiply(bb).mod(p)
            z2 = e.multiply(aa.add(a24.multiply(e))).mod(p)
        }
        if (swap == 1) {
            var tmp = x2; x2 = x3; x3 = tmp
            tmp = z2; z2 = z3; z3 = tmp
        }
        return encode(x2.multiply(z2.modInverse(p)).mod(p))
    }

    private fun generateX25519(): Pair<String, String> {
        val priv = ByteArray(32)
        SecureRandom().nextBytes(priv)
        val pub = x25519ScalarMult(priv, ByteArray(32).also { it[0] = 9 })
        return b64(priv) to b64(pub)
    }

    private fun generateP256(): Pair<String, String> {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val kp = kpg.generateKeyPair()
        return b64(kp.private.encoded) to b64(kp.public.encoded)
    }

    /** 1.53 — is ANY VPN up, at the SYSTEM level: our own
     *  service OR another app's (TRANSPORT_VPN on the
     *  active network — the same pattern as
     *  SmartConnectService.anyVpnUp() and
     *  CloudflareActivity.isVpnOn()). 1.52 only asked our
     *  own VpnClientService, so a foreign VPN counted as
     *  "no VPN" and the message lied. */
    fun isAnyVpnOn(ctx: Context): Boolean {
        try { if (VpnClientService.isRunning) return true } catch (_: Exception) {}
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } catch (_: Exception) { false }
    }

    // ---- HTTP to the public Cloudflare API ----
    private fun apiCall(
        url: String,
        method: String,
        body: String?,
        token: String?,
        proxy: Proxy?,
        timeoutMs: Int = 15000
    ): Pair<Int, String?> {
        var conn: HttpsURLConnection? = null
        return try {
            val u = URL(url)
            conn = (if (proxy != null) u.openConnection(proxy) else u.openConnection())
                as HttpsURLConnection
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.setRequestProperty("User-Agent", WarpReg.UA)
            conn.setRequestProperty("CF-Client-Version", WarpReg.CF_VER)
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val text = try {
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                stream?.bufferedReader()?.readText()
            } catch (_: Exception) { null }
            code to text
        } catch (e: Exception) {
            -1 to (e.message ?: e.javaClass.simpleName)
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /** 1.52 — ONE API call to a CLEAN IP, not to the
     *  hostname: a raw TLS socket to <cleanIp>:443 with SNI
     *  api.cloudflareclient.com and Host: api.cloudflareclient.com
     *  (exactly what aether's AETHER_ENROLL_ADDRESS does —
     *  account.rs: only the CONNECTION goes to the IP, the
     *  API's name stays the server name and the HTTP host).
     *  1.53: `path` here is the FULL path (WarpReg.REG_PATH + suffix) —
     *  1.52 passed only the suffix and the edge answered 400.
     *  1.54: [sniHost] is the SNI name AND the Host header
     *  (primary api.cloudflareclient.com, or the alternate
     *  api.devices.cloudflare.com leg — VM-proven 200).
     *  The chain IS validated (default SSLSocketFactory);
     *  hostname verification is HttpsURLConnection's job and
     *  does not apply to a raw socket — the SNI name is what
     *  the server presents. "Connection: close" forces EOF so
     *  the body can be read without chunked parsing. Returns
     *  (HTTP code, body) — or (-1, the REAL error text). */
    fun apiCallCleanIp(
        method: String,
        path: String,
        body: String?,
        token: String?,
        cleanIp: String,
        timeoutMs: Int = 15000,
        sniHost: String = WarpReg.REG_HOST
    ): Pair<Int, String?> {
        var sock: javax.net.ssl.SSLSocket? = null
        return try {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val s = factory.createSocket() as javax.net.ssl.SSLSocket
            sock = s
            s.connect(InetSocketAddress(cleanIp, 443), timeoutMs)
            s.soTimeout = timeoutMs
            val params = SSLParameters()
            params.serverNames = listOf(SNIHostName(sniHost))
            s.sslParameters = params
            s.startHandshake()
            s.outputStream.write(WarpReg.buildCleanIpRequest(method, path, body, token, sniHost).toByteArray(Charsets.UTF_8))
            s.outputStream.flush()
            val raw = s.inputStream.readBytes()
            val text = String(raw, Charsets.UTF_8)
            val headEnd = text.indexOf("\r\n\r\n")
            if (headEnd < 0) return -1 to "bad HTTP reply from $cleanIp"
            val statusLine = text.substringBefore("\r\n")
            val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
            code to text.substring(headEnd + 4).ifBlank { null }
        } catch (e: Exception) {
            -1 to (e.message ?: e.javaClass.simpleName)
        } finally {
            try { sock?.close() } catch (_: Exception) {}
        }
    }

    /** 1.53 — the routed API call: clean IP first (the
     *  address Asha enrolls to), then the hostname, then —
     *  only for registration — the active tunnel's SOCKS.
     *  1.52 REGRESSION FIXED HERE: the clean-IP leg now
     *  gets the FULL path (REG_PATH + suffix) — 1.52 sent
     *  only the suffix ("POST  HTTP/1.1") and the edge
     *  answered 400, which short-circuited the working
     *  hostname leg. A transport failure is code -1 WITH
     *  the real error text; an HTTP error code IS an
     *  answer and is NOT retried on another transport —
     *  but it IS recorded in lastRegisterError with its
     *  body, and ALL leg errors accumulate instead of
     *  overwriting each other.
     *  1.54: [cleanIpSni] is the SNI the enroll probe
     *  VERIFIED for this IP (primary, or the devices
     *  host when only that answered) — leg 1 uses it.
     *  When leg 1 used the primary SNI and both it and
     *  the direct leg fail with transport -1, leg 3
     *  tries the SAME clean IP with SNI+Host
     *  api.devices.cloudflare.com (Asha's shape,
     *  VM-proven 200 on the real edge, reg154probe.py).
     *  An HTTP error on any leg is still an answer and
     *  is never retried on another leg. */
    private fun apiCallRouted(
        ctx: Context,
        method: String,
        path: String,
        body: String?,
        token: String?,
        cleanIp: String?,
        proxy: Proxy?,
        timeoutMs: Int = 15000,
        cleanIpSni: String = WarpReg.REG_HOST
    ): Pair<Int, String?> {
        val fullPath = WarpReg.regPath(path)
        val errors = mutableListOf<String>()
        if (!cleanIp.isNullOrBlank()) {
            val sniNote = if (cleanIpSni != WarpReg.REG_HOST) " (SNI $cleanIpSni)" else ""
            wlog(ctx, "تلاش ثبت‌نام: $method $fullPath از آی‌پی تمیز $cleanIp$sniNote…",
                "Registration attempt: $method $fullPath via clean IP $cleanIp$sniNote…")
            val viaIp = apiCallCleanIp(method, fullPath, body, token, cleanIp, timeoutMs, cleanIpSni)
            if (viaIp.first != -1) {
                if (viaIp.first !in 200..299) lastRegisterError =
                    WarpReg.httpErrorWhy(viaIp.first, viaIp.second, "clean-IP $cleanIp $method $fullPath")
                return viaIp
            }
            errors += WarpReg.legError("clean-IP $cleanIp", "$method $fullPath", viaIp.second)
        }
        wlog(ctx, "تلاش ثبت‌نام: $method $fullPath مستقیم…",
            "Registration attempt: $method $fullPath direct…")
        val direct = apiCall("$REG_URL$path", method, body, token, null, timeoutMs)
        if (direct.first != -1) {
            if (direct.first !in 200..299) lastRegisterError =
                WarpReg.httpErrorWhy(direct.first, direct.second, "direct $method $fullPath")
            return direct
        }
        errors += WarpReg.legError("direct", "$method $fullPath", direct.second)
        // 1.54 — the alternate-SNI leg: same clean IP,
        // SNI+Host api.devices.cloudflare.com. Only when
        // leg 1 used the primary SNI (otherwise leg 1
        // already WAS this shape) and only on transport
        // failure — never after an HTTP answer.
        if (!cleanIp.isNullOrBlank() && cleanIpSni == WarpReg.REG_HOST) {
            wlog(ctx, "تلاش ثبت‌نام: $method $fullPath از آی‌پی تمیز $cleanIp (SNI ${WarpReg.DEVICES_HOST})…",
                "Registration attempt: $method $fullPath via clean IP $cleanIp (SNI ${WarpReg.DEVICES_HOST})…")
            val viaDev = apiCallCleanIp(method, fullPath, body, token, cleanIp, timeoutMs, WarpReg.DEVICES_HOST)
            if (viaDev.first != -1) {
                if (viaDev.first !in 200..299) lastRegisterError =
                    WarpReg.httpErrorWhy(viaDev.first, viaDev.second, "clean-IP-devices $cleanIp $method $fullPath")
                return viaDev
            }
            errors += WarpReg.legError("clean-IP-devices $cleanIp", "$method $fullPath", viaDev.second)
        }
        if (proxy != null) {
            wlog(ctx, "تلاش ثبت‌نام: $method $fullPath از تونل…",
                "Registration attempt: $method $fullPath via tunnel…")
            val via = apiCall("$REG_URL$path", method, body, token, proxy, timeoutMs)
            if (via.first != -1) {
                if (via.first !in 200..299) lastRegisterError =
                    WarpReg.httpErrorWhy(via.first, via.second, "tunnel $method $fullPath")
                return via
            }
            errors += WarpReg.legError("tunnel", "$method $fullPath", via.second)
        }
        lastRegisterError = errors.joinToString("; ")
        return direct
    }

    /** 1.52 — fetch the ECHConfigList over DoH to a clean
     *  IP (Asha: "key request will use <clean IP>"): POST
     *  the DNS HTTPS-record query for cloudflare-ech.com to
     *  https://<cleanIp>/dns-query with SNI + Host
     *  cloudflare-dns.com (the core's own lookup asks the
     *  same record of udp://1.1.1.1). Returns the RAW list
     *  bytes, or null on ANY failure — ECH is opportunistic
     *  and must never block the dial. Only the byte count
     *  is ever logged, never the key. */
    fun fetchEchConfigList(cleanIp: String, timeoutMs: Int = 10000): ByteArray? {
        var sock: javax.net.ssl.SSLSocket? = null
        return try {
            val query = AetherRungPlan.buildDnsHttpsQuery(AetherRungPlan.ECH_DOMAIN)
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val s = factory.createSocket() as javax.net.ssl.SSLSocket
            sock = s
            s.connect(InetSocketAddress(cleanIp, 443), timeoutMs)
            s.soTimeout = timeoutMs
            val params = SSLParameters()
            params.serverNames = listOf(SNIHostName(AetherRungPlan.ECH_DOH_HOST))
            s.sslParameters = params
            s.startHandshake()
            val req = "POST /dns-query HTTP/1.1\r\n" +
                "Host: ${AetherRungPlan.ECH_DOH_HOST}\r\n" +
                "Content-Type: application/dns-message\r\n" +
                "Accept: application/dns-message\r\n" +
                "Content-Length: ${query.size}\r\n" +
                "Connection: close\r\n\r\n"
            s.outputStream.write(req.toByteArray(Charsets.UTF_8))
            s.outputStream.write(query)
            s.outputStream.flush()
            val raw = s.inputStream.readBytes()
            val headEnd = raw.indexOfSequence("\r\n\r\n".toByteArray(Charsets.UTF_8))
            if (headEnd < 0) return null
            val head = String(raw, 0, headEnd, Charsets.UTF_8)
            if (!head.contains(" 200 ")) return null
            val bodyBytes = raw.copyOfRange(headEnd + 4, raw.size)
            AetherRungPlan.parseEchFromDnsResponse(bodyBytes)
        } catch (_: Exception) {
            null
        } finally {
            try { sock?.close() } catch (_: Exception) {}
        }
    }

    private fun ByteArray.indexOfSequence(seq: ByteArray): Int {
        if (seq.isEmpty() || size < seq.size) return -1
        outer@ for (i in 0..size - seq.size) {
            for (j in seq.indices) if (this[i + j] != seq[j]) continue@outer
            return i
        }
        return -1
    }

    /** Register a fresh identity (or return the stored
     *  one). proxy = an already-working tunnel's SOCKS
     *  (127.0.0.1:port) — used only when the direct
     *  API call fails. Returns null with the exact
     *  stage in the log.
     *  1.60 — serialized per tag through
     *  RegistrationGuard (see the guard's note): the
     *  fetch, the background sweep and the AETHER rung
     *  all ask for the same identity, and only the
     *  first caller registers — the rest reuse the
     *  saved file. A renewal (expiring key) runs
     *  in place, never as a second account. */
    fun ensure(ctx: Context, tag: String = "main", socksPort: Int = -1, cleanIp: String? = null, cleanIpSni: String = WarpReg.REG_HOST): Identity? {
        // 1.52 diagnostic, once: why is there no identity?
        val (existing0, loadWhy) = loadWithReason(ctx, tag)
        if (existing0 == null && loadWhy != null) {
            wlog(ctx, "هویت وارپ خوانده نشد: $loadWhy.",
                "WARP identity not loaded: $loadWhy.")
        }
        return RegistrationGuard.ensureOnce(tag,
            load = { load(ctx, tag)?.takeIf { it.masqueKeyValid() } },
            register = {
                val existing = load(ctx, tag)
                if (existing == null) {
                    register(ctx, tag, socksPort, cleanIp, cleanIpSni)
                } else {
                    // Key missing or under 7 days to expiry:
                    // re-enrol in place, but keep the old key
                    // when the enrolment fails and it is still
                    // valid. An expired key that will not
                    // renew returns null, as before — never a
                    // second account.
                    val renewed = enrollMasque(ctx, tag, existing, proxyFor(socksPort), cleanIp, cleanIpSni)
                    if (renewed != null) renewed
                    else if (existing.masquePrivateKeyB64.isNotBlank() &&
                        existing.masqueExpiresAt > System.currentTimeMillis()
                    ) {
                        wlog(ctx,
                            "تمدید کلید MASQUE ناموفق بود؛ کلید قبلی هنوز معتبر است و نگه داشته شد.",
                            "MASQUE key renewal failed; the old key is still valid and was kept.")
                        existing
                    } else null
                }
            })
    }

    private fun proxyFor(socksPort: Int): Proxy? =
        if (socksPort > 0) Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        else null

    private fun register(ctx: Context, tag: String, socksPort: Int, cleanIp: String? = null, cleanIpSni: String = WarpReg.REG_HOST): Identity? {
        wlog(ctx, "ثبت‌نام وارپ: ساخت کلید محلی و ارسال درخواست…",
            "WARP registration: generating local keys, sending request…")
        val (wgPriv, wgPub) = try { generateX25519() } catch (e: Exception) {
            lastRegisterError = "ساخت کلید محلی ممکن نشد"
            wlog(ctx, "ثبت‌نام وارپ ناموفق بود: ساخت کلید محلی ممکن نشد.",
                "WARP registration failed: local key generation failed.")
            return null
        }
        val body = JSONObject().apply {
            put("key", wgPub)
            put("install_id", "")
            put("fcm_token", "")
            put("tos", nowIso())
            put("model", "PC")
            put("serial_number", randomSerialHex())
            put("locale", "en_US")
            put("key_type", "curve25519")
            put("tunnel_type", "wireguard")
        }.toString()
        // 1.53: the VPN state is the SYSTEM state (a foreign
        // VPN counts — 1.52 only asked our own service and
        // told a user WITH a VPN on that registration
        // without a VPN is impossible).
        if (!isAnyVpnOn(ctx)) {
            wlog(ctx, "هویت وارپ وجود ندارد؛ ثبت‌نام مستقیم از آی‌پی تمیز را امتحان می‌کنم.",
                "No WARP identity; trying direct registration from a clean IP.")
        } else {
            wlog(ctx, "VPN فعال است؛ ثبت‌نام وارپ را امتحان می‌کنم.",
                "A VPN is on; trying WARP registration.")
        }
        val (code, text) = apiCallRouted(ctx, "POST", "", body, null, cleanIp, proxyFor(socksPort), 15000, cleanIpSni)
        if (code !in 200..299 || text.isNullOrBlank()) {
            if (lastRegisterError == null) lastRegisterError =
                if (code in 200..299) "پاسخ سرور خالی بود (کد $code)" else "کد $code"
            val why = lastRegisterError ?: "کد $code"
            wlog(ctx, "ثبت‌نام وارپ ناموفق بود: $why.",
                "WARP registration failed: $why.")
            return null
        }
        val reg = try { JSONObject(text) } catch (_: Exception) { null } ?: run {
            lastRegisterError = "پاسخ سرور خوانا نبود"
            wlog(ctx, "ثبت‌نام وارپ ناموفق بود: پاسخ سرور خوانا نبود.",
                "WARP registration failed: the server reply was unreadable.")
            return null
        }
        val id = reg.optString("id", "")
        val token = reg.optString("token", "")
        val config = reg.optJSONObject("config")
        val iface = config?.optJSONObject("interface")?.optJSONObject("addresses")
        val peers = config?.optJSONArray("peers")
        val peer0 = if (peers != null && peers.length() > 0) peers.optJSONObject(0) else null
        val peerPub = peer0?.optString("public_key", "") ?: ""
        val endpointV4 = peer0?.optJSONObject("endpoint")?.optString("v4", "")
            ?.substringBefore(":") ?: ""
        val clientId = config?.optString("client_id", "") ?: ""
        val ipv4 = iface?.optString("v4", "") ?: ""
        val ipv6 = iface?.optString("v6", "") ?: ""
        if (id.isBlank() || token.isBlank() || peerPub.isBlank() || ipv4.isBlank()) {
            lastRegisterError = "پاسخ سرور ناقص بود"
            wlog(ctx, "ثبت‌نام وارپ ناموفق بود: پاسخ سرور ناقص بود.",
                "WARP registration failed: the server reply was incomplete.")
            return null
        }
        wlog(ctx, "[✓] ثبت‌نام وارپ انجام شد (پاسخ ۲۰۰).",
            "[✓] WARP registration done (reply 200).")
        // Enable WARP on the account, then enrol the
        // MASQUE key. Both are PATCH /reg/{id}.
        val enCode = apiCallRouted(ctx, "PATCH", "/$id",
            JSONObject().apply { put("warp_enabled", true) }.toString(),
            token, cleanIp, null, 15000, cleanIpSni).first
        if (enCode in 200..299) {
            wlog(ctx, "[✓] وارپ روی حساب فعال شد (۲۰۰).",
                "[✓] WARP enabled on the account (200).")
        } else {
            wlog(ctx, "فعال‌سازی وارپ جواب نداد (کد $enCode)؛ ادامه با همان حساب.",
                "Enabling WARP did not answer (code $enCode); continuing with the account as-is.")
        }
        val base = Identity(
            deviceId = id, token = token,
            wgPrivateKeyB64 = wgPriv, wgPeerPublicKeyB64 = peerPub,
            wgPeerEndpointV4 = endpointV4, clientId = clientId,
            ipv4 = ipv4, ipv6 = ipv6,
            masquePrivateKeyB64 = "", masquePublicSpkiB64 = "",
            masqueEnrolledAt = 0L, masqueExpiresAt = 0L
        )
        val enrolled = enrollMasque(ctx, tag, base, proxyFor(socksPort), cleanIp, cleanIpSni) ?: run {
            wlog(ctx, "ثبت کلید MASQUE ناموفق بود؛ هویت وایرگارد ذخیره شد و ثبت MASQUE در تلاش بعدی تکرار می‌شود.",
                "MASQUE key enrolment failed; the WireGuard identity was saved and MASQUE enrolment will retry next time.")
            save(ctx, tag, base)
            return base
        }
        return enrolled
    }

    /** Enrol (or renew) the MASQUE P-256 key for an
     *  already-registered identity. Returns the
     *  updated identity, or null on failure. */
    fun enrollMasque(ctx: Context, tag: String, id: Identity, proxy: Proxy?, cleanIp: String? = null, cleanIpSni: String = WarpReg.REG_HOST): Identity? {
        val (privB64, spkiB64) = try { generateP256() } catch (_: Exception) {
            lastRegisterError = "ساخت کلید MASQUE ممکن نشد"
            return null
        }
        val body = JSONObject().apply {
            put("key", spkiB64)
            put("key_type", "secp256r1")
            put("tunnel_type", "masque")
        }.toString()
        // 1.52: clean IP first (same routed call as
        // registration); the failure carries the real error.
        val (code, _) = if (!cleanIp.isNullOrBlank() || proxy == null) {
            apiCallRouted(ctx, "PATCH", "/${id.deviceId}", body, id.token, cleanIp, proxy, 15000, cleanIpSni)
        } else {
            // No clean IP and a tunnel is available: go
            // straight through the tunnel (no direct leg) —
            // but record the failure like every other leg
            // (1.52 called apiCall directly here and
            // lastRegisterError stayed null → "؟").
            val r = apiCall("$REG_URL/${id.deviceId}", "PATCH", body, id.token, proxy)
            if (r.first == -1) lastRegisterError =
                WarpReg.legError("tunnel", "PATCH ${WarpReg.regPath("/${id.deviceId}")}", r.second)
            else if (r.first !in 200..299) lastRegisterError =
                WarpReg.httpErrorWhy(r.first, r.second, "tunnel PATCH ${WarpReg.regPath("/${id.deviceId}")}")
            r
        }
        if (code !in 200..299) {
            val why = lastRegisterError ?: "کد $code"
            wlog(ctx, "ثبت کلید MASQUE ناموفق بود ($why).",
                "MASQUE key enrolment failed ($why).")
            return null
        }
        val now = System.currentTimeMillis()
        val updated = id.copy(
            masquePrivateKeyB64 = privB64,
            masquePublicSpkiB64 = spkiB64,
            masqueEnrolledAt = now,
            masqueExpiresAt = now + MASQUE_KEY_TTL_MS
        )
        save(ctx, tag, updated)
        wlog(ctx, "[✓] کلید MASQUE ثبت شد (۲۰۰).",
            "[✓] MASQUE key enrolled (200).")
        return updated
    }

    /** Capture the MASQUE endpoint's leaf SPKI live:
     *  TLS handshake to the gateway with SNI
     *  www.cloudflare.com. Returns
     *  (spkiBase64, sha256Hex) or null. 1.46: DIAGNOSTIC
     *  ONLY — it proves a TLS handshake answers and its
     *  fingerprint is logged, but the SPKI is NOT used
     *  as a pin any more (see the header note). Only the
     *  fingerprint is ever logged, never the key. */
    fun fetchServerSpki(gatewayIp: String, timeoutMs: Int = 6000): Pair<String, String>? {
        return try { fetchServerSpkiThrowing(gatewayIp, timeoutMs) } catch (_: Exception) { null }
    }

    /** 1.47: like fetchServerSpki, but the failure
     *  reason is returned too — the scan logs WHY a
     *  gateway's TLS failed instead of silently
     *  dropping it. Returns (result, errorMessage). */
    fun fetchServerSpkiWithError(gatewayIp: String, timeoutMs: Int = 6000): Pair<Pair<String, String>?, String?> {
        return try {
            fetchServerSpkiThrowing(gatewayIp, timeoutMs) to null
        } catch (e: Exception) {
            null to (e.message ?: e.javaClass.simpleName)
        }
    }

    /** The handshake itself: throws on failure
     *  (fetchServerSpki swallows it for old callers).
     *  Returns null only when the peer presented no
     *  certificate. */
    private fun fetchServerSpkiThrowing(gatewayIp: String, timeoutMs: Int = 6000): Pair<String, String>? {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val sock = factory.createSocket() as javax.net.ssl.SSLSocket
        try {
            sock.connect(InetSocketAddress(gatewayIp, 443), timeoutMs)
            sock.soTimeout = timeoutMs
            val params = SSLParameters()
            params.serverNames = listOf(SNIHostName(MASQUE_SNI))
            sock.sslParameters = params
            sock.startHandshake()
            val cert = sock.session.peerCertificates.firstOrNull() as? X509Certificate
                ?: return null
            val spkiDer = cert.publicKey.encoded
            val sha = MessageDigest.getInstance("SHA-256").digest(spkiDer)
                .joinToString("") { "%02x".format(it) }
            return b64(spkiDer) to sha
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    /** GET /cdn-cgi/trace from a gateway IP (SNI
     *  www.cloudflare.com — a bare-IP URL gets no
     *  answer). Returns (loc, colo) or null. */
    fun fetchTrace(gatewayIp: String, timeoutMs: Int = 6000): Pair<String, String>? {
        return try {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val sock = factory.createSocket() as javax.net.ssl.SSLSocket
            try {
                sock.connect(InetSocketAddress(gatewayIp, 443), timeoutMs)
                sock.soTimeout = timeoutMs
                val params = SSLParameters()
                params.serverNames = listOf(SNIHostName(TRACE_SNI))
                sock.sslParameters = params
                sock.startHandshake()
                val out = sock.outputStream
                out.write(("GET /cdn-cgi/trace HTTP/1.1\r\nHost: $TRACE_SNI\r\n" +
                    "Connection: close\r\nUser-Agent: InternetAzad2026\r\n\r\n")
                    .toByteArray(Charsets.UTF_8))
                out.flush()
                val buf = ByteArray(8192)
                val sb = StringBuilder()
                var n: Int
                var total = 0
                while (total < 8192) {
                    n = try { sock.inputStream.read(buf) } catch (_: Exception) { -1 }
                    if (n <= 0) break
                    sb.append(String(buf, 0, n, Charsets.UTF_8))
                    total += n
                    if (sb.contains("colo=") && sb.contains("loc=")) break
                }
                parseTrace(sb.toString())
            } finally {
                try { sock.close() } catch (_: Exception) {}
            }
        } catch (_: Exception) { null }
    }

    private fun parseTrace(text: String): Pair<String, String>? {
        var loc = ""
        var colo = ""
        for (line in text.lines()) {
            val t = line.trim()
            if (t.startsWith("loc=")) loc = t.removePrefix("loc=").trim()
            if (t.startsWith("colo=")) colo = t.removePrefix("colo=").trim()
        }
        return if (loc.isNotBlank() || colo.isNotBlank()) loc to colo else null
    }

    /** Trace THROUGH a running tunnel's SOCKS port
     *  (the real exit country after connect). */
    fun fetchTraceThroughSocks(socksPort: Int, timeoutMs: Int = 10000): Pair<String, String>? {
        return try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
            val conn = URL("http://$TRACE_SNI/cdn-cgi/trace").openConnection(proxy)
                as java.net.HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", "InternetAzad2026")
            if (conn.responseCode != 200) {
                try { conn.disconnect() } catch (_: Exception) {}
                return null
            }
            val text = conn.inputStream.bufferedReader().readText()
            try { conn.disconnect() } catch (_: Exception) {}
            parseTrace(text)
        } catch (_: Exception) { null }
    }

    /** 1.69 — (D) the trace WITH the exit IP:
     *  parseTrace returns (loc, colo) only; the
     *  connected-exit log needs the tested IP too
     *  (the trace body's "ip=" line). Returns
     *  (ip, loc, colo) — loc/colo as written in
     *  the trace body (callers normalise). */
    fun parseTraceFull(text: String): Triple<String, String, String>? {
        var ip = ""
        var loc = ""
        var colo = ""
        for (line in text.lines()) {
            val t = line.trim()
            if (t.startsWith("ip=")) ip = t.removePrefix("ip=").trim()
            if (t.startsWith("loc=")) loc = t.removePrefix("loc=").trim()
            if (t.startsWith("colo=")) colo = t.removePrefix("colo=").trim()
        }
        return if (loc.isNotBlank() || colo.isNotBlank() || ip.isNotBlank()) Triple(ip, loc, colo) else null
    }

    /** Trace THROUGH a running tunnel's SOCKS
     *  port, WITH the exit IP: (ip, loc, colo)
     *  or null. The live IP test behind the
     *  1.69 connected-exit log entry. */
    fun fetchTraceFullThroughSocks(socksPort: Int, timeoutMs: Int = 10000): Triple<String, String, String>? {
        return try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
            val conn = URL("http://$TRACE_SNI/cdn-cgi/trace").openConnection(proxy)
                as java.net.HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", "InternetAzad2026")
            if (conn.responseCode != 200) {
                try { conn.disconnect() } catch (_: Exception) {}
                return null
            }
            val text = conn.inputStream.bufferedReader().readText()
            try { conn.disconnect() } catch (_: Exception) {}
            parseTraceFull(text)
        } catch (_: Exception) { null }
    }

    /** Real-traffic check through a running tunnel's
     *  SOCKS port: generate_204 must answer 200/204.
     *  Returns the HTTP code, or -1. */
    fun generate204ThroughSocks(socksPort: Int, timeoutMs: Int = 10000): Int =
        generate204ThroughSocksDetailed(socksPort, timeoutMs).first

    /** 1.61 — the same probe, but the failure carries
     *  the REAL exception ("SocketTimeoutException:
     *  Read timed out", "ConnectException: …") instead
     *  of a bare -1. The 1.60 device run proved why:
     *  generate204ThroughSocks swallowed the exception
     *  (catch (_: Exception) { -1 }), so the log could
     *  only ever say "code -1" while the engine had
     *  already validated the tunnel (connect-ip 200,
     *  SOCKS serving, exitloc IR via FRA) — nobody
     *  could tell a slow first request from a dead
     *  tunnel. Returns (code, error): error is null
     *  on any HTTP answer, the exception text when
     *  no answer came back at all. */
    fun generate204ThroughSocksDetailed(socksPort: Int, timeoutMs: Int = 10000): Pair<Int, String?> {
        return try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
            val conn = URL(XraySpeedtest.TEST_URL).openConnection(proxy)
                as java.net.HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", "InternetAzad2026")
            val code = conn.responseCode
            try { conn.disconnect() } catch (_: Exception) {}
            code to null
        } catch (e: Exception) {
            val msg = e.message?.takeIf { it.isNotBlank() }
            -1 to (if (msg != null) e.javaClass.simpleName + ": " + msg else e.javaClass.simpleName)
        }
    }

    /** 1.61 — one probe attempt's outcome, with how
     *  many attempts it took. */
    data class Generate204Result(val code: Int, val error: String?, val attempts: Int)

    /** 1.61 — the generate_204 probe with retries. The
     *  engine's own validation is lighter than a full
     *  HTTPS GET, and the first request through a
     *  fresh tunnel can exceed 10s — the 1.60 run
     *  failed AETHER H2 on 198.2 on exactly that
     *  (engine validated, our single 10s probe -1)
     *  while the same gateway passed in 1395ms on
     *  the next run. So: up to 3 attempts, 20s for
     *  the first request, 10s after, ~750ms between
     *  attempts. The session stays alive between
     *  retries (the caller only stops the CLI on
     *  final failure, as before). The probe is an
     *  injectable lambda so the JVM test can drive
     *  the retry loop with a fake. */
    internal fun retryGenerate204(
        probe: (timeoutMs: Int) -> Pair<Int, String?>,
        firstTimeoutMs: Int = 20_000,
        retryTimeoutMs: Int = 10_000,
        attempts: Int = 3,
        pauseMs: Long = 750
    ): Generate204Result {
        var lastError: String? = null
        var used = 0
        for (i in 0 until attempts) {
            used = i + 1
            val (code, err) = probe(if (i == 0) firstTimeoutMs else retryTimeoutMs)
            if (code == 200 || code == 204) return Generate204Result(code, null, used)
            lastError = err ?: "HTTP $code"
            if (i + 1 < attempts) {
                try { Thread.sleep(pauseMs) } catch (_: Exception) {}
            }
        }
        return Generate204Result(-1, lastError, used)
    }

    /** 1.61 — the retrying probe against a real SOCKS
     *  port (defaults: 3 attempts, 20s first, 10s
     *  after, 750ms between). */
    fun generate204ThroughSocksWithRetry(socksPort: Int): Generate204Result =
        retryGenerate204({ t -> generate204ThroughSocksDetailed(socksPort, t) })
}
