package com.dmujeres.traccar.location

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.dmujeres.traccar.BuildConfig
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dmujeres.traccar.DmujeresApp
import com.dmujeres.traccar.R
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.db.PendingPosition
import com.dmujeres.traccar.db.StopDrainPolicy
import com.dmujeres.traccar.mqtt.Envelope
import com.dmujeres.traccar.mqtt.HttpFallbackDispatcher
import com.dmujeres.traccar.mqtt.HttpFlushPolicy
import com.dmujeres.traccar.mqtt.MqttStatus
import com.dmujeres.traccar.mqtt.MqttManager
import com.dmujeres.traccar.mqtt.PendingAlertPolicy
import com.dmujeres.traccar.util.JourneyFormatter
import com.dmujeres.traccar.util.LocationState
import com.dmujeres.traccar.util.NETCONF_SUSPECTED
import com.dmujeres.traccar.util.NetCause
import com.dmujeres.traccar.util.NetSnapshot
import com.dmujeres.traccar.util.Notifications
import com.dmujeres.traccar.util.TelInfo
import com.dmujeres.traccar.util.readTelInfo
import com.dmujeres.traccar.util.refine
import com.dmujeres.traccar.util.snapshot
import com.dmujeres.traccar.worker.TrackingRecoveryWorker
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
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

        /** Ventana anti re-entrega: mismo punto de red <5 min y <2 m no se encola. */
        private const val NETWORK_RELAY_SKIP_MS = 5 * 60_000L

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
    /**
     * Anti-livelock R1: rechazos consecutivos con ventana vacía (cero capturas).
     * Tras 10 (o 5 min desde startTracking) se funda la ventana con
     * Accept(lowQuality=true). Se resetea al aceptar.
     */
    @Volatile internal var emptyWindowRejects = 0

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            // FLP puede agrupar varios fixes mientras el proceso estaba ocupado o dormido.
            // Observabilidad: cada fix crudo cuenta como recibido.
            result.locations.forEach {
                runCatching { config.incFixReceived() }
                onNewLocation(it)
            }
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
        // Arranque del filtro anti-drift: el primer fix exige accuracy < 150 m.
        // Anti-livelock R1: si se recupera jornada NO se limpia la ventana (la
        // memoria del bueno persiste y evita re-livelock); en arranque nuevo sí
        // se parte de ventana vacía. Tras 10 rechazos con ventana vacía (o 5 min
        // desde startTracking) se funda la ventana con Accept(lowQuality=true).
        if (FixFilter.shouldClearWindowOnStart(recoveringJourney)) {
            recentFixes.clear()
        }
        emptyWindowRejects = 0
        // Adquisición activa: parte en modo quieto (0 m) para maximizar la
        // primera captura; el reloj es monotónico (elapsedRealtime).
        val bootNowElapsed = SystemClock.elapsedRealtimeNanos()
        trackingStartElapsedNanos = bootNowElapsed
        lastFixElapsedNanos = 0L
        lastPollAttemptElapsedNanos = 0L
        pollFailures = 0
        pollInFlight = false
        lastFixSpeedMps = null
        adaptiveMoving = false
        gnssForced = false
        currentMinDistanceM = AdaptiveDistancePolicy.DISTANCE_STATIONARY_M
        if (recoveringJourney) {
            // El monotónico no sobrevive a la muerte del proceso: mapea el
            // lastFixAt (wall) al arranque para no disparar polling con fix reciente.
            val wallDeltaMs = System.currentTimeMillis() - config.lastFixAt
            if (config.lastFixAt > 0L && wallDeltaMs >= 0L) {
                lastFixElapsedNanos = (bootNowElapsed - wallDeltaMs * 1_000_000L).coerceAtLeast(0L)
            }
        } else {
            GnssState.reset()
        }
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
        registerGnssCallback()
        registerNetworkCallback()
        registerAirplaneReceiver()
        serviceScope.launch { watchdogLoop() }
        refreshStateAndNotify()
    }

    private fun newServiceScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "Error en corutina del servicio", e)
        })

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var airplaneReceiver: BroadcastReceiver? = null

    @Volatile private var lastReportedNetwork: String = ""

    /** Foto de red sin fricción + causa probable (Fase 1). Persiste para la UI. */
    private fun currentNetSnapshot(): NetSnapshot =
        runCatching { snapshot(this, lastReportedNetwork) }.getOrDefault(
            NetSnapshot(
                wifiOn = true, airplane = false,
                hasWifiTransport = false, hasCellTransport = false,
                validated = false, captive = false,
                previousLabel = lastReportedNetwork,
            )
        )

    private fun currentNetCause(shot: NetSnapshot = currentNetSnapshot()): NetCause =
        runCatching { NetCause.detect(shot) }.getOrDefault(NetCause.OK)

    private fun persistNetState(shot: NetSnapshot, cause: NetCause) {
        runCatching {
            config.netCause = cause.value
            // netLabel guarda la última etiqueta usable para discriminar wifi_lost.
            if (shot.validated) {
                config.netLabel = when {
                    shot.hasWifiTransport -> "wifi"
                    shot.hasCellTransport -> "mobile"
                    else -> config.netLabel
                }
            }
        }
    }

    private fun registerAirplaneReceiver() {
        if (airplaneReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_AIRPLANE_MODE_CHANGED) return
                if (!started.get() || stopping) {
                    refreshStateAndNotify()
                    return
                }
                serviceScope.launch {
                    try {
                        val shot = currentNetSnapshot()
                        persistNetState(shot, currentNetCause(shot))
                        enqueuePresence()
                    } catch (e: Exception) {
                        Log.w(TAG, "No se pudo refrescar presencia por modo avión", e)
                    }
                    refreshStateAndNotify()
                }
            }
        }
        airplaneReceiver = receiver
        try {
            val filter = IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(receiver, filter)
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo registrar el receiver de modo avión", e)
            airplaneReceiver = null
        }
    }

    private fun unregisterAirplaneReceiver() {
        val receiver = airplaneReceiver ?: return
        airplaneReceiver = null
        runCatching { unregisterReceiver(receiver) }
    }

    private fun registerNetworkCallback() {
        val connectivity = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                runCatching {
                    val shot = currentNetSnapshot()
                    val cause = currentNetCause(shot)
                    persistNetState(shot, cause)
                    Log.i(TAG, "Red disponible netCause=${cause.value} validated=${shot.validated}")
                }
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
                runCatching {
                    val shot = currentNetSnapshot()
                    val cause = currentNetCause(shot)
                    persistNetState(shot, cause)
                    Log.i(TAG, "Red perdida netCause=${cause.value}")
                }
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
                    runCatching {
                        val shot = currentNetSnapshot()
                        persistNetState(shot, currentNetCause(shot))
                    }
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

            override fun onBlockedStatusChanged(network: android.net.Network, blocked: Boolean) {
                // Defensivo: solo telemetría, sin cambiar lógica de envío.
                Log.i(TAG, "onBlockedStatusChanged blocked=$blocked")
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
    /**
     * Adquisición GPS activa (reloj monotónico, nunca wall-clock para decidir):
     * - trackingStartElapsedNanos: arranque de la jornada (referencia si aún no hay fix).
     * - lastFixElapsedNanos: último fix encolado OK (avanza junto a config.lastFixAt).
     * - lastPollAttemptElapsedNanos/pollFailures: backoff 90 s → 3 min → 5 min.
     * - pollInFlight: one-shot en vuelo (viaja en presence como `pollActive`).
     * - lastFixSpeedMps/adaptiveMoving/currentMinDistanceM: min-distance adaptativa
     *   (0 m quieto / 15 m en movimiento, con histéresis).
     */
    @Volatile private var trackingStartElapsedNanos = 0L
    @Volatile private var lastFixElapsedNanos = 0L
    @Volatile private var lastPollAttemptElapsedNanos = 0L
    @Volatile private var pollFailures = 0
    @Volatile private var pollInFlight = false
    @Volatile private var lastFixSpeedMps: Float? = null
    @Volatile private var adaptiveMoving = false
    @Volatile private var currentMinDistanceM = AdaptiveDistancePolicy.DISTANCE_STATIONARY_M
    private var gnssCallback: GnssStatus.Callback? = null

    /**
     * GNSS forzado: si el FLP pasa >60 s sin entregar fix fresco (reentrega
     * indefinida del mismo fix cacheado de red, el caso del P8 de santiago), el
     * request se registra con el proveedor GNSS forzado (API 31+) y, donde no es
     * posible o la ROM lo rechaza, un listener directo de GPS_PROVIDER alimenta
     * el mismo pipeline onNewLocation.
     */
    @Volatile private var gnssForced = false
    @Volatile private var gnssFallbackRegistered = false
    @Volatile private var lastNoGpsAlertAt = 0L
    private val gnssFallbackListener = android.location.LocationListener { location ->
        // La misma vía que el FLP: filtro, cola y telemetría sin duplicar lógica.
        if (!started.get() || stopping) return@LocationListener
        runCatching { config.incFixReceived() }
        onNewLocation(location)
    }

    /** Etiqueta del contrato (server/panel): "gps"|"network"|"fused"|"unknown". */
    private fun providerLabel(location: Location): String =
        when (location.provider?.lowercase()) {
            "gps" -> "gps"
            "network" -> "network"
            null, "" -> "unknown"
            else -> "fused"
        }

    private fun fixAgeSeconds(location: Location): Long {
        val nanos = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
        return if (location.elapsedRealtimeNanos > 0L && nanos > 0L) nanos / 1_000_000_000L else 0L
    }

    private fun registerGnssFallback() {
        if (gnssFallbackRegistered || !hasFineLocation()) return
        runCatching {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            @Suppress("DEPRECATION")
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, config.intervalSeconds * 1000L, 0f, gnssFallbackListener)
            gnssFallbackRegistered = true
            Log.i(TAG, "Fallback GPS_PROVIDER registrado")
        }.onFailure { Log.w(TAG, "No se pudo registrar el fallback GPS", it) }
    }

    private fun unregisterGnssFallback() {
        if (!gnssFallbackRegistered) return
        gnssFallbackRegistered = false
        runCatching {
            (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(gnssFallbackListener)
        }
    }

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
        val builder = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            effectiveIntervalSeconds() * 1000L
        ).setMinUpdateIntervalMillis(effectiveIntervalSeconds() * 500L)
            // Anti-drift adaptativa: 0 m en quietud (recibir todo lo que el FLP
            // dé, caso detenido/interior) y 15 m en movimiento. Ver
            // AdaptiveDistancePolicy; se re-registra solo al cambiar de modo.
            .setMinUpdateDistanceMeters(currentMinDistanceM)
        val request = builder.build()
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

    /**
     * GNSS real (API 24+, minSdk 26): cuenta satélites en vista/usados en fix.
     * Con 0 en vista + sin fix = "bajo techo/sin cielo", distinto de "GPS
     * apagado" (eso lo dicen `gps`/`gpsEnabled` en presence). Se registra
     * mientras el servicio corre y se desregistra en onDestroy/stop.
     */
    private fun registerGnssCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        if (gnssCallback != null) return
        if (!hasFineLocation()) return
        try {
            val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
            val callback = object : GnssStatus.Callback() {
                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    try {
                        val flags = (0 until status.satelliteCount).map { status.usedInFix(it) }
                        val counts = GnssSummary.summarize(flags)
                        GnssState.update(counts.used, counts.total, System.currentTimeMillis())
                    } catch (e: Exception) {
                        Log.w(TAG, "No se pudo leer GnssStatus", e)
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                locationManager.registerGnssStatusCallback(
                    ContextCompat.getMainExecutor(this), callback,
                )
            } else {
                @Suppress("DEPRECATION")
                locationManager.registerGnssStatusCallback(callback)
            }
            gnssCallback = callback
        } catch (e: SecurityException) {
            Log.w(TAG, "GNSS sin permiso de ubicación", e)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo registrar GnssStatus", e)
        }
    }

    private fun unregisterGnssCallback() {
        val callback = gnssCallback ?: return
        gnssCallback = null
        try {
            (getSystemService(LOCATION_SERVICE) as LocationManager)
                .unregisterGnssStatusCallback(callback)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo desregistrar GnssStatus", e)
        }
    }

    /**
     * Aplica la min-distance adaptativa. Actualiza el modo siempre (para que
     * `resumeCaptureAfterBuffer` use el valor vigente) pero re-registra el
     * request SOLO al cambiar de modo, reutilizando el re-registro existente
     * (sin timers nuevos). Devuelve true si re-registró.
     */
    private fun applyAdaptiveMode(hasRecentFix: Boolean): Boolean {
        val current = if (adaptiveMoving) {
            AdaptiveDistancePolicy.Mode.MOVING
        } else {
            AdaptiveDistancePolicy.Mode.STATIONARY
        }
        val next = AdaptiveDistancePolicy.nextMode(current, lastFixSpeedMps, hasRecentFix)
        if (next == current) return false
        adaptiveMoving = next == AdaptiveDistancePolicy.Mode.MOVING
        currentMinDistanceM = AdaptiveDistancePolicy.distanceFor(next)
        if (!started.get() || stopping || capturePausedForBuffer) return false
        Log.i(TAG, "Distancia adaptativa → ${currentMinDistanceM}m (modo $next, speed=$lastFixSpeedMps)")
        runCatching { fused?.removeLocationUpdates(locationCallback) }
        requestLocationUpdates()
        lastGpsReregisterAt = System.currentTimeMillis()
        return true
    }

    /**
     * Polling activo one-shot: si la jornada sigue activa y el FLP pasivo no
     * entrega fixes, busca activamente el fix con `getCurrentLocation`
     * (HIGH_ACCURACY, timeout 30 s). El resultado entra por el MISMO
     * `onNewLocation`/filtro (no hay vía alterna). Backoff 90 s → 3 min →
     * 5 min entre intentos fallidos; con batería < 15 % cada 10 min.
     */
    private fun maybeActivePoll(batteryPct: Int) {
        if (!started.get() || stopping || !config.trackingEnabled) return
        if (capturePausedForBuffer) return
        if (!hasFineLocation()) return
        val shouldFire = ActivePollPolicy.shouldPoll(
            trackingActive = true,
            nowElapsedNanos = SystemClock.elapsedRealtimeNanos(),
            lastFixElapsedNanos = lastFixElapsedNanos,
            startElapsedNanos = trackingStartElapsedNanos,
            lastPollAttemptElapsedNanos = lastPollAttemptElapsedNanos,
            consecutiveFailures = pollFailures,
            batteryPct = batteryPct,
        )
        if (!shouldFire) return
        launchActivePoll()
    }

    private fun launchActivePoll() {
        if (pollInFlight) return
        val client = fused ?: return
        pollInFlight = true
        lastPollAttemptElapsedNanos = SystemClock.elapsedRealtimeNanos()
        Log.i(TAG, "Polling activo: getCurrentLocation HIGH_ACCURACY (timeout 30 s)")
        val cts = CancellationTokenSource()
        serviceScope.launch {
            delay(ActivePollPolicy.POLL_TIMEOUT_MS)
            if (pollInFlight) runCatching { cts.cancel() }
        }
        try {
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnSuccessListener { location ->
                    if (location != null) {
                        runCatching { config.incFixReceived() }
                        pollInFlight = false
                        onNewLocation(location)
                    } else {
                        pollFailures++
                        pollInFlight = false
                        Log.i(TAG, "Polling activo sin resultado (null); reintento en " +
                            "${ActivePollPolicy.retryDelayNanos(pollFailures) / 1_000_000_000L} s")
                    }
                }
                .addOnFailureListener { error ->
                    pollFailures++
                    pollInFlight = false
                    Log.w(TAG, "Polling activo falló; backoff", error)
                }
                .addOnCanceledListener {
                    pollFailures++
                    pollInFlight = false
                    Log.i(TAG, "Polling activo cancelado/timeout; backoff")
                }
        } catch (e: SecurityException) {
            pollInFlight = false
            Log.w(TAG, "Polling activo sin permiso", e)
        } catch (e: Exception) {
            pollFailures++
            pollInFlight = false
            Log.w(TAG, "Polling activo error", e)
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
                persistNetState(telemetry.shot, telemetry.cause)
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
                    rttMs = telemetry.rttMs,
                    signal = telemetry.signal,
                    netCause = telemetry.cause.value,
                    validated = telemetry.shot.validated,
                    wifiEnabled = telemetry.shot.wifiOn,
                    airplane = telemetry.shot.airplane,
                    dataEnabled = telemetry.dataEnabled,
                    simPresent = telemetry.simPresent,
                    service = telemetry.service,
                    netConf = telemetry.netConf,
                    fixReceived = telemetry.fixReceived,
                    fixRejected = telemetry.fixRejected,
                    fixEnqueued = telemetry.fixEnqueued,
                    permFine = telemetry.permFine,
                    permBackground = telemetry.permBackground,
                    gpsEnabled = telemetry.gpsEnabled,
                    gnssUsed = telemetry.gnssUsed,
                    gnssTotal = telemetry.gnssTotal,
                    pollActive = telemetry.pollActive.takeIf { it },
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
        val signal: Int,
        val rttMs: Int,
        val shot: NetSnapshot,
        val cause: NetCause,
        /** Telefonía opcional (null sin permiso READ_PHONE_STATE). */
        val dataEnabled: Boolean?,
        val simPresent: Boolean?,
        val service: String?,
        /** Confianza de la causa: "confirmed" | "suspected". */
        val netConf: String,
        /** Observabilidad anti "cero capturas en silencio". */
        val fixReceived: Long,
        val fixRejected: Long,
        val fixEnqueued: Long,
        val permFine: Boolean,
        val permBackground: Boolean,
        val gpsEnabled: Boolean,
        /** GNSS real (null si aún sin eventos) + polling one-shot en vuelo. */
        val gnssUsed: Int?,
        val gnssTotal: Int?,
        val pollActive: Boolean,
    )

    /**
     * Etiqueta común de red (wifi|mobile|none) para presence y position. Requiere
     * NET_CAPABILITY_VALIDATED; un socket sin validar se reporta como "none".
     */
    private fun currentNetworkLabel(): String {
        val connectivity = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?: return "none"
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return "none"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            else -> "mobile"
        }
    }

    /**
     * Nivel de señal 0-4 de la red activa. Con SDK>=29 usa signalStrength (-1..4;
     * -1 = desconocido → fallback); el fallback mapea el ancho de banda estimado a buckets.
     */
    private fun currentSignalLevel(): Int {
        val connectivity = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?: return 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val strength = capabilities.signalStrength
            if (strength >= 0) return strength.coerceIn(0, 4)
        }
        val kbps = capabilities.linkDownstreamBandwidthKbps.toLong()
        return when {
            kbps > 10_000L -> 4
            kbps > 3_000L -> 3
            kbps > 1_000L -> 2
            kbps > 300L -> 1
            else -> 0
        }
    }

    private fun telemetry(): Telemetry {
        val pendingCount = try {
            runBlockingSafe { dao.count() }
        } catch (e: Exception) {
            0
        }
        val batteryManager = getSystemService(BATTERY_SERVICE) as BatteryManager
        val battery = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val network = currentNetworkLabel()
        val gpsOn = LocationState.isEnabled(this)
        val shot = currentNetSnapshot()
        val base = currentNetCause(shot)
        // Telefonía opcional (READ_PHONE_STATE bajo demanda): sin permiso se
        // degrada a la heurística sin permiso; nunca bloquea ni lanza.
        val tel = runCatching { readTelInfo(this) }.getOrDefault(TelInfo.noPermission())
        val (cause, conf) = runCatching { refine(base, tel) }.getOrDefault(base to NETCONF_SUSPECTED)
        return Telemetry(
            pending = pendingCount,
            battery = battery,
            network = network,
            vendor = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            appVersion = BuildConfig.VERSION_NAME,
            gps = if (gpsOn) "on" else "off",
            signal = currentSignalLevel(),
            rttMs = config.mobileRttMs,
            shot = shot,
            cause = cause,
            dataEnabled = tel.dataEnabled,
            simPresent = tel.simPresent,
            service = tel.service,
            netConf = conf,
            fixReceived = runCatching { config.fixReceived }.getOrDefault(0L),
            fixRejected = runCatching { config.fixRejected }.getOrDefault(0L),
            fixEnqueued = runCatching { config.fixEnqueued }.getOrDefault(0L),
            permFine = hasFineLocation(),
            permBackground = hasBackgroundLocation(),
            gpsEnabled = gpsOn,
            // GNSS real solo si hay eventos; pollActive solo viaja en true.
            gnssUsed = GnssState.satsUsed.takeIf { GnssState.hasData() },
            gnssTotal = GnssState.satsTotal.takeIf { GnssState.hasData() },
            pollActive = pollInFlight,
        )
    }

    private fun hasFineLocation(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasBackgroundLocation(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return hasFineLocation()
        return ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
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

    private val recentFixes = ArrayDeque<FixFilter.RecentFix>()
    private val fixWindowSize = FixFilter.WINDOW_SIZE

    /**
     * Ventana honesta: registra TODOS los evaluados (aceptados y rechazados, con su
     * accuracy/tiempo), cap 6. El llamador evita fundar la ventana con el primer fix
     * malo (ver onNewLocation).
     */
    private fun recordRecentFix(location: Location) {
        val elapsed = runCatching { location.elapsedRealtimeNanos }.getOrDefault(0L)
        recentFixes.addLast(
            FixFilter.RecentFix(
                location.latitude,
                location.longitude,
                location.accuracy,
                location.time,
                elapsed,
            ),
        )
        if (recentFixes.size > fixWindowSize) recentFixes.removeFirst()
    }

    private fun recentMovingConsistently(): Boolean =
        FixFilter.recentMovingConsistently(recentFixes, config.consistentSpeedMps)

    private fun Location.isPlausibleFix(nowElapsedNanos: Long = SystemClock.elapsedRealtimeNanos()): FixFilter.Decision {
        val elapsed = runCatching { elapsedRealtimeNanos }.getOrDefault(0L)
        val decision = FixFilter.evaluate(
            lat = latitude,
            lon = longitude,
            accuracyM = accuracy,
            wallTimeMs = time,
            elapsedNanos = elapsed,
            nowElapsedNanos = nowElapsedNanos,
            window = recentFixes,
            maxImpliedSpeedMps = config.maxImpliedSpeedMps,
            accuracyBadM = config.accuracyBadM,
            accuracyGoodM = config.accuracyGoodM,
            consistentSpeedMps = config.consistentSpeedMps,
        )
        if (decision is FixFilter.Decision.Reject) {
            val impliedHint = when (decision.reason) {
                "implied_speed" -> {
                    val prev = recentFixes.lastOrNull()
                    if (prev != null) {
                        val dt = (time - prev.timeMs) / 1000.0
                        if (dt > 0) {
                            " (implícita %.1f m/s)".format(
                                FixFilter.distanceMeters(prev.lat, prev.lon, latitude, longitude) / dt,
                            )
                        } else ""
                    } else ""
                }
                "degraded" -> " (accuracy %.0f m)".format(accuracy)
                else -> ""
            }
            Log.w(TAG, "Fix descartado por filtro ${decision.reason}$impliedHint")
        }
        return decision
    }

    private fun onNewLocation(location: Location) {
        if (!started.get() || stopping) return
        if (!location.isValidLocation()) {
            runCatching { config.incFixRejected() }
            return
        }
        val nowElapsed = SystemClock.elapsedRealtimeNanos()
        val rawDecision = location.isPlausibleFix(nowElapsed)
        val decision: FixFilter.Decision = if (rawDecision is FixFilter.Decision.Reject) {
            if (recentFixes.isEmpty() && rawDecision.reason == "first_fix_bad") {
                // Anti-livelock R1: ventana vacía + todo >=150 = cero capturas.
                emptyWindowRejects++
                val elapsedSinceStart = System.currentTimeMillis() - startedTrackingAt
                if (FixFilter.shouldForceEmptyWindowAccept(emptyWindowRejects, elapsedSinceStart)) {
                    Log.i(
                        TAG,
                        "Anti-livelock R1: fundo ventana tras $emptyWindowRejects rechazos " +
                            "ventana vacía (${elapsedSinceStart}ms desde start), " +
                            "accept lowQuality acc=${location.accuracy}",
                    )
                    emptyWindowRejects = 0
                    FixFilter.Decision.Accept(lowQuality = true)
                } else {
                    runCatching { config.incFixRejected() }
                    return
                }
            } else {
                // Ventana honesta: el rechazo también deja memoria, salvo el primer fix
                // malo que no debe fundar la ventana (arranque sin referencia fiable).
                if (recentFixes.isNotEmpty()) recordRecentFix(location)
                runCatching { config.incFixRejected() }
                return
            }
        } else {
            emptyWindowRejects = 0
            rawDecision
        }
        val lowQuality = (decision as FixFilter.Decision.Accept).lowQuality
        recordRecentFix(location)
        // Fix válido aceptado por el filtro (misma vía para FLP pasivo y
        // polling activo): memoriza speed para la distancia adaptativa. El fix
        // en curso es reciente por definición.
        lastFixSpeedMps = if (location.hasSpeed()) location.speed.coerceAtLeast(0f) else null
        runCatching { applyAdaptiveMode(hasRecentFix = true) }
        // lastFixAt avanza SOLO tras insert OK (ver abajo): avanzar antes
        // enmascara fallos de DB/buffer y suprime heartbeats.
        val deviceId = config.deviceId
        if (deviceId.isBlank()) return
        serviceScope.launch {
            try {
                enqueueMutex.withLock {
                    if (!started.get() || stopping) return@withLock
                    // Re-entrega del mismo fix cacheado de red (GPS muerto, wifi
                    // repetida): no encolar ni avanzar lastFixAt — heartbeat, polling
                    // y GNSS forzado deben seguir contándolo como "sin fix".
                    if (providerLabel(location) == "network" && config.journeyHasLastLocation &&
                        System.currentTimeMillis() - config.lastFixAt < NETWORK_RELAY_SKIP_MS &&
                        distanceMeters(
                            lastJourneyLat, lastJourneyLon, location.latitude, location.longitude,
                        ) < 2.0
                    ) {
                        runCatching { config.incFixRejected() }
                        return@withLock
                    }
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
                    val network = currentNetworkLabel()
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
                        lowQuality = lowQuality,
                        provider = providerLabel(location),
                        fixAgeSec = fixAgeSeconds(location),
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
                    if (!FixFilter.shouldAdvanceLastFix(discarded)) {
                        setState(TrackingState.BUFFER_FULL)
                        return@withLock
                    }
                    // lastFixAt DESPUÉS de insert OK: si el buffer/DB falla (-1)
                    // no avanza y el heartbeat sigue avisando sin GPS útil.
                    // El monotónico avanza junto a él y resetea el backoff del polling.
                    config.lastFixAt = System.currentTimeMillis()
                    lastFixElapsedNanos = SystemClock.elapsedRealtimeNanos()
                    pollFailures = 0
                    runCatching { config.incFixEnqueued() }
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

            // Fase A: densidad constante — adaptación por batería deshabilitada.
            // Se mantiene telemetría de batería pero no se altera frecuencia de captura.
            // Código anterior que duplicaba intervalo eliminado; lastBatteryAdaptationAt se conserva
            // solo para no romper estado persistido, pero no se usa.

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

            // GNSS forzado (ver campos gnssForced arriba): sin fix fresco sostenido,
            // re-solicitar y enganchar listener directo de GPS_PROVIDER (no existe
            // API pública para forzar proveedor en el request del FLP).
            if (gpsWithoutFix && started.get() && !capturePausedForBuffer) {
                if (!gnssForced) {
                    gnssForced = true
                    Log.i(TAG, "GNSS forzado: sin fix fresco >60 s")
                    runCatching { fused?.removeLocationUpdates(locationCallback) }
                    requestLocationUpdates()
                } else {
                    registerGnssFallback()
                }
                if (now - startedTrackingAt > 5 * 60_000L && now - lastNoGpsAlertAt > 10 * 60_000L &&
                    (!GnssState.hasData() || (GnssState.satsUsed ?: 0) <= 1)
                ) {
                    lastNoGpsAlertAt = now
                    Log.w(TAG, "Jornada activa sin satélites GNSS (used=${GnssState.satsUsed})")
                    Notifications.alert(
                        this,
                        getString(R.string.sin_gps_title),
                        getString(R.string.sin_gps_body),
                    )
                }
            } else if (!gpsWithoutFix && gnssForced) {
                gnssForced = false
                unregisterGnssFallback()
                Log.i(TAG, "Fix fresco recuperado: se levanta el GNSS forzado")
                runCatching { fused?.removeLocationUpdates(locationCallback) }
                requestLocationUpdates()
            }

            // Adquisición activa (SIN timers nuevos: corre en este watchdog de 30 s):
            // - Sin fix reciente → modo quieto (0 m) para recibir todo lo que el
            //   FLP dé (el re-registro lo hace applyAdaptiveMode al cambiar de modo).
            // - Sin fix válido en > 90 s → one-shot getCurrentLocation con backoff.
            val batteryNow = batteryLevel()
            runCatching {
                val staleAfterNanos = maxOf(60_000L, effectiveIntervalSeconds() * 3_000L) * 1_000_000L
                val fixElapsed = lastFixElapsedNanos
                val nowElapsed = SystemClock.elapsedRealtimeNanos()
                val hasRecentFix = fixElapsed > 0L && nowElapsed - fixElapsed <= staleAfterNanos
                applyAdaptiveMode(hasRecentFix)
            }.onFailure { Log.w(TAG, "No se pudo aplicar distancia adaptativa", it) }
            runCatching { maybeActivePoll(batteryNow) }
                .onFailure { Log.w(TAG, "No se pudo disparar polling activo", it) }

            // Solo es "sin confirmación" lo anormal (ver PendingAlertPolicy): con el dispatch
            // secuencial (1 en vuelo, ackTimeout 15 s) casi siempre hay 1-5 pendientes sanos y
            // no deben disparar estado de error, alerta ni wake. Anormal = más viejo > 10 min
            // (usa oldestEnqueuedAt) o > 30 pendientes o sin ACK > 2 min.
            val pendingWithoutAck = PendingAlertPolicy.isAbnormal(
                pendingCount = pendingCount,
                oldestPendingAt = oldestPendingAt,
                lastAckAt = config.lastAckAt,
                now = now,
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
            maybeNotifyOperationalAlerts(now, connectionUnavailable, batteryNow)

            // El plan B HTTP drena la cola cuando MQTT no entrega: desconectado, o conectado
            // pero sin ACK reciente (broker/servidor mudo, caso del bug de 3906). Además ayuda
            // aunque haya ACKs recientes si el pendiente más viejo envejece: los ACKs de
            // presencia (heartbeat sin fix) refrescan lastAckAt y ocultarían un backlog de
            // posiciones atascado con MQTT "conectado". Ver HttpFlushPolicy.
            if (HttpFlushPolicy.shouldFlush(
                    pendingCount = pendingCount,
                    mqttDelivering = !connectionUnavailable,
                    lastAckAt = config.lastAckAt,
                    oldestPendingAt = oldestPendingAt,
                    now = now,
                )
            ) {
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
                val (hours, minutes) = JourneyFormatter.durationParts(System.currentTimeMillis() - journeyStart)
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

    /** Intervalo efectivo: Fase A densidad constante — NO se altera por batería baja. */
    private fun effectiveIntervalSeconds(): Long {
        return config.intervalSeconds
    }

    private fun Location.isValidLocation(): Boolean {
        val reason = FixFilter.invalidLocationReason(latitude, longitude, accuracy)
        if (reason != null) {
            Log.w(TAG, "Fix descartado por inválido $reason")
            return false
        }
        return true
    }

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
        unregisterAirplaneReceiver()
        Notifications.alert(this, getString(R.string.jornada_finalizada), getString(R.string.jornada_finalizada_body))
        try {
            fused?.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            // ignorar
        }
        unregisterGnssCallback()
        unregisterGnssFallback()
        gnssForced = false
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
        // Al finalizar NO se borra la cola: se drena por HTTP hasta vaciarla o
        // hasta el deadline (ver StopDrainPolicy). Lo que quede sigue en Room y
        // lo drena TrackingRecoveryWorker; nunca se descarta.
        val deadline = System.currentTimeMillis() + StopDrainPolicy.TIMEOUT_MS
        while (!StopDrainPolicy.timedOut(System.currentTimeMillis(), deadline)) {
            val pending = withContext(Dispatchers.IO) { dao.count() }
            if (pending == 0) return
            val flushed = HttpFallbackDispatcher.flush(dao, config)
            delay(StopDrainPolicy.retryDelayAfter(flushed))
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
        val (hours, minutes) = JourneyFormatter.durationParts(System.currentTimeMillis() - start)
        val duration = getString(R.string.journey_duration, hours, minutes)
        val km = JourneyFormatter.formatKm(distanceM)
        config.lastJourneySummary = JourneyFormatter.buildSummary(duration, km, points, confirmedPoints)
        return if (points > 0) {
            getString(R.string.notif_journey_finished_body, duration, km, points, confirmedPoints)
        } else {
            getString(R.string.notif_journey_finished_duration, duration)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Si el usuario cierra la app desde la lista de recientes, el tracking continúa.
        // Doble red: reinicio directo + worker inmediato (en Xiaomi/MIUI el reinicio
        // directo tras swipe suele morir de nuevo; el worker expedited lo reintenta
        // con backoff aunque el proceso caiga).
        if (config.trackingEnabled) {
            Notifications.ensureChannel(this)
            runCatching { TrackingService.start(this) }
            runCatching { TrackingRecoveryWorker.enqueueImmediate(this) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        unregisterAirplaneReceiver()
        runCatching {
            networkCallback?.let {
                (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(it)
            }
        }
        networkCallback = null
        if (this::config.isInitialized && config.trackingEnabled && !stopping) {
            publishState(TrackingState.SERVICE_RECOVERY)
        }
        stopJob?.cancel()
        stopControllerScope.cancel()
        serviceScope.cancel()
        unregisterGnssCallback()
        try {
            fused?.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            // ignorar
        }
        mqtt?.disconnect()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
