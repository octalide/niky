package com.octalide.niky

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.content.ComponentName
import android.location.LocationRequest
import android.os.IBinder
import android.os.PowerManager
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the BLE link and the location subscription.
 * It survives the activity going away so a real shoot doesn't require keeping
 * the app on screen.
 */
@SuppressLint("MissingPermission")
class NikyService : LifecycleService() {

    private lateinit var locMgr: LocationManager
    private lateinit var nus: NusClient
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        locMgr = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        nus = NusClient(this)
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "niky::ble").apply {
                setReferenceCounted(false)
                acquire()
            }
        lifecycleScope.launch {
            nus.state.collect { _bleState.value = it }
        }
        lifecycleScope.launch {
            _running.collect {
                NikyToggleWidget.refreshAll(this@NikyService)
                TileService.requestListeningState(
                    this@NikyService,
                    ComponentName(this@NikyService, NikyQsTile::class.java),
                )
            }
        }
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        nus.start()
        try {
            val provider = if (locMgr.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
                LocationManager.FUSED_PROVIDER
            } else {
                LocationManager.GPS_PROVIDER
            }
            // Modern API: legacy minTime arg is treated as a hint and gets throttled
            // hard on Android 14+, which produced 30s gaps in v0.1 and broke the
            // camera's 2s-max NMEA fix tolerance.
            val req = LocationRequest.Builder(1000L)
                .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                .setMinUpdateIntervalMillis(1000L)
                .build()
            locMgr.requestLocationUpdates(
                provider,
                req,
                ContextCompat.getMainExecutor(this),
                locListener,
            )
            _running.value = true
        } catch (_: SecurityException) {
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try { locMgr.removeUpdates(locListener) } catch (_: Exception) {}
        nus.stop()
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        _running.value = false
        _bleState.value = NusClient.State.IDLE
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private val locListener = LocationListener { loc ->
        _lastFix.value = loc
        nus.send(Nmea.encode(loc))
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "niky GPS", NotificationManager.IMPORTANCE_LOW).apply {
                description = "GPS bridge to camera"
            },
        )
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("niky GPS")
            .setContentText("Bridging phone GPS to camera")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    }

    companion object {
        private const val CHANNEL_ID = "niky_gps"
        private const val NOTIF_ID = 1

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        private val _lastFix = MutableStateFlow<Location?>(null)
        val lastFix: StateFlow<Location?> = _lastFix

        private val _bleState = MutableStateFlow(NusClient.State.IDLE)
        val bleState: StateFlow<NusClient.State> = _bleState

        fun startSvc(ctx: Context) = ctx.startForegroundService(Intent(ctx, NikyService::class.java))
        fun stopSvc(ctx: Context)  = ctx.stopService(Intent(ctx, NikyService::class.java))
    }
}
