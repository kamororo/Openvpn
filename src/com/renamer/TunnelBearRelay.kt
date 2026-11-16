package com.renamer

import android.net.VpnService
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Loopback split relay for TunnelBear (3.01).
 *
 * The OpenVPN3 core does NOT dial the server
 * directly: from Iran the TunnelBear handshake is
 * cut at TLS on UDP and TCP alike (the server
 * answers the reset, then nothing comes back).
 * What gets recognised is the shape of the first
 * records, so the core dials this relay on
 * loopback, and the relay dials the server itself
 * on TCP 7011 and writes the first 6 KB of the
 * client's stream in small segments cut at random
 * offsets, with a few milliseconds between them —
 * no segment starts on a record boundary, so no
 * segment carries a length+opcode header a
 * per-packet matcher can key on. TCP delivers the
 * same byte stream to the server, which therefore
 * sees an ordinary client. Past that budget the
 * relay is a plain copy in both directions, so the
 * session runs at direct speed.
 *
 * The mechanism (split budget, first-record-whole
 * rule, receive-window clamp during the handshake)
 * follows the published MLM implementation's
 * measured behaviour, reimplemented here in this
 * app's own code — no MLM file is copied.
 */
class TunnelBearRelay(
    private val service: VpnService,
    /** Candidate server IPs, answering ones first. */
    private val hosts: List<String>,
    private val port: Int = TunnelBear.TCP_PORT,
    /** The relay's own upstream sockets must bypass
     *  any VPN that is up: this socket IS the direct
     *  path. The service's protect() does that. */
    private val protectSocket: (Socket) -> Boolean = { s ->
        try { service.protect(s) } catch (_: Exception) { false }
    }
) {
    init { require(hosts.isNotEmpty()) }

    @Volatile private var next = 0
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val localPort: Int get() = server.localPort
    @Volatile private var closed = false
    private val open = mutableListOf<Socket>()

    fun start() {
        thread(name = "tb-relay-accept", isDaemon = true) {
            while (!closed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                thread(name = "tb-relay-conn", isDaemon = true) { serve(client) }
            }
        }
    }

    private fun serve(client: Socket) {
        synchronized(open) { open += client }
        var upstream: Socket? = null
        try {
            client.tcpNoDelay = true
            val (host, up) = connect()
            upstream = up
            val t0 = System.currentTimeMillis()
            // The server's certificate flight is a few
            // KB; on some routes it is cut when it
            // arrives in big segments. A small receive
            // window makes the server send that flight
            // in small segments. Set after connect, so
            // the window scale agreed in the SYN stays
            // the normal one; lifted once the handshake
            // is through (12 KB down or 20 s).
            val downBytes = java.util.concurrent.atomic.AtomicLong(0)
            val clamped = clamp(up, HANDSHAKE_WINDOW)
            if (clamped) thread(name = "tb-relay-unclamp", isDaemon = true) {
                try {
                    while (!closed && downBytes.get() < UNCLAMP_AFTER_BYTES &&
                        System.currentTimeMillis() - t0 < UNCLAMP_AFTER_MS) Thread.sleep(100)
                    clamp(up, OPEN_WINDOW)
                } catch (_: Exception) {}
            }
            thread(name = "tb-relay-down", isDaemon = true) {
                try {
                    pump(up.getInputStream(), client.getOutputStream(), downBytes)
                    if (downBytes.get() < HANDSHAKE_BYTES) skip(host)
                } catch (_: Exception) {
                } finally {
                    runCatching { client.shutdownOutput() }
                }
            }
            upSplit(client.getInputStream(), up.getOutputStream())
            // The core gave up on a server that answered
            // at most the reset: the known cut. The next
            // connection goes to another address instead
            // of the same silent one.
            if (downBytes.get() < HANDSHAKE_BYTES) skip(host)
            runCatching { up.shutdownOutput() }
        } catch (_: Exception) {
            runCatching { client.close() }
            runCatching { upstream?.close() }
        }
    }

    /** The first address that takes a TCP connection,
     *  starting at the current best. A socket whose
     *  connect failed is closed for good, so every
     *  attempt gets a fresh one. */
    private fun connect(): Pair<String, Socket> {
        var last: Exception? = null
        val start = next
        for (i in hosts.indices) {
            if (closed) break
            val at = (start + i) % hosts.size
            val socket = Socket()
            synchronized(open) { open += socket }
            try {
                socket.tcpNoDelay = true
                protectSocket(socket)
                socket.connect(
                    InetSocketAddress(InetAddress.getByName(hosts[at]), port),
                    if (hosts.size > 1) FAILOVER_CONNECT_MS else CONNECT_MS
                )
                next = at
                return hosts[at] to socket
            } catch (e: java.io.IOException) {
                last = e
                runCatching { socket.close() }
                synchronized(open) { open -= socket }
            }
        }
        throw last ?: java.io.IOException("relay closed")
    }

    /** [host] never got past the handshake: try the
     *  next address next time. */
    private fun skip(host: String) {
        if (hosts.size > 1 && hosts[next] == host) {
            next = (next + 1) % hosts.size
        }
    }

    /** Client -> server: the first [SPLIT_BYTES] cut
     *  up, then a plain copy. */
    private fun upSplit(input: InputStream, output: OutputStream) {
        val rng = SecureRandom()
        val buf = ByteArray(16 * 1024)
        var budget = SPLIT_BYTES
        var first = true
        // The opening record — the reset — goes out
        // whole. The filter lets a reset through (a
        // bare one is answered on every server), and
        // cutting it made some routes never answer at
        // all. What gets recognised is what follows,
        // so the cutting starts there.
        var resetLeft = -1
        while (true) {
            val n = input.read(buf)
            if (n < 0) return
            var at = 0
            if (resetLeft != 0) {
                if (resetLeft < 0) {
                    if (n < 2) { output.write(buf, 0, n); output.flush(); resetLeft = 0; continue }
                    resetLeft = 2 + (((buf[0].toInt() and 0xff) shl 8) or (buf[1].toInt() and 0xff))
                }
                val whole = minOf(resetLeft, n)
                output.write(buf, 0, whole)
                output.flush()
                resetLeft -= whole
                at = whole
                if (at >= n) continue
            }
            if (budget <= 0) { output.write(buf, at, n - at); output.flush(); continue }
            while (at < n) {
                val size = if (first) 1 else MIN_PIECE + rng.nextInt(MAX_PIECE - MIN_PIECE + 1)
                first = false
                val end = minOf(n, at + size)
                output.write(buf, at, end - at)
                output.flush()
                budget -= end - at
                at = end
                if (budget <= 0) { if (at < n) { output.write(buf, at, n - at); output.flush() }; break }
                if (at < n) Thread.sleep(PAUSE_MIN_MS + rng.nextInt(PAUSE_SPREAD_MS).toLong())
            }
        }
    }

    /** TCP_WINDOW_CLAMP on the live socket. False
     *  where the platform refuses it. */
    private fun clamp(socket: Socket, bytes: Int): Boolean = try {
        android.os.ParcelFileDescriptor.fromSocket(socket).use { pfd ->
            android.system.Os.setsockoptInt(pfd.fileDescriptor, IPPROTO_TCP, TCP_WINDOW_CLAMP, bytes)
        }
        true
    } catch (_: Exception) {
        false
    }

    private fun pump(input: InputStream, output: OutputStream, downBytes: java.util.concurrent.atomic.AtomicLong) {
        val buf = ByteArray(32 * 1024)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) return
                downBytes.addAndGet(n.toLong())
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (_: Exception) { }
    }

    fun close() {
        closed = true
        runCatching { server.close() }
        synchronized(open) { open.forEach { runCatching { it.close() } }; open.clear() }
    }

    companion object {
        private const val CONNECT_MS = 8_000
        private const val FAILOVER_CONNECT_MS = 4_000
        /** Less than this down means no certificate
         *  flight arrived: the session never started. */
        private const val HANDSHAKE_BYTES = 2048L
        private const val IPPROTO_TCP = 6
        /** linux/tcp.h */
        private const val TCP_WINDOW_CLAMP = 10
        /** The kernel raises anything smaller to half
         *  its minimum buffer, about 1.1 KB. */
        private const val HANDSHAKE_WINDOW = 1024
        private const val OPEN_WINDOW = 16 * 1024 * 1024
        /** The certificate flight is a few KB; past
         *  this the session is in its data phase. */
        private const val UNCLAMP_AFTER_BYTES = 12 * 1024L
        private const val UNCLAMP_AFTER_MS = 20_000L
        /** The reset, the ClientHello and the key
         *  exchange all fit well inside this. */
        const val SPLIT_BYTES = 6 * 1024
        private const val MIN_PIECE = 3
        private const val MAX_PIECE = 29
        private const val PAUSE_MIN_MS = 2
        private const val PAUSE_SPREAD_MS = 6
    }
}
