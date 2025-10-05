package com.example.dualwan

import java.nio.ByteBuffer

// Minimal IPv4 header parsing for unit tests
object PacketParser {
    data class IPv4Header(
        val version: Int,
        val ihl: Int,
        val totalLength: Int,
        val protocol: Int,
        val src: String,
        val dst: String
    )

    fun parse(buf: ByteBuffer): IPv4Header {
        buf.mark()
        val b0 = buf.get().toInt() and 0xFF
        val version = b0 shr 4
        val ihl = b0 and 0x0F
        val dscpEcn = buf.get()
        val totalLength = buf.short.toInt() and 0xFFFF
        val identification = buf.short
        val flagsFrag = buf.short
        val ttl = buf.get()
        val protocol = buf.get().toInt() and 0xFF
        val headerChecksum = buf.short
        val srcBytes = ByteArray(4)
        buf.get(srcBytes)
        val dstBytes = ByteArray(4)
        buf.get(dstBytes)
        // restore for higher-level processing if needed
        buf.reset()
        return IPv4Header(
            version = version,
            ihl = ihl,
            totalLength = totalLength,
            protocol = protocol,
            src = srcBytes.joinToString(".") { (it.toInt() and 0xFF).toString() },
            dst = dstBytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
        )
    }
}
