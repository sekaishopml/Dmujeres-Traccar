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
import com.dmujeres.traccar.db.DeadLetter
import com.dmujeres.traccar.db.OutboxRetentionPolicy
import com.dmujeres.traccar.db.PendingPosition
import com.dmujeres.traccar.db.StopDrainPolicy
import com.dmujeres.traccar.mqtt.Envelope
import com.dmujeres.traccar.mqtt.MqttServerNormalizer
import com.dmujeres.traccar.mqtt.MqttStatus
import com.dmujeres.traccar.mqtt.MqttManager
import com.dmujeres.traccar.mqtt.PendingAlertPolicy
import com.dmujeres.traccar.mqtt.PositionOutboxDispatcher
import com.dmujeres.traccar.util.DiagnosticsCollector
import com.dmujeres.traccar.util.DiagnosticsReporter
import com.dmujeres.traccar.util.JourneyFormatter
import com.dmujeres.traccar.util.LocationState
import com.dmujeres.traccar.util.LinkState
import com.dmujeres.traccar.util.MqttLink
import com.dmujeres.traccar.util.Transport
import com.dmujeres.traccar.util.NETCONF_SUSPECTED
import com.dmujeres.traccar.util.NetCause
import com.dmujeres.traccar.util.NetSnapshot
import com.dmujeres.traccar.util.Notifications
import com.dmujeres.traccar.util.SentryLog
import com.dmujeres.traccar.util.SpeedEstimator
import com.dmujeres.traccar.util.TelInfo
import com.dmujeres.traccar.util.readTelInfo
import com.dmujeres.traccar.util.refine
import com.dmujeres.traccar.util.snapshot
import com.dmujeres.traccar.widget.JourneyWidget
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

        /**
         * Debounce entre drenajes del outbox: `onAvailable` puede flapear en
         * handovers WiFi↔datos y además dispara `onMqttStateChanged(CONNECTED)`;
         * sin esto el mismo backlog se drenaba 2-3 veces en segundos (trabajo
         * duplicado; el Mutex lo serializaba pero igual gastaba radio).
         */
        private const val DRAIN_DEBOUNCE_MS = 10_000L
        private const val DRAIN_CHAIN_DELAY_MS = 10_500L

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
    /** El foreground ya fue reclamado (startForeground OK) en esta corrida del servicio. */
    @Volatile private var foregroundClaimed = false
    /** Heavy init (DB/recoveries/FLP) hecho, post-reclamo. Ver [initCore]. */
    @Volatile private var coreReady = false
    @Volatile private var currentState = TrackingState.TRACKING_DISABLED_BY_USER
    @Volatile private var startedTrackingAt = 0L
    /**
     * Acumulador monotónico de la duración de jornada (inmune a saltos NTP):
     * elapsed de la última sesión + base de elapsedRealtime al arrancar/recuperar.
     * monoBaseElapsed == 0 es centinela de "aún no corrió startTracking".
     */
    @Volatile private var monoBasePersisted = 0L
    @Volatile private var monoBaseElapsed = 0L
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

    /** Última re-inicialización del motor de ubicación (escalera anti-hambruna). */
    @Volatile private var lastEngineReinitAt = 0L

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            // FLP puede agrupar varios fixes mientras el proceso estaba ocupado o dormido.
            // Reloj por capa (§13): callback crudo, haya o no fix válido después.
            runCatching { config.lastLocationCallbackAt = System.currentTimeMillis() }
            orderFixes(result.locations).forEach {
                runCatching { config.incFixReceived() }
                onNewLocation(it)
            }
        }
    }

    /**
     * Ordena un lote del FLP por tiempo de fix ASC para procesar cronológico:
     * si TODOS traen elapsedRealtimeNanos > 0 se ordena por el monotónico
     * (robusto a saltos NTP); si no, si todos traen time > 0 se ordena por wall
     * del fix; si no, llega el orden original del proveedor. Ver FixTime.
     */
    private fun orderFixes(locations: List<Location>): List<Location> {
        if (locations.size < 2) return locations
        val elapsed = locations.map { runCatching { it.elapsedRealtimeNanos }.getOrDefault(0L) }
        val times = locations.map { it.time }
        val indices = FixTime.orderIndices(elapsed, times)
        return if (indices == elapsed.indices.toList()) locations else indices.map { locations[it] }
    }

    /**
     * onCreate deliberadamente mínimo: el canal (que necesita la notificación del
     * reclamo) + prefs. La apertura de DB, las heurísticas de arranque anómalo y
     * el cliente FLP se corren DESPUÉS de reclamar el foreground en
     * [onStartCommand] ([initCore]) para no gastar el timeout de
     * ForegroundServiceDidNotStartInTimeException (crash real en Sentry ×2) en
     * trabajo pesado antes del startForeground.
     */
    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
        config = AppConfig(this)
    }

    /**
     * Reclama el foreground con la notificación mínima ("Iniciando seguimiento…")
     * idempotente. Devuelve null OK / el Throwable del fallo (SecurityException en
     * Android 14 sin FOREGROUND_SERVICE_LOCATION, u otro).
     */
    private fun claimForeground(): Throwable? {
        if (foregroundClaimed) return null
        return try {
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
            foregroundClaimed = true
            null
        } catch (e: Exception) {
            e
        }
    }

    /** Libera el reclamo (quita la notificación ongoing del id 1, también la de boot). */
    private fun releaseForeground() {
        foregroundClaimed = false
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    /**
     * Init pesado que antes corría en onCreate, ahora tras el reclamo.
     * Idempotente. false = BD inutilizable (el servicio ya se auto-detuvo).
     * Mantiene el orden original: detecciones anómalas ANTES de pisar
     * cleanShutdown y antes de abrir la BD.
     */
    private fun initCore(): Boolean {
        if (coreReady) return true
        runCatching { detectAbnormalRestarts() }
        return try {
            dao = (application as DmujeresApp).database.positionDao()
            fused = LocationServices.getFusedLocationProviderClient(this)
            coreReady = true
            isRunning = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir la base de datos", e)
            config.trackingEnabled = false
            config.lastStartError = "Error de base de datos: " + (e.message ?: e.javaClass.simpleName)
            publishState(TrackingState.SERVER_UNAVAILABLE)
            releaseForeground()
            stopSelf()
            false
        }
    }

    /**
     * Heurística de arranques anómalos tras reclamar el foreground (ver KDoc del
     * bloque de salud en AppConfig):
     * - cleanShutdown==false con jornada abierta → la corrida anterior murió sin
     *   ACTION_STOP (crash, kill de OEM o reboot) → crashes24h++ + breadcrumb +
     *   report("crash_boot") (reason forzada, salta el throttle).
     * - journeyStopRequested con jornada abierta → crash a mitad de stop
     *   (decisión START_PREFER_RECOVERY de StuckStopPolicy, la cuentan BootReceiver
     *   y TrackingRecoveryWorker antes de volver a arrancar el servicio) →
     *   stuckStops24h++.
     * Después se apaga cleanShutdown: el servicio vivo aún no cerró limpio.
     */
    private fun detectAbnormalRestarts() {
        val unclean = !config.cleanShutdown && config.journeyStartAt > 0L
        if (config.journeyStopRequested && config.journeyStartAt > 0L) {
            runCatching { config.incStuckStop24h() }
        }
        config.cleanShutdown = false
        if (unclean) {
            runCatching { config.incCrash24h() }
            SentryLog.breadcrumb("journey", "crash_boot", "Arranque tras muerte inesperada con jornada abierta")
            runCatching { DiagnosticsReporter.report(this, "crash_boot") }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val isStop = intent?.action == ACTION_STOP
        // ANTI ForegroundServiceDidNotStartInTimeException (crash real Sentry): el
        // primer acto es pagar la promesa de startForegroundService con
        // ServiceCompat.startForeground + notificación mínima, ANTES de tocar
        // config/DAO/MQTT. STOP sobre servicio no arrancado jamás reclama
        // (Android 14+); basta con stopSelf() abajo, que cancela la promesa.
        val alive = started.get() || stopping || isRunning
        if (ForegroundClaimPolicy.shouldClaimForeground(isStop, alive)) {
            claimForeground()?.let { error ->
                if (isStop) {
                    // Estaba vivo: ya había reclamado antes; el cierre sigue igual.
                    Log.w(TAG, "No se pudo re-afirmar el foreground en ACTION_STOP", error)
                } else {
                    val permissionFailure = error is SecurityException
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
            }
        }
        // STOP sobre servicio muerto sin jornada colgada: no se toca la DB ni nada
        // pesado; se publica, se quita la ongoing de boot y stopSelf inmediato.
        val needCore = !isStop || started.get() || stopping || config.journeyStartAt > 0L
        if (needCore && !initCore()) {
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                // Breadcrumb del botón (widget y MainActivity llegan por aquí).
                runCatching { SentryLog.breadcrumb("journey", "service_action", "ACTION_STOP") }
                // Cierre solicitado por el usuario: cuenta como apagado limpio.
                runCatching { config.cleanShutdown = true }
                config.trackingEnabled = false
                pendingStart = false
                if (started.get() || stopping || config.journeyStartAt > 0L) {
                    stopTracking()
                } else {
                    publishState(TrackingState.TRACKING_DISABLED_BY_USER)
                    config.journeyStopRequested = false
                    releaseForeground()
                    // Refresca el widget ANTES de morir: si no, quedaría pintando
                    // "Finalizar jornada" hasta el siguiente onUpdate del launcher.
                    runCatching { JourneyWidget.updateAll(this) }
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            else -> {
                // START_STICKY null-intent y arranques (widget/MainActivity/Boot) caen aquí.
                runCatching { SentryLog.breadcrumb("journey", "service_action", "ACTION_START (${intent?.action ?: "sticky"})") }
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
                    releaseForeground()
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
        // Guardián de sesión (anti-OEM): con jornada activa, una alarma cada
        // 15 min revive el servicio si el fabricante mató el proceso.
        runCatching {
            com.dmujeres.traccar.receiver.SessionKeeper.schedule(this)
        }
        // Acelómetro auxiliar (solo telemetría, no decisiones): register con
        // el ciclo de vida del tracking; unregister en stop/onDestroy.
        runCatching {
            com.dmujeres.traccar.util.MotionSensor.register(this)
        }
        // Giroscopio opcional (Fase 12): espejo del acelerómetro, solo el tag
        // rot= de los POSITION_*; sin sensor queda available=false sin error.
        runCatching {
            com.dmujeres.traccar.util.GyroSensor.register(this)
        }
        val recoveringJourney = config.journeyStartAt > 0L
        startedTrackingAt = if (recoveringJourney) config.journeyStartAt else System.currentTimeMillis()
        // El único reloj digno de confianza mientras el servicio vive es el
        // monotónico: se arraiga en elapsedRealtime y parte del elapsed
        // persistido (o del seed legacy acotado si nunca se persistió).
        monoBasePersisted = if (recoveringJourney) {
            JourneyFormatter.seedElapsedOnRecovery(
                config.journeyElapsedMs, config.journeyStartAt, System.currentTimeMillis(),
            )
        } else {
            0L
        }
        monoBaseElapsed = SystemClock.elapsedRealtime()
        // Ejecución lógica nueva (incluso recuperando jornada): cada start es
        // una corrida distinta para agrupar fixes en el servidor.
        runCatching { config.newSessionId() }
        runCatching { config.bootIdRefresh(SystemClock.elapsedRealtime()) }
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
        clockAnchorWallMs = 0L
        clockAnchorMonoMs = 0L
        lastFixSpeedMps = null
        lastSpeedRefTimeMs = 0L
        consecDopplerStuck = 0
        // Regla OR: arranca sin referencia (primer fix siempre acepta); si se
        // recupera jornada se hereda la última posición persistida.
        lastAcceptedLat = if (recoveringJourney) config.journeyLastLat else 0.0
        lastAcceptedLon = if (recoveringJourney) config.journeyLastLon else 0.0
        lastAcceptedTimeMs = 0L
        lastAcceptedBearingDeg = Double.NaN
        // Stop detection: arranca en MODO MARCHA (cadencia normal). El
        // heartbeat de parada entra solo tras 60 s quieto DENTRO de esta
        // sesión; heredar lastFixAt viejo dejaba la captura en heartbeat de
        // 60 s desde el arranque (causa raíz de "no hay ruta": el teléfono
        // parado en casa/work nunca volvía a cadencia de marcha).
        lastMovementMs = System.currentTimeMillis()
        adaptiveMoving = false
        gnssForced = false
        currentMinDistanceM = AdaptiveDistancePolicy.DISTANCE_STATIONARY_M
        currentIntervalSeconds = config.intervalSeconds
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
            config.journeyElapsedMs = 0L
            config.journeyElapsedWallMs = 0L
            // Inmediata por diseño: el usuario acaba de pedir arrancar; no puede
            // quedar sujeta al cooldown global (alertNow la exime).
            Notifications.alertNow(this, getString(R.string.jornada_iniciada), getString(R.string.jornada_iniciada_body))
            enqueuePresence("started")
            // Diagnóstico: snapshot forzado al abrir jornada (cola reciénStarted → pending 0).
            runCatching { SentryLog.breadcrumb("journey", "journey_start", "Jornada $startedTrackingAt iniciada") }
            runCatching { DiagnosticsReporter.report(this, "journey_start", pendingCount = 0) }
        } else {
            Notifications.alert(this, getString(R.string.service_recovery_title), getString(R.string.service_recovery_body))
            // Re-ancla el elapsed persistido/sembrado con el wall actual: a partir
            // de aquí la UI suma desde el servicio vivo, no desde el inicio de jornada.
            persistJourneyElapsed()
            // Reafirma el inicio después de una muerte del proceso; el estado online es idempotente.
            enqueuePresence("started")
        }
        publishState(TrackingState.SERVICE_RECOVERY)
        val manager = MqttManager(
            context = this,
            config = config,
            dao = dao,
            scope = serviceScope,
            onStateChange = { _ -> onMqttStateChanged() },
            // Presencia con NACK terminal vía MQTT: misma cuarentena + aviso
            // que el camino HTTP (un solo criterio en ambos transportes).
            onQuarantined = { dead -> onQuarantined(dead) },
        )
        mqtt = manager
        manager.connect()

        requestLocationUpdates()
        registerGnssCallback()
        registerNetworkCallback()
        registerAirplaneReceiver()
        serviceScope.launch { watchdogLoop() }
        // Microbatch online: mismo owner de DELETE (PositionOutboxDispatcher).
        // El insert solo SEÑALA; el wake flush es el mismo flushOnce con
        // DispatchLock.mutex, así microbatch y replay nunca compiten por Room.
        PositionOutboxDispatcher.mqttReady = { mqtt?.ready == true }
        // Métricas de transporte (ACK/retry/cuarentena) en cualquier camino de
        // flush; reloj de ACK independiente para LinkState y diagnóstico.
        PositionOutboxDispatcher.onFlushOutcome = { outcome ->
            runCatching {
                if (outcome.confirmed > 0) {
                    config.incAckTotal(outcome.confirmed)
                    config.lastAckAt = System.currentTimeMillis()
                }
                if (outcome.retryScheduled > 0) config.incRetryTotal(outcome.retryScheduled)
            }
        }
        PositionOutboxDispatcher.startWakeLoop(
            serviceScope, dao, PositionOutboxDispatcher.HttpTransport, ::dispatchContext,
        )
        refreshStateAndNotify()
    }

    private fun newServiceScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "Error en corutina del servicio", e)
        })

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var airplaneReceiver: BroadcastReceiver? = null

    @Volatile private var lastReportedNetwork: String = ""

    /**
     * Single-flight del drenaje: un solo `drainBacklog` corre a la vez aunque
     * se acumulen eventos (red + MQTT + watchdog). El que llega tarde se omite:
     * el drenaje en curso ya vacía por lotes hasta que un lote confirma 0.
     */
    private val drainInProgress = AtomicBoolean(false)
    @Volatile private var lastDrainAt = 0L

    /** Foto de red sin fricción + causa probable (Fase 1). Persiste para la UI. */
    private fun currentNetSnapshot(): NetSnapshot =
        runCatching { snapshot(this, lastReportedNetwork, config.lastDataEnabled) }.getOrDefault(
            NetSnapshot(
                wifiOn = true, airplane = false,
                hasWifiTransport = false, hasCellTransport = false,
                validated = false, captive = false,
                previousLabel = lastReportedNetwork,
                previousDataEnabled = config.lastDataEnabled,
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
            // lastDataEnabled: referencia para detectar manipulación MANUAL del
            // switch (transición true→false). Solo se avanza con lectura real.
            if (shot.dataEnabled != null) {
                config.lastDataEnabled = shot.dataEnabled
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
                val shot = runCatching {
                    val s = currentNetSnapshot()
                    persistNetState(s, currentNetCause(s))
                    Log.i(TAG, "Red disponible netCause=${currentNetCause(s).value} validated=${s.validated}")
                    s
                }.getOrNull()
                // Señal, no verdad: sin VALIDATED no hay Internet real (captive /
                // WiFi sin salida). No se intenta MQTT ni se drena (fallarían y
                // quemarían backoff); igual se encola presencia para que el
                // servidor vea network=none. La verdad la pone el watchdog con
                // LinkState (validated + mqtt + reachability).
                if (shot != null && !shot.validated) {
                    if (started.get() && !stopping) {
                        serviceScope.launch {
                            try {
                                enqueuePresence()
                            } catch (e: Exception) {
                                Log.w(TAG, "No se pudo enviar presencia al recuperar red", e)
                            }
                        }
                    }
                    return
                }
                mqtt?.let { manager ->
                    if (!manager.connected) {
                        // Vía inmediata de la puerta: vuelta de red validada.
                        serviceScope.launch { manager.connect(immediate = true) }
                    }
                }
                if (config.trackingEnabled) {
                    drainBacklog("red-disponible")
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
    @Volatile private var lastQuarantineAlertAt = 0L
    @Volatile private var lastStorageAlertAt = 0L
    @Volatile private var lastJourneyLat = 0.0
    @Volatile private var lastJourneyLon = 0.0

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
    // Referencia del último fix ACEPTADO (regla OR Traccar) + último movimiento
    // visto (stop detection). Se tocan solo en el hilo del mutex de encolado
    // salvo re-inicio/stop.
    private var lastAcceptedLat = 0.0
    private var lastAcceptedLon = 0.0
    private var lastAcceptedTimeMs = 0L
    private var lastAcceptedBearingDeg = Double.NaN
    private var lastMovementMs = 0L
    // Referencia del último fix aceptado para velocidad implícita (respaldo
    // cuando el Doppler miente en 0) + racha de Doppler atascado para Sentry.
    private var lastSpeedRefLat = 0.0
    private var lastSpeedRefLon = 0.0
    private var lastSpeedRefTimeMs = 0L
    /** elapsedRealtimeNanos del fix de referencia de velocidad (0 = desconocido). */
    private var lastSpeedRefElapsedNanos = 0L
    private var consecDopplerStuck = 0
    @Volatile private var currentMinDistanceM = AdaptiveDistancePolicy.DISTANCE_STATIONARY_M
    @Volatile private var currentIntervalSeconds = 10L
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
        if (gnssFallbackRegistered) return
        if (ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_FINE_LOCATION,
            ) != PackageManager.PERMISSION_GRANTED
        ) return
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

    /**
     * Manejo único de cuarentena en ambos transportes (HTTP y MQTT): contador
     * acumulado + aviso con throttle. Un NACK terminal es raro y merece
     * atención, nunca silencio.
     */
    private fun onQuarantined(@Suppress("UNUSED_PARAMETER") dead: DeadLetter) {
        val total = runCatching { config.incQuarantinedTotal() }.getOrDefault(0L)
        Log.w(TAG, "Outbox: cuarentena #$total (ver dead_letters en diagnóstico)")
        val now = System.currentTimeMillis()
        if (now - lastQuarantineAlertAt > 60_000) {
            lastQuarantineAlertAt = now
            Notifications.alert(
                this@TrackingService,
                getString(R.string.quarantine_warning_title),
                getString(R.string.quarantine_warning_body, total),
            )
        }
    }

    /**
     * Contexto del dispatcher HTTP-first (propietario único de posiciones):
     * base web + API key + métricas de jornada y cuarentena (contador + aviso
     * con throttle: un NACK terminal es raro y merece atención, no silencio).
     */
    private fun dispatchContext() = PositionOutboxDispatcher.DispatchContext(
        webBaseUrl = MqttServerNormalizer.webBase(config.serverUrl, AppConfig.WEB_PORT),
        apiKey = AppConfig.HTTP_API_KEY,
        journeyStartAt = config.journeyStartAt,
        onConfirmedPosition = { item ->
            if (PositionOutboxDispatcher.isCurrentJourneyPosition(config.journeyStartAt, item)) {
                runCatching { config.recordJourneyConfirmed(item.journeyId) }
            }
        },
        onQuarantined = { dead -> onQuarantined(dead) },
    )

    /**
     * Drena el outbox por lotes HTTP tras recuperar conexión: el propietario
     * único (PositionOutboxDispatcher) envía posiciones FIFO con ACK de negocio;
     * MQTT lleva solo presencia en paralelo disjunto. Corre en serviceScope.
     * Nunca borra: el flush solo elimina lo confirmado; lo terminal va a
     * cuarentena; lo no confirmado reintenta con backoff.
     *
     * Single-flight + debounce: si ya hay un drenaje en curso o el último fue
     * hace <10 s, se omite (el en curso vacía hasta que un lote confirma 0 y
     * el watchdog hace trickle cada 30 s de todos modos).
     */
    private fun drainBacklog(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastDrainAt < DRAIN_DEBOUNCE_MS) {
            Log.i(TAG, "Drenaje omitido ($reason): debounce")
            return
        }
        if (!drainInProgress.compareAndSet(false, true)) {
            Log.i(TAG, "Drenaje omitido ($reason): ya hay uno en curso")
            return
        }
        lastDrainAt = now
        // Plan B para presencia: si MQTT no entrega, HTTP también barre los
        // controles vencidos (con MQTT sano los lleva MQTT en vivo).
        val includePresence = mqtt?.ready != true
        val ctx = dispatchContext()
        serviceScope.launch {
            try {
                var batches = 0
                var totalConfirmed = 0
                var totalQuarantined = 0
                var progress = 0
                do {
                    val outcome = PositionOutboxDispatcher.flushOnce(
                        dao, PositionOutboxDispatcher.HttpTransport, ctx,
                        includePresence = includePresence,
                    )
                    // Progreso = confirmadas + cuarentenadas (ambas vacían outbox).
                    progress = outcome.confirmed + outcome.quarantined
                    totalConfirmed += outcome.confirmed
                    totalQuarantined += outcome.quarantined
                    batches++
                    if (!outcome.transportOk) break
                } while (com.dmujeres.traccar.db.BufferDrainPolicy.continueDraining(progress, batches))
                Log.i(TAG, "Drenaje ($reason): $batches lotes, confirmed=$totalConfirmed "
                    + "quarantined=$totalQuarantined")
                if (totalConfirmed > 0) {
                    runCatching { config.lastHttpAt = System.currentTimeMillis() }
                    // Anti-hambre (Fase 10): si quedó backlog tras un lote de
                    // progreso, se AUTO-ENCADENA el siguiente drain ~10 s
                    // después (el debounce lo admite). Si el transporte falla
                    // o el outbox vacía, la cadena termina sola: solo se
                    // encadena cuando CONFIRMÓ algo.
                    val pendingLeft = runCatching {
                        withContext(Dispatchers.IO) { dao.countFlow().first() }
                    }.getOrDefault(0)
                    if (pendingLeft > 0) {
                        serviceScope.launch {
                            delay(DRAIN_CHAIN_DELAY_MS)
                            drainBacklog("continuación")
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Drenaje post-reconexión ($reason)", e)
            } finally {
                drainInProgress.set(false)
            }
        }
    }

    private fun onMqttStateChanged() {
        runCatching { config.lastMqttAt = System.currentTimeMillis() }
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
            when (status) {
                // "Conectado" solo en transición real OFF→ON, persistida: un restart
                // del servicio ya conectado no vuelve a sonar (y si alguna vez se
                // perdió, la recuperación sí avisa).
                MqttStatus.CONNECTED -> if (!Notifications.wasConnectedNotified(this)) {
                    Notifications.setConnectedNotified(this, true)
                    Notifications.alert(this, getString(R.string.connected_title), getString(R.string.connected_body))
                }
                MqttStatus.DISCONNECTED -> Notifications.setConnectedNotified(this, false)
                else -> Unit
            }
            // Al reconectar MQTT también se drena: cubre el caso de enlace débil
            // donde la red nunca se "perdió" del todo (sin evento onAvailable).
            if (status == MqttStatus.CONNECTED && config.trackingEnabled) {
                drainBacklog("mqtt-conectado")
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
     *
     * Stop detection (perfil oculto Traccar, ON): quieto más de 60 s →
     * heartbeat de 60 s (GPS ciclado, jornada viva); al moverse vuelve la
     * cadencia normal por el propio cambio de modo.
     */
    private fun applyAdaptiveMode(hasRecentFix: Boolean): Boolean {
        val current = if (adaptiveMoving) {
            AdaptiveDistancePolicy.Mode.MOVING
        } else {
            AdaptiveDistancePolicy.Mode.STATIONARY
        }
        val next = AdaptiveDistancePolicy.nextMode(current, lastFixSpeedMps, hasRecentFix)
        var changed = false
        if (next != current) {
            adaptiveMoving = next == AdaptiveDistancePolicy.Mode.MOVING
            currentMinDistanceM = AdaptiveDistancePolicy.distanceFor(next)
            currentIntervalSeconds = AdaptiveDistancePolicy.intervalFor(next, config.intervalSeconds)
            changed = true
        }
        // Heartbeat de parada: prima sobre la cadencia del modo.
        if (FixFilter.heartbeatDue(lastMovementMs, System.currentTimeMillis()) &&
            currentIntervalSeconds != FixFilter.STOP_HEARTBEAT_SECONDS
        ) {
            currentIntervalSeconds = FixFilter.STOP_HEARTBEAT_SECONDS
            Log.i(TAG, "Stop detection: quietud prolongada → heartbeat ${FixFilter.STOP_HEARTBEAT_SECONDS}s")
            changed = true
        }
        if (!changed) return false
        if (!started.get() || stopping || capturePausedForBuffer) return false
        Log.i(TAG, "Adaptativo → ${currentMinDistanceM}m cada ${currentIntervalSeconds}s (modo $next, speed=$lastFixSpeedMps)")
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
                    rejectBreakdown = runCatching { config.rejectBreakdown() }
                        .getOrNull()?.takeIf { it.isNotBlank() },
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
     * Transporte del activeNetwork para [LinkState] (sin implicar Internet:
     * la validación la aporta [isNetworkAvailable]).
     */
    private fun currentTransport(): Transport {
        val connectivity = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = runCatching {
            connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        }.getOrNull() ?: return Transport.NONE
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
            else -> Transport.OTHER
        }
    }

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
        val (cause, conf) = runCatching { refine(base, tel, config.lastDataEnabled) }
            .getOrDefault(base to NETCONF_SUSPECTED)
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
        // Dt real ENTRE FIJOS: elapsedRealtime de ambos si existe (monotónico,
        // robusto a saltos NTP); fallback location.time; null = default del filtro.
        val prev = recentFixes.lastOrNull()
        val dtOverride = if (prev != null) {
            FixTime.dtSeconds(prev.elapsedNanos, prev.timeMs, elapsed, time)
        } else {
            null
        }
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
            dtSecondsOverride = dtOverride,
        )
        when (decision) {
            is FixFilter.Decision.Reject -> {
                val impliedHint = when (decision.reason) {
                    "implied_speed" -> {
                        if (prev != null && dtOverride != null && dtOverride > 0.0) {
                            " (implícita %.1f m/s)".format(
                                FixFilter.distanceMeters(prev.lat, prev.lon, latitude, longitude) / dtOverride,
                            )
                        } else ""
                    }
                    "degraded" -> " (accuracy %.0f m)".format(accuracy)
                    else -> ""
                }
                Log.w(TAG, "Fix descartado por filtro ${decision.reason}$impliedHint")
                Log.d(
                    TAG,
                    "POSITION_EVALUATED verdict=reject reason=${decision.reason} " +
                        "motion=${com.dmujeres.traccar.util.MotionSensor.currentState()} " +
                        gyroTag() +
                        "confidence=${confidenceFor(this, elapsed, nowElapsedNanos)}",
                )
            }
            is FixFilter.Decision.Accept -> Log.d(
                TAG,
                "POSITION_EVALUATED verdict=accept reason=none " +
                    "motion=${com.dmujeres.traccar.util.MotionSensor.currentState()} " +
                    gyroTag() +
                    "confidence=${confidenceFor(this, elapsed, nowElapsedNanos)}",
            )
        }
        return decision
    }

    /**
     * Tag opcional del giroscopio (Fase 12): "rot=STEADY " cuando hay sensor;
     * cadena vacía (nada) si no está disponible (p. ej. ZTE Z2450).
     */
    private fun gyroTag(): String =
        if (com.dmujeres.traccar.util.GyroSensor.available) {
            "rot=${com.dmujeres.traccar.util.GyroSensor.currentState()} "
        } else {
            ""
        }

    /** Score operacional del fix (0..100) con los datos GNSS/velocidad vigentes. */
    private fun confidenceFor(
        location: Location,
        elapsedNanos: Long,
        nowElapsedNanos: Long,
    ): Int {
        val gnssHasData = GnssState.hasData()
        return LocationQuality.calculateConfidenceScore(
            horizontalAccuracyM = location.accuracy.takeIf { it > 0f }?.toDouble(),
            speedAccuracyMps = runCatching { location.speedAccuracyMetersPerSecond }.getOrNull()
                ?.takeIf { it.isFinite() && it > 0f },
            gnssUsableRatio = LocationQuality.usableRatio(
                GnssState.satsUsed.takeIf { gnssHasData },
                GnssState.satsTotal.takeIf { gnssHasData },
            ),
            fixAgeSec = FixTime.ageSeconds(
                elapsedNanos, nowElapsedNanos, location.time, System.currentTimeMillis(),
            ),
            impliedSpeedMps = null,
            dopplerValid = false,
        )
    }

    private fun onNewLocation(location: Location) {
        if (!started.get() || stopping) return
        val invalidReason = FixFilter.invalidLocationReason(
            location.latitude, location.longitude, location.accuracy,
        )
        if (invalidReason != null) {
            Log.w(TAG, "POSITION_REJECTED reason=$invalidReason")
            runCatching { config.incRejected(invalidReason) }
            return
        }
        val nowElapsed = SystemClock.elapsedRealtimeNanos()
        // Tiempo del FIX (no de llegada): wall del fix con fallback al wall de
        // llegada solo si el fix no trae time; elapsed del fix para dt/edad.
        val fixWallMs = if (location.time > 0L) location.time else System.currentTimeMillis()
        val fixElapsedNanos = runCatching { location.elapsedRealtimeNanos }.getOrDefault(0L)
            .takeIf { it > 0L } ?: 0L
        Log.d(
            TAG,
            "LOCATION_RECEIVED provider=${providerLabel(location)} " +
                "fixAge=${FixTime.ageSeconds(fixElapsedNanos, nowElapsed, location.time, System.currentTimeMillis())?.toInt() ?: -1}s " +
                "acc=${location.accuracy} elapsed=${fixElapsedNanos > 0L}",
        )
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
                    Log.w(TAG, "POSITION_REJECTED reason=first_fix_bad")
                    runCatching { config.incRejected("first_fix_bad") }
                    return
                }
            } else {
                // Ventana honesta: el rechazo también deja memoria, salvo el primer fix
                // malo que no debe fundar la ventana (arranque sin referencia fiable).
                if (recentFixes.isNotEmpty()) recordRecentFix(location)
                Log.w(TAG, "POSITION_REJECTED reason=${rawDecision.reason}")
                runCatching { config.incRejected(rawDecision.reason) }
                return
            }
        } else {
            emptyWindowRejects = 0
            rawDecision
        }
        val lowQuality = (decision as FixFilter.Decision.Accept).lowQuality
        recordRecentFix(location)
        runCatching { config.lastAcceptedAt = System.currentTimeMillis() }
        // Fix válido aceptado por el filtro (misma vía para FLP pasivo y
        // polling activo): velocidad EFECTIVA para la distancia adaptativa.
        // El Doppler de algunos equipos (ZTE) se atasca en 0 en marcha: si no
        // es creíble se usa la implícita (geometría entre fixes), que en parado
        // también tiende a ~0 y no dispara MOVING por jitter.
        val nowMs = System.currentTimeMillis()
        val doppler = if (location.hasSpeed()) location.speed.coerceAtLeast(0f) else null
        val dopplerAccuracy = runCatching { location.speedAccuracyMetersPerSecond }.getOrNull()
            ?.takeIf { it.isFinite() && it > 0f }
            ?.takeIf { it.isFinite() && it >= 0f }
        val prevSpeedRefTimeMs = lastSpeedRefTimeMs
        val speedRefElapsedNanos = lastSpeedRefElapsedNanos
        val implied = if (prevSpeedRefTimeMs > 0) {
            // Dt real entre fixes (elapsedRealtime preferente): un salto NTP no
            // debe inventar (ni anular) la velocidad implícita.
            val dt = FixTime.dtSeconds(
                speedRefElapsedNanos, prevSpeedRefTimeMs, fixElapsedNanos, fixWallMs,
            )
            if (dt != null) {
                val dist = SpeedEstimator.haversineMeters(
                    lastSpeedRefLat, lastSpeedRefLon,
                    location.latitude, location.longitude,
                )
                (dist / dt).toFloat().takeIf { it.isFinite() }
            } else {
                null
            }
        } else {
            null
        }
        lastFixSpeedMps = SpeedEstimator.choose(doppler, dopplerAccuracy, implied, config.maxImpliedSpeedMps)?.mps
        // Stop detection (perfil oculto, ON): hay movimiento si la efectiva
        // supera 1.5 m/s O la geometría se movió > 8 m desde el último fix
        // (el Doppler atascado en 0 no debe llamar a "quieto" un viaje real).
        val movedSinceLast = if (lastSpeedRefTimeMs > 0) {
            SpeedEstimator.haversineMeters(
                lastSpeedRefLat, lastSpeedRefLon,
                location.latitude, location.longitude,
            )
        } else {
            0.0
        }
        if ((lastFixSpeedMps ?: 0f) >= 1.5f || movedSinceLast >= 8.0) {
            lastMovementMs = nowMs
        }
        lastSpeedRefLat = location.latitude
        lastSpeedRefLon = location.longitude
        lastSpeedRefTimeMs = fixWallMs
        lastSpeedRefElapsedNanos = fixElapsedNanos
        // Caza del Doppler atascado para Sentry/telemetría: Doppler en 0 con
        // implícita de marcha (>= 5 m/s) 3 fixes seguidos.
        if ((doppler ?: 0f) <= SpeedEstimator.DOPPLER_TRUST_MPS
            && (implied ?: 0f) >= SpeedEstimator.STUCK_IMPL_MIN_MPS
        ) {
            consecDopplerStuck += 1
            if (consecDopplerStuck == SpeedEstimator.STUCK_CONSECUTIVE) {
                val count = runCatching { config.incSpeedStuck24h() }.getOrDefault(0)
                SentryLog.breadcrumb(
                    "diag", "speed",
                    "doppler atascado en 0 con implícita ${(implied ?: 0f)} m/s (veces hoy: $count)",
                )
                if (count == 1) {
                    SentryLog.event("speed_doppler_stuck", "Doppler en 0 en marcha (implícita >= 5 m/s)")
                }
            }
        } else {
            consecDopplerStuck = 0
        }
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
                        runCatching { config.incRejected("network_relay") }
                        return@withLock
                    }
                    // Regla OR Traccar (frecuencia/distancia 24 m/ángulo 15°):
                    // solo se encola lo que aporta geometría al trazo; lo demás
                    // se filtra sin tocar contadores, journey ni lastFixAt.
                    // Se juzga con el tiempo del FIX, no el de llegada.
                    val acceptedRef = when {
                        lastAcceptedTimeMs > 0 -> FixFilter.AcceptedRef(
                            lastAcceptedLat, lastAcceptedLon,
                            lastAcceptedTimeMs, lastAcceptedBearingDeg,
                        )
                        config.journeyHasLastLocation -> FixFilter.AcceptedRef(
                            lastJourneyLat, lastJourneyLon, config.lastFixAt, Double.NaN,
                        )
                        else -> null
                    }
                    if (!FixFilter.acceptByRule(
                            ref = acceptedRef,
                            lat = location.latitude,
                            lon = location.longitude,
                            timeMs = fixWallMs,
                            frequencyMs = currentIntervalSeconds * 1000L,
                        )
                    ) {
                        // Regla OR: se difiere (no es un error, el próximo intervalo
                        // lo toma); se cuenta aparte para no inflar "rechazos".
                        runCatching { config.incRejected("rule_deferred") }
                        return@withLock
                    }
                    val currentCount = withContext(Dispatchers.IO) { dao.count() }
                    // STOP_CAPTURE solo pausa más allá de la retención dura
                    // (100 000 / 7 d): con el default antiguo (5 000 ≈ 14 h) esto
                    // detenía el GPS en outages de 1 día y la ruta quedaba con
                    // hueco para siempre. La retención efectiva sanea ese valor.
                    val retentionMax = OutboxRetentionPolicy.effectiveMax(config.bufferMax)
                    if (config.bufferPolicy == AppConfig.POLICY_STOP_CAPTURE
                        && currentCount >= retentionMax
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
                    // Velocidad EFECTIVA (nunca Doppler crudo): Doppler creíble,
                    // si no implícita acotada (un salto de reloj no inventa
                    // 200 km/h), si no 0 + source unknown ("no se sabe", NO
                    // "detenido"). Ver SpeedEstimator + caso Joseph (0 km/h
                    // en marcha por Doppler atascado).
                    val effectiveMps = SpeedEstimator.effectiveMps(
                        doppler, implied, config.maxImpliedSpeedMps,
                    )
                    val speedSource = SpeedEstimator.speedSource(
                        doppler, implied, config.maxImpliedSpeedMps,
                    )
                    val speedKmh = ((effectiveMps ?: 0f).toDouble() * 3.6).coerceAtLeast(0.0)
                    val gnssHasData = GnssState.hasData()
                    val confidence = LocationQuality.calculateConfidenceScore(
                        horizontalAccuracyM = location.accuracy.takeIf { it > 0f }?.toDouble(),
                        speedAccuracyMps = dopplerAccuracy,
                        gnssUsableRatio = LocationQuality.usableRatio(
                            GnssState.satsUsed.takeIf { gnssHasData },
                            GnssState.satsTotal.takeIf { gnssHasData },
                        ),
                        fixAgeSec = FixTime.ageSeconds(
                            fixElapsedNanos,
                            SystemClock.elapsedRealtimeNanos(),
                            location.time,
                            System.currentTimeMillis(),
                        ),
                        impliedSpeedMps = implied,
                        dopplerValid = speedSource == SpeedEstimator.SPEED_SOURCE_DOPPLER,
                    )
                    val qualityClass = LocationQuality.run {
                        classify(
                            horizontalAccuracyM = location.accuracy.takeIf { it > 0f }?.toDouble(),
                            fixAgeSec = fixAgeSeconds(location).toDouble(),
                            rejected = false,
                            invalidCoords = false,
                        )
                    }
                    val payload = Envelope.buildPosition(
                        messageId = messageId,
                        deviceId = deviceId,
                        sequence = sequence,
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracy = location.accuracy.toDouble(),
                        speed = speedKmh,
                        bearing = if (location.hasBearing()) location.bearing.toDouble() else 0.0,
                        altitude = if (location.hasAltitude()) location.altitude else 0.0,
                        observedAt = observedAt,
                        pending = currentCount,
                        battery = battery,
                        network = network,
                        lowQuality = lowQuality,
                        provider = providerLabel(location),
                        fixAgeSec = fixAgeSeconds(location),
                        speedSource = speedSource,
                        sessionId = config.sessionId.takeIf { it.isNotBlank() },
                        bootId = config.bootIdRefresh(SystemClock.elapsedRealtime()).takeIf { it.isNotBlank() },
                        speedAccuracyMps = dopplerAccuracy,
                        confidenceScore = confidence,
                        gnssUsed = GnssState.satsUsed.takeIf { gnssHasData },
                        gnssTotal = GnssState.satsTotal.takeIf { gnssHasData },
                        qualityClass = qualityClass.name,
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
                    // Traza estructurada por posición (§15): con esto + los
                    // POSITION_REJECTED se reconstruye CAPTURE→PERSIST→SEND→ACK.
                    Log.i(TAG, "POSITION_ACCEPTED+STORED provider=${providerLabel(location)} "
                        + "acc=${location.accuracy} speed=${"%.1f".format(speedKmh)}kmh($speedSource) "
                        + "motion=${com.dmujeres.traccar.util.MotionSensor.currentState()} "
                        + gyroTag()
                        + "quality=$qualityClass "
                        + "seq=$sequence sessionId=${config.sessionId} journey=${config.journeyStartAt} "
                        + "observed=$observedAt received=${Envelope.nowIso()} confidence=$confidence")
                    // Fix aceptado: el acelerómetro vuelve a medir si estaba
                    // en pausa por STATIONARY (re-register on fix).
                    runCatching {
                        com.dmujeres.traccar.util.MotionSensor.reRegisterOnFix(this@TrackingService)
                    }
                    // Señala microbatch online (single-owner: el wake usa el
                    // mismo flushOnce/DispatchLock que el replay; nada compite).
                    PositionOutboxDispatcher.requestFlush()
                    if (config.journeyHasLastLocation) {
                        config.journeyDistanceM += distanceMeters(
                            lastJourneyLat,
                            lastJourneyLon,
                            location.latitude,
                            location.longitude,
                        )
                    }
                    config.journeyPoints = config.journeyPoints + 1
                    // El elapsed monotónico viaja junto a los contadores de jornada.
                    persistJourneyElapsed()
                    // Referencia de la regla OR: rumbo del tramo que llegó aquí
                    // (solo si el tramo mide; si no, se conserva el anterior).
                    if (lastAcceptedTimeMs > 0) {
                        val inboundLeg = FixFilter.distanceMeters(
                            lastAcceptedLat, lastAcceptedLon,
                            location.latitude, location.longitude,
                        )
                        if (inboundLeg >= 1.0) {
                            lastAcceptedBearingDeg = FixFilter.bearingDeg(
                                lastAcceptedLat, lastAcceptedLon,
                                location.latitude, location.longitude,
                            )
                        }
                    } else if (config.journeyHasLastLocation) {
                        val inboundLeg = FixFilter.distanceMeters(
                            lastJourneyLat, lastJourneyLon,
                            location.latitude, location.longitude,
                        )
                        if (inboundLeg >= 1.0) {
                            lastAcceptedBearingDeg = FixFilter.bearingDeg(
                                lastJourneyLat, lastJourneyLon,
                                location.latitude, location.longitude,
                            )
                        }
                    }
                    lastAcceptedLat = location.latitude
                    lastAcceptedLon = location.longitude
                    lastAcceptedTimeMs = fixWallMs
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
                            Log.w(TAG, "Retención del outbox (100k/7d): se purgaron $discarded posiciones antiguas ya fuera de ventana")
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

    /**
     * Anclaje rodante (wall + elapsedRealtime) para [maybeDetectClockStep].
     * Se arraiga por primera vez en el primer tick del watchdog tras cada
     * startTracking y se re-ancla en cada comparación: un paso sostenido
     * cuenta UNA vez, no cada 30 s.
     */
    @Volatile private var clockAnchorWallMs = 0L
    @Volatile private var clockAnchorMonoMs = 0L

    /**
     * Paso de reloj (NTP/zona/manual) durante la jornada: en la misma
     * ventana, el delta del wall clock se desvía del monotónico más de
     * [DiagnosticsCollector.CLOCK_STEP_THRESHOLD_MS]. Al detectar:
     * clockSteps24h++ (bucket diario, ver AppConfig) + breadcrumb + log.
     */
    private fun maybeDetectClockStep() {
        val nowWall = System.currentTimeMillis()
        val nowMono = SystemClock.elapsedRealtime()
        if (clockAnchorMonoMs == 0L) {
            clockAnchorWallMs = nowWall
            clockAnchorMonoMs = nowMono
            return
        }
        val wallDiff = nowWall - clockAnchorWallMs
        val monoDiff = nowMono - clockAnchorMonoMs
        clockAnchorWallMs = nowWall
        clockAnchorMonoMs = nowMono
        if (DiagnosticsCollector.clockStepDelta(wallDiff, monoDiff)) {
            runCatching { config.incClockStep24h() }
            Log.w(TAG, "Paso de reloj: wall=$wallDiff ms monotónico=$monoDiff ms")
            runCatching {
                SentryLog.breadcrumb("diag", "clock_step", "Salto de reloj ${wallDiff - monoDiff} ms")
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
            // Anti-pasos-de-reloj: compara wall vs monotónico en la ventana del
            // tick (30 s) y apunta el anclaje para el siguiente.
            runCatching { maybeDetectClockStep() }
            // Fase 6: verificación honesta del intento del guardián. Servicio
            // vivo + intento sin confirmar → "ok" UNA vez (marker persistido
            // KEY_RECOVERY_CONFIRM_AT evita repetir en cada tick).
            runCatching {
                if (com.dmujeres.traccar.util.RecoveryJournal.shouldConfirmOnServiceAlive(
                        trackingEnabled = config.trackingEnabled,
                        serviceRunning = started.get(),
                        lastRecoveryAtMs = config.lastRecoveryAt,
                        confirmAtMs = config.recoveryConfirmAt,
                    )
                ) {
                    config.lastRecoveryResult =
                        com.dmujeres.traccar.util.RecoveryJournal.RESULT_OK
                    config.recoveryConfirmAt = System.currentTimeMillis()
                    config.attemptsSinceLastSuccess = 0
                    Log.i(TAG, "RECOVERY_SUCCESS (servicio vivo tras intento del guardián)")
                }
            }
            // Acelerómetro: STATIONARY estable >= 5 min → desregistrar
            // (batería); un fix aceptado lo re-registra (re-register on fix).
            runCatching { com.dmujeres.traccar.util.MotionSensor.maybePauseIfStationary() }
            // Heartbeat ≥30 s: refresca el par (elapsed monotónico, ancla wall)
            // aunque no lleguen fixes (GPS muerto/nocturno) para que la UI y un
            // hipotético proc nuevo partan de un estado fresco e inmune a NTP.
            if (started.get() && !stopping && config.journeyStartAt > 0L) {
                persistJourneyElapsed()
            }
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
                // Escalera anti-hambruna (caso Joseph: 28 callbacks en 7 h con cielo
                // abierto): si NI SIQUIERA hay callbacks crudos del FLP en 10 min,
                // el cliente fused puede estar atascado/muerto (Doze/OEM/Play). Se
                // recrea el cliente y se re-solicita, como máximo 1 vez cada
                // 15 min (sin loops: time-gated, con log y breadcrumb).
                val lastCallback = config.lastLocationCallbackAt
                if (lastCallback > 0L && now - lastCallback > 10 * 60_000L &&
                    now - lastEngineReinitAt > 15 * 60_000L
                ) {
                    lastEngineReinitAt = now
                    Log.w(TAG, "FLP sin callbacks en >10 min: re-inicializando motor de ubicación")
                    runCatching {
                        SentryLog.breadcrumb("gps", "engine_reinit", "Sin callbacks FLP >10 min, recreo cliente")
                    }
                    runCatching { fused?.removeLocationUpdates(locationCallback) }
                    runCatching {
                        fused = LocationServices.getFusedLocationProviderClient(this)
                    }
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
            // Estado del enlace en 4 dominios separados (ver LinkState): transporte,
            // Internet validada, sesión MQTT y reachability del servidor. Ninguno
            // equivale a otro: WiFi sin validar no es Internet; TCP conectado sin
            // subscribe no entrega; ACKs de presence no prueban que las posiciones
            // salgan (por eso la pata oldestPendingAt).
            val mgr = mqtt
            val link = LinkState.current(
                transport = currentTransport(),
                validatedInternet = isNetworkAvailable(),
                mqttReady = mgr?.ready == true,
                mqttConnected = mgr?.connected == true,
                pendingCount = pendingCount,
                lastAckAt = config.lastAckAt,
                oldestPendingAt = oldestPendingAt,
                now = now,
            )
            val mqttUnavailable = link.mqtt != MqttLink.READY
            val networkAvailable = link.validatedInternet
            val connectionUnavailable = link.isUnavailable()

            if (link.shouldAttemptMqtt()) {
                // Reintenta conectar MQTT periódicamente aunque no haya evento de
                // red. La cadencia la impone la puerta (ReconnectGate dentro de
                // connect()): este tick es solo un evento más, no un loop propio.
                try {
                    mgr?.connect()
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo reconectar MQTT", e)
                }
            }

            val retentionMax = OutboxRetentionPolicy.effectiveMax(config.bufferMax)
            if (config.bufferPolicy == AppConfig.POLICY_STOP_CAPTURE && pendingCount >= retentionMax) {
                pauseCaptureForBuffer()
            } else if (capturePausedForBuffer && pendingCount < (retentionMax * 0.8).toInt()) {
                resumeCaptureAfterBuffer()
            }

            if (connectionUnavailable) {
                if (connectionUnavailableSince == 0L) connectionUnavailableSince = now
            } else {
                connectionUnavailableSince = 0L
            }
            maybeNotifyOperationalAlerts(now, connectionUnavailable, batteryNow)

            // HTTP es el transporte PRINCIPAL de posiciones (lotes FIFO con ACK
            // de negocio); MQTT lleva solo presencia. Goteo de 1 lote/30 s con
            // red validada + backlog: mantiene el outbox al día sin esperar
            // eventos. Las presencias van por HTTP solo si MQTT no entrega.
            if (pendingCount > 0 && networkAvailable) {
                try {
                    val outcome = PositionOutboxDispatcher.flushOnce(
                        dao,
                        PositionOutboxDispatcher.HttpTransport,
                        dispatchContext(),
                        nowMs = now,
                        includePresence = mqttUnavailable,
                    )
                    if (outcome.confirmed > 0 || outcome.quarantined > 0) {
                        Log.i(TAG, "Trickle HTTP confirmó ${outcome.confirmed} "
                            + "cuarentena ${outcome.quarantined}")
                    }
                    if (outcome.confirmed > 0) {
                        runCatching { config.lastHttpAt = System.currentTimeMillis() }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Trickle HTTP falló", e)
                }
            }

            // Pantalla de aviso SOLO por pérdida de conexión (único caso con
            // acción real del usuario: recuperar señal). Sin GPS pero con red,
            // el heartbeat sigue y el servidor ya marca STALE: despertar por
            // GPS solo generaba spam estacionado (parqueaderos, interiores).
            if (connectionUnavailable && !wakeAlertActive) {
                // Alerta de pantalla una sola vez por episodio, no cada 20 minutos.
                wakeAlertActive = true
                Notifications.wakeScreen(
                    this,
                    getString(R.string.wake_title),
                    getString(R.string.wake_mqtt_body),
                )
            } else if (!connectionUnavailable) {
                wakeAlertActive = false
            }
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                setState(TrackingState.PERMISSION_MISSING)
            } else if (config.bufferPolicy == AppConfig.POLICY_STOP_CAPTURE && pendingCount >= OutboxRetentionPolicy.effectiveMax(config.bufferMax)) {
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

    /**
     * Último estado POR EL QUE SE ALERTÓ (distinto de currentState): una alerta de
     * warning/ok solo sale si el estado alertable CAMBIÓ respecto a la última alerta,
     * no en cada ciclo de refresh ni en flaps A→recovery→A (refresca por fix + watchdog
     * 30 s y el flap re-alarmaba ok_title/warning_title cada pocos segundos).
     */
    @Volatile private var lastAlertedState: TrackingState? = null

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
        // GPS y pendientes-ACK excluidos del canal de alertas: cambian con
        // frecuencia (interiores, estacionamiento, lotes a la espera) y la
        // persistente ya los muestra. Sonar aquí solo genera spam.
        val silentState = state == TrackingState.GPS_DISABLED
            || state == TrackingState.PENDING_ACK_TIMEOUT
        if (state != TrackingState.TRACKING_ACTIVE && state != TrackingState.TRACKING_DISABLED_BY_USER
            && state != TrackingState.SERVICE_RECOVERY && !delayedAlertState && !silentState) {
            if (state != lastAlertedState) {
                lastAlertedState = state
                Notifications.alert(this, getString(R.string.warning_title), state.label)
            }
        }
        // "Todo en orden" sin notificación: el estado se ve en la persistente y
        // en el banner; sonar cada regreso a activo era spam silencioso-útil.
        if (state == TrackingState.TRACKING_ACTIVE && previous != TrackingState.TRACKING_ACTIVE) {
            lastAlertedState = TrackingState.TRACKING_ACTIVE
        }
        refreshStateAndNotify()
    }

    private fun refreshStateAndNotify() {
        serviceScope.launch {
            val pending = withContext(Dispatchers.IO) {
                runCatching { dao.countFlow().first() }.getOrDefault(0)
            }
            // Piggyback diagnóstico: con jornada activa, reporte periódico cada
            // 60 min (se dispara desde la ruta por-fix y desde los cambios de
            // estado, que también llaman aquí). El throttle real lo aplica el
            // reporter; aquí solo el umbral horario.
            runCatching {
                if (started.get() && !stopping &&
                    System.currentTimeMillis() - config.diagnosticsLastReportAt > DiagnosticsReporter.PERIODIC_MS
                ) {
                    DiagnosticsReporter.report(this@TrackingService, "periodic", pending)
                }
            }
            val battery = batteryLevel()
            val state = TrackingState.fromName(config.trackingState)

            val journeyStart = config.journeyStartAt
            val journeyLine = if (config.trackingEnabled && journeyStart > 0) {
                // Reloj del servicio (monotónico), no wall bruto: un paso NTP no
                // colapsa ni infla la duración de la notificación.
                val (hours, minutes) = JourneyFormatter.durationParts(elapsedNowMs())
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
            // Push del widget de inicio/fin de jornada: mismo ciclo que la notificación,
            // así el reloj del widget nunca se queda rezagado respecto al panel.
            runCatching { JourneyWidget.updateAll(this@TrackingService) }
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

    /** Intervalo efectivo: base quieto, 5 s en marcha (re-registrado al cambiar de modo). */
    private fun effectiveIntervalSeconds(): Long {
        return currentIntervalSeconds.coerceAtLeast(1L)
    }

    /**
     * Duración de jornada según el reloj monotónico del servicio: base
     * persistida (o sembrada en recuperación) + tiempo desde que se arraigó.
     * Con centinela sin arraigar cae al valor persistido + gap desde su ancla,
     * nunca al wall bruto desde el inicio (inmune a pasos NTP en ambos sentidos).
     */
    private fun elapsedNowMs(): Long {
        val base = monoBaseElapsed
        return if (base == 0L) {
            JourneyFormatter.displayElapsedMs(
                persistedElapsedMs = config.journeyElapsedMs,
                persistedWallMs = config.journeyElapsedWallMs,
                journeyStartWallMs = config.journeyStartAt,
                nowWallMs = System.currentTimeMillis(),
            )
        } else {
            (monoBasePersisted + (SystemClock.elapsedRealtime() - base)).coerceAtLeast(0L)
        }
    }

    /** Fija el par (elapsed monotónico, ancla wall) que lee la UI y otras sesiones. */
    private fun persistJourneyElapsed() {
        config.journeyElapsedMs = elapsedNowMs()
        config.journeyElapsedWallMs = System.currentTimeMillis()
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
        runCatching { com.dmujeres.traccar.util.MotionSensor.unregister() }
        runCatching { com.dmujeres.traccar.util.GyroSensor.unregister() }
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
        // Al finalizar NO se borra la cola: se drena por HTTP (propietario
        // único de posiciones) hasta vaciarla o hasta el deadline (ver
        // StopDrainPolicy). Lo que quede sigue en Room y lo drena
        // TrackingRecoveryWorker; nunca se descarta.
        val ctx = dispatchContext()
        val deadline = System.currentTimeMillis() + StopDrainPolicy.TIMEOUT_MS
        while (!StopDrainPolicy.timedOut(System.currentTimeMillis(), deadline)) {
            val pending = withContext(Dispatchers.IO) { dao.count() }
            if (pending == 0) return
            val outcome = PositionOutboxDispatcher.flushOnce(
                dao, PositionOutboxDispatcher.HttpTransport, ctx, includePresence = true,
            )
            delay(StopDrainPolicy.retryDelayAfter(outcome.confirmed + outcome.quarantined))
        }
    }

    private fun finishStopping(closingScope: CoroutineScope, closingMqtt: MqttManager?) {
        if (!stopping) return
        closingMqtt?.disconnect()
        if (mqtt === closingMqtt) mqtt = null
        closingScope.cancel()
        val finalText = finishedJourneyText()
        releaseForeground()
        Notifications.finished(this, finalText)
        stopJob = null

        if (pendingStart && config.trackingEnabled) {
            pendingStart = false
            stopping = false
            serviceScope = newServiceScope()
            started.set(false)
            // Reclamo vía el mismo camino idempotente (claimForeground ya no está
            // reclamado tras releaseForeground). Su fallo NO puede dejar la nueva
            // promesa sin pagar: si reclama mal, se apaga sin reiniciar.
            val claimError = claimForeground()
            if (claimError == null) {
                startTracking()
            } else {
                Log.w(TAG, "No se pudo reafirmar el foreground al retomar jornada", claimError)
                config.trackingEnabled = false
                config.lastStartError = "No se pudo reiniciar la jornada"
                publishState(TrackingState.TRACKING_DISABLED_BY_USER)
                stopSelf()
            }
        } else {
            pendingStart = false
            stopping = false
            // Diagnóstico: cierre real de jornada (tras drenar), reason forzada.
            // pending = -1: el drain ya corrió y no se toca la DB desde el hilo main.
            runCatching { SentryLog.breadcrumb("journey", "journey_stop", "Jornada finalizada (servicio detenido)") }
            runCatching { DiagnosticsReporter.report(this, "journey_stop") }
            stopSelf()
        }
    }

    /** Texto de la notificación final: jornada finalizada con duración y resumen. */
    private fun finishedJourneyText(): String {
        val start = config.journeyStartAt
        // El resumen usa el elapsed monotónico capturado ANTES de limpiar el
        // acumulador; así un salto NTP durante la jornada no deforma el cierre.
        val finalElapsedMs = elapsedNowMs()
        val distanceM = config.journeyDistanceM
        val points = config.journeyPoints
        val confirmedPoints = config.journeyConfirmedPoints
        config.journeyStartAt = 0
        // La jornada cerró: el guardián de sesión deja de revivir el proceso.
        runCatching {
            com.dmujeres.traccar.receiver.SessionKeeper.cancel(this)
        }
        config.journeyElapsedMs = 0L
        config.journeyElapsedWallMs = 0L
        config.journeyDistanceM = 0.0
        config.journeyPoints = 0
        config.journeyConfirmedPoints = 0
        config.journeyLastLat = 0.0
        config.journeyLastLon = 0.0
        config.journeyHasLastLocation = false
        lastAcceptedTimeMs = 0L
        lastAcceptedBearingDeg = Double.NaN
        if (start <= 0) {
            config.journeyStopRequested = false
            return getString(R.string.notif_journey_finished)
        }
        val (hours, minutes) = JourneyFormatter.durationParts(finalElapsedMs)
        val duration = getString(R.string.journey_duration, hours, minutes)
        val km = JourneyFormatter.formatKm(distanceM)
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
        runCatching { com.dmujeres.traccar.util.MotionSensor.unregister() }
        runCatching { com.dmujeres.traccar.util.GyroSensor.unregister() }
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

/**
 * Decisión pura (JVM-testable) de si onStartCommand debe reclamar el foreground
 * (ServiceCompat.startForeground) como PRIMER acto, antes de cualquier init
 * pesado — anti ForegroundServiceDidNotStartInTimeException:
 * - START (ACTION_START o null-intent sticky): siempre. Hay una promesa de
 *   startForegroundService viva que pagar, incluso si el servicio está
 *   stopping: el drenaje de cierre puede exceder con creces el timeout.
 * - STOP sobre servicio vivo/arrancado: sí, re-afirmar barato (companion.stop
 *   cae a startForegroundService cuando startService es rechazado en background
 *   y eso también deja promesa).
 * - STOP sobre servicio NO arrancado: nunca. startForeground sin promesa en
 *   Android 14+ puede saltar en ForegroundServiceStartNotAllowedException; el
 *   camino solo publica estado y stopSelf(), que cancela la promesa pendiente.
 */
object ForegroundClaimPolicy {
    fun shouldClaimForeground(isStopAction: Boolean, serviceAlive: Boolean): Boolean =
        !isStopAction || serviceAlive
}
