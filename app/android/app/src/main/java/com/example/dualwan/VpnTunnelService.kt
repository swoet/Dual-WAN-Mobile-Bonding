package com.example.dualwan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

class VpnTunnelService : VpnService() {
    private var vpnJob: Job? = null
    private var tunInterface: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, buildNotification("Dual-WAN VPN is running"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (vpnJob?.isActive == true) return START_STICKY
        vpnJob = CoroutineScope(Dispatchers.IO).launch {
            startVpnLoop()
        }
        return START_STICKY
    }

    private suspend fun startVpnLoop() {
        val builder = Builder()
            .addAddress("10.0.0.2", 24)
            .addRoute("0.0.0.0", 0)
            .setSession("DualWanVpn")
        tunInterface = builder.establish()
        val pfd = tunInterface ?: return
        val input = FileInputStream(pfd.fileDescriptor).channel
        val output = FileOutputStream(pfd.fileDescriptor).channel
        val packetBuf = ByteBuffer.allocate(32767)

        while (isActive) {
            packetBuf.clear()
            val read = input.read(packetBuf)
            if (read > 0) {
                packetBuf.flip()
                try {
                    val ip = PacketParser.parse(packetBuf)
                    if (ip.protocol == PacketParser.PROTO_UDP) {
                        UdpForwarder.handlePacket(applicationContext, packetBuf, ip)
                    } else {
                        // For now, submit non-UDP packets to placeholder scheduler
                        FlowScheduler.submit(ip)
                    }
                } catch (e: Exception) {
                    // ignore malformed packets in M1/M2 scaffolding
                }
            } else {
                delay(10)
            }
        }
        input.close()
        output.close()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                "dualwan_vpn",
                "Dual-WAN VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(chan)
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = NotificationCompat.Builder(this, "dualwan_vpn")
            .setContentTitle("Dual-WAN VPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
        return builder.build()
    }

    override fun onDestroy() {
        vpnJob?.cancel()
        tunInterface?.close()
        super.onDestroy()
    }
}
