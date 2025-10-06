package com.example.dualwan

import android.content.Context
import android.net.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MultiNetworkSocksProxy provides a local SOCKS5 proxy server that routes connections
 * through different Android networks based on our intelligent network selection logic.
 * 
 * This enables integration with tun2socks libraries while maintaining full control
 * over network selection and multi-path routing.
 */
class MultiNetworkSocksProxy(private val context: Context) {
    private var proxyPort = 1080
    private var proxyServer: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val isRunning = AtomicBoolean(false)
    
    fun start(): Int {
        if (isRunning.get()) return proxyPort
        
        try {
            val serverSocket = ServerSocket(0) // Use any available port
            proxyPort = serverSocket.localPort
            proxyServer = serverSocket
            isRunning.set(true)
            
            android.util.Log.i("MultiNetworkSocksProxy", "Started SOCKS5 proxy on port $proxyPort")
            
            // Accept SOCKS5 connections in background
            scope.launch {
                while (isRunning.get() && !serverSocket.isClosed) {
                    try {
                        val client = serverSocket.accept()
                        scope.launch {
                            handleSocksConnection(client)
                        }
                    } catch (e: Exception) {
                        if (isRunning.get()) {
                            android.util.Log.w("MultiNetworkSocksProxy", "Error accepting connection: ${e.message}")
                        }
                    }
                }
            }
            
            return proxyPort
        } catch (e: Exception) {
            android.util.Log.e("MultiNetworkSocksProxy", "Failed to start SOCKS proxy: ${e.message}")
            throw e
        }
    }
    
    fun stop() {
        if (!isRunning.get()) return
        
        isRunning.set(false)
        try {
            proxyServer?.close()
            android.util.Log.i("MultiNetworkSocksProxy", "Stopped SOCKS5 proxy")
        } catch (e: Exception) {
            android.util.Log.w("MultiNetworkSocksProxy", "Error stopping proxy: ${e.message}")
        }
    }
    
    private suspend fun handleSocksConnection(client: Socket) {
        try {
            client.soTimeout = 30000 // 30 second timeout
            val input = client.getInputStream()
            val output = client.getOutputStream()
            
            // SOCKS5 handshake
            if (!performSocks5Handshake(input, output)) {
                client.close()
                return
            }
            
            // Parse CONNECT request
            val connectRequest = parseSocks5ConnectRequest(input) ?: run {
                sendSocks5Response(output, 0x01) // General failure
                client.close()
                return
            }
            
            // Select network for this connection
            val network = selectNetworkForConnection(connectRequest.host, connectRequest.port)
            
            // Establish connection to target server
            val serverSocket = try {
                createNetworkBoundSocket(network, connectRequest.host, connectRequest.port)
            } catch (e: Exception) {
                android.util.Log.w("MultiNetworkSocksProxy", "Failed to connect to ${connectRequest.host}:${connectRequest.port} via network: ${e.message}")
                sendSocks5Response(output, 0x04) // Host unreachable
                client.close()
                return
            }
            
            // Send success response
            sendSocks5Response(output, 0x00) // Success
            
            // Start bidirectional forwarding
            forwardTraffic(client, serverSocket)
            
        } catch (e: Exception) {
            android.util.Log.w("MultiNetworkSocksProxy", "Error handling SOCKS connection: ${e.message}")
        } finally {
            try {
                client.close()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
    
    private fun performSocks5Handshake(input: InputStream, output: OutputStream): Boolean {
        try {
            // Read client greeting
            val greeting = ByteArray(2)
            if (input.read(greeting) != 2) return false
            
            val version = greeting[0].toInt() and 0xFF
            val nMethods = greeting[1].toInt() and 0xFF
            
            if (version != 5) return false // Only SOCKS5
            
            // Read authentication methods
            val methods = ByteArray(nMethods)
            if (input.read(methods) != nMethods) return false
            
            // We only support "no authentication" (0x00)
            if (!methods.contains(0x00.toByte())) return false
            
            // Send method selection response
            output.write(byteArrayOf(0x05, 0x00)) // SOCKS5, no auth
            output.flush()
            
            return true
        } catch (e: Exception) {
            return false
        }
    }
    
    data class ConnectRequest(val host: String, val port: Int)
    
    private fun parseSocks5ConnectRequest(input: InputStream): ConnectRequest? {
        try {
            val request = ByteArray(4)
            if (input.read(request) != 4) return null
            
            val version = request[0].toInt() and 0xFF
            val cmd = request[1].toInt() and 0xFF
            val atyp = request[3].toInt() and 0xFF
            
            if (version != 5 || cmd != 1) return null // Only SOCKS5 CONNECT
            
            val host = when (atyp) {
                0x01 -> { // IPv4
                    val addr = ByteArray(4)
                    if (input.read(addr) != 4) return null
                    InetAddress.getByAddress(addr).hostAddress
                }
                0x03 -> { // Domain name
                    val domainLen = input.read()
                    if (domainLen <= 0) return null
                    val domain = ByteArray(domainLen)
                    if (input.read(domain) != domainLen) return null
                    String(domain)
                }
                0x04 -> { // IPv6
                    val addr = ByteArray(16)
                    if (input.read(addr) != 16) return null
                    InetAddress.getByAddress(addr).hostAddress
                }
                else -> return null
            }
            
            // Read port
            val portBytes = ByteArray(2)
            if (input.read(portBytes) != 2) return null
            val port = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
            
            return ConnectRequest(host, port)
        } catch (e: Exception) {
            return null
        }
    }
    
    private fun sendSocks5Response(output: OutputStream, status: Int) {
        try {
            // SOCKS5 response: VER(1) + REP(1) + RSV(1) + ATYP(1) + BND.ADDR(4) + BND.PORT(2) = 10 bytes
            val response = byteArrayOf(
                0x05.toByte(), // SOCKS5
                status.toByte(), // Status code
                0x00, // Reserved
                0x01, // IPv4 address type
                0x00, 0x00, 0x00, 0x00, // Bind address (0.0.0.0)
                0x00, 0x00 // Bind port (0)
            )
            output.write(response)
            output.flush()
        } catch (e: Exception) {
            android.util.Log.w("MultiNetworkSocksProxy", "Error sending SOCKS response: ${e.message}")
        }
    }
    
    private fun selectNetworkForConnection(host: String, port: Int): Network {
        // Use our existing network selection logic from TcpForwarder
        return when {
            port in listOf(443, 8443) -> {
                // HTTPS: prefer best WiFi
                NetworkQualityMonitor.getBestNetwork(context, NetworkBinder.Transport.WIFI)
                    ?: NetworkQualityMonitor.getBestNetwork(context)
                    ?: getDefaultNetwork()
            }
            port in listOf(22, 2222) -> {
                // SSH: prefer most stable network
                NetworkQualityMonitor.getCurrentQualities(context)
                    .filter { it.isAvailable && it.score > 10f }
                    .maxByOrNull { it.stability }?.network
                    ?: NetworkQualityMonitor.getBestNetwork(context, NetworkBinder.Transport.WIFI)
                    ?: getDefaultNetwork()
            }
            port in listOf(80, 8080) -> {
                // HTTP: quality-based load balancing
                val goodNetworks = NetworkQualityMonitor.getCurrentQualities(context)
                    .filter { it.isAvailable && it.score > 15f }
                if (goodNetworks.isNotEmpty()) {
                    val index = (host.hashCode() + port) % goodNetworks.size
                    goodNetworks[index].network
                } else {
                    NetworkQualityMonitor.getBestNetwork(context) ?: getDefaultNetwork()
                }
            }
            else -> {
                // General: use best available network
                NetworkQualityMonitor.getBestNetwork(context) ?: getDefaultNetwork()
            }
        }
    }
    
    private fun createNetworkBoundSocket(network: Network, host: String, port: Int): Socket {
        val socket = Socket()
        network.bindSocket(socket)
        socket.soTimeout = 30000
        socket.connect(InetSocketAddress(host, port), 10000)
        return socket
    }
    
    private fun getDefaultNetwork(): Network {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        return cm.activeNetwork ?: throw RuntimeException("No active network available")
    }
    
    private fun forwardTraffic(client: Socket, server: Socket) {
        scope.launch {
            try {
                // Client -> Server forwarding
                scope.launch {
                    client.getInputStream().use { input ->
                        server.getOutputStream().use { output ->
                            val buffer = ByteArray(8192)
                            while (isRunning.get()) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                output.flush()
                            }
                        }
                    }
                }
                
                // Server -> Client forwarding
                server.getInputStream().use { input ->
                    client.getOutputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (isRunning.get()) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            output.flush()
                        }
                    }
                }
            } catch (e: Exception) {
                // Connection closed or error
            } finally {
                try {
                    client.close()
                    server.close()
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
    }
