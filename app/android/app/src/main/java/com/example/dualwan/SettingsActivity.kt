package com.example.dualwan

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    private lateinit var hostEdit: EditText
    private lateinit var portEdit: EditText
    private lateinit var insecureCheck: CheckBox
    private lateinit var statusText: TextView

    private lateinit var settings: SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.title = getString(R.string.settings_title)

        settings = SettingsManager(this)

        hostEdit = findViewById(R.id.server_host)
        portEdit = findViewById(R.id.server_port)
        insecureCheck = findViewById(R.id.insecure)
        statusText = findViewById(R.id.status)

        hostEdit.setText(settings.serverHost)
        portEdit.setText(settings.serverPort.toString())
        insecureCheck.isChecked = settings.insecure

        findViewById<Button>(R.id.save).setOnClickListener {
            settings.serverHost = hostEdit.text.toString().trim()
            settings.serverPort = portEdit.text.toString().toIntOrNull() ?: 8443
            settings.insecure = insecureCheck.isChecked
            statusText.text = "Saved"
        }

        findViewById<Button>(R.id.test_connect).setOnClickListener {
            val host = hostEdit.text.toString().trim()
            val port = portEdit.text.toString().toIntOrNull() ?: 8443
            val insecure = insecureCheck.isChecked
            statusText.text = "Testing..."
            CoroutineScope(Dispatchers.IO).launch {
                val ok = BondingClient.testConnect(this@SettingsActivity, host, port, insecure)
                runOnUiThread {
                    statusText.text = if (ok) "OK (CONNECT example.com:443 succeeded)" else "Failed"
                }
            }
        }
    }
}
