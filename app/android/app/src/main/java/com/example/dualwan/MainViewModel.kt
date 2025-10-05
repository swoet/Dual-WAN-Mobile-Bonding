package com.example.dualwan

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Socket

class MainViewModel(private val context: Context) {
    data class Selection(
        val main: NetworkBinder.Transport? = null,
        val helper: NetworkBinder.Transport? = null
    )

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val _selection = MutableStateFlow(Selection())
    val selection: StateFlow<Selection> = _selection

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats

    data class Stats(
        val mainRttMs: Long = -1,
        val helperRttMs: Long = -1,
        val helperFraction: Int = 15
    )

    fun setSelection(main: NetworkBinder.Transport?, helper: NetworkBinder.Transport?) {
        _selection.value = Selection(main, helper)
    }

    fun startMonitoring() {
        scope.launch {
            var lastMain = -1L
            while (isActive) {
                val nets = NetworkBinder.getAvailableNetworks(context)
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val mainNet = nets.firstOrNull { it.transport == _selection.value.main }?.network
                val helperNet = nets.firstOrNull { it.transport == _selection.value.helper }?.network
                val mainRtt = mainNet?.let { measureRtt(cm, it) } ?: -1
                val helperRtt = helperNet?.let { measureRtt(cm, it) } ?: -1
                val updated = adjustHelperFraction(_stats.value.helperFraction, mainRtt, helperRtt, lastMain)
                _stats.value = _stats.value.copy(mainRttMs = mainRtt, helperRttMs = helperRtt, helperFraction = updated)
                lastMain = mainRtt
                delay(2000)
            }
        }
    }

    private fun adjustHelperFraction(current: Int, mainRtt: Long, helperRtt: Long, lastMain: Long): Int {
        var hf = current
        if (mainRtt > 0 && lastMain > 0) {
            val spike = mainRtt - lastMain
            if (spike > 200) hf = minOf(50, hf + 10)
        }
        if (mainRtt in 1..150) {
            // decay towards 15
            if (hf > 15) hf = maxOf(15, hf - 5)
        }
        return hf
    }

    private fun measureRtt(cm: ConnectivityManager, net: Network): Long {
        // Simple HTTP HEAD to example.com to estimate RTT
        val client = OkHttpClient.Builder()
            .socketFactory(net.socketFactory)
            .build()
        val req = Request.Builder().url("https://example.com/").head().build()
        val start = System.nanoTime()
        return try {
            client.newCall(req).execute().use { }
            ((System.nanoTime() - start) / 1_000_000)
        } catch (e: Exception) {
            -1
        }
    }
}
