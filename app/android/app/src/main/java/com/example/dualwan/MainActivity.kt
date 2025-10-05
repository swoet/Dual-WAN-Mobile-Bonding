package com.example.dualwan

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var mainRttText: TextView
    private lateinit var helperRttText: TextView

    private val vm by lazy { MainViewModel(this) }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else {
            statusText.text = "VPN permission denied"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        startButton = findViewById(R.id.start_button)
        stopButton = findViewById(R.id.stop_button)
        mainRttText = findViewById(R.id.main_rtt)
        helperRttText = findViewById(R.id.helper_rtt)

        findViewById<Button>(R.id.select_main_wifi).setOnClickListener {
            vm.setSelection(NetworkBinder.Transport.WIFI, vm.selection.value.helper)
        }
        findViewById<Button>(R.id.select_main_cell).setOnClickListener {
            vm.setSelection(NetworkBinder.Transport.CELLULAR, vm.selection.value.helper)
        }
        findViewById<Button>(R.id.select_helper_wifi).setOnClickListener {
            vm.setSelection(vm.selection.value.main, NetworkBinder.Transport.WIFI)
        }
        findViewById<Button>(R.id.select_helper_cell).setOnClickListener {
            vm.setSelection(vm.selection.value.main, NetworkBinder.Transport.CELLULAR)
        }

        startButton.setOnClickListener { prepareAndStartVpn() }
        stopButton.setOnClickListener { stopVpnService() }
        findViewById<Button>(R.id.test_download).setOnClickListener { startTestDownload() }

        maybeRequestNotificationPermission()

        // Start RTT monitoring
        vm.startMonitoring()
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {})
        // Update UI with a simple polling (avoid full LiveData setup for M1/M2)
        val handler = android.os.Handler(mainLooper)
        val runnable = object : Runnable {
            override fun run() {
                val s = vm.stats.value
                mainRttText.text = "Main RTT: ${'$'}{if (s.mainRttMs>=0) s.mainRttMs else "-"} ms"
                helperRttText.text = "Helper RTT: ${'$'}{if (s.helperRttMs>=0) s.helperRttMs else "-"} ms"
                handler.postDelayed(this, 2000)
            }
        }
        handler.post(runnable)
    }

    private fun prepareAndStartVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, VpnTunnelService::class.java)
        ContextCompat.startForegroundService(this, intent)
        statusText.text = "VPN started"
    }

    private fun startTestDownload() {
        val nets = NetworkBinder.getAvailableNetworks(this)
        val mainNet = nets.firstOrNull { it.transport == vm.selection.value.main }?.network
        val helperNet = nets.firstOrNull { it.transport == vm.selection.value.helper }?.network
        val url = "https://speed.hetzner.de/100MB.bin"
        val sched = DownloadScheduler(this)
        sched.downloadUrl(
            url,
            mainNet,
            helperNet,
            vm.stats.value.helperFraction,
            totalBytes = 10L shl 20,
            onProgress = { res ->
                runOnUiThread {
                    statusText.text = "Main: ${'$'}{res.mainBytes/1024/1024} MiB, Helper: ${'$'}{res.helperBytes/1024/1024} MiB"
                }
            },
            onDone = { res ->
                runOnUiThread {
                    statusText.text = "Done — Main: ${'$'}{res.mainBytes/1024/1024} MiB, Helper: ${'$'}{res.helperBytes/1024/1024} MiB"
                }
            }
        )
    }

    private fun stopVpnService() {
        val intent = Intent(this, VpnTunnelService::class.java)
        stopService(intent)
        statusText.text = "VPN stopped"
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    100
                )
            }
        }
    }
}
