package com.dmujeres.traccar.location

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dmujeres.traccar.DmujeresApp
import com.dmujeres.traccar.R
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.db.PendingPosition
import com.dmujeres.traccar.mqtt.Envelope
import com.dmujeres.traccar.mqtt.MqttStatus
import com.dmujeres.traccar.mqtt.MqttManager
import com.dmujeres.traccar.util.Notifications
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Servicio en primer plano que captura la ubicación y la envía de forma confiable al
 * servidor (MQTT QoS1 + ACK + cola offline + watchdog).
 */
class TrackingService : Service() {

    companion object {
        const val ACTION_START = "com.dmujeres.traccar.START"
        const val ACTION_STOP = "com.dmujeres.traccar.STOP"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, TrackingService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TrackingService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }

    private var serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var config: AppConfig
    private lateinit var dao: com.dmujeres.traccar.db.PositionDao
    private var fused: FusedLocationProviderClient? = null
    private var mqtt: MqttManager? = null
    private val started = AtomicBoolean(false)
    @Volatile private var lastFixAt = 0L
    @Volatile private var currentState = TrackingState.TRACKING_DISABLED_BY_USER
    @Volatile private var startedTrackingAt = 0L

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            onNewLocation(location)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Notifications.ensureChannel(this)
        config = AppConfig(this)
        dao = (application as DmujeresApp).database.positionDao()
        fused = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                config.trackingEnabled = false
                stopTracking()
                return START_NOT_STICKY
            }
            else -> {
                if (!config.trackingEnabled) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                try {
                    ServiceCompat.startForeground(
                        this,
                        Notifications.NOTIFICATION_ID,
                        Notifications.foregroundNotification(this, "Tracking", "Arrancando…"),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    )
                } catch (e: SecurityException) {
                    config.trackingEnabled = false
                    stopSelf()
                    return START_NOT_STICKY
                }
                startTracking()
            }
        }
        return START_STICKY
    }

    private fun startTracking() {
        if (started.getAndSet(true)) return
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        startedTrackingAt = System.currentTimeMillis()

        Notifications.alert(this, getString(R.string.jornada_iniciada), getString(R.string.jornada_iniciada_body))
        val manager = MqttManager(
            context = this,
            config = config,
            dao = dao,
            scope = serviceScope,
            onStateChange = { onMqttStateChanged() }
        )
        mqtt = manager
        manager.connect()

        requestLocationUpdates()
        serviceScope.launch { watchdogLoop() }
    }

    @Volatile private var lastMqttStatus: String = ""

    private fun onMqttStateChanged() {
        val status = MqttStatus.status
        if (status != lastMqttStatus) {
            when (status) {
                MqttStatus.CONNECTED ->
                    Notifications.alert(this, getString(R.string.connected_title), getString(R.string.connected_body))
                MqttStatus.DISCONNECTED ->
                    Notifications.alert(this, getString(R.string.disconnected_title), getString(R.string.disconnected_body))
            }
            lastMqttStatus = status
        }
        refreshStateAndNotify()
    }

    private fun requestLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            setState(TrackingState.PERMISSION_MISSING)
            return
        }
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            config.intervalSeconds * 1000L
        ).setMinUpdateIntervalMillis(config.intervalSeconds * 500L).build()
        try {
            fused?.requestLocationUpdates(request, locationCallback, null)
            setState(TrackingState.TRACKING_ACTIVE)
        } catch (e: SecurityException) {
            setState(TrackingState.PERMISSION_MISSING)
        }
    }

    private fun onNewLocation(location: Location) {
        if (!location.isValidLocation()) return
        lastFixAt = System.currentTimeMillis()
        val deviceId = config.deviceId
        if (deviceId.isBlank()) return
        val sequence = config.nextSequence()
        val messageId = Envelope.newMessageId(deviceId, sequence)
        val payload = Envelope.buildPosition(
            messageId = messageId,
            deviceId = deviceId,
            sequence = sequence,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy.toDouble(),
            speed = location.speed.toDouble().coerceAtLeast(0.0),
            bearing = location.bearing.toDouble(),
            altitude = location.altitude.toDouble(),
            observedAt = Envelope.nowIso()
        )
        val pending = PendingPosition(
            messageId = messageId,
            deviceId = deviceId,
            sequence = sequence,
            payload = payload,
            observedAt = Envelope.nowIso()
        )
        serviceScope.launch {
            withContext(Dispatchers.IO) {
                val count = dao.count()
                if (count >= config.bufferMax) {
                    dao.deleteOldest(count - config.bufferMax + 1)
                }
                dao.insert(pending)
            }
            refreshStateAndNotify()
        }
    }

    private suspend fun watchdogLoop() {
        while (serviceScope.isActive) {
            delay(30_000)
            if (!config.trackingEnabled) {
                setState(TrackingState.TRACKING_DISABLED_BY_USER)
                continue
            }
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                setState(TrackingState.PERMISSION_MISSING)
            } else if (lastFixAt == 0L
                && System.currentTimeMillis() - startedTrackingAt > 60_000
            ) {
                setState(TrackingState.GPS_DISABLED)
            } else if (lastFixAt > 0 && System.currentTimeMillis() - lastFixAt > 3 * config.intervalSeconds * 1000) {
                setState(TrackingState.GPS_DISABLED)
            } else if (!isNetworkAvailable()) {
                setState(TrackingState.NETWORK_OFFLINE)
            } else if (mqtt?.connected == false) {
                setState(TrackingState.MQTT_DISCONNECTED)
            } else if (isBatteryLow()) {
                setState(TrackingState.BATTERY_LOW)
            } else {
                setState(TrackingState.TRACKING_ACTIVE)
            }
        }
    }

    private fun setState(state: TrackingState) {
        if (currentState == state) return
        val previous = currentState
        currentState = state
        if (state != TrackingState.TRACKING_ACTIVE && state != TrackingState.TRACKING_DISABLED_BY_USER
            && state != TrackingState.SERVICE_RECOVERY) {
            Notifications.alert(this, getString(R.string.warning_title), state.label)
        }
        if (state == TrackingState.TRACKING_ACTIVE && previous != TrackingState.TRACKING_ACTIVE) {
            Notifications.alert(this, getString(R.string.ok_title), getString(R.string.ok_body))
        }
        refreshStateAndNotify()
    }

    private fun refreshStateAndNotify() {
        serviceScope.launch {
            val pending = withContext(Dispatchers.IO) { dao.countFlow().first() }
            val text = currentState.label + " · " + MqttStatus.status + " · " + pending + " pendientes"
            Notifications.update(this@TrackingService, "DMujeres Tracking", text)
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun isBatteryLow(): Boolean {
        val manager = getSystemService(BATTERY_SERVICE) as BatteryManager
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return level in 1..15
    }

    private fun Location.isValidLocation(): Boolean =
        latitude in -90.0..90.0 && longitude in -180.0..180.0 && accuracy < 500f

    private fun stopTracking() {
        started.set(false)
        Notifications.alert(this, getString(R.string.jornada_finalizada), getString(R.string.jornada_finalizada_body))
        try {
            fused?.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            // ignorar
        }
        mqtt?.disconnect()
        mqtt = null
        currentState = TrackingState.TRACKING_DISABLED_BY_USER
        Notifications.update(this, "DMujeres Tracking", "Tracking desactivado")
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Si el usuario cierra la app desde la lista de recientes, el tracking continúa.
        if (config.trackingEnabled) {
            Notifications.ensureChannel(this)
            runCatching { TrackingService.start(this) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serviceScope.cancel()
        try {
            fused?.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            // ignorar
        }
        mqtt?.disconnect()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
