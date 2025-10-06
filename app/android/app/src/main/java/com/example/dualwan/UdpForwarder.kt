package com.example.dualwan

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

// UdpForwarder: NAT-enabled UDP forwarder for all UDP traffic (DNS, QUIC, etc.)
// Implements session tracking, multi-network scheduling, and proper NAT translation
object UdpForwarder {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    @Volatile private var tunWriter: FileChannel? = null
    
    // NAT session tracking
    data class NatSession(
        val clientAddr: String,
        val clientPort: Int,
        val serverAddr: String,
        val serverPort: Int,
        val localPort: Int,
        val network: Network,
        val socket: DatagramSocket,
        var lastActivity: Long = System.currentTimeMillis()
    )
    
    private val natTable = ConcurrentHashMap<String, NatSession>()
    private var localPortCounter = 10000 + Random.nextInt(20000)

    fun setTunWriter(writer: FileChannel?) {
        tunWriter = writer
    }

    fun handlePacket(context: Context, buf: ByteBuffer, ip: PacketParser.IPv4Header) {
        scope.launch { handleUdpPacket(context, buf, ip) }
    }
    
    private suspend fun handleUdpPacket(context: Context, buf: ByteBuffer, ip: PacketParser.IPv4Header) {
        try {
            val udp = PacketParser.parseUdp(buf, ip)
            val ipHeaderLen = ip.ihl * 4
            val udpHeaderLen = 8
            val payloadLen = ip.totalLength - ipHeaderLen - udpHeaderLen
            if (payloadLen <= 0) return
            
            val dup = buf.duplicate()
            dup.position(ipHeaderLen + udpHeaderLen)
            val payload = ByteArray(payloadLen)
            dup.get(payload)
            
            // Create session key for NAT tracking
            val sessionKey = "${ip.src}:${udp.srcPort}->${ip.dst}:${udp.dstPort}"
            
            // Check if this is a new outbound connection or existing session response
            val existingSession = natTable[sessionKey]
            if (existingSession != null) {
                // This is a response from server - forward back to client
                forwardResponse(existingSession, payload)
                existingSession.lastActivity = System.currentTimeMillis()
            } else {
                // New outbound connection - create NAT session
                createNewSession(context, sessionKey, ip, udp, payload)
            }
            
        } catch (e: Exception) {
            // Log and ignore malformed packets for robustness
            android.util.Log.w("UdpForwarder", "Error handling UDP packet: ${e.message}")
        }
    }
    
    private suspend fun createNewSession(
        context: Context,
        sessionKey: String,
        ip: PacketParser.IPv4Header,
        udp: PacketParser.UdpHeader,
        payload: ByteArray
    ) {
        try {
            // Select network for this connection using multi-path logic
            val network = selectNetworkForConnection(context, ip.dst, udp.dstPort)
            
            // Create socket bound to selected network
            val socket = DatagramSocket()
            network.bindSocket(socket)
            socket.soTimeout = 30000 // 30 second timeout for UDP sessions
            
            // Allocate local port for NAT
            val localPort = getNextLocalPort()
            
            // Create NAT session
            val session = NatSession(
                clientAddr = ip.src,
                clientPort = udp.srcPort,
                serverAddr = ip.dst,
                serverPort = udp.dstPort,
                localPort = localPort,
                network = network,
                socket = socket
            )
            
            natTable[sessionKey] = session
            
            // Send outbound packet
            val dstAddr = InetAddress.getByName(ip.dst)
            val packet = DatagramPacket(payload, payload.size, InetSocketAddress(dstAddr, udp.dstPort))
            socket.send(packet)
            
            // Start listening for responses in background
            scope.launch { listenForResponses(session, sessionKey) }
            
        } catch (e: Exception) {
            android.util.Log.w("UdpForwarder", "Failed to create NAT session: ${e.message}")
        }
    }
    
    private fun selectNetworkForConnection(context: Context, dstAddr: String, dstPort: Int): Network {
        // Use NetworkQualityMonitor for intelligent selection
        return when {
            dstPort == 53 -> {
                // DNS: use fastest available network for low latency
                NetworkQualityMonitor.getBestNetwork(context)
                    ?: NetworkQualityMonitor.getBestNetwork(context, NetworkBinder.Transport.WIFI)
                    ?: getDefaultNetwork(context)
            }
            dstPort == 443 || dstPort in 80..8080 -> {
                // QUIC/HTTPS: use quality-based round-robin
                val qualities = NetworkQualityMonitor.getCurrentQualities(context)
                    .filter { it.isAvailable && it.score > 10f } // Only good networks
                if (qualities.isNotEmpty()) {
                    val index = (dstAddr.hashCode() + dstPort) % qualities.size
                    qualities[index].network
                } else {
                    getDefaultNetwork(context)
                }
            }
            else -> {
                // Other UDP: prefer best available network with cellular preference
                NetworkQualityMonitor.getBestNetwork(context, NetworkBinder.Transport.CELLULAR)
                    ?: NetworkQualityMonitor.getBestNetwork(context)
                    ?: getDefaultNetwork(context)
            }
        }
    }
    
    private fun getDefaultNetwork(context: Context): Network {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetwork ?: throw RuntimeException("No active network available")
    }
    
    private fun getNextLocalPort(): Int {
        synchronized(this) {
            localPortCounter++
            if (localPortCounter > 65535) localPortCounter = 10000
            return localPortCounter
        }
    }
    
    private suspend fun listenForResponses(session: NatSession, sessionKey: String) {
        try {
            val buffer = ByteArray(1500) // Standard MTU size
            val packet = DatagramPacket(buffer, buffer.size)
            
            while (true) {
                try {
                    session.socket.receive(packet)
                    val responseData = packet.data.copyOfRange(0, packet.length)
                    
                    // Forward response back to client via TUN
                    forwardResponseToClient(session, responseData)
                    
                    // Update session activity
                    session.lastActivity = System.currentTimeMillis()
                    
                } catch (e: java.net.SocketTimeoutException) {
                    // Session timeout - clean up
                    break
                } catch (e: Exception) {
                    android.util.Log.w("UdpForwarder", "Error receiving UDP response: ${e.message}")
                    break
                }
            }
        } finally {
            // Clean up session
            cleanupSession(sessionKey)
        }
    }
    
    private fun forwardResponse(session: NatSession, payload: ByteArray) {
        forwardResponseToClient(session, payload)
    }
    
    private fun forwardResponseToClient(session: NatSession, responseData: ByteArray) {
        try {
            // Build IPv4 + UDP reply packet back to TUN interface
            val srcIP = session.serverAddr
            val dstIP = session.clientAddr
            val srcPort = session.serverPort
            val dstPort = session.clientPort
            
            val ipHeader = buildIPv4Header(srcIP, dstIP, 20 + 8 + responseData.size, 17)
            val udpHeader = buildUdpHeader(srcPort, dstPort, 8 + responseData.size)
            val packet = ByteBuffer.allocate(ipHeader.size + udpHeader.size + responseData.size)
            packet.put(ipHeader)
            packet.put(udpHeader)
            packet.put(responseData)
            packet.flip()
            
            tunWriter?.write(packet)
            
        } catch (e: Exception) {
            android.util.Log.w("UdpForwarder", "Failed to forward response to client: ${e.message}")
        }
    }
    
    private fun cleanupSession(sessionKey: String) {
        natTable.remove(sessionKey)?.let { session ->
            try {
                session.socket.close()
            } catch (e: Exception) {
                android.util.Log.w("UdpForwarder", "Error closing session socket: ${e.message}")
            }
        }
    }
    
    // Periodic cleanup of expired sessions
    init {
        scope.launch {
            while (true) {
                delay(30000) // Check every 30 seconds
                val now = System.currentTimeMillis()
                val expiredSessions = natTable.entries.filter {
                    now - it.value.lastActivity > 300000 // 5 minutes timeout
                }
                expiredSessions.forEach { 
                    cleanupSession(it.key)
                }
            }
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
