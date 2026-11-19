package com.uacspoofer.mobile.vpn

/** Packet decisions used by the optional QUIC blocker. */
internal enum class TunPacketAction { FORWARD, REJECT }

internal object TunPacketPolicy {
    /**
     * QUIC uses UDP/443. Reject only packets whose UDP destination port is 443;
     * TCP/443 and all other UDP traffic pass unchanged.
     */
    fun inspect(packet: ByteArray, length: Int, quicEnabled: Boolean): TunPacketAction {
        if (!quicEnabled || length < 1) return TunPacketAction.FORWARD
        return when ((packet[0].toInt() ushr 4) and 0x0f) {
            4 -> inspectIpv4(packet, length)
            6 -> inspectIpv6(packet, length)
            else -> TunPacketAction.FORWARD
        }
    }

    private fun inspectIpv4(packet: ByteArray, length: Int): TunPacketAction {
        if (length < 28 || (packet[9].toInt() and 0xff) != PROTO_UDP) return TunPacketAction.FORWARD
        val ihl = (packet[0].toInt() and 0x0f) * 4
        if (ihl < 20 || length < ihl + 8) return TunPacketAction.FORWARD
        val fragmentOffset = ((packet[6].toInt() and 0x1f) shl 8) or (packet[7].toInt() and 0xff)
        if (fragmentOffset != 0) return TunPacketAction.FORWARD
        return if (u16(packet, ihl + 2) == QUIC_PORT) TunPacketAction.REJECT else TunPacketAction.FORWARD
    }

    private fun inspectIpv6(packet: ByteArray, length: Int): TunPacketAction {
        // Match the base IPv6 header's next-header field, as the source implementation does.
        if (length < 48 || (packet[6].toInt() and 0xff) != PROTO_UDP) return TunPacketAction.FORWARD
        return if (u16(packet, 42) == QUIC_PORT) TunPacketAction.REJECT else TunPacketAction.FORWARD
    }

    /** Build ICMP destination-unreachable / ICMPv6 destination-unreachable (port). */
    fun buildPortUnreachable(packet: ByteArray, length: Int, reply: ByteArray): Int {
        if (length < 28) return 0
        return when ((packet[0].toInt() ushr 4) and 0x0f) {
            4 -> buildIpv4Reject(packet, length, reply)
            6 -> buildIpv6Reject(packet, length, reply)
            else -> 0
        }
    }

    private fun buildIpv4Reject(packet: ByteArray, length: Int, reply: ByteArray): Int {
        val ihl = (packet[0].toInt() and 0x0f) * 4
        if (ihl < 20 || length < ihl + 8) return 0
        val quoteLength = ihl + 8
        val total = 28 + quoteLength
        if (reply.size < total) return 0
        java.util.Arrays.fill(reply, 0, total, 0)
        reply[0] = 0x45
        reply[2] = (total ushr 8).toByte(); reply[3] = total.toByte()
        reply[8] = 64; reply[9] = 1
        System.arraycopy(packet, 16, reply, 12, 4)
        System.arraycopy(packet, 12, reply, 16, 4)
        val ipSum = checksum(reply, 0, 20)
        reply[10] = (ipSum ushr 8).toByte(); reply[11] = ipSum.toByte()
        reply[20] = 3; reply[21] = 3
        System.arraycopy(packet, 0, reply, 28, quoteLength)
        val icmpSum = checksum(reply, 20, 8 + quoteLength)
        reply[22] = (icmpSum ushr 8).toByte(); reply[23] = icmpSum.toByte()
        return total
    }

    private fun buildIpv6Reject(packet: ByteArray, length: Int, reply: ByteArray): Int {
        if (length < 48) return 0
        val quoteLength = minOf(length, 1232)
        val payloadLength = 8 + quoteLength
        val total = 40 + payloadLength
        if (reply.size < total) return 0
        java.util.Arrays.fill(reply, 0, total, 0)
        reply[0] = 0x60
        reply[4] = (payloadLength ushr 8).toByte(); reply[5] = payloadLength.toByte()
        reply[6] = 58; reply[7] = 64
        System.arraycopy(packet, 24, reply, 8, 16)
        System.arraycopy(packet, 8, reply, 24, 16)
        reply[40] = 1; reply[41] = 4
        System.arraycopy(packet, 0, reply, 48, quoteLength)
        var pseudo = 0L
        var i = 8
        while (i < 40) { pseudo += u16(reply, i); i += 2 }
        pseudo += payloadLength + 58
        val sum = checksum(reply, 40, payloadLength, pseudo)
        reply[42] = (sum ushr 8).toByte(); reply[43] = sum.toByte()
        return total
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun checksum(bytes: ByteArray, offset: Int, length: Int, extra: Long = 0L): Int {
        var sum = extra
        var i = 0
        while (i + 1 < length) {
            sum += ((bytes[offset + i].toInt() and 0xff) shl 8) or (bytes[offset + i + 1].toInt() and 0xff)
            i += 2
        }
        if (i < length) sum += (bytes[offset + i].toInt() and 0xff) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.inv().toInt() and 0xffff
    }

    private const val PROTO_UDP = 17
    private const val QUIC_PORT = 443
}
