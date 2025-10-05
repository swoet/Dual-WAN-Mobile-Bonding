package com.example.dualwan

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class PacketParserTest {
    @Test
    fun parse_ipv4_header_basic() {
        // Build a minimal IPv4 header: version=4, IHL=5, totalLength=40, protocol=6 (TCP)
        val buf = ByteBuffer.allocate(20)
        buf.put(((4 shl 4) or 5).toByte()) // version & IHL
        buf.put(0) // DSCP/ECN
        buf.putShort(40) // total length
        buf.putShort(0) // identification
        buf.putShort(0) // flags+frag
        buf.put(64) // TTL
        buf.put(6) // protocol TCP
        buf.putShort(0) // checksum
        buf.put(byteArrayOf(192.toByte(), 168.toByte(), 1.toByte(), 10.toByte())) // src
        buf.put(byteArrayOf(93, 184.toByte(), 216.toByte(), 34)) // dst 93.184.216.34 example.com
        buf.flip()

        val header = PacketParser.parse(buf)
        assertEquals(4, header.version)
        assertEquals(5, header.ihl)
        assertEquals(40, header.totalLength)
        assertEquals(6, header.protocol)
        assertEquals("192.168.1.10", header.src)
        assertEquals("93.184.216.34", header.dst)
    }
}
