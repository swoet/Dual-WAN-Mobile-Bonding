package com.example.dualwan

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

// UdpForwarder scaffolding: parse UDP header and prepare for future forwarding
object UdpForwarder {
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    fun handlePacket(context: Context, buf: ByteBuffer, ip: PacketParser.IPv4Header) {
        try {
            val udp = PacketParser.parseUdp(buf, ip)
            // TODO: implement NAT + DatagramSocket routing bound to selected Network
            // For now, just enqueue a no-op task so we have a place to extend.
            scope.launch {
                // Placeholder for sending/receiving UDP packets via per-network sockets
                // e.g., for DNS (53), QUIC, etc.
            }
        } catch (_: Exception) {
            // ignore malformed UDP for now
        }
    }
}
