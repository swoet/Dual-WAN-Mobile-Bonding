package com.example.dualwan

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.measureTimeMillis

data class NetworkQuality(
    val network: Network,
    val transport: NetworkBinder.Transport,
    val rttMs: Long = -1L,
    val packetLoss: Float = 0f,
    val bandwidth: Long = -1L, // Estimated bandwidth in bytes/sec
    val stability: Float = 1f, // 0-1, higher = more stable
    val lastUpdate: Long = System.currentTimeMillis(),
    val isAvailable: Boolean = true
) {
    val score: Float get() {
        if (!isAvailable || rttMs < 0) return 0f
        // Higher score = better network
        // Factors: low RTT, low packet loss, high stability
        val rttScore = maxOf(0f, 1f - (rttMs / 1000f)) // RTT penalty
        val lossScore = 1f - packetLoss
        return (rttScore * 0.4f + lossScore * 0.3f + stability * 0.3f) * 100f
    }
}

object NetworkQualityMonitor {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val qualityHistory = ConcurrentHashMap<Network, MutableList<NetworkQuality>>()
    private val latestQuality = ConcurrentHashMap<Network, NetworkQuality>()
    private var isMonitoring = false
    
    // Test endpoints for quality measurement
    private val testHosts = listOf(
        "8.8.8.8",      // Google DNS
        "1.1.1.1",      // Cloudflare DNS
        "208.67.222.222" // OpenDNS
    )
    
    fun startMonitoring(context: Context) {
        if (isMonitoring) return
        isMonitoring = true
        
        scope.launch {
            while (isMonitoring) {
                measureAllNetworks(context)
                delay(10000) // Measure every 10 seconds
            }
        }
    }
    
    fun stopMonitoring() {
        isMonitoring = false
    }
    
    fun getBestNetwork(context: Context, preferredTransport: NetworkBinder.Transport? = null): Network? {
        val qualities = getCurrentQualities(context)
        
        // Filter by preferred transport if specified
        val filtered = if (preferredTransport != null) {
            qualities.filter { it.transport == preferredTransport }
        } else {
            qualities
        }
        
        // Return network with highest score
        return filtered.maxByOrNull { it.score }?.network
    }
    
    fun getCurrentQualities(context: Context): List<NetworkQuality> {
        val networks = NetworkBinder.getAvailableNetworks(context)
        return networks.map { netInfo ->
            latestQuality[netInfo.network] ?: NetworkQuality(
                network = netInfo.network,
                transport = netInfo.transport,
                isAvailable = true
            )
        }
    }
    
    fun getNetworkQuality(network: Network): NetworkQuality? {
        return latestQuality[network]
    }
    
    fun shouldFailover(currentNetwork: Network, alternativeNetwork: Network): Boolean {
        val current = latestQuality[currentNetwork] ?: return false
        val alternative = latestQuality[alternativeNetwork] ?: return false
        
        // Failover if current network is significantly worse
        return when {
            !current.isAvailable -> true
            current.packetLoss > 0.1f && alternative.packetLoss < 0.05f -> true
            current.rttMs > 2000 && alternative.rttMs < 1000 -> true
            current.score < alternative.score - 20f -> true // 20 point difference threshold
            else -> false
        }
    }
    
    private suspend fun measureAllNetworks(context: Context) {
        val networks = NetworkBinder.getAvailableNetworks(context)
        
        networks.forEach { netInfo ->
            scope.launch {
                val quality = measureNetworkQuality(context, netInfo)
                latestQuality[netInfo.network] = quality
                
                // Keep history for stability calculation
                val history = qualityHistory.getOrPut(netInfo.network) { mutableListOf() }
                history.add(quality)
                if (history.size > 10) history.removeAt(0) // Keep last 10 measurements
                
                // Update stability based on RTT variance
                if (history.size >= 3) {
                    val rttValues = history.map { it.rttMs }.filter { it > 0 }
                    if (rttValues.size >= 3) {
                        val avgRtt = rttValues.average()
                        val variance = rttValues.map { (it - avgRtt) * (it - avgRtt) }.average()
                        val stability = maxOf(0f, 1f - (variance / (avgRtt * avgRtt)).toFloat())
                        
                        latestQuality[netInfo.network] = quality.copy(stability = stability)
                    }
                }
            }
        }
    }
    
    private suspend fun measureNetworkQuality(
        context: Context, 
        netInfo: NetworkBinder.NetInfo
    ): NetworkQuality {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val capabilities = cm.getNetworkCapabilities(netInfo.network)
            
            if (capabilities == null || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return NetworkQuality(
                    network = netInfo.network,
                    transport = netInfo.transport,
                    isAvailable = false
                )
            }
            
            // Measure RTT to multiple hosts
            val rttResults = mutableListOf<Long>()
            var successCount = 0
            
            for (host in testHosts.take(2)) { // Test 2 hosts for speed
                try {
                    val rtt = measureRTT(netInfo.network, host)
                    if (rtt > 0) {
                        rttResults.add(rtt)
                        successCount++
                    }
                } catch (e: Exception) {
                    android.util.Log.w("NetworkQualityMonitor", "RTT test failed for $host: ${e.message}")
                }
            }
            
            val avgRtt = if (rttResults.isNotEmpty()) rttResults.average().toLong() else -1L
            val packetLoss = 1f - (successCount.toFloat() / testHosts.take(2).size)
            
            return NetworkQuality(
                network = netInfo.network,
                transport = netInfo.transport,
                rttMs = avgRtt,
                packetLoss = packetLoss,
                isAvailable = successCount > 0
            )
            
        } catch (e: Exception) {
            android.util.Log.w("NetworkQualityMonitor", "Quality measurement failed: ${e.message}")
            return NetworkQuality(
                network = netInfo.network,
                transport = netInfo.transport,
                isAvailable = false
            )
        }
    }
    
    private suspend fun measureRTT(network: Network, host: String): Long {
        return try {
            val socket = java.net.Socket()
            network.bindSocket(socket)
            socket.soTimeout = 3000
            
            val startTime = System.currentTimeMillis()
            
            // Try to resolve and connect
            val address = InetAddress.getByName(host)
            socket.connect(java.net.InetSocketAddress(address, 53), 3000) // DNS port
            
            val rtt = System.currentTimeMillis() - startTime
            socket.close()
            rtt
        } catch (e: Exception) {
            // Fallback: try ping via InetAddress.isReachable (less reliable but works)
            try {
                val address = InetAddress.getByName(host)
                val rtt = measureTimeMillis {
                    network.bindSocket(java.net.DatagramSocket().also {
                        it.soTimeout = 3000
                        it.connect(java.net.InetSocketAddress(address, 53))
                        it.close()
                    })
                }
                rtt
            } catch (e2: Exception) {
                -1L
            }
        }
    }
}