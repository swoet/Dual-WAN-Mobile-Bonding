package com.example.dualwan

import java.nio.ByteBuffer

// Minimal IPv4 + UDP header parsing for unit tests and VPN loop scaffolding
object PacketParser {
    const val PROTO_UDP = 17

    data class IPv4Header(
        val version: Int,
        val ihl: Int,
        val totalLength: Int,
        val protocol: Int,
        val src: String,
        val dst: String
    )

    data class UdpHeader(
        val srcPort: Int,
        val dstPort: Int,
        val length: Int
    )

    fun parse(buf: ByteBuffer): IPv4Header {
        val dup = buf.duplicate()
        val b0 = (dup.get().toInt() and 0xFF)
        val version = b0 shr 4
        val ihl = b0 and 0x0F
        dup.get() // dscp/ecn
        val totalLength = dup.short.toInt() and 0xFFFF
        dup.short // identification
        dup.short // flags+frag
        dup.get() // ttl
        val protocol = dup.get().toInt() and 0xFF
        dup.short // checksum
        val srcBytes = ByteArray(4)
        dup.get(srcBytes)
        val dstBytes = ByteArray(4)
        dup.get(dstBytes)
        return IPv4Header(
            version = version,
            ihl = ihl,
            totalLength = totalLength,
            protocol = protocol,
            src = srcBytes.joinToString(".") { (it.toInt() and 0xFF).toString() },
            dst = dstBytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
        )
    }

    fun parseUdp(buf: ByteBuffer, ip: IPv4Header? = null): UdpHeader {
        val ipHdr = ip ?: parse(buf)
        val dup = buf.duplicate()
        dup.position(ipHdr.ihl * 4)
        val srcPort = dup.short.toInt() and 0xFFFF
        val dstPort = dup.short.toInt() and 0xFFFF
        val length = dup.short.toInt() and 0xFFFF
        // checksum next (ignored here)
        return UdpHeader(srcPort, dstPort, length)
    }
}
