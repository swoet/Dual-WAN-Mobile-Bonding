package com.example.dualwan

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: android.graphics.drawable.Drawable?,
    var isSelected: Boolean = false,
    val hasNetworkPermission: Boolean = false
)

class AppSelectionActivity : AppCompatActivity() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AppAdapter
    private val settingsManager by lazy { SettingsManager(this) }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_selection)
        
        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        
        // Load apps with network permissions
        CoroutineScope(Dispatchers.IO).launch {
            val apps = loadAppsWithNetworkPermission()
            withContext(Dispatchers.Main) {
                adapter = AppAdapter(apps) { app, isSelected ->
                    settingsManager.setAppSelected(app.packageName, isSelected)
                }
                recyclerView.adapter = adapter
            }
        }
    }
    
    private fun loadAppsWithNetworkPermission(): List<AppInfo> {
        val pm = packageManager
        val apps = mutableListOf<AppInfo>()
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        
        for (appInfo in installedApps) {
            // Skip system apps unless they have network permission
            if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && 
                !hasNetworkPermission(appInfo.packageName)) {
                continue
            }
            
            val hasNetworkPerm = hasNetworkPermission(appInfo.packageName)
            if (!hasNetworkPerm) continue // Only show apps that can use network
            
            val appName = try {
                pm.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                appInfo.packageName
            }
            
            val icon = try {
                pm.getApplicationIcon(appInfo.packageName)
            } catch (e: Exception) {
                null
            }
            
            val isSelected = settingsManager.isAppSelected(appInfo.packageName)
            
            apps.add(
                AppInfo(
                    packageName = appInfo.packageName,
                    appName = appName,
                    icon = icon,
                    isSelected = isSelected,
                    hasNetworkPermission = hasNetworkPerm
                )
            )
        }
        
        return apps.sortedBy { it.appName.lowercase() }
    }
    
    private fun hasNetworkPermission(packageName: String): Boolean {
        return try {
            val packageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            val permissions = packageInfo.requestedPermissions
            permissions?.contains(android.Manifest.permission.INTERNET) == true
        } catch (e: Exception) {
            false
        }
    }
}

class AppAdapter(
    private val apps: List<AppInfo>,
    private val onAppToggle: (AppInfo, Boolean) -> Unit
) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {
    
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.appIcon)
        val name: TextView = view.findViewById(R.id.appName)
        val packageName: TextView = view.findViewById(R.id.packageName)
        val switch: Switch = view.findViewById(R.id.appSwitch)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_selection, parent, false)
        return ViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        
        holder.icon.setImageDrawable(app.icon)
        holder.name.text = app.appName
        holder.packageName.text = app.packageName
        holder.switch.isChecked = app.isSelected
        
        holder.switch.setOnCheckedChangeListener { _, isChecked ->
            app.isSelected = isChecked
            onAppToggle(app, isChecked)
        }
    }
    
    override fun getItemCount() = apps.size
}