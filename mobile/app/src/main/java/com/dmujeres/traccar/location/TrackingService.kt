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
import android.util.Log
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        private const val TAG = "TrackingService"

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
    @Volatile private var currentState = TrackingState.TRACKING_DISABLED_BY_USER
    @Volatile private var startedTrackingAt = 0L
    private val enqueueMutex = Mutex()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            // FLP puede agrupar varios fixes mientras el proceso estaba ocupado o dormido.
            result.locations.forEach { onNewLocation(it) }
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
            onStateChange = { _ -> onMqttStateChanged() }
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
            val task = fused?.requestLocationUpdates(request, locationCallback, null)
            if (task == null) {
                setState(TrackingState.GPS_DISABLED)
                return
            }
            task.addOnSuccessListener {
                if (started.get()) setState(TrackingState.TRACKING_ACTIVE)
            }
            task.addOnFailureListener { error ->
                Log.e(TAG, "No se pudieron solicitar actualizaciones de ubicación", error)
                if (started.get()) setState(TrackingState.GPS_DISABLED)
            }
            task.addOnCanceledListener {
                Log.e(TAG, "La solicitud de actualizaciones de ubicación fue cancelada")
                if (started.get()) setState(TrackingState.GPS_DISABLED)
            }
        } catch (e: SecurityException) {
            setState(TrackingState.PERMISSION_MISSING)
        } catch (e: Exception) {
            Log.e(TAG, "Error al solicitar actualizaciones de ubicación", e)
            setState(TrackingState.GPS_DISABLED)
        }
    }

    /** Si no hay fix de GPS reciente (parking interior/plaza), envía 'presence' para seguir online. */
    private fun sendPresenceHeartbeat() {
        val deviceId = config.deviceId
        if (deviceId.isBlank()) return
        val fixStaleAfter = maxOf(60_000L, config.intervalSeconds * 3_000L)
        val lastFixAt = config.lastFixAt
        val hasRecentFix = lastFixAt > 0 && System.currentTimeMillis() - lastFixAt <= fixStaleAfter
        if (hasRecentFix) return
        val sequence = config.nextSequence()
        val messageId = Envelope.newMessageId(deviceId, sequence)
        val payload = Envelope.buildPresence(messageId, deviceId, sequence)
        val pending = PendingPosition(
            messageId = messageId,
            deviceId = deviceId,
            sequence = sequence,
            payload = payload,
            observedAt = Envelope.nowIso(),
        )
        serviceScope.launch {
            withContext(Dispatchers.IO) {
                dao.insert(pending)
            }
            refreshStateAndNotify()
        }
    }

    private fun onNewLocation(location: Location) {
        if (!location.isValidLocation()) return
        config.lastFixAt = System.currentTimeMillis()
        val deviceId = config.deviceId
        if (deviceId.isBlank()) return
        val sequence = config.nextSequence()
        val messageId = Envelope.newMessageId(deviceId, sequence)
        val observedAt = Envelope.nowIso()
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
            observedAt = observedAt
        )
        val enqueuedAt = System.currentTimeMillis()
        val pending = PendingPosition(
            messageId = messageId,
            deviceId = deviceId,
            sequence = sequence,
            payload = payload,
            observedAt = observedAt,
            enqueuedAt = enqueuedAt
        )
        serviceScope.launch {
            try {
                enqueueMutex.withLock {
                    val discarded = withContext(Dispatchers.IO) {
                        dao.insertWithinLimit(pending, config.bufferMax)
                    }
                    config.lastEnqueuedAt = maxOf(config.lastEnqueuedAt, System.currentTimeMillis())
                    mqtt?.wakeDispatch()
                    if (discarded > 0) {
                        Log.w(TAG, "Buffer Room lleno; se descartaron $discarded posiciones")
                        Notifications.alert(
                            this@TrackingService,
                            getString(R.string.buffer_warning_title),
                            getString(R.string.buffer_warning_body, discarded),
                        )
                    }
                }
                refreshStateAndNotify()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "No se pudo guardar la posición en Room", e)
                Notifications.alert(
                    this@TrackingService,
                    getString(R.string.storage_warning_title),
                    getString(R.string.storage_warning_body),
                )
                refreshStateAndNotify()
            }
        }
    }

    private suspend fun watchdogLoop() {
        var lastWakeAlertAt = 0L
        var lastHeartbeatAt = 0L
        while (serviceScope.isActive) {
            delay(30_000)
            if (!config.trackingEnabled) {
                setState(TrackingState.TRACKING_DISABLED_BY_USER)
                continue
            }
            val now = System.currentTimeMillis()
            if (now - lastHeartbeatAt > 60_000) {
                lastHeartbeatAt = now
                sendPresenceHeartbeat()
            }
            val pendingInfo = try {
                withContext(Dispatchers.IO) {
                    dao.count() to dao.oldestEnqueuedAt()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (error: Exception) {
                Log.e(TAG, "No se pudo consultar la cola Room", error)
                0 to null
            }
            val pendingCount = pendingInfo.first
            val oldestPendingAt = pendingInfo.second
            val lastFixAt = config.lastFixAt
            val fixForCurrentRun = lastFixAt >= startedTrackingAt && lastFixAt > 0L
            val fixStaleAfter = maxOf(60_000L, config.intervalSeconds * 3_000L)
            val gpsWithoutFix =
                (!fixForCurrentRun && now - startedTrackingAt > 60_000L) ||
                    (fixForCurrentRun && now - lastFixAt > fixStaleAfter)
            val pendingWithoutAck = pendingCount > 0 && pendingAgeExceedsLimit(
                now = now,
                oldestPendingAt = oldestPendingAt,
            )
            val mqttUnavailable = mqtt?.ready != true

            if ((gpsWithoutFix || mqttUnavailable || pendingWithoutAck)
                && now - lastWakeAlertAt > 20 * 60_000) {
                lastWakeAlertAt = now
                Notifications.wakeScreen(
                    this,
                    getString(R.string.wake_title),
                    when {
                        gpsWithoutFix -> getString(R.string.wake_gps_body)
                        pendingWithoutAck -> getString(R.string.wake_pending_body)
                        else -> getString(R.string.wake_mqtt_body)
                    },
                )
            }
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                setState(TrackingState.PERMISSION_MISSING)
            } else if (gpsWithoutFix) {
                setState(TrackingState.GPS_DISABLED)
            } else if (!isNetworkAvailable()) {
                setState(TrackingState.NETWORK_OFFLINE)
            } else if (mqttUnavailable) {
                setState(TrackingState.MQTT_DISCONNECTED)
            } else if (pendingWithoutAck) {
                setState(TrackingState.PENDING_ACK_TIMEOUT)
            } else if (isBatteryLow()) {
                setState(TrackingState.BATTERY_LOW)
            } else {
                setState(TrackingState.TRACKING_ACTIVE)
            }
        }
    }

    private fun pendingAgeExceedsLimit(now: Long, oldestPendingAt: Long?): Boolean {
        val tenMinutes = 10 * 60_000L
        val knownOldest = oldestPendingAt?.takeIf { it > 0L }
        if (knownOldest != null) return now - knownOldest > tenMinutes

        // Rows created before the Room migration have no per-row timestamp. Do not use
        // the latest metric here: a newer position must not hide an older stuck row.
        val fallback = startedTrackingAt.takeIf { it > 0L }
            ?: listOf(
                config.lastEnqueuedAt,
                config.lastPublishedAt,
                config.lastAckAt,
            ).filter { it > 0L }.minOrNull()
            ?: 0L
        return fallback > 0L && now - fallback > tenMinutes
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
            val now = System.currentTimeMillis()
            val metrics = listOf(
                "fix=" + metricAge(config.lastFixAt, now),
                "enc=" + metricAge(config.lastEnqueuedAt, now),
                "pub=" + metricAge(config.lastPublishedAt, now),
                "ACK=" + metricAge(config.lastAckAt, now),
            ).joinToString(" ")
            val error = mqtt?.lastError?.takeIf { it.isNotBlank() }
            val text = buildString {
                append(currentState.label)
                append(" · ")
                append(MqttStatus.status)
                append(" · ")
                append(pending)
                append(" pendientes · ")
                append(metrics)
                if (error != null) {
                    append(" · ")
                    append(error)
                }
            }
            Notifications.update(this@TrackingService, "DMujeres Tracking", text)
        }
    }

    private fun metricAge(timestamp: Long, now: Long): String {
        if (timestamp <= 0L) return "nunca"
        return "${((now - timestamp).coerceAtLeast(0L)) / 1_000}s"
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
