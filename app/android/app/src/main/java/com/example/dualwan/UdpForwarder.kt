package com.example.dualwan

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

// UdpForwarder: minimal DNS forwarder (UDP/53) wired into VPN loop.
// This is a basic step for Path A; it forwards a single DNS query and writes the response back to TUN.
object UdpForwarder {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    @Volatile private var tunWriter: FileChannel? = null

    fun setTunWriter(writer: FileChannel?) {
        tunWriter = writer
    }

    fun handlePacket(context: Context, buf: ByteBuffer, ip: PacketParser.IPv4Header) {
        try {
            val udp = PacketParser.parseUdp(buf, ip)
            // Only handle DNS for now
            if (udp.dstPort != 53 && udp.srcPort != 53) return
            val ipHeaderLen = ip.ihl * 4
            val udpHeaderLen = 8
            val payloadLen = ip.totalLength - ipHeaderLen - udpHeaderLen
            if (payloadLen <= 0) return
            val dup = buf.duplicate()
            dup.position(ipHeaderLen + udpHeaderLen)
            val payload = ByteArray(payloadLen)
            dup.get(payload)

            scope.launch {
                try {
                    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    val active = cm.activeNetwork
                    val socket = DatagramSocket()
                    if (active != null) {
                        // Bind UDP socket to active network if available
                        active.bindSocket(socket)
                    }
                    socket.soTimeout = 3000
                    val dstAddr = InetAddress.getByName(ip.dst)
                    val req = DatagramPacket(payload, payload.size, InetSocketAddress(dstAddr, udp.dstPort))
                    socket.send(req)
                    val respBuf = ByteArray(1500)
                    val respPkt = DatagramPacket(respBuf, respBuf.size)
                    socket.receive(respPkt)
                    val resp = respPkt.data.copyOfRange(0, respPkt.length)
                    socket.close()

                    // Build IPv4 + UDP reply back to TUN
                    val srcIP = ip.dst
                    val dstIP = ip.src
                    val srcPort = udp.dstPort
                    val dstPort = udp.srcPort

                    val ipHeader = buildIPv4Header(srcIP, dstIP, 20 + 8 + resp.size, 17)
                    val udpHeader = buildUdpHeader(srcPort, dstPort, 8 + resp.size)
                    val packet = ByteBuffer.allocate(ipHeader.size + udpHeader.size + resp.size)
                    packet.put(ipHeader)
                    packet.put(udpHeader)
                    packet.put(resp)
                    packet.flip()
                    tunWriter?.write(packet)
                } catch (_: Exception) {
                    // Drop on error for now
                }
            }
        } catch (_: Exception) {
            // ignore malformed UDP for now
        }
    }

    private fun buildIPv4Header(src: String, dst: String, totalLen: Int, proto: Int): ByteArray {
        val buf = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
        buf.put(((4 shl 4) or 5).toByte()) // V=4, IHL=5
        buf.put(0) // DSCP/ECN
        buf.putShort(totalLen.toShort())
        buf.putShort(0) // identification
        buf.putShort(0) // flags/fragment
        buf.put(64.toByte()) // TTL
        buf.put(proto.toByte()) // protocol
        buf.putShort(0) // checksum placeholder
        buf.put(ipv4ToBytes(src))
        buf.put(ipv4ToBytes(dst))
        val arr = buf.array()
        val csum = ipHeaderChecksum(arr)
        arr[10] = (csum.toInt() shr 8).toByte()
        arr[11] = (csum.toInt() and 0xFF).toByte()
        return arr
    }

    private fun buildUdpHeader(srcPort: Int, dstPort: Int, length: Int): ByteArray {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putShort(length.toShort())
        buf.putShort(0) // checksum 0 (optional for IPv4)
        return buf.array()
    }

    private fun ipv4ToBytes(ip: String): ByteArray = ip.split('.')
        .map { it.toInt().toByte() }
        .toByteArray()

    private fun ipHeaderChecksum(header: ByteArray): Short {
        var sum = 0L
        var i = 0
        while (i < header.size) {
            if (i == 10) { i += 2; continue } // skip checksum field
            val word = ((header[i].toInt() and 0xFF) shl 8) or (header[i+1].toInt() and 0xFF)
            sum += word
            if ((sum and 0xFFFF0000L) != 0L) {
                sum = (sum and 0xFFFF) + (sum shr 16)
            }
            i += 2
        }
        while ((sum shr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        val csum = sum.inv() and 0xFFFF
        return csum.toShort()
    }
}
