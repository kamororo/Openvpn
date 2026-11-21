package com.renamer

/** 1.53 — pure WARP-registration helpers: NO Android imports,
 *  so the JVM test (TestMain153.kt) compiles and runs this
 *  file alone and asserts the exact request the clean-IP leg
 *  sends. The app code calls these same functions — the test
 *  exercises the real code, not a copy.
 *
 *  Why this file exists: 1.52 passed only the path SUFFIX
 *  to the clean-IP leg ("POST  HTTP/1.1", "PATCH /<id>
 *  HTTP/1.1") and the Cloudflare edge answered 400 — a
 *  one-string regression, proven twice against the real
 *  edge (~/workspace/.tmp/reg400test/reg400real.py).
 *  REG_PATH is defined once here and used by both legs. */
import java.net.InetSocketAddress
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocketFactory

object WarpReg {
    const val REG_PATH = "/v0a4471/reg"
    const val REG_HOST = "api.cloudflareclient.com"
    /** 1.58 — enroll-probe timeouts (Part A: faster
     *  registration without losing accuracy). The fast
     *  waves use 3s per attempt — a healthy edge IP
     *  answers the shaped probe in ~0.1s, so 3s is
     *  generous; the slow fallback wave keeps the old
     *  8s for slow networks. The probe SHAPE is
     *  unchanged (same SNI + same empty POST) — only
     *  the scheduling changed. */
    const val PROBE_FAST_TIMEOUT_MS = 3000
    const val PROBE_SLOW_TIMEOUT_MS = 8000
    /** 1.54 — Asha's registration SNI/Host (KeyManager
     *  --api-sni/--api-host). VM-PROVEN on the real edge
     *  2026-11-20 (reg154probe.py): POST /v0a4471/reg
     *  with a valid body to SNI+Host api.devices...
     *  returns 200 OK — it IS a registration endpoint,
     *  an alternate leg when the primary SNI gets no
     *  answer. Never the first leg: the primary host
     *  is the proven default. */
    const val DEVICES_HOST = "api.devices.cloudflare.com"
    const val UA = "WARP for Android"
    const val CF_VER = "a-6.35-4471"

    /** The full API path for one call: the clean-IP leg
     *  needs it (it builds the request line by hand) and
     *  the hostname leg needs the same suffix. */
    fun regPath(suffix: String): String = REG_PATH + suffix

    /** The exact bytes the clean-IP leg sends. Headers
     *  match aether's front_headers: Accept + Content-Type
     *  with charset. */
    fun buildCleanIpRequest(method: String, path: String, body: String?, token: String?): String =
        buildCleanIpRequest(method, path, body, token, REG_HOST)

    /** Same request, but to an alternate API host
     *  (1.54 devices leg): the SNI name and the Host
     *  header both change — the path and the rest do
     *  not. */
    fun buildCleanIpRequest(method: String, path: String, body: String?, token: String?, host: String): String {
        val req = StringBuilder()
        req.append("$method $path HTTP/1.1\r\n")
        req.append("Host: $host\r\n")
        req.append("Accept: application/json\r\n")
        req.append("Content-Type: application/json; charset=UTF-8\r\n")
        req.append("User-Agent: $UA\r\n")
        req.append("CF-Client-Version: $CF_VER\r\n")
        if (token != null) req.append("Authorization: Bearer $token\r\n")
        if (body != null) req.append("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n")
        req.append("Connection: close\r\n\r\n")
        if (body != null) req.append(body)
        return req.toString()
    }

    /** 1.54 — THE ENROLL PROBE (registration-shaped):
     *  POST /v0a4471/reg with an EMPTY body, same SNI,
     *  same Host, same headers as the real registration.
     *  VM-PROVEN on the real edge (reg154probe.py):
     *  the edge answers 400 Bad Request in ~0.11s — a
     *  prompt, deterministic verdict that creates
     *  NOTHING (no key in the body, so no account).
     *  Any HTTP status line at all is a PASS: the
     *  verdict is "this IP carries a full HTTP exchange
     *  to the registration endpoint", never a code.
     *  A timeout / transport failure is a FAIL — exactly
     *  the device failure mode (1.53: 162.159.192.1
     *  passed TCP+TLS with SNI www.cloudflare.com and
     *  then the POST with SNI api.cloudflareclient.com
     *  read-timed-out on both legs). The old TCP+TLS
     *  probe tested a DIFFERENT SNI than registration
     *  and could not prove the IP can register. */
    fun buildRegProbeRequest(host: String): String {
        val req = StringBuilder()
        req.append("POST $REG_PATH HTTP/1.1\r\n")
        req.append("Host: $host\r\n")
        req.append("Accept: application/json\r\n")
        req.append("Content-Type: application/json; charset=UTF-8\r\n")
        req.append("User-Agent: $UA\r\n")
        req.append("CF-Client-Version: $CF_VER\r\n")
        req.append("Content-Length: 0\r\n")
        req.append("Connection: close\r\n\r\n")
        return req.toString()
    }

    /** 1.54 — probe candidates in parallel and return
     *  the FIRST that answers (or null). The probe
     *  lambda runs on pool threads; a slow candidate
     *  never blocks a fast one, and the pool is shut
     *  down on return. Pure — the JVM test drives it
     *  with fake probes. */
    fun pickFirstAnswering(
        candidates: List<String>,
        probe: (String) -> Boolean,
        maxThreads: Int = 8,
        deadlineMs: Long = 30_000L
    ): String? {
        val clean = candidates.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (clean.isEmpty()) return null
        val pool = java.util.concurrent.Executors.newFixedThreadPool(
            maxThreads.coerceIn(1, clean.size))
        try {
            val completion = java.util.concurrent.ExecutorCompletionService<String?>(pool)
            for (ip in clean) {
                completion.submit(java.util.concurrent.Callable {
                    try { if (probe(ip)) ip else null } catch (_: Exception) { null }
                })
            }
            val end = System.currentTimeMillis() + deadlineMs
            var remaining = clean.size
            while (remaining > 0) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) break
                val fut = try {
                    completion.poll(left, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (_: Exception) { null } ?: break
                remaining--
                val hit = try { fut.get() } catch (_: Exception) { null }
                if (hit != null) return hit
            }
            return null
        } finally {
            pool.shutdownNow()
        }
    }

    /** 1.58 — first-answer-wins over (ip, sni) PAIRS
     *  (Part A: the enroll probe waves run FLAT, not
     *  SNI-major — the old code paid a whole 8s-timeout
     *  REG_HOST wave before even trying the DEVICES
     *  SNI that answers on this network). Same
     *  mechanics as pickFirstAnswering: the probe runs
     *  on pool threads, a slow pair never blocks a
     *  fast one, the pool is shut down on return.
     *  Pure — the JVM test drives it with fake probes. */
    fun pickFirstPair(
        candidates: List<Pair<String, String>>,
        probe: (String, String) -> Boolean,
        maxThreads: Int = 8,
        deadlineMs: Long = 30_000L
    ): Pair<String, String>? {
        val clean = candidates.map { it.first.trim() to it.second.trim() }
            .filter { it.first.isNotBlank() && it.second.isNotBlank() }.distinct()
        if (clean.isEmpty()) return null
        val pool = java.util.concurrent.Executors.newFixedThreadPool(
            maxThreads.coerceIn(1, clean.size))
        try {
            val completion = java.util.concurrent.ExecutorCompletionService<Pair<String, String>?>(pool)
            for (pair in clean) {
                completion.submit(java.util.concurrent.Callable {
                    try { if (probe(pair.first, pair.second)) pair else null } catch (_: Exception) { null }
                })
            }
            val end = System.currentTimeMillis() + deadlineMs
            var remaining = clean.size
            while (remaining > 0) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) break
                val fut = try {
                    completion.poll(left, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (_: Exception) { null } ?: break
                remaining--
                val hit = try { fut.get() } catch (_: Exception) { null }
                if (hit != null) return hit
            }
            return null
        } finally {
            pool.shutdownNow()
        }
    }

    /** One leg's failure, with its target, for the
     *  accumulated error: "clean-IP 1.2.3.4 POST
     *  /v0a4471/reg: Read timed out". Never "؟". */
    fun legError(leg: String, target: String, err: String?): String =
        "$leg $target: ${err?.ifBlank { null } ?: "خطای نامشخص"}"

    /** An HTTP error as the "why": the code, the attempted
     *  target, and the first ~200 chars of the body
     *  (401/403/429 bodies carry the real reason). Error
     *  bodies never carry credentials. */
    fun httpErrorWhy(code: Int, body: String?, target: String): String {
        val snippet = body?.trim()?.replace(Regex("\\s+"), " ")?.take(200)?.ifBlank { null }
        return if (snippet != null) "کد $code ($target): $snippet" else "کد $code ($target)"
    }

    /** The registration-failure screen message: the VPN
     *  state is the SYSTEM state. A foreign VPN on +
     *  failure ⇒ "با VPN هم ثبت‌نام نشد", never
     *  "بدون VPN". */
    fun registrationFailureMessage(vpnOn: Boolean, ownTunnel: Boolean, why: String): Pair<String, String> {
        return when {
            ownTunnel -> "ثبت‌نام وارپ ناموفق بود ($why)؛ لاگ وارپ را بفرست." to
                "WARP registration failed ($why); send the warp log."
            vpnOn -> "با VPN هم ثبت‌نام نشد ($why) — لاگ وارپ را بفرست." to
                "Registration failed even with a VPN on ($why) — send the warp log."
            // 1.54: never claim impossibility — this is
            // what THIS attempt observed on this network.
            // 1.55: no VPN-first advice anywhere in the
            // WARP section (user order: no-VPN).
            else -> "ثبت‌نام وارپ ناموفق بود ($why)؛ روی این شبکه بدون VPN جواب نداد — لاگ وارپ را بفرست." to
                "WARP registration failed ($why); it did not answer without a VPN on this network — send the warp log."
        }
    }

    /** 1.54 — THE ENROLL PROBE (registration-shaped).
     *  Pure (java.* only) so the JVM test runs this exact
     *  code against a local mock TLS server. A raw TLS
     *  socket to <cleanIp>:443 with SNI = [host] (the
     *  registration SNI, NOT www.cloudflare.com), then
     *  the exact registration-shaped request
     *  (buildRegProbeRequest: POST /v0a4471/reg, empty
     *  body, same headers). Returns (HTTP code, error):
     *  ANY HTTP status line is a PASS (the edge answers
     *  the empty POST with 400 in ~0.11s — a prompt
     *  verdict that creates nothing); a timeout or
     *  transport failure is (-1, error) — exactly the
     *  device failure mode (TCP+TLS OK, POST read
     *  timeout). Only the status line is read — the body
     *  is never needed for the verdict. The [factory]
     *  parameter exists so the JVM test can drive this
     *  against a local mock TLS server; the app always
     *  passes the default factory (real chain
     *  validation). */
    fun probeRegShaped(
        cleanIp: String,
        host: String = REG_HOST,
        timeoutMs: Int = 8000,
        factory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
        port: Int = 443
    ): Pair<Int, String?> {
        var sock: javax.net.ssl.SSLSocket? = null
        return try {
            val socket = factory.createSocket() as javax.net.ssl.SSLSocket
            sock = socket
            socket.connect(InetSocketAddress(cleanIp, port), timeoutMs)
            socket.soTimeout = timeoutMs
            val params = SSLParameters()
            params.serverNames = listOf(SNIHostName(host))
            socket.sslParameters = params
            socket.startHandshake()
            socket.outputStream.write(buildRegProbeRequest(host).toByteArray(Charsets.UTF_8))
            socket.outputStream.flush()
            val buf = ByteArray(4096)
            val sb = StringBuilder()
            var code = -1
            var gotLine = false
            while (!gotLine && sb.length < 4096) {
                val n = try { socket.inputStream.read(buf) } catch (e: Exception) {
                    return -1 to (e.message ?: e.javaClass.simpleName)
                }
                if (n <= 0) break
                sb.append(String(buf, 0, n, Charsets.UTF_8))
                val idx = sb.indexOf("\r\n")
                if (idx >= 0) {
                    val statusLine = sb.substring(0, idx)
                    code = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
                    gotLine = true
                }
            }
            if (!gotLine || code < 100) -1 to "no HTTP reply from $cleanIp"
            else code to null
        } catch (e: Exception) {
            -1 to (e.message ?: e.javaClass.simpleName)
        } finally {
            try { sock?.close() } catch (_: Exception) {}
        }
    }

    /** 1.54 — the REAL masque:// link for a MASQUE
     *  record (P1). Pure string building (the same
     *  shape WarpFree.buildMasqueLink wraps), so the
     *  JVM test asserts the exact link text. Carries
     *  the gateway, the enrolled CLIENT keys (Asha
     *  semantics), the tunnel addresses, SNI/ALPN/
     *  host/path — everything the app's own parser
     *  needs to rebuild the exact masque outbound.
     *  Returns null when a key or the gateway is
     *  missing. */
    fun buildMasqueLink(
        gatewayIp: String,
        gatewayPort: Int,
        alpn: String,
        name: String,
        masquePrivB64: String,
        masquePubB64: String,
        ipv4: String,
        ipv6: String
    ): String? {
        if (masquePrivB64.isBlank() || masquePubB64.isBlank()) return null
        if (gatewayIp.isBlank()) return null
        val addrs = listOfNotNull(
            ipv4.takeIf { it.isNotBlank() }?.let { if (it.contains("/")) it else "$it/32" },
            ipv6.takeIf { it.isNotBlank() }?.let { if (it.contains("/")) it else "$it/128" }
        )
        if (addrs.isEmpty()) return null
        fun enc(v: String) = try { java.net.URLEncoder.encode(v, "UTF-8") } catch (_: Exception) { v }
        val carrier = if (alpn == "h3") "h3" else "h2"
        val port = gatewayPort.takeIf { it > 0 } ?: 443
        val host = if (gatewayIp.contains(":")) "[$gatewayIp]" else gatewayIp
        val q = listOf(
            "type" to "masque",
            "security" to "tls",
            "sni" to "www.cloudflare.com",
            "fp" to "chrome",
            "alpn" to carrier,
            "warp_priv" to masquePrivB64,
            "warp_pub" to masquePubB64,
            "warp_addr" to addrs.joinToString(","),
            "masque_host" to "cloudflareaccess.com",
            "masque_path" to "/"
        ).joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        return "masque://$host:$port?$q#${enc(name)}"
    }
}
