package com.renamer

/**
 * OpenVPN v2 control-channel packets (3.02), for the
 * TunnelBear TLS-handshake probe only.
 *
 * The probe does what a real connect does: the reset
 * (P_CONTROL_HARD_RESET_CLIENT_V2), then the TLS
 * ClientHello inside a P_CONTROL_V1 packet, and waits
 * for the server's certificate flight. A TCP connect
 * alone is NOT proof — from Iran servers answer the
 * reset and then cut at TLS, so a TCP-only probe showed
 * green for servers that never connected (the same
 * lesson MLM's latency probe records).
 *
 * The framing follows the OpenVPN v2 wire format:
 * - reset request: opcode 0x38, 8-byte client session,
 *   5 zero bytes (no ack array, no packet id yet).
 * - server reset reply: opcode 0x40, server session,
 *   ack array of zero packet ids (one of them is the
 *   client's reset, acknowledged as 0).
 * - control packet: opcode 0x20, client session, ack
 *   count 1 + the server's packet id, remote session,
 *   our packet id, then the TLS payload.
 * - a server control packet carries its TLS bytes past
 *   the opcode, session, ack array (plus remote session
 *   when it acks anything) and its own packet id.
 */
object OvpnProbePacket {
    fun request(session: ByteArray): ByteArray {
        require(session.size == 8)
        return byteArrayOf(0x38) + session + byteArrayOf(0, 0, 0, 0, 0)
    }

    /** The server's session id in its reset reply. */
    fun serverSession(reply: ByteArray): ByteArray = reply.copyOfRange(1, 9)

    /** The packet id of the server's reset reply, which our next control packet acknowledges. */
    fun packetId(reply: ByteArray): Long {
        val count = reply[9].toInt() and 0xff
        val at = 10 + count * 4 + 8
        return (0 until 4).fold(0L) { acc, i -> (acc shl 8) or (reply[at + i].toLong() and 0xff) }
    }

    /** P_CONTROL_V1 (key id 0) carrying [payload] — the next packet a real client sends after the
     *  reset: it acknowledges the server's reset and starts TLS inside the control channel. */
    fun control(session: ByteArray, ackId: Long, remoteSession: ByteArray, packetId: Long, payload: ByteArray): ByteArray {
        fun u32(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        return byteArrayOf(0x20) + session + byteArrayOf(1) + u32(ackId) + remoteSession + u32(packetId) + payload
    }

    /** The opcode of a control-channel packet (the high five bits of its first byte). */
    fun opcode(packet: ByteArray): Int = if (packet.isEmpty()) -1 else (packet[0].toInt() and 0xff) ushr 3

    /** The TLS bytes a server's P_CONTROL_V1 carries: past the opcode, session, ack array (and the
     *  remote session when it acknowledges anything) and its own packet id. */
    fun controlPayload(packet: ByteArray): ByteArray? {
        if (opcode(packet) != 4 || packet.size < 14) return null
        val count = packet[9].toInt() and 0xff
        val at = 10 + count * 4 + (if (count > 0) 8 else 0) + 4
        return if (at <= packet.size) packet.copyOfRange(at, packet.size) else null
    }

    fun accepts(packet: ByteArray, session: ByteArray): Boolean {
        if (session.size != 8 || packet.size < 26 || packet[0].toInt() and 0xff != 0x40) return false
        val count = packet[9].toInt() and 0xff
        if (count !in 1..32 || packet.size < 10 + count * 4 + 12) return false
        val offset = 10 + count * 4
        return packet.copyOfRange(offset, offset + 8).contentEquals(session) &&
            (0 until count).any { i -> (10 + i * 4 until 14 + i * 4).all { packet[it] == 0.toByte() } }
    }
}
