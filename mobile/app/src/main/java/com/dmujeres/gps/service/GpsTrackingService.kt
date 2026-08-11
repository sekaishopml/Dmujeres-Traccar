package com.dmujeres.gps.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.BatteryManager
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.dmujeres.gps.GpsApplication
import com.dmujeres.gps.R
import com.dmujeres.gps.data.AppDatabase
import com.dmujeres.gps.data.PositionEntity
import com.dmujeres.gps.data.PreferencesManager
import com.dmujeres.gps.data.TrackingStatusHolder
import com.dmujeres.gps.ui.MainActivity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class GpsTrackingService : LifecycleService() {

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var prefsManager: PreferencesManager
    private lateinit var database: AppDatabase

    private var mqttManager: MqttManager? = null
    private var locationCallback: LocationCallback? = null
    private var signalMonitorJob: Job? = null
    private var queueFlushJob: Job? = null

    private val lastSuccessfulPublish = AtomicLong(System.currentTimeMillis())
    private var noSignalNotificationShown = false

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        prefsManager = PreferencesManager(this)
        database = AppDatabase.getInstance(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(GpsApplication.NOTIFICATION_ID_TRACKING, buildTrackingNotification())
        lifecycleScope.launch {
            startTracking()
        }
        return START_STICKY
    }

    private suspend fun startTracking() {
        val prefs = prefsManager.preferences.first()
        if (prefs.uniqueId.isBlank() || prefs.serverHost.isBlank()) {
            Log.w(TAG, "Missing uniqueId or server host")
            stopSelf()
            return
        }

        TrackingStatusHolder.update { it.copy(isTracking = true) }

        mqttManager = MqttManager(prefs.serverHost, prefs.serverPort, prefs.uniqueId)
        val connected = mqttManager?.connect() == true
        updateConnectionState(connected)

        startLocationUpdates(prefs.locationIntervalSeconds)
        startSignalMonitor()
        startQueueFlushLoop()
        flushOfflineQueue()
    }

    private fun startLocationUpdates(intervalSeconds: Int) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Location permission missing")
            return
        }

        val intervalMs = (intervalSeconds.coerceAtLeast(1) * 1000L)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs)
            .setWaitForAccurateLocation(false)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    lifecycleScope.launch { handleLocation(location) }
                }
            }
        }

        fusedClient.requestLocationUpdates(
            request,
            locationCallback!!,
            Looper.getMainLooper()
        )
    }

    private suspend fun handleLocation(location: Location) {
        val payload = buildPositionJson(location)
        val sent = publishOrQueue(payload)

        if (sent) {
            lastSuccessfulPublish.set(System.currentTimeMillis())
            if (noSignalNotificationShown) {
                clearNoSignalNotification()
            }
            val text = String.format(
                Locale.US,
                "%.5f, %.5f",
                location.latitude,
                location.longitude
            )
            TrackingStatusHolder.update {
                it.copy(
                    lastPositionText = text,
                    hasSignal = true,
                    isConnected = mqttManager?.isConnected == true
                )
            }
        } else {
            updateQueueSize()
        }
    }

    private fun buildPositionJson(location: Location): String {
        val battery = getBatteryLevel()
        val json = JSONObject()
        json.put("lat", location.latitude)
        json.put("lon", location.longitude)
        json.put("ts", location.time / 1000)
        json.put("speed", location.speed.toDouble())
        json.put("course", location.bearing.toDouble())
        json.put("alt", location.altitude)
        json.put("acc", location.accuracy.toDouble())
        json.put("batt", battery)
        return json.toString()
    }

    private fun getBatteryLevel(): Int {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private suspend fun publishOrQueue(payload: String): Boolean {
        val manager = mqttManager ?: return false
        if (!manager.isConnected) {
            enqueue(payload)
            return false
        }
        val success = manager.publishPosition(payload)
        if (!success) {
            enqueue(payload)
        }
        return success
    }

    private suspend fun enqueue(payload: String) {
        database.positionDao().insert(PositionEntity(payload = payload))
        updateQueueSize()
    }

    private suspend fun flushOfflineQueue() {
        val dao = database.positionDao()
        val pending = dao.getAll()
        for (item in pending) {
            val sent = mqttManager?.publishPosition(item.payload) == true
            if (sent) {
                dao.deleteById(item.id)
                lastSuccessfulPublish.set(System.currentTimeMillis())
            } else {
                break
            }
        }
        updateQueueSize()
    }

    private fun startQueueFlushLoop() {
        queueFlushJob?.cancel()
        queueFlushJob = lifecycleScope.launch {
            while (isActive) {
                delay(10_000)
                if (mqttManager?.isConnected == true) {
                    flushOfflineQueue()
                }
            }
        }
    }

    private fun startSignalMonitor() {
        signalMonitorJob?.cancel()
        signalMonitorJob = lifecycleScope.launch {
            while (isActive) {
                delay(5_000)
                val elapsed = System.currentTimeMillis() - lastSuccessfulPublish.get()
                val hasSignal = elapsed < SIGNAL_TIMEOUT_MS
                TrackingStatusHolder.update { it.copy(hasSignal = hasSignal) }
                if (!hasSignal && !noSignalNotificationShown) {
                    showNoSignalNotification()
                } else if (hasSignal && noSignalNotificationShown) {
                    clearNoSignalNotification()
                }
            }
        }
    }

    private fun updateConnectionState(connected: Boolean) {
        TrackingStatusHolder.update { it.copy(isConnected = connected) }
    }

    private suspend fun updateQueueSize() {
        val count = database.positionDao().count()
        TrackingStatusHolder.update { it.copy(queueSize = count) }
    }

    private fun showNoSignalNotification() {
        noSignalNotificationShown = true
        val notification = NotificationCompat.Builder(this, GpsApplication.CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(getString(R.string.notification_no_signal_title))
            .setContentText(getString(R.string.notification_no_signal_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(this).notify(
            GpsApplication.NOTIFICATION_ID_NO_SIGNAL,
            notification
        )
    }

    private fun clearNoSignalNotification() {
        noSignalNotificationShown = false
        NotificationManagerCompat.from(this).cancel(GpsApplication.NOTIFICATION_ID_NO_SIGNAL)
    }

    private fun buildTrackingNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, GpsApplication.CHANNEL_TRACKING)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.notification_tracking_title))
            .setContentText(getString(R.string.notification_tracking_text))
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        locationCallback?.let { fusedClient.removeLocationUpdates(it) }
        signalMonitorJob?.cancel()
        queueFlushJob?.cancel()
        mqttManager?.disconnect()
        TrackingStatusHolder.update {
            it.copy(isTracking = false, isConnected = false)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    companion object {
        private const val TAG = "GpsTrackingService"
        private const val SIGNAL_TIMEOUT_MS = 60_000L

        fun start(context: Context) {
            val intent = Intent(context, GpsTrackingService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GpsTrackingService::class.java))
        }
    }
}
