package com.renamer

import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * v1.13 — TLS fingerprint rotation (account-creation device-limit
 * bypass, through the VPN only).
 *
 * The signup service sees only the VPN exit IP and the TLS/client
 * fingerprint (it gets NO device identity). Rotating the User-Agent
 * alone does not change the TLS handshake, which stays identical
 * across attempts — so a refused exit IP keeps being recognised.
 * This factory rotates, per attempt, the enabled cipher-suite
 * order/subset and the preferred TLS version (TLS 1.3 vs 1.2).
 *
 * SECURITY: only modern strong suites are ever offered — the
 * rotation changes ORDER and SUBSET among the strong suites only,
 * never below the platform defaults. Every suite/protocol is
 * intersected with what the socket actually supports, so an
 * unsupported suite can never break the handshake; if the
 * intersection is empty the socket keeps its defaults.
 *
 * Pure JDK (javax.net.ssl / java.net) — NO Android imports, so
 * this file can be compiled standalone and tested against a
 * real HTTPS endpoint from the build VM.
 */
object TlsFingerprint {

    /** TLS 1.3 suites (order matters: first = most preferred). */
    private val TLS13 = arrayOf(
        "TLS_AES_256_GCM_SHA384",
        "TLS_AES_128_GCM_SHA256",
        "TLS_CHACHA20_POLY1305_SHA256"
    )

    /** Strong TLS 1.2 suites (AEAD only). */
    private val TLS12 = arrayOf(
        "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256"
    )

    /** 3 rotation profiles: 0 = TLS 1.3 first, 1 = TLS 1.2 first,
     *  2 = TLS 1.3 first with ChaCha first. profile = idx % 3. */
    fun factoryFor(profile: Int): SSLSocketFactory {
        val idx = ((profile % 3) + 3) % 3
        return RotatingFactory(HttpsURLConnection.getDefaultSSLSocketFactory(), idx)
    }

    /** The suites profile `idx` would offer (before intersecting
     *  with the socket's supported list) — for tests/logging. */
    fun suitesFor(profile: Int): Array<String> {
        return when (((profile % 3) + 3) % 3) {
            0 -> TLS13 + arrayOf(
                "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
                "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256")
            1 -> arrayOf(
                "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
                "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
                "TLS_AES_128_GCM_SHA256",
                "TLS_AES_256_GCM_SHA384",
                "TLS_CHACHA20_POLY1305_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384")
            else -> arrayOf(
                "TLS_CHACHA20_POLY1305_SHA256",
                "TLS_AES_128_GCM_SHA256",
                "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
                "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
                "TLS_AES_256_GCM_SHA384",
                "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256")
        }
    }

    fun protocolsFor(profile: Int): Array<String> {
        return when (((profile % 3) + 3) % 3) {
            1 -> arrayOf("TLSv1.2", "TLSv1.3")
            else -> arrayOf("TLSv1.3", "TLSv1.2")
        }
    }

    private class RotatingFactory(
        private val delegate: SSLSocketFactory,
        private val idx: Int
    ) : SSLSocketFactory() {

        private fun tune(s: SSLSocket) {
            try {
                val supportedSuites = s.supportedCipherSuites.toSet()
                val wanted = suitesFor(idx).filter { it in supportedSuites }
                if (wanted.isNotEmpty()) s.enabledCipherSuites = wanted.toTypedArray()
                val supportedProtos = s.supportedProtocols.toSet()
                val wantedProtos = protocolsFor(idx).filter { it in supportedProtos }
                if (wantedProtos.isNotEmpty()) s.enabledProtocols = wantedProtos.toTypedArray()
            } catch (_: Exception) {
                // Never break a handshake: defaults stay.
            }
        }

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket {
            val sock = delegate.createSocket(s, host, port, autoClose)
            if (sock is SSLSocket) tune(sock)
            return sock
        }

        override fun createSocket(host: String, port: Int): Socket {
            val sock = delegate.createSocket(host, port)
            if (sock is SSLSocket) tune(sock)
            return sock
        }

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
            val sock = delegate.createSocket(host, port, localHost, localPort)
            if (sock is SSLSocket) tune(sock)
            return sock
        }

        override fun createSocket(host: InetAddress, port: Int): Socket {
            val sock = delegate.createSocket(host, port)
            if (sock is SSLSocket) tune(sock)
            return sock
        }

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket {
            val sock = delegate.createSocket(address, port, localAddress, localPort)
            if (sock is SSLSocket) tune(sock)
            return sock
        }
    }
}
