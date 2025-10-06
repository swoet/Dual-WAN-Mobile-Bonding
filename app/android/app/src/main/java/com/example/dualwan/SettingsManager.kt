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
        
    // Per-app VPN routing
    fun setAppSelected(packageName: String, selected: Boolean) {
        prefs.edit().putBoolean("app_selected_$packageName", selected).apply()
    }
    
    fun isAppSelected(packageName: String): Boolean {
        return prefs.getBoolean("app_selected_$packageName", false)
    }
    
    fun getSelectedApps(): Set<String> {
        return prefs.all.entries
            .filter { it.key.startsWith("app_selected_") && it.value == true }
            .map { it.key.removePrefix("app_selected_") }
            .toSet()
    }
    
    fun clearAllAppSelections() {
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith("app_selected_") }
            .forEach { editor.remove(it) }
        editor.apply()
    }
    
    // VPN mode: "include" (only selected apps) or "exclude" (all except selected apps)
    var vpnMode: String
        get() = prefs.getString("vpn_mode", "exclude") ?: "exclude"
        set(value) = prefs.edit().putString("vpn_mode", value).apply()
}
