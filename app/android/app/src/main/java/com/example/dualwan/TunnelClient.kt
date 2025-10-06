package com.example.dualwan

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket

// TunnelClient opens an HTTP Upgrade tunnel to /tunnel for a given link (main/helper)
class TunnelClient(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    fun stop() { scope.cancel() }

    fun start(host: String, port: Int, link: String, network: Network, onReady: () -> Unit = {}, onError: (Exception) -> Unit = {}) {
        scope.launch {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            try {
                val socket = network.socketFactory.createSocket() as Socket
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), 5000)
                val out = BufferedOutputStream(socket.getOutputStream())
                val `in` = BufferedInputStream(socket.getInputStream())
                val req = (
                    "GET /tunnel?link=${'$'}link HTTP/1.1\r\n" +
                    "Host: ${'$'}host:${'$'}port\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Upgrade: dwnb\r\n" +
                    "\r\n"
                ).toByteArray()
                out.write(req); out.flush()
                val buf = ByteArray(1024)
                val n = `in`.read(buf)
                val resp = if (n>0) String(buf, 0, n) else ""
                if (!resp.contains("101")) throw RuntimeException("Upgrade failed: ${'$'}resp")
                onReady()
                // From here, socket is raw binary stream for TunnelFrame encode/decode
                // TODO: send/receive frames
            } catch (e: Exception) {
                onError(e)
            }
        }
    }
}
