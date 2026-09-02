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
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import com.dmujeres.traccar.BuildConfig
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dmujeres.traccar.DmujeresApp
import com.dmujeres.traccar.R
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.db.PendingPosition
import com.dmujeres.traccar.mqtt.Envelope
import com.dmujeres.traccar.mqtt.HttpFallbackDispatcher
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.collections.ArrayDeque
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Servicio en primer plano que captura la ubicación y la envía al servidor por
 * MQTT QoS1 con cola offline para cuando no hay conexión.
 */
class TrackingService : Service() {

    companion object {
        const val ACTION_START = "com.dmujeres.traccar.START"
        const val ACTION_STOP = "com.dmujeres.traccar.STOP"
        private const val TAG = "TrackingService"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context): Boolean =
            runCatching {
                val intent = Intent(context, TrackingService::class.java).setAction(ACTION_START)
                ContextCompat.startForegroundService(context, intent)
            }.isSuccess

        fun stop(context: Context) {
            val intent = Intent(context, TrackingService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
                .onFailure { ContextCompat.startForegroundService(context, intent) }
        }
    }

    private var serviceScope = newServiceScope()
    private val stopControllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var config: AppConfig
    private lateinit var dao: com.dmujeres.traccar.db.PositionDao
    private var fused: FusedLocationProviderClient? = null
    private var mqtt: MqttManager? = null
    private val started = AtomicBoolean(false)
    @Volatile private var currentState = TrackingState.TRACKING_DISABLED_BY_USER
    @Volatile private var startedTrackingAt = 0L
    private val enqueueMutex = Mutex()
    @Volatile private var stopping = false
    @Volatile private var pendingStart = false
    private var stopJob: Job? = null
    @Volatile private var capturePausedForBuffer = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            // FLP puede agrupar varios fixes mientras el proceso estaba ocupado o dormido.
            result.locations.forEach { onNewLocation(it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
        config = AppConfig(this)
        try {
            dao = (application as DmujeresApp).database.positionDao()
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir la base de datos", e)
            config.trackingEnabled = false
            config.lastStartError = "Error de base de datos: " + (e.message ?: e.javaClass.simpleName)
            publishState(TrackingState.SERVER_UNAVAILABLE)
            stopSelf()
            return
        }
        fused = LocationServices.getFusedLocationProviderClient(this)
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                config.trackingEnabled = false
                pendingStart = false
                if (started.get() || stopping || config.journeyStartAt > 0L) {
                    stopTracking()
                } else {
                    publishState(TrackingState.TRACKING_DISABLED_BY_USER)
                    config.journeyStopRequested = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            else -> {
                if (stopping) {
                    pendingStart = true
                    config.trackingEnabled = true
                    return START_STICKY
                }
                if (config.journeyStopRequested && config.journeyStartAt > 0L && !started.get()) {
                    pendingStart = true
                    config.trackingEnabled = true
                    stopTracking()
                    return START_STICKY
                }
                if (!config.trackingEnabled) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                try {
                    ServiceCompat.startForeground(
                        this,
                        Notifications.NOTIFICATION_ID,
                        Notifications.foregroundNotification(
                            this,
                            getString(R.string.app_name),
                            getString(R.string.tracking_starting),
                        ),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    )
                } catch (e: Exception) {
                    val permissionFailure = e is SecurityException
                    config.trackingEnabled = !permissionFailure
                    config.lastStartError = if (permissionFailure) {
                        "Sin permiso de ubicación en segundo plano"
                    } else {
                        "No se pudo iniciar el servicio de seguimiento"
                    }
                    publishState(
                        if (permissionFailure) TrackingState.PERMISSION_MISSING
                        else TrackingState.SERVER_UNAVAILABLE,
                    )
                    Notifications.alert(this, getString(R.string.warning_title), config.lastStartError)
                    stopSelf()
                    return START_NOT_STICKY
                }
                config.lastStartError = ""
                startTracking()
            }
        }
        return START_STICKY
    }

    private fun startTracking() {
        if (started.getAndSet(true)) return
        serviceScope = newServiceScope()
        stopping = false
        val recoveringJourney = config.journeyStartAt > 0L
        startedTrackingAt = if (recoveringJourney) config.journeyStartAt else System.currentTimeMillis()
        lastJourneyLat = if (recoveringJourney) config.journeyLastLat else 0.0
        lastJourneyLon = if (recoveringJourney) config.journeyLastLon else 0.0
        capturePausedForBuffer = false
        if (!recoveringJourney) {
            config.journeyStartAt = startedTrackingAt
            config.journeyDistanceM = 0.0
            config.journeyPoints = 0
            config.journeyConfirmedPoints = 0
            config.journeyLastLat = 0.0
            config.journeyLastLon = 0.0
            config.journeyHasLastLocation = false
            config.journeyStopRequested = false
            Notifications.alert(this, getString(R.string.jornada_iniciada), getString(R.string.jornada_iniciada_body))
            enqueuePresence("started")
        } else {
            Notifications.alert(this, getString(R.string.service_recovery_title), getString(R.string.service_recovery_body))
            // Reafirma el inicio después de una muerte del proceso; el estado online es idempotente.
            enqueuePresence("started")
        }
        publishState(TrackingState.SERVICE_RECOVERY)
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
        registerNetworkCallback()
        serviceScope.launch { watchdogLoop() }
        refreshStateAndNotify()
    }

    private fun newServiceScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "Error en corutina del servicio", e)
        })

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile private var lastReportedNetwork: String = ""

    private fun registerNetworkCallback() {
        val connectivity = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                mqtt?.let { manager ->
                    if (!manager.connected) {
                        serviceScope.launch { manager.connect() }
                    }
                }
                if (config.trackingEnabled) {
                    serviceScope.launch {
                        try {
                            HttpFallbackDispatcher.flush(dao, config)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Fallback HTTP al recuperar red", e)
                        }
                    }
                }
                // Notificar al servidor que la red se recuperó
                if (started.get() && !stopping) {
                    serviceScope.launch {
                        try {
                            enqueuePresence()
                        } catch (e: Exception) {
                            Log.w(TAG, "No se pudo enviar presencia al recuperar red", e)
                        }
                    }
                }
            }

            override fun onLost(network: android.net.Network) {
                // Red perdida: enviar presencia inmediata con network=none
                if (started.get() && !stopping) {
                    serviceScope.launch {
                        try {
                            enqueuePresence()
                        } catch (e: Exception) {
                            Log.w(TAG, "No se pudo enviar presencia al perder red", e)
                        }
                    }
                }
            }

            override fun onCapabilitiesChanged(
                network: android.net.Network,
                capabilities: android.net.NetworkCapabilities
            ) {
                // Detectar cambio de tipo de red (wifi ↔ mobile)
                if (!started.get() || stopping) return
                val hasWifi = capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                val hasMobile = capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
                val currentNetwork = when {
                    hasWifi -> "wifi"
                    hasMobile -> "mobile"
                    else -> "other"
                }
                if (lastReportedNetwork.isNotEmpty() && currentNetwork != lastReportedNetwork) {
                    serviceScope.launch {
                        try {
                            enqueuePresence()
                        } catch (e: Exception) {
                            Log.w(TAG, "No se pudo enviar presencia al cambiar tipo de red", e)
                        }
                    }
                }
                lastReportedNetwork = currentNetwork
            }
        }
        try {
            connectivity.registerDefaultNetworkCallback(networkCallback!!)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo registrar el callback de red", e)
        }
    }

    @Volatile private var lastMqttStatus: String = ""
    @Volatile private var lastBufferFullAlertAt = 0L
    @Volatile private var lastDiscardAlertAt = 0L
    @Volatile private var lastStorageAlertAt = 0L
    @Volatile private var lastJourneyLat = 0.0
    @Volatile private var lastJourneyLon = 0.0

    @Volatile private var lastConnectAlertAt = 0L
    @Volatile private var connectionUnavailableSince = 0L
    @Volatile private var lastConnectionAlertAt = 0L
    @Volatile private var lastBatteryAlertAt = 0L
    @Volatile private var wakeAlertActive = false
    @Volatile private var connectionAlertActive = false
    @Volatile private var lastBatteryAdaptationAt = 0L
    @Volatile private var lastGpsReregisterAt = 0L

    private fun onMqttStateChanged() {
        val status = MqttStatus.status
        if (started.get()) {
            when (status) {
                MqttStatus.CONNECTING -> publishState(TrackingState.SERVICE_RECOVERY)
                MqttStatus.DISCONNECTED -> publishState(TrackingState.MQTT_DISCONNECTED)
                MqttStatus.CONNECTED -> if (mqtt?.ready == true && config.lastFixAt >= startedTrackingAt) {
                    publishState(TrackingState.TRACKING_ACTIVE)
                } else {
                    publishState(TrackingState.SERVICE_RECOVERY)
                }
            }
        }
        if (status != lastMqttStatus) {
            val now = System.currentTimeMillis()
            when (status) {
                MqttStatus.CONNECTED -> if (now - lastConnectAlertAt > 5 * 60_000) {
                    lastConnectAlertAt = now
                    Notifications.alert(this, getString(R.string.connected_title), getString(R.string.connected_body))
                }
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
            effectiveIntervalSeconds() * 1000L
        ).setMinUpdateIntervalMillis(effectiveIntervalSeconds() * 500L).build()
        try {
            val task = fused?.requestLocationUpdates(request, locationCallback, null)
            if (task == null) {
                setState(TrackingState.GPS_DISABLED)
                return
            }
            task.addOnSuccessListener {
                if (started.get()) {
                    if (mqtt?.ready == true) {
                        setState(TrackingState.TRACKING_ACTIVE)
                    } else if (MqttStatus.status == MqttStatus.DISCONNECTED) {
                        setState(TrackingState.MQTT_DISCONNECTED)
                    } else {
                        publishState(TrackingState.SERVICE_RECOVERY)
                        refreshStateAndNotify()
                    }
                }
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

    /** Encola una señal de presencia con la misma política y secuencia que una posición. */
    private fun enqueuePresence(
        journeyStatus: String? = null,
        targetScope: CoroutineScope = serviceScope,
        targetMqtt: MqttManager? = null,
    ): Job = targetScope.launch {
        try {
            enqueueMutex.withLock {
                val deviceId = config.deviceId
                if (deviceId.isBlank()) return@withLock
                if (journeyStatus == null && (!started.get() || stopping)) return@withLock
                if (journeyStatus == "started" && stopping) return@withLock
                val sequence = withContext(Dispatchers.IO) {
                    dao.nextSequence(config.sequence)
                }
                config.sequence = sequence
                val messageId = Envelope.newMessageId(deviceId, sequence)
                val telemetry = telemetry()
                lastReportedNetwork = telemetry.network
                val observedAt = Envelope.nowIso()
                val payload = Envelope.buildPresence(
                    messageId = messageId,
                    deviceId = deviceId,
                    sequence = sequence,
                    pending = telemetry.pending,
                    battery = telemetry.battery,
                    network = telemetry.network,
                    vendor = telemetry.vendor,
                    model = telemetry.model,
                    appVersion = telemetry.appVersion,
                    gps = telemetry.gps,
                    journeyStatus = journeyStatus,
                    journeyId = config.journeyStartAt,
                )
                val pending = PendingPosition(
                    messageId = messageId,
                    deviceId = deviceId,
                    sequence = sequence,
                    payload = payload,
                    observedAt = observedAt,
                    isControl = journeyStatus != null,
                    journeyId = config.journeyStartAt,
                )
                val inserted = withContext(Dispatchers.IO) {
                    dao.insertWithinLimit(pending, config.bufferMax)
                }
                if (inserted < 0) {
                    Log.w(TAG, "No hay espacio para encolar presencia $journeyStatus")
                    return@withLock
                }
                config.lastEnqueuedAt = maxOf(config.lastEnqueuedAt, System.currentTimeMillis())
                (targetMqtt ?: mqtt)?.wakeDispatch()
            }
            refreshStateAndNotify()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo guardar la presencia en Room", e)
        }
    }

    /** Si no hay fix de GPS reciente (parking interior/plaza), envía 'presence' con telemetría. */
    private fun sendPresenceHeartbeat() {
        if (!started.get() || stopping) return
        val deviceId = config.deviceId
        if (deviceId.isBlank()) return
        val fixStaleAfter = maxOf(60_000L, effectiveIntervalSeconds() * 3_000L)
        val lastFixAt = config.lastFixAt
        val hasRecentFix = lastFixAt > 0 && System.currentTimeMillis() - lastFixAt <= fixStaleAfter
        if (hasRecentFix) return
        enqueuePresence()
    }

    private data class Telemetry(
        val pending: Int,
        val battery: Int,
        val network: String,
        val vendor: String,
        val model: String,
        val appVersion: String,
        val gps: String,
    )

    private fun telemetry(): Telemetry {
        val pendingCount = try {
            runBlockingSafe { dao.count() }
        } catch (e: Exception) {
            0
        }
        val batteryManager = getSystemService(BATTERY_SERVICE) as BatteryManager
        val battery = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val connectivity = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = when {
            connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    && it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            } == true -> "wifi"
            connectivity.getNetworkCapabilities(connectivity.activeNetwork)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true -> "mobile"
            else -> "none"
        }
        val gpsMode = Settings.Secure.getInt(contentResolver, Settings.Secure.LOCATION_MODE, 0)
        return Telemetry(
            pending = pendingCount,
            battery = battery,
            network = network,
            vendor = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            appVersion = BuildConfig.VERSION_NAME,
            gps = if (gpsMode != 0) "on" else "off",
        )
    }

    private fun runBlockingSafe(block: suspend () -> Int): Int =
        runCatching { kotlinx.coroutines.runBlocking { withContext(Dispatchers.IO) { block() } } }
            .getOrDefault(0)

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earth = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return earth * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    private fun observedAt(location: Location): String =
        if (location.time > 0L) Instant.ofEpochMilli(location.time).toString() else Envelope.nowIso()

    private fun pauseCaptureForBuffer() {
        if (capturePausedForBuffer) return
        capturePausedForBuffer = true
        runCatching { fused?.removeLocationUpdates(locationCallback) }
    }

    private fun resumeCaptureAfterBuffer() {
        if (!capturePausedForBuffer) return
        capturePausedForBuffer = false
        requestLocationUpdates()
    }

    private data class RecentFix(val lat: Double, val lon: Double, val accuracyM: Float, val timeMs: Long)

    private val recentFixes = ArrayDeque<RecentFix>()
    private val fixWindowSize = 6

    private fun recordRecentFix(location: Location) {
        recentFixes.addLast(RecentFix(location.latitude, location.longitude, location.accuracy, location.time))
        if (recentFixes.size > fixWindowSize) recentFixes.removeFirst()
    }

    private fun recentMovingConsistently(): Boolean {
        if (recentFixes.size < 2) return false
        val a = recentFixes[recentFixes.size - 2]
        val b = recentFixes[recentFixes.size - 1]
        val dt = (b.timeMs - a.timeMs) / 1000.0
        if (dt <= 0) return false
        return distanceMeters(a.lat, a.lon, b.lat, b.lon) / dt > config.consistentSpeedMps
    }

    private fun Location.isPlausibleFix(): Boolean {
        val previous = recentFixes.lastOrNull()
        if (previous == null) return true
        val dt = (time - previous.timeMs) / 1000.0
        if (dt <= 0) return true
        val implied = distanceMeters(previous.lat, previous.lon, latitude, longitude) / dt
        if (implied > config.maxImpliedSpeedMps && !recentMovingConsistently()) {
            Log.w(TAG, "Fix descartado por GPS loco (implícita %.1f m/s)".format(implied))
            return false
        }
        if (accuracy > config.accuracyBadM && recentFixes.any { it.accuracyM <= config.accuracyGoodM }) {
            Log.w(TAG, "Fix degradado descartado (accuracy %.0f m)".format(accuracy))
            return false
        }
        return true
    }

    private fun onNewLocation(location: Location) {
        if (!started.get() || stopping || !location.isValidLocation()) return
        if (!location.isPlausibleFix()) return
        recordRecentFix(location)
        config.lastFixAt = System.currentTimeMillis()
        val deviceId = config.deviceId
        if (deviceId.isBlank()) return
        serviceScope.launch {
            try {
                enqueueMutex.withLock {
                    if (!started.get() || stopping) return@withLock
                    val currentCount = withContext(Dispatchers.IO) { dao.count() }
                    if (config.bufferPolicy == AppConfig.POLICY_STOP_CAPTURE
                        && currentCount >= config.bufferMax
                    ) {
                        val now = System.currentTimeMillis()
                        if (now - lastBufferFullAlertAt > 60_000) {
                            lastBufferFullAlertAt = now
                            Notifications.alert(
                                this@TrackingService,
                                getString(R.string.buffer_warning_title),
                                getString(R.string.buffer_full_stop_body),
                            )
                        }
                        pauseCaptureForBuffer()
                        setState(TrackingState.BUFFER_FULL)
                        return@withLock
                    }
                    val sequence = withContext(Dispatchers.IO) {
                        dao.nextSequence(config.sequence)
                    }
                    config.sequence = sequence
                    val messageId = Envelope.newMessageId(deviceId, sequence)
                    val observedAt = observedAt(location)
                    val batteryManager = getSystemService(BATTERY_SERVICE) as BatteryManager
                    val battery = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    val connectivity = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                    val network = if (connectivity.getNetworkCapabilities(connectivity.activeNetwork)
                            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) "online" else "none"
                    val payload = Envelope.buildPosition(
                        messageId = messageId,
                        deviceId = deviceId,
                        sequence = sequence,
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracy = location.accuracy.toDouble(),
                        speed = if (location.hasSpeed()) {
                            (location.speed.toDouble() * 3.6).coerceAtLeast(0.0)
                        } else 0.0,
                        bearing = if (location.hasBearing()) location.bearing.toDouble() else 0.0,
                        altitude = if (location.hasAltitude()) location.altitude else 0.0,
                        observedAt = observedAt,
                        pending = currentCount,
                        battery = battery,
                        network = network,
                    )
                    val enqueuedAt = System.currentTimeMillis()
                    val pending = PendingPosition(
                        messageId = messageId,
                        deviceId = deviceId,
                        sequence = sequence,
                        payload = payload,
                        observedAt = observedAt,
                        enqueuedAt = enqueuedAt,
                        journeyId = config.journeyStartAt,
                    )
                    val discarded = withContext(Dispatchers.IO) {
                        dao.insertWithinLimit(pending, config.bufferMax)
                    }
                    if (discarded < 0) {
                        setState(TrackingState.BUFFER_FULL)
                        return@withLock
                    }
                    if (config.journeyHasLastLocation) {
                        config.journeyDistanceM += distanceMeters(
                            lastJourneyLat,
                            lastJourneyLon,
                            location.latitude,
                            location.longitude,
                        )
                    }
                    config.journeyPoints = config.journeyPoints + 1
                    lastJourneyLat = location.latitude
                    lastJourneyLon = location.longitude
                    config.journeyLastLat = location.latitude
                    config.journeyLastLon = location.longitude
                    config.journeyHasLastLocation = true
                    config.lastEnqueuedAt = maxOf(config.lastEnqueuedAt, System.currentTimeMillis())
                    mqtt?.wakeDispatch()
                    if (discarded > 0) {
                        val now = System.currentTimeMillis()
                        if (now - lastDiscardAlertAt > 60_000) {
                            lastDiscardAlertAt = now
                            Log.w(TAG, "Buffer Room lleno; se descartaron $discarded posiciones")
                            Notifications.alert(
                                this@TrackingService,
                                getString(R.string.buffer_warning_title),
                                getString(R.string.buffer_warning_body, discarded),
                            )
                        }
                    }
                }
                if (started.get()) {
                    if (mqtt?.ready == true) {
                        setState(TrackingState.TRACKING_ACTIVE)
                    } else if (MqttStatus.status == MqttStatus.DISCONNECTED) {
                        setState(TrackingState.MQTT_DISCONNECTED)
                    }
                }
                refreshStateAndNotify()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val now = System.currentTimeMillis()
                if (now - lastStorageAlertAt > 60_000) {
                    lastStorageAlertAt = now
                    Log.e(TAG, "No se pudo guardar la posición en Room", e)
                    Notifications.alert(
                        this@TrackingService,
                        getString(R.string.storage_warning_title),
                        getString(R.string.storage_warning_body),
                    )
                }
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
            val fixStaleAfter = maxOf(60_000L, effectiveIntervalSeconds() * 3_000L)
            val gpsWithoutFix =
                (!fixForCurrentRun && now - startedTrackingAt > 60_000L) ||
                    (fixForCurrentRun && now - lastFixAt > fixStaleAfter)

            // Adaptación por batería: al cruzar el 15% se re-solicita el FLP con el nuevo intervalo.
            val batteryNow = batteryLevel()
            val lowBatteryNow = batteryNow in 0..100 && batteryNow < 15
            val wasLowBattery = lastBatteryAdaptationAt != 0L
            if (lowBatteryNow != wasLowBattery) {
                lastBatteryAdaptationAt = if (lowBatteryNow) now else 0L
                if (started.get() && !capturePausedForBuffer) {
                    Log.i(TAG, if (lowBatteryNow) "Batería baja: intervalo duplicado" else "Batería normal: intervalo restaurado")
                    runCatching { fused?.removeLocationUpdates(locationCallback) }
                    requestLocationUpdates()
                }
            }

            // Re-registro de GPS: si FLP dejó de entregar fixes, se re-solicita cada 2 minutos.
            if (gpsWithoutFix && !capturePausedForBuffer && started.get()) {
                if (now - lastGpsReregisterAt > 2 * 60_000L) {
                    lastGpsReregisterAt = now
                    Log.i(TAG, "GPS sin fix: re-solicitando actualizaciones de ubicación")
                    runCatching { fused?.removeLocationUpdates(locationCallback) }
                    requestLocationUpdates()
                }
            } else if (!gpsWithoutFix) {
                lastGpsReregisterAt = 0L
            }

            val pendingWithoutAck = pendingCount > 0 && pendingAgeExceedsLimit(
                now = now,
                oldestPendingAt = oldestPendingAt,
            )
            val mqttUnavailable = mqtt?.ready != true
            val networkAvailable = isNetworkAvailable()
            val connectionUnavailable = !networkAvailable || mqttUnavailable

            if (mqttUnavailable && networkAvailable) {
                // Reintenta conectar MQTT periódicamente aunque no haya evento de red.
                try {
                    mqtt?.connect()
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo reconectar MQTT", e)
                }
            }

            if (config.bufferPolicy == AppConfig.POLICY_STOP_CAPTURE && pendingCount >= config.bufferMax) {
                pauseCaptureForBuffer()
            } else if (capturePausedForBuffer && pendingCount < (config.bufferMax * 0.8).toInt()) {
                resumeCaptureAfterBuffer()
            }

            if (connectionUnavailable) {
                if (connectionUnavailableSince == 0L) connectionUnavailableSince = now
            } else {
                connectionUnavailableSince = 0L
            }
            maybeNotifyOperationalAlerts(now, connectionUnavailable, batteryLevel())

            // El plan B HTTP drena la cola cuando MQTT no entrega: desconectado, o conectado
            // pero sin ACK reciente (broker/servidor mudo, caso del bug de 3906). No depende de
            // la antigüedad de la cola para no congelar posiciones frescas que MQTT no confirma.
            val mqttStuck = mqttUnavailable
                || config.lastAckAt == 0L
                || (now - config.lastAckAt > 2 * 60_000L)
            if (pendingCount > 0 && mqttStuck) {
                try {
                    val flushed = HttpFallbackDispatcher.flush(dao, config)
                    if (flushed > 0) {
                        Log.i(TAG, "Fallback HTTP confirmó $flushed mensajes")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Fallback HTTP falló", e)
                }
            }

if ((gpsWithoutFix || connectionUnavailable || pendingWithoutAck)
                && !wakeAlertActive) {
                // Alerta de pantalla una sola vez por episodio, no cada 20 minutos.
                wakeAlertActive = true
                Notifications.wakeScreen(
                    this,
                    getString(R.string.wake_title),
                    when {
                        gpsWithoutFix -> getString(R.string.wake_gps_body)
                        pendingWithoutAck -> getString(R.string.wake_pending_body)
                        else -> getString(R.string.wake_mqtt_body)
                    },
                )
            } else if (!gpsWithoutFix && !connectionUnavailable && !pendingWithoutAck) {
                wakeAlertActive = false
            }
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                setState(TrackingState.PERMISSION_MISSING)
            } else if (config.bufferPolicy == AppConfig.POLICY_STOP_CAPTURE && pendingCount >= config.bufferMax) {
                setState(TrackingState.BUFFER_FULL)
            } else if (gpsWithoutFix) {
                setState(TrackingState.GPS_DISABLED)
            } else if (!networkAvailable) {
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

    private fun maybeNotifyOperationalAlerts(now: Long, connectionUnavailable: Boolean, battery: Int) {
        if (connectionUnavailable) {
            val since = connectionUnavailableSince
            if (since > 0L && now - since >= 5 * 60_000L && !connectionAlertActive) {
                // Una sola alerta por episodio de desconexión, no cada 30 minutos.
                connectionAlertActive = true
                lastConnectionAlertAt = now
                Notifications.alert(
                    this,
                    getString(R.string.connection_lost_title),
                    getString(R.string.connection_lost_body),
                    Notifications.CONNECTION_ALERT_ID,
                )
            }
        } else {
            connectionAlertActive = false
        }
        if (battery in 1..20 && (lastBatteryAlertAt == 0L || now - lastBatteryAlertAt >= 60 * 60_000L)) {
            lastBatteryAlertAt = now
            Notifications.alert(
                this,
                getString(R.string.battery_low_title),
                getString(R.string.battery_low_body, battery),
                Notifications.BATTERY_ALERT_ID,
            )
        }
    }

    private fun pendingAgeExceedsLimit(now: Long, oldestPendingAt: Long?): Boolean {
        val tenMinutes = 10 * 60_000L
        val knownOldest = oldestPendingAt?.takeIf { it > 0L }
        if (knownOldest != null) return now - knownOldest > tenMinutes

        // Las filas previas a la migración de Room tienen enqueuedAt=0; no usar la
        // métrica más reciente porque una posición nueva ocultaría una vieja atascada.
        return true
    }

    private fun publishState(state: TrackingState) {
        currentState = state
        config.trackingState = state.name
    }

    private fun setState(state: TrackingState) {
        if (currentState == state) {
            config.trackingState = state.name
            return
        }
        val previous = currentState
        publishState(state)
        val delayedAlertState = state == TrackingState.NETWORK_OFFLINE
            || state == TrackingState.MQTT_DISCONNECTED
            || state == TrackingState.SERVER_UNAVAILABLE
            || state == TrackingState.BATTERY_LOW
        if (state != TrackingState.TRACKING_ACTIVE && state != TrackingState.TRACKING_DISABLED_BY_USER
            && state != TrackingState.SERVICE_RECOVERY && !delayedAlertState) {
            Notifications.alert(this, getString(R.string.warning_title), state.label)
        }
        if (state == TrackingState.TRACKING_ACTIVE && previous != TrackingState.TRACKING_ACTIVE) {
            Notifications.alert(this, getString(R.string.ok_title), getString(R.string.ok_body))
        }
        refreshStateAndNotify()
    }

    private fun refreshStateAndNotify() {
        serviceScope.launch {
            val pending = withContext(Dispatchers.IO) {
                runCatching { dao.countFlow().first() }.getOrDefault(0)
            }
            val battery = batteryLevel()
            val state = TrackingState.fromName(config.trackingState)

            val journeyStart = config.journeyStartAt
            val journeyLine = if (config.trackingEnabled && journeyStart > 0) {
                val elapsed = (System.currentTimeMillis() - journeyStart).coerceAtLeast(0)
                val hours = elapsed / 3_600_000
                val minutes = (elapsed % 3_600_000) / 60_000
                getString(R.string.notif_journey_duration, hours, minutes)
            } else {
                getString(R.string.notif_journey_finished)
            }

            val details = mutableListOf<String>()
            details += getString(R.string.notif_state, state.label)
            details += MqttStatus.status
            if (battery in 0..100) {
                details += getString(R.string.notif_battery, battery)
            }
            if (pending > 0) {
                details += getString(R.string.notif_pending, pending)
            }

            val lines = mutableListOf<String>(journeyLine)
            lines += details.joinToString(" · ")
            if (battery in 1..20) {
                lines += getString(R.string.notif_warn_battery)
            }
            val lastFix = config.lastFixAt
            if (lastFix > 0 && System.currentTimeMillis() - lastFix > 5 * 60_000) {
                lines += getString(R.string.notif_warn_gps)
            }
            Notifications.update(this@TrackingService, getString(R.string.app_name), lines.joinToString("\n"))
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun batteryLevel(): Int =
        (getSystemService(BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private fun isBatteryLow(): Boolean {
        return batteryLevel() in 1..20
    }

    /** Intervalo efectivo: se duplica si la batería está por debajo del 15 % para ahorrar energía. */
    private fun effectiveIntervalSeconds(): Long {
        val battery = batteryLevel()
        return if (battery in 0..100 && battery < 15) config.intervalSeconds * 2 else config.intervalSeconds
    }

    private fun Location.isValidLocation(): Boolean =
        latitude.isFinite() && longitude.isFinite()
            && latitude in -90.0..90.0
            && longitude in -180.0..180.0
            && accuracy.isFinite() && accuracy >= 0f && accuracy < 500f

    private fun stopTracking() {
        if (stopping) return
        stopping = true
        started.set(false)
        networkCallback?.let {
            runCatching {
                (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(it)
            }
        }
        networkCallback = null
        Notifications.alert(this, getString(R.string.jornada_finalizada), getString(R.string.jornada_finalizada_body))
        try {
            fused?.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            // ignorar
        }
        publishState(TrackingState.TRACKING_DISABLED_BY_USER)
        Notifications.update(this, getString(R.string.app_name), getString(R.string.notif_journey_finished))
        // Drena primero las posiciones ya capturadas. Si MQTT está conectado pero no entrega
        // ACK, el cierre no puede dejar la cola abandonada al cancelar el servicio.
        val closingScope = serviceScope
        val closingMqtt = mqtt
        stopJob = stopControllerScope.launch {
            runCatching { flushPendingOnStop() }

            // Se encola después del primer drenaje para que la señal ended informe pending=0
            // cuando la cola pudo vaciarse. El segundo drenaje entrega la señal por HTTP si
            // MQTT sigue sin confirmar.
            val endedJob = enqueuePresence("ended", closingScope, closingMqtt)
            runCatching { endedJob.join() }
            runCatching { flushPendingOnStop() }
            delay(3000)
            withContext(Dispatchers.Main) {
                finishStopping(closingScope, closingMqtt)
            }
        }
    }

    private suspend fun flushPendingOnStop() {
        val deadline = System.currentTimeMillis() + 90_000L
        while (System.currentTimeMillis() < deadline) {
            val pending = withContext(Dispatchers.IO) { dao.count() }
            if (pending == 0) return
            val flushed = HttpFallbackDispatcher.flush(dao, config)
            if (flushed <= 0) return
        }
    }

    private fun finishStopping(closingScope: CoroutineScope, closingMqtt: MqttManager?) {
        if (!stopping) return
        closingMqtt?.disconnect()
        if (mqtt === closingMqtt) mqtt = null
        closingScope.cancel()
        val finalText = finishedJourneyText()
        stopForeground(STOP_FOREGROUND_REMOVE)
        Notifications.finished(this, finalText)
        stopJob = null

        if (pendingStart && config.trackingEnabled) {
            pendingStart = false
            stopping = false
            serviceScope = newServiceScope()
            started.set(false)
            try {
                ServiceCompat.startForeground(
                    this,
                    Notifications.NOTIFICATION_ID,
                    Notifications.foregroundNotification(
                        this,
                        getString(R.string.app_name),
                        getString(R.string.tracking_starting),
                    ),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                )
                startTracking()
            } catch (e: Exception) {
                config.trackingEnabled = false
                config.lastStartError = "No se pudo reiniciar la jornada"
                publishState(TrackingState.TRACKING_DISABLED_BY_USER)
                stopSelf()
            }
        } else {
            pendingStart = false
            stopping = false
            stopSelf()
        }
    }

    /** Texto de la notificación final: jornada finalizada con duración y resumen. */
    private fun finishedJourneyText(): String {
        val start = config.journeyStartAt
        val distanceM = config.journeyDistanceM
        val points = config.journeyPoints
        val confirmedPoints = config.journeyConfirmedPoints
        config.journeyStartAt = 0
        config.journeyDistanceM = 0.0
        config.journeyPoints = 0
        config.journeyConfirmedPoints = 0
        config.journeyLastLat = 0.0
        config.journeyLastLon = 0.0
        config.journeyHasLastLocation = false
        if (start <= 0) {
            config.journeyStopRequested = false
            return getString(R.string.notif_journey_finished)
        }
        val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(0)
        val hours = elapsed / 3_600_000
        val minutes = (elapsed % 3_600_000) / 60_000
        val duration = getString(R.string.journey_duration, hours, minutes)
        val km = String.format(java.util.Locale.US, "%.1f", distanceM / 1000.0)
        config.lastJourneySummary = "$duration|$km|$points|$confirmedPoints"
        return if (points > 0) {
            getString(R.string.notif_journey_finished_body, duration, km, points, confirmedPoints)
        } else {
            getString(R.string.notif_journey_finished_duration, duration)
        }
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
        if (this::config.isInitialized && config.trackingEnabled && !stopping) {
            publishState(TrackingState.SERVICE_RECOVERY)
        }
        stopJob?.cancel()
        stopControllerScope.cancel()
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
