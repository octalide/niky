package com.octalide.niky

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.octalide.niky.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private val requiredPerms = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.all { it.value }) startBridge() else b.status.text = "permissions denied"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.toggle.setOnClickListener {
            if (NikyService.running.value) {
                NikyService.stopSvc(this)
            } else {
                if (hasPerms()) startBridge() else permLauncher.launch(requiredPerms)
            }
        }

        b.batteryButton.setOnClickListener { requestBatteryExemption() }

        lifecycleScope.launch {
            combine(NikyService.running, NikyService.bleState, NikyService.lastFix) { run, ble, fix ->
                Triple(run, ble, fix)
            }.collect { (run, ble, fix) ->
                b.toggle.text = if (run) "Stop" else "Start"
                val fixStr = fix?.let {
                    "%.5f, %.5f  +/-%.0fm".format(it.latitude, it.longitude, it.accuracy)
                } ?: "no fix yet"
                b.status.text = buildString {
                    append("service: "); append(if (run) "running" else "stopped"); append('\n')
                    append("ble: "); append(ble.name.lowercase()); append('\n')
                    append("fix: "); append(fixStr)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshBatteryHint()
    }

    private fun hasPerms() = requiredPerms.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startBridge() = NikyService.startSvc(this)

    private fun refreshBatteryHint() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoring = pm.isIgnoringBatteryOptimizations(packageName)
        b.batteryHint.visibility = if (ignoring) View.GONE else View.VISIBLE
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName"))
            startActivity(intent)
        } catch (_: Exception) {
            // Fallback: list view
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            catch (_: Exception) { /* give up silently */ }
        }
    }
}
