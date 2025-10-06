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
                    
                    // Forward payload to real socket
                    scope.launch {
                        try {
                            connection.socket.getOutputStream().write(payload)
                            connection.socket.getOutputStream().flush()
                        } catch (e: Exception) {
                            android.util.Log.w("TcpForwarder", "Error forwarding TCP data: ${e.message}")
                        }
                    }
                }
            }
        }
    }
    
    private fun selectNetworkForTcpConnection(context: Context, dstAddr: String, dstPort: Int): Network {
        val networks = NetworkBinder.getAvailableNetworks(context)
        
        // TCP connection strategy:
        // - HTTPS/TLS (443, 8443): prefer WiFi for bandwidth
        // - HTTP (80, 8080): load balance between networks  
        // - SSH/other secure (22, etc.): prefer stable connection (WiFi)
        // - General: round-robin for load distribution
        return when {
            dstPort in listOf(443, 8443) -> {
                // HTTPS: prefer WiFi for bandwidth
                networks.find { it.transport == NetworkBinder.Transport.WIFI }?.network
                    ?: networks.firstOrNull()?.network
                    ?: getDefaultNetwork(context)
            }
            dstPort in listOf(22, 2222) -> {
                // SSH: prefer WiFi for stability
                networks.find { it.transport == NetworkBinder.Transport.WIFI }?.network
                    ?: networks.firstOrNull()?.network
                    ?: getDefaultNetwork(context)
            }
            else -> {
                // General TCP: round-robin load balancing
                val index = (dstAddr.hashCode() + dstPort) % maxOf(1, networks.size)
                networks.getOrNull(index)?.network ?: getDefaultNetwork(context)
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
        // TODO: Implement proper TCP packet building (similar to UDP)
        // This is a placeholder for the TCP response packet construction
        android.util.Log.d("TcpForwarder", "TCP response: flags=$flags, ack=$ackNum")
    }
    
    private fun sendTcpDataToClient(connection: TcpConnection, payload: ByteArray) {
        // TODO: Implement TCP data packet building and send to TUN
        // This requires proper sequence number management and packet construction
        android.util.Log.d("TcpForwarder", "TCP data to client: ${payload.size} bytes")
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