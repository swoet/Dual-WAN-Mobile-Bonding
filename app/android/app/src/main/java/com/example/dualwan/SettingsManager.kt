package com.example.dualwan

import android.content.Context

class SettingsManager(context: Context) {
    private val prefs = context.getSharedPreferences("dualwan_prefs", Context.MODE_PRIVATE)

    var serverHost: String
        get() = prefs.getString("server_host", "") ?: ""
        set(value) = prefs.edit().putString("server_host", value).apply()

    var serverPort: Int
        get() = prefs.getInt("server_port", 8443)
        set(value) = prefs.edit().putInt("server_port", value).apply()

    var insecure: Boolean
        get() = prefs.getBoolean("insecure", true)
        set(value) = prefs.edit().putBoolean("insecure", value).apply()
}
