package com.example.dualwan

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

// TcpForwarder: tun2socks implementation for TCP traffic forwarding
// Maintains TCP connection state and forwards streams via network-bound sockets
object TcpForwarder {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    @Volatile private var tunWriter: FileChannel? = null
    
    // TCP connection tracking (simplified state machine)
    data class TcpConnection(
        val clientAddr: String,
        val clientPort: Int,
        val serverAddr: String,
        val serverPort: Int,
        val network: Network,
        val socket: Socket,
        var clientSeq: Long,
        var serverSeq: Long,
        var lastActivity: Long = System.currentTimeMillis(),
        var state: TcpState = TcpState.CLOSED
    )
    
    enum class TcpState { CLOSED, SYN_SENT, ESTABLISHED, FIN_WAIT, CLOSE_WAIT }
    
    private val connections = ConcurrentHashMap<String, TcpConnection>()
    
    fun setTunWriter(writer: FileChannel?) {
        tunWriter = writer
    }
    
    fun handlePacket(context: Context, buf: ByteBuffer, ip: PacketParser.IPv4Header) {
        scope.launch { handleTcpPacket(context, buf, ip) }
    }
    
    private suspend fun handleTcpPacket(context: Context, buf: ByteBuffer, ip: PacketParser.IPv4Header) {
        try {
            val tcp = PacketParser.parseTcp(buf, ip)
            val connectionKey = "${ip.src}:${tcp.srcPort}->${ip.dst}:${tcp.dstPort}"
            
            val existingConn = connections[connectionKey]
            
            when {
                tcp.isSyn && !tcp.isAck && existingConn == null -> {
                    // New outbound connection attempt
                    handleNewConnection(context, connectionKey, ip, tcp)
                }
                existingConn != null -> {
                    // Existing connection - handle based on flags and state
                    handleExistingConnection(existingConn, ip, tcp, buf)
                }
                else -> {
                    // RST or other cases - ignore for now
                    android.util.Log.d("TcpForwarder", "Ignored TCP packet: flags=${tcp.flags}")
                }
            }
            
        } catch (e: Exception) {
            android.util.Log.w("TcpForwarder", "Error handling TCP packet: ${e.message}")
        }
    }
    
    private suspend fun handleNewConnection(
        context: Context,
        connectionKey: String,
        ip: PacketParser.IPv4Header,
        tcp: PacketParser.TcpHeader
    ) {
        try {
            // Select network for this connection
            val network = selectNetworkForTcpConnection(context, ip.dst, tcp.dstPort)
            
            // Create socket bound to selected network
            val socket = Socket()
            network.bindSocket(socket)
            socket.tcpNoDelay = true
            socket.soTimeout = 30000
            
            // Create connection tracking entry
            val connection = TcpConnection(
                clientAddr = ip.src,
                clientPort = tcp.srcPort,
                serverAddr = ip.dst,
                serverPort = tcp.dstPort,
                network = network,
                socket = socket,
                clientSeq = tcp.seqNum,
                serverSeq = 0L, // Will be set when server responds
                state = TcpState.SYN_SENT
            )
            
            connections[connectionKey] = connection
            
            // Connect to remote server asynchronously
            scope.launch {
                try {
                    socket.connect(InetSocketAddress(ip.dst, tcp.dstPort), 5000)
                    connection.state = TcpState.ESTABLISHED
                    connection.serverSeq = Random.nextLong(1000000L) // Fake initial server seq
                    
                    // Send SYN-ACK back to client via TUN
                    sendTcpResponse(connection, flags = 0x12, ackNum = connection.clientSeq + 1)
                    
                    // Start bidirectional forwarding
                    startTcpForwarding(connection, connectionKey)
                    
                } catch (e: Exception) {
                    android.util.Log.w("TcpForwarder", "TCP connection failed: ${e.message}")
                    // Send RST back to client
                    sendTcpResponse(connection, flags = 0x14) // RST+ACK
                    cleanupConnection(connectionKey)
                }
            }
            
        } catch (e: Exception) {
            android.util.Log.w("TcpForwarder", "Failed to handle new TCP connection: ${e.message}")
        }
    }
    
    private fun handleExistingConnection(
        connection: TcpConnection,
        ip: PacketParser.IPv4Header,
        tcp: PacketParser.TcpHeader,
        buf: ByteBuffer
    ) {
        connection.lastActivity = System.currentTimeMillis()
        
        when {
            tcp.isFin -> {
                // Client wants to close connection
                connection.state = TcpState.FIN_WAIT
                try {
                    connection.socket.shutdownOutput()
                } catch (e: Exception) {
                    android.util.Log.w("TcpForwarder", "Error closing connection output: ${e.message}")
                }
            }
            tcp.isRst -> {
                // Reset connection
                cleanupConnection("${ip.src}:${tcp.srcPort}->${ip.dst}:${tcp.dstPort}")
            }
            else -> {
                // Regular data packet - extract payload and forward to socket
                val ipHeaderLen = ip.ihl * 4
                val tcpHeaderLen = tcp.headerLength * 4
                val payloadLen = ip.totalLength - ipHeaderLen - tcpHeaderLen
                
                if (payloadLen > 0) {
                    val dup = buf.duplicate()
                    dup.position(ipHeaderLen + tcpHeaderLen)
                    val payload = ByteArray(payloadLen)
                    dup.get(payload)
                    
                    // Forward payload to real socket and update sequence tracking
                    scope.launch {
                        try {
                            connection.socket.getOutputStream().write(payload)
                            connection.socket.getOutputStream().flush()
                            // Update client sequence number
                            updateClientSequence(connection, tcp, payloadLen)
                        } catch (e: Exception) {
                            android.util.Log.w("TcpForwarder", "Error forwarding TCP data: ${e.message}")
                        }
                    }
                }
            }
        }
    }
    
    private fun selectNetworkForTcpConnection(context: Context, dstAddr: String, dstPort: Int): Network {
        // Use NetworkQualityMonitor for intelligent TCP connection selection
        return when {
            dstPort in listOf(443, 8443) -> {
                // HTTPS: prefer best WiFi for bandwidth, fallback to best available
                NetworkQualityMonitor.getBestNetwork(context, NetworkBinder.Transport.WIFI)
                    ?: NetworkQualityMonitor.getBestNetwork(context)
                    ?: getDefaultNetwork(context)
            }
            dstPort in listOf(22, 2222) -> {
                // SSH: prefer most stable network (highest stability score)
                NetworkQualityMonitor.getCurrentQualities(context)
                    .filter { it.isAvailable && it.score > 10f }
                    .maxByOrNull { it.stability }?.network
                    ?: NetworkQualityMonitor.getBestNetwork(context, NetworkBinder.Transport.WIFI)
                    ?: getDefaultNetwork(context)
            }
            dstPort in listOf(80, 8080) -> {
                // HTTP: intelligent load balancing based on quality
                val goodNetworks = NetworkQualityMonitor.getCurrentQualities(context)
                    .filter { it.isAvailable && it.score > 15f } // Higher threshold for HTTP
                if (goodNetworks.isNotEmpty()) {
                    val index = (dstAddr.hashCode() + dstPort) % goodNetworks.size
                    goodNetworks[index].network
                } else {
                    NetworkQualityMonitor.getBestNetwork(context) ?: getDefaultNetwork(context)
                }
            }
            else -> {
                // General TCP: use best available network
                NetworkQualityMonitor.getBestNetwork(context) ?: getDefaultNetwork(context)
            }
        }
    }
    
    private fun getDefaultNetwork(context: Context): Network {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetwork ?: throw RuntimeException("No active network available")
    }
    
    private suspend fun startTcpForwarding(connection: TcpConnection, connectionKey: String) {
        try {
            val socket = connection.socket
            val inputStream = socket.getInputStream()
            val outputStream = socket.getOutputStream()
            
            // Read from socket and forward to TUN
            val buffer = ByteArray(8192) // 8KB buffer for TCP streaming
            
            scope.launch {
                try {
                    while (connection.state == TcpState.ESTABLISHED) {
                        val bytesRead = inputStream.read(buffer)
                        if (bytesRead == -1) {
                            // Server closed connection
                            sendTcpResponse(connection, flags = 0x11) // FIN+ACK
                            break
                        } else if (bytesRead > 0) {
                            // Forward data back to client via TUN
                            val payload = buffer.copyOfRange(0, bytesRead)
                            sendTcpDataToClient(connection, payload)
                            connection.lastActivity = System.currentTimeMillis()
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("TcpForwarder", "Error in TCP forwarding: ${e.message}")
                } finally {
                    cleanupConnection(connectionKey)
                }
            }
            
        } catch (e: Exception) {
            android.util.Log.w("TcpForwarder", "Failed to start TCP forwarding: ${e.message}")
            cleanupConnection(connectionKey)
        }
    }
    
    private fun sendTcpResponse(connection: TcpConnection, flags: Int, ackNum: Long = 0) {
        try {
            val finalAckNum = if (ackNum != 0L) ackNum else connection.clientSeq + 1
            val packet = buildTcpPacket(
                srcAddr = connection.serverAddr,
                dstAddr = connection.clientAddr,
                srcPort = connection.serverPort,
                dstPort = connection.clientPort,
                seq = connection.serverSeq,
                ack = finalAckNum,
                flags = flags
            )
            
            val buffer = ByteBuffer.wrap(packet)
            tunWriter?.write(buffer)
            
            // Update server sequence if we sent data
            if ((flags and 0x02) != 0) { // SYN flag
                connection.serverSeq++
            }
            if ((flags and 0x01) != 0) { // FIN flag
                connection.serverSeq++
            }
            
        } catch (e: Exception) {
            android.util.Log.w("TcpForwarder", "Failed to send TCP response: ${e.message}")
        }
    }
    
    private fun sendTcpDataToClient(connection: TcpConnection, payload: ByteArray) {
        try {
            val packet = buildTcpPacket(
                srcAddr = connection.serverAddr,
                dstAddr = connection.clientAddr,
                srcPort = connection.serverPort,
                dstPort = connection.clientPort,
                seq = connection.serverSeq,
                ack = connection.clientSeq,
                flags = 0x18, // PSH+ACK
                payload = payload
            )
            
            val buffer = ByteBuffer.wrap(packet)
            tunWriter?.write(buffer)
            
            // Update server sequence number
            connection.serverSeq += payload.size
            
        } catch (e: Exception) {
            android.util.Log.w("TcpForwarder", "Failed to send TCP data: ${e.message}")
        }
    }
    
    private fun buildTcpPacket(
        srcAddr: String, dstAddr: String,
        srcPort: Int, dstPort: Int,
        seq: Long, ack: Long, flags: Int,
        payload: ByteArray = ByteArray(0)
    ): ByteArray {
        val ipHeaderSize = 20
        val tcpHeaderSize = 20
        val totalSize = ipHeaderSize + tcpHeaderSize + payload.size
        
        // Build IPv4 header
        val ipHeader = buildIPv4Header(srcAddr, dstAddr, totalSize, PacketParser.PROTO_TCP)
        
        // Build TCP header
        val tcpHeader = buildTcpHeader(srcPort, dstPort, seq, ack, flags, tcpHeaderSize + payload.size, srcAddr, dstAddr, payload)
        
        // Combine all parts
        val packet = ByteArray(totalSize)
        System.arraycopy(ipHeader, 0, packet, 0, ipHeaderSize)
        System.arraycopy(tcpHeader, 0, packet, ipHeaderSize, tcpHeaderSize)
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, packet, ipHeaderSize + tcpHeaderSize, payload.size)
        }
        
        return packet
    }
    
    private fun buildIPv4Header(src: String, dst: String, totalLen: Int, proto: Int): ByteArray {
        val buf = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
        buf.put(((4 shl 4) or 5).toByte()) // V=4, IHL=5
        buf.put(0) // DSCP/ECN
        buf.putShort(totalLen.toShort())
        buf.putShort(Random.nextInt(65536).toShort()) // identification
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
    
    private fun buildTcpHeader(
        srcPort: Int, dstPort: Int, seq: Long, ack: Long, flags: Int,
        totalLen: Int, srcAddr: String, dstAddr: String, payload: ByteArray
    ): ByteArray {
        val buf = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putInt((seq and 0xFFFFFFFFL).toInt())
        buf.putInt((ack and 0xFFFFFFFFL).toInt())
        val headerLenAndFlags = (5 shl 12) or (flags and 0x3F) // Header length = 5 (20 bytes)
        buf.putShort(headerLenAndFlags.toShort())
        buf.putShort(8192) // Window size
        buf.putShort(0) // Checksum placeholder
        buf.putShort(0) // Urgent pointer
        
        val tcpHeader = buf.array()
        val checksum = tcpChecksum(tcpHeader, srcAddr, dstAddr, payload)
        tcpHeader[16] = (checksum.toInt() shr 8).toByte()
        tcpHeader[17] = (checksum.toInt() and 0xFF).toByte()
        
        return tcpHeader
    }
    
    private fun updateClientSequence(connection: TcpConnection, tcp: PacketParser.TcpHeader, payloadLen: Int) {
        // Update client sequence based on received data
        connection.clientSeq = tcp.seqNum + payloadLen
        if (tcp.isSyn) connection.clientSeq++
        if (tcp.isFin) connection.clientSeq++
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
        return (sum.inv() and 0xFFFF).toShort()
    }
    
    private fun tcpChecksum(tcpHeader: ByteArray, srcAddr: String, dstAddr: String, payload: ByteArray): Short {
        // TCP checksum includes pseudo-header + TCP header + data
        val srcBytes = ipv4ToBytes(srcAddr)
        val dstBytes = ipv4ToBytes(dstAddr)
        val tcpLen = tcpHeader.size + payload.size
        
        // Pseudo header: src(4) + dst(4) + zero(1) + protocol(1) + length(2) = 12 bytes
        val pseudoHeader = ByteArray(12)
        System.arraycopy(srcBytes, 0, pseudoHeader, 0, 4)
        System.arraycopy(dstBytes, 0, pseudoHeader, 4, 4)
        pseudoHeader[8] = 0
        pseudoHeader[9] = PacketParser.PROTO_TCP.toByte()
        pseudoHeader[10] = (tcpLen shr 8).toByte()
        pseudoHeader[11] = (tcpLen and 0xFF).toByte()
        
        var sum = 0L
        
        // Checksum pseudo-header
        for (i in pseudoHeader.indices step 2) {
            val word = ((pseudoHeader[i].toInt() and 0xFF) shl 8) or 
                      (if (i + 1 < pseudoHeader.size) pseudoHeader[i + 1].toInt() and 0xFF else 0)
            sum += word
        }
        
        // Checksum TCP header (skip checksum field at offset 16-17)
        for (i in tcpHeader.indices step 2) {
            if (i == 16) continue // Skip checksum field
            val word = ((tcpHeader[i].toInt() and 0xFF) shl 8) or 
                      (if (i + 1 < tcpHeader.size) tcpHeader[i + 1].toInt() and 0xFF else 0)
            sum += word
        }
        
        // Checksum payload
        for (i in payload.indices step 2) {
            val word = ((payload[i].toInt() and 0xFF) shl 8) or 
                      (if (i + 1 < payload.size) payload[i + 1].toInt() and 0xFF else 0)
            sum += word
        }
        
        // Fold carries
        while ((sum shr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        
        return (sum.inv() and 0xFFFF).toShort()
    }
    
    private fun cleanupConnection(connectionKey: String) {
        connections.remove(connectionKey)?.let { connection ->
            try {
                connection.socket.close()
            } catch (e: Exception) {
                android.util.Log.w("TcpForwarder", "Error closing TCP socket: ${e.message}")
            }
        }
    }
    
    // Periodic cleanup of expired connections
    init {
        scope.launch {
            while (true) {
                delay(60000) // Check every minute
                val now = System.currentTimeMillis()
                val expiredConnections = connections.entries.filter {
                    now - it.value.lastActivity > 600000 // 10 minutes timeout
                }
                expiredConnections.forEach { 
                    cleanupConnection(it.key)
                }
            }
        }
    }
}