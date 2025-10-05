package com.example.dualwan

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

// Network binder helper: enumerate networks and filter by transport types
object NetworkBinder {
    enum class Transport { WIFI, CELLULAR, OTHER }

    data class NetInfo(val network: Network, val transport: Transport)

    fun getAvailableNetworks(context: Context): List<NetInfo> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.allNetworks.mapNotNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
            val transport = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
                else -> Transport.OTHER
            }
            NetInfo(n, transport)
        }
    }
}
