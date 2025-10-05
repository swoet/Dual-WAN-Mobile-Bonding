package com.example.dualwan

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate

object BondingClient {
    private fun insecureTrustAllFactory(): SSLSocketFactory {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sc = SSLContext.getInstance("TLS")
        sc.init(null, trustAllCerts, SecureRandom())
        return sc.socketFactory
    }

    fun testConnect(context: Context, host: String, port: Int, insecure: Boolean): Boolean {
        if (host.isEmpty()) return false
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net: Network? = cm.activeNetwork
        val sf = net?.socketFactory
        val socket = sf?.createSocket() ?: Socket()
        try {
            socket.connect(InetSocketAddress(host, port), 5000)
            val out = BufferedOutputStream(socket.getOutputStream())
            val `in` = BufferedInputStream(socket.getInputStream())
            val line = "CONNECT example.com 443\n".toByteArray()
            out.write(line)
            out.flush()
            // After this, server pipes bytes; we try to start TLS to example.com
            val sslFactory = if (insecure) insecureTrustAllFactory() else SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = sslFactory.createSocket(socket, "example.com", 443, true)
            ssl.soTimeout = 5000
            // Send TLS ClientHello implicitly by starting handshake
            (ssl as javax.net.ssl.SSLSocket).startHandshake()
            // If handshake succeeds, return true
            ssl.close()
            return true
        } catch (e: Exception) {
            return false
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }
}
