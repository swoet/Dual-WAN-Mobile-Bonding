package com.example.dualwan

import java.nio.ByteBuffer

// IPv4, UDP, and TCP header parsing for VPN packet processing
object PacketParser {
    const val PROTO_TCP = 6
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
    
    data class TcpHeader(
        val srcPort: Int,
        val dstPort: Int,
        val seqNum: Long,
        val ackNum: Long,
        val headerLength: Int,
        val flags: Int,
        val windowSize: Int
    ) {
        val isSyn: Boolean get() = (flags and 0x02) != 0
        val isAck: Boolean get() = (flags and 0x10) != 0
        val isFin: Boolean get() = (flags and 0x01) != 0
        val isRst: Boolean get() = (flags and 0x04) != 0
    }

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
    
    fun parseTcp(buf: ByteBuffer, ip: IPv4Header? = null): TcpHeader {
        val ipHdr = ip ?: parse(buf)
        val dup = buf.duplicate()
        dup.position(ipHdr.ihl * 4)
        val srcPort = dup.short.toInt() and 0xFFFF
        val dstPort = dup.short.toInt() and 0xFFFF
        val seqNum = dup.int.toLong() and 0xFFFFFFFFL
        val ackNum = dup.int.toLong() and 0xFFFFFFFFL
        val headerLenAndFlags = dup.short.toInt() and 0xFFFF
        val headerLength = (headerLenAndFlags shr 12) and 0x0F
        val flags = headerLenAndFlags and 0x3F
        val windowSize = dup.short.toInt() and 0xFFFF
        // checksum and urgent pointer next (ignored here)
        return TcpHeader(srcPort, dstPort, seqNum, ackNum, headerLength, flags, windowSize)
    }
}
