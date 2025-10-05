package com.example.dualwan

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

// Minimal network binder helper for M1
object NetworkBinder {
    fun getAvailableNetworks(context: Context): List<Network> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.allNetworks.filter { n ->
            val caps = cm.getNetworkCapabilities(n)
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }
    }
}
