package com.dmujeres.traccar.tracking

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.dmujeres.traccar.DmujeresApp
import com.dmujeres.traccar.R
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.core.MobileProtocol
import com.dmujeres.traccar.core.MqttServerNormalizer
import com.dmujeres.traccar.core.MqttStatus
import com.dmujeres.traccar.core.SpeedEstimator
import com.dmujeres.traccar.core.TrackingState
import com.dmujeres.traccar.data.DeadLetter
import com.dmujeres.traccar.data.OutboxRetentionPolicy
import com.dmujeres.traccar.data.PendingPosition
import com.dmujeres.traccar.diagnostics.DiagnosticsReporter
import com.dmujeres.traccar.health.HealthStateProvider
import com.dmujeres.traccar.health.TrackingHealthMonitor
import com.dmujeres.traccar.location.FixFilter
import com.dmujeres.traccar.location.FixTime
import com.dmujeres.traccar.location.GnssState
import com.dmujeres.traccar.location.LocationEngine
import com.dmujeres.traccar.location.MovementRescueController
import com.dmujeres.traccar.location.MovementRescuePolicy
import com.dmujeres.traccar.location.LocationQuality
import com.dmujeres.traccar.location.classify
import com.dmujeres.traccar.outbox.OutboxCoordinator
import com.dmujeres.traccar.outbox.PositionOutboxDispatcher
import com.dmujeres.traccar.outbox.RoomControlQueueStore
import com.dmujeres.traccar.platform.Notifications
import com.dmujeres.traccar.platform.SentryLog
import com.dmujeres.traccar.readiness.ContinuityTracker
import com.dmujeres.traccar.recovery.TrackingRecoveryWorker
import com.dmujeres.traccar.sensors.SensorCoordinator
import com.dmujeres.traccar.transport.Envelope
import com.dmujeres.traccar.transport.MqttManager
import com.dmujeres.traccar.ui.widget.JourneyWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.collections.ArrayDeque
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Servicio en primer plano (FGS location). Es el ORQUESTADOR del plano de
 * tracking: ciclo de vida Android + pipeline de captura + cableado de los
 * controladores de dominio (sesión, motor de ubicación, presencia, watchdog,
 * outbox, salud, notificación, cierre).
 *
 * Entrega: posiciones por HTTP en lotes FIFO con ACK de negocio (primario) y
 * presencia por MQTT QoS1; todo con cola offline durable en Room.
 *
 * El detalle de cada plano vive en sus clases (ver docs/ANDROID_ARCHITECTURE.md);
 * aquí solo se secuencia.
 */
class TrackingService : Service() {

    companion object {
        const val ACTION_START = "com.dmujeres.traccar.START"
        const val ACTION_STOP = "com.dmujeres.traccar.STOP"
        const val ACTION_REFRESH = "com.dmujeres.traccar.REFRESH"
        private const val TAG = "TrackingService"

        /** Ventana anti re-entrega: mismo punto de red <5 min y <2 m no se encola. */
        private const val NETWORK_RELAY_SKIP_MS = 5 * 60_000L

        // El debounce y el single-flight de drenaje viven en
        // [com.dmujeres.traccar.outbox.OutboxCoordinator].

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context): Boolean =
            runCatching {
                val intent = Intent(context, TrackingService::class.java).setAction(ACTION_START)
                ContextCompat.startForegroundService(context, intent)
            }.isSuccess

        /**
         * UX: refresco manual. NUNCA arranca ni detiene el servicio por sí solo:
         * solo pide un nudge al servicio YA en marcha (GPS/MQTT/presencia/outbox).
         */
        fun refresh(context: Context) {
            runCatching {
                val intent = Intent(context, TrackingService::class.java).setAction(ACTION_REFRESH)
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TrackingService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
                .onFailure { ContextCompat.startForegroundService(context, intent) }
        }
    }

    private var serviceScope = newServiceScope()
    private val stopControllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var config: AppConfig
    private lateinit var dao: com.dmujeres.traccar.data.PositionDao
    private lateinit var notifier: TrackingNotificationController
    private lateinit var engine: LocationEngine
    private lateinit var health: TrackingHealthMonitor
    private lateinit var movementRescue: MovementRescueController
    private lateinit var healthStateProvider: HealthStateProvider
    private lateinit var outbox: OutboxCoordinator
    private lateinit var session: TrackingSessionController
    private lateinit var sampler: DeviceTelemetrySampler
    private lateinit var presence: PresenceController
    private lateinit var connectivity: ConnectivityObserver
    private lateinit var watchdog: TrackingWatchdog
    private lateinit var stopCoordinator: JourneyStopCoordinator
    private lateinit var summaryPresenter: JourneySummaryPresenter
    private val sensors by lazy { SensorCoordinator(this) }
    private var mqtt: MqttManager? = null
    private val started = AtomicBoolean(false)
    /** Heavy init (DB/recoveries/FLP) hecho, post-reclamo. Ver [initCore]. */
    @Volatile private var coreReady = false
    private val enqueueMutex = Mutex()
    @Volatile private var stopping = false
    @Volatile private var pendingStart = false
    private var stopJob: Job? = null
    @Volatile private var capturePausedForBuffer = false

    /** R8: inicio de ESTA corrida de captura (el anti-livelock no debe usar el
     * inicio original de la jornada, que tras una recuperación es viejo). */
    @Volatile
    private var runStartedAtMs = 0L

    /** R9: último fix que renovó la ventana de CPU en movimiento (rate-limit). */
    @Volatile
    private var lastMovingWindowAtMs = 0L

    /** R8 (angle): bearing del último segmento y rate-limit de peticiones. */
    @Volatile
    private var lastSegmentBearingDeg = Double.NaN
    @Volatile
    private var lastTurnSampleAtMs = 0L
    /**
     * Anti-livelock R1: rechazos consecutivos con ventana vacía (cero capturas).
     * Tras 10 (o 5 min desde startTracking) se funda la ventana con
     * Accept(lowQuality=true). Se resetea al aceptar.
     */
    @Volatile internal var emptyWindowRejects = 0

    /**
     * Ordena un lote del FLP por tiempo de fix ASC para procesar cronológico:
     * si TODOS traen elapsedRealtimeNanos > 0 se ordena por el monotónico
     * (robusto a saltos NTP); si no, si todos traen time > 0 se ordena por wall
     * del fix; si no, llega el orden original del proveedor. Ver FixTime.
     * (Movido a [LocationEngine]; se conserva el KDoc por trazabilidad.)
     */

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
        // FASE R3: muestreo de telemetría (red/batería/permisos) + observadores
        // de conectividad, extraídos del servicio (misma semántica).
        sampler = DeviceTelemetrySampler(
            context = this,
            config = config,
            pendingCount = { withContext(Dispatchers.IO) { dao.count() } },
            pollInFlight = { engine.pollInFlight },
        )
        // FASE 3: componentes extraídos (misma semántica que el código original).
        notifier = TrackingNotificationController(
            service = this,
            config = config,
            scopeProvider = { serviceScope },
            pendingCount = { dao.countFlow().first() },
            journeyElapsedMs = { session.elapsedNowMs() },
            batteryLevel = { sampler.batteryLevel() },
            isStarted = { started.get() },
            isStopping = { stopping },
            onStateTransition = { state ->
                // FASE 7: evidencia de transición (STATE_CHANGE) + subida async.
                serviceScope.launch {
                    runCatching {
                        health.persistTransition(
                            state,
                            motion = sensors.motionStateName(),
                            network = config.netLabel,
                            healthState = healthStateProvider.now(),
                        )
                    }
                    runCatching { uploadHealthPending() }
                }
            },
            onRefreshWidget = { JourneyWidget.updateAll(this@TrackingService) },
        )
        engine = LocationEngine(
            context = this,
            config = config,
            scopeProvider = { serviceScope },
            lastMovementAtMs = { lastMovementMs },
            mqttReady = { mqtt?.ready == true },
            mqttDisconnected = { MqttStatus.status == MqttStatus.DISCONNECTED },
            callbacks = object : LocationEngine.Callbacks {
                override fun onFix(location: Location) = onNewLocation(location)
                override fun onState(state: TrackingState) = notifier.setState(state)
                override fun onSoftRecovery() {
                    notifier.publishState(TrackingState.SERVICE_RECOVERY)
                    notifier.refreshStateAndNotify()
                }
                override fun onNoGnssAlert() {
                    Notifications.alert(
                        this@TrackingService,
                        getString(R.string.sin_gps_title),
                        getString(R.string.sin_gps_body),
                    )
                }
                override fun onAdaptiveModeChanged(moving: Boolean) {
                    runCatching {
                        com.dmujeres.traccar.recovery.SessionKeeper.schedule(
                            this@TrackingService,
                            ForegroundGuardPolicy.keeperPeriodMs(moving, journeyActive = true),
                        )
                    }
                }
                override fun shouldCapture(): Boolean =
                    started.get() && !stopping && !capturePausedForBuffer
                override fun isStarted(): Boolean = started.get()
            },
        )
        health = TrackingHealthMonitor(
            context = this,
            config = config,
            fgsRunning = { isRunning },
            outboxCount = { dao.count() },
        )
        // R5: rescate de localización (GPS degradado/lost + movimiento probable
        // → reacquisición agresiva TEMPORAL con burst acotado). Nunca produce
        // coordenadas: solo acciones sobre el motor y evidencia en health.
        movementRescue = MovementRescueController(
            scopeProvider = { serviceScope },
            deps = MovementRescueController.Deps(
                journeyActive = { config.journeyStartAt > 0L },
                lastFixAtMs = { config.lastFixAt.takeIf { it > 0L } },
                sensorMoving = { com.dmujeres.traccar.sensors.MotionSensor.isMoving() == true },
                displacementM = {
                    runCatching {
                        val anchor = engine.stationaryAnchorOrNull()
                        val last = engine.lastAcceptedLocation()
                        if (anchor != null && last != null) anchor.distanceTo(last) else null
                    }.getOrNull()
                },
                onRescueActions = {
                    runCatching { engine.nudgeRefresh() }
                    runCatching { engine.requestOneShotFix() }
                },
                onEvent = { eventType, reason, durationMs ->
                    runCatching { SentryLog.breadcrumb("gps", eventType, "$reason${durationMs?.let { " (${it}ms)" } ?: ""}") }
                    // persistCritical es suspend: se lanza en el scope del servicio
                    // (observabilidad asíncrona, no bloquea el rescue).
                    serviceScope.launch {
                        runCatching {
                            health.persistCritical(
                                eventType,
                                motion = com.dmujeres.traccar.sensors.MotionSensor.currentState().name,
                                network = config.netLabel,
                                healthState = healthStateProvider.now(),
                            )
                        }
                    }
                },
            ),
        )
        session = TrackingSessionController(config)
        healthStateProvider = HealthStateProvider(
            context = this,
            config = config,
            health = health,
            serviceRunning = { started.get() },
            networkAvailable = { sampler.isNetworkAvailable() },
        )
        // Fase 1: evidencia post-hoc de congelado OEM (API 33+). Se reporta
        // UNA vez por arranque de proceso si la ROM nos congeló antes, para que
        // el diagnóstico y la puesta a punto digan la verdad (sin alarmar).
        runCatching {
            val freezes = com.dmujeres.traccar.recovery.FreezeEvidence.freezeCount(this)
            if (freezes > 0) {
                android.util.Log.w("TrackingService", "PROCESS_FREEZE evidence=$freezes")
                serviceScope.launch {
                    runCatching {
                        health.persistCritical(
                            "PROCESS_FREEZE_DETECTED",
                            motion = com.dmujeres.traccar.sensors.MotionSensor.currentState().name,
                            network = config.netLabel,
                            healthState = healthStateProvider.now(),
                        )
                    }
                }
            }
        }
        outbox = OutboxCoordinator(
            dispatchContextProvider = { outboxDispatchContext() },
            dao = { dao },
            scopeProvider = { serviceScope },
            mqttReady = { mqtt?.ready == true },
            onHttpConfirmed = { config.lastHttpAt = System.currentTimeMillis() },
        )
        presence = PresenceController(
            config = config,
            dao = { dao },
            sampler = sampler,
            // MISMA instancia de mutex que el pipeline de posiciones.
            enqueueMutex = enqueueMutex,
            scopeProvider = { serviceScope },
            isStarted = { started.get() },
            isStopping = { stopping },
            wakeMqtt = { mqtt?.wakeDispatch() },
            onEnqueued = { notifier.refreshStateAndNotify() },
            effectiveIntervalSeconds = { engine.effectiveIntervalSeconds() },
        )
        connectivity = ConnectivityObserver(
            context = this,
            sampler = sampler,
            scopeProvider = { serviceScope },
            isActive = { started.get() && !stopping },
            onPresenceRequested = { presence.enqueue() },
            onNetworkValidated = {
                mqtt?.let { manager ->
                    if (!manager.connected) {
                        // Vía inmediata de la puerta: vuelta de red validada.
                        serviceScope.launch { manager.connect(immediate = true) }
                    }
                }
                if (config.trackingEnabled) {
                    outbox.drainBacklog("red-disponible")
                }
            },
            onRefreshState = { notifier.refreshStateAndNotify() },
        )
        stopCoordinator = JourneyStopCoordinator(
            controllerScope = stopControllerScope,
            flushPendingOnStop = { outbox.flushPendingOnStop() },
            enqueueEnded = { scope, closingMqtt ->
                presence.enqueue(MobileProtocol.JOURNEY_STATUS_ENDED, scope, wake = { closingMqtt?.wakeDispatch() })
            },
            onFinished = { scope, closingMqtt -> finishStopping(scope, closingMqtt) },
        )
        summaryPresenter = JourneySummaryPresenter(
            context = this,
            config = config,
            session = session,
            resetLegacyRefs = {
                lastAcceptedTimeMs = 0L
                lastAcceptedBearingDeg = Double.NaN
            },
        )
        watchdog = TrackingWatchdog(
            context = this,
            config = config,
            session = session,
            engine = engine,
            health = health,
            outbox = outbox,
            notifier = notifier,
            sampler = sampler,
            presence = presence,
            sensors = sensors,
            dao = { dao },
            scopeProvider = { serviceScope },
            mqttProvider = { mqtt },
            healthStateNow = { healthStateProvider.now() },
            isStarted = { started.get() },
            isStopping = { stopping },
            isCapturePaused = { capturePausedForBuffer },
            pauseCapture = { pauseCaptureForBuffer() },
            resumeCapture = { resumeCaptureAfterBuffer() },
            uploadHealthPending = { uploadHealthPending() },
        )
        // Guard anti-OEM: al apagar la pantalla los OEM des-promueven el FGS y
        // congelan el proceso; al volver la luz re-afirmamos el foreground.
        registerScreenReceiver()
    }

    private var screenReceiver: BroadcastReceiver? = null

    /**
     * Receiver dinámico de pantalla (SOLO dinámico, nunca en el manifest):
     * SCREEN_ON re-afirma el foreground porque el OEM lo des-promueve al
     * apagar; SCREEN_OFF solo telemetría (la re-promoción ocurre al volver).
     */
    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        notifier.reassertForeground("screen_on", logAlways = true)
                        runCatching { com.dmujeres.traccar.platform.SentryLog.breadcrumb("device", "screen_on", "") }
                        // Cierra la ventana de CONTINUIDAD si una prueba está en curso.
                        runCatching { ContinuityTracker.onScreenOn(this@TrackingService) }
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        Log.i(TAG, "Pantalla apagada; guard de re-promoción armado")
                        runCatching { com.dmujeres.traccar.platform.SentryLog.breadcrumb("device", "screen_off", "") }
                        // Abre la ventana de continuidad si la prueba está corriendo.
                        runCatching { ContinuityTracker.onScreenOff(this@TrackingService) }
                    }
                }
            }
        }
        screenReceiver = receiver
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(receiver, filter)
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo registrar el receiver de pantalla", e)
            screenReceiver = null
        }
    }

    private fun unregisterScreenReceiver() {
        val receiver = screenReceiver ?: return
        screenReceiver = null
        runCatching { unregisterReceiver(receiver) }
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
            coreReady = true
            isRunning = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir la base de datos", e)
            config.trackingEnabled = false
            config.lastStartError = "Error de base de datos: " + (e.message ?: e.javaClass.simpleName)
            notifier.publishState(TrackingState.SERVER_UNAVAILABLE)
            notifier.releaseForeground()
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
            notifier.claimForeground()?.let { error ->
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
                    notifier.publishState(
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
            ACTION_REFRESH -> {
                // Nudge manual: solo con el servicio realmente capturando.
                if (started.get() && !stopping) {
                    onManualRefresh()
                }
                return if (started.get()) START_STICKY else START_NOT_STICKY
            }
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
                    notifier.publishState(TrackingState.TRACKING_DISABLED_BY_USER)
                    config.journeyStopRequested = false
                    notifier.releaseForeground()
                    // Refresca el widget ANTES de morir: si no, quedaría pintando
                    // "Finalizar jornada" hasta el siguiente onUpdate del launcher.
                    notifier.refreshWidget()
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
                    notifier.releaseForeground()
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
        // Guardián de sesión (anti-OEM): con jornada activa, una alarma revive
        // el servicio si el fabricante mató el proceso. El periodo lo impone el
        // guard (2 min en marcha / 15 min quieto); el motor lo re-agenda solo
        // al cambiar de modo (onAdaptiveModeChanged).
        runCatching {
            com.dmujeres.traccar.recovery.SessionKeeper.schedule(
                this, ForegroundGuardPolicy.keeperPeriodMs(engine.adaptiveMoving, journeyActive = true),
            )
        }
        // Acelómetro/giroscopio auxiliares (solo telemetría, no decisiones):
        // ciclo de vida del tracking vía SensorCoordinator (el giroscopio es
        // opcional; sin sensor queda available=false sin error).
        runCatching { sensors.register() }
        // R7: movimiento significativo (patrón OwnTracks): al reanudar tras
        // quietud pide fix YA y refuerza el guardián — ataca el salto que se
        // veía al volver a rodar (caso kevin 19-sep).
        runCatching {
            com.dmujeres.traccar.sensors.SignificantMotion.register(this) {
                if (config.trackingEnabled) {
                    runCatching { engine.nudgeRefresh() }
                    runCatching { engine.requestOneShotFix() }
                    runCatching { com.dmujeres.traccar.recovery.SessionKeeper.schedule(this, 30_000L) }
                }
            }
        }
        // R7: wakelock de jornada SOLO con el interruptor experimental (oculto).
        if (config.wakeLockExperimentEnabled &&
            WakeLockPolicy.shouldHold(config.trackingEnabled, config.journeyStartAt > 0L)
        ) {
            runCatching {
                JourneyWakeLock.acquire(
                    this,
                    WakeLockPolicy.effectiveWindowMs(ForegroundGuardPolicy.keeperPeriodMs(moving = false)),
                )
            }
        }
        val recoveringJourney = config.journeyStartAt > 0L
        // FASE 3: sesión/jornada (sessionId, bootId, anclas monotónicas).
        session.begin(recoveringJourney)
        runStartedAtMs = System.currentTimeMillis()
        lastSegmentBearingDeg = Double.NaN
        // R8.2 (H3): TTFF en el arranque — pedir fix INMEDIATO al INICIAR
        // (sin esperar el tick del motor/polling pasivo de 90 s). Con esto el
        // primer punto de la jornada llega en segundos, no minutos.
        runCatching { engine.requestOneShotFix() }
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
        // primera captura; el reloj es monotónico (elapsedRealtime). Todo el
        // estado del motor (trackingStartElapsedNanos, lastFixElapsedNanos,
        // poll, GNSS forzado, intervalo/distancia) lo inicializa
        // [LocationEngine.start] más abajo, antes del primer request.
        lastSpeedRefTimeMs = 0L
        lastKeeperPeriodMs = 0L
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
        if (!recoveringJourney) {
            config.journeyStartAt = session.startedTrackingAt
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
            presence.enqueue(MobileProtocol.JOURNEY_STATUS_STARTED)
            // Diagnóstico: snapshot forzado al abrir jornada (cola reciénStarted → pending 0).
            runCatching { SentryLog.breadcrumb("journey", "journey_start", "Jornada ${session.startedTrackingAt} iniciada") }
            runCatching { DiagnosticsReporter.report(this, "journey_start", pendingCount = 0) }
        } else {
            Notifications.alert(this, getString(R.string.service_recovery_title), getString(R.string.service_recovery_body))
            // Re-ancla el elapsed persistido/sembrado con el wall actual: a partir
            // de aquí la UI suma desde el servicio vivo, no desde el inicio de jornada.
            session.persistJourneyElapsed()
            // Reafirma el inicio después de una muerte del proceso; el estado online es idempotente.
            presence.enqueue("started")
        }
        notifier.publishState(TrackingState.SERVICE_RECOVERY)
        // FASE 6: si había un aviso "toca para reanudar", ya no aplica.
        runCatching { Notifications.clearResume(this) }
        val manager = MqttManager(
            context = this,
            config = config,
            store = RoomControlQueueStore(dao),
            scope = serviceScope,
            onStateChange = { _ -> onMqttStateChanged() },
            // Presencia con NACK terminal vía MQTT: misma cuarentena + aviso
            // que el camino HTTP (un solo criterio en ambos transportes).
            onQuarantined = { onQuarantined() },
        )
        mqtt = manager
        manager.connect()

        // FASE 3: motor de ubicación (FLP + GNSS fallback + polling + timeouts).
        engine.start(recoveringJourney)
        // R5: rescate de localización (observador + burst acotado).
        movementRescue.start()
        connectivity.start()
        watchdog.start()
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
            serviceScope, dao, PositionOutboxDispatcher.HttpTransport, outbox::dispatchContext,
        )
        notifier.refreshStateAndNotify()
    }

    private fun newServiceScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "Error en corutina del servicio", e)
        })

    @Volatile private var lastMqttStatus: String = ""
    @Volatile private var lastBufferFullAlertAt = 0L
    @Volatile private var lastDiscardAlertAt = 0L
    @Volatile private var lastQuarantineAlertAt = 0L
    @Volatile private var lastStorageAlertAt = 0L
    @Volatile private var lastJourneyLat = 0.0
    @Volatile private var lastJourneyLon = 0.0

    // Adquisición GPS activa, GNSS forzado, fallback y parámetros del request
    // viven en [LocationEngine] (FASE 3) con la misma semántica temporal.
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

    /** 1.1.8: último periodo de keeper aplicado (evita re-schedule redundante). */
    private var lastKeeperPeriodMs = 0L
    /** elapsedRealtimeNanos del fix de referencia de velocidad (0 = desconocido). */
    private var lastSpeedRefElapsedNanos = 0L
    private var consecDopplerStuck = 0

    private fun fixAgeSeconds(location: Location): Long {
        val nanos = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
        return if (location.elapsedRealtimeNanos > 0L && nanos > 0L) nanos / 1_000_000_000L else 0L
    }

    /**
     * Manejo único de cuarentena en ambos transportes (HTTP y MQTT): contador
     * acumulado + aviso con throttle. Un NACK terminal es raro y merece
     * atención, nunca silencio.
     */
    private fun onQuarantined(@Suppress("UNUSED_PARAMETER") dead: DeadLetter) = onQuarantined()

    private fun onQuarantined() {
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
     * Contexto HTTP-first del outbox (misma semántica que la construcción
     * previa dentro de OutboxCoordinator): journeyId vivo + métricas de
     * confirmación/cuarentena. El coordinador ya no conoce AppConfig.
     */
    private fun outboxDispatchContext() = PositionOutboxDispatcher.DispatchContext(
        webBaseUrl = MqttServerNormalizer.webBase(config.serverUrl, MobileProtocol.WEB_PORT),
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
     * UX "ACTUALIZAR": nudge coordinado reutilizando los mecanismos vivos.
     * NO detiene nada, NO borra nada, NO crea sesiones: GPS re-solicitado,
     * MQTT forzado si no está listo (misma puerta ReconnectGate), presencia
     * inmediata y señal al wake del Outbox (mismo flushOnce con ACK real).
     */
    private fun onManualRefresh() {
        runCatching { SentryLog.breadcrumb("journey", "manual_refresh", "ACTION_REFRESH") }
        runCatching { engine.nudgeRefresh() }
        // R8: nudge solo re-registra el request pasivo; el rescate necesita una
        // ADQUISICIÓN activa dentro de la ventana (despierta pero no fija GPS).
        runCatching { engine.requestOneShotFix() }
        runCatching { mqtt?.let { if (!it.ready) it.connect(immediate = true) } }
        runCatching { presence.heartbeat() }
        runCatching { PositionOutboxDispatcher.requestFlush() }
    }

    private fun onMqttStateChanged() {
        runCatching { config.lastMqttAt = System.currentTimeMillis() }
        val status = MqttStatus.status
        if (started.get()) {
            when (status) {
                MqttStatus.CONNECTING -> notifier.publishState(TrackingState.SERVICE_RECOVERY)
                MqttStatus.DISCONNECTED -> notifier.publishState(TrackingState.MQTT_DISCONNECTED)
                MqttStatus.CONNECTED -> if (mqtt?.ready == true && config.lastFixAt >= session.startedTrackingAt) {
                    runCatching { com.dmujeres.traccar.platform.SentryLog.breadcrumb("journey", "tracking_started", "jornada activa") }
                    notifier.publishState(TrackingState.TRACKING_ACTIVE)
                } else {
                    notifier.publishState(TrackingState.SERVICE_RECOVERY)
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
                outbox.drainBacklog("mqtt-conectado")
            }
            lastMqttStatus = status
        }
        notifier.refreshStateAndNotify()
    }

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
        engine.pauseForBuffer()
    }

    private fun resumeCaptureAfterBuffer() {
        if (!capturePausedForBuffer) return
        capturePausedForBuffer = false
        engine.resumeAfterBuffer()
    }

    private val recentFixes = ArrayDeque<FixFilter.RecentFix>()
    private val fixWindowSize = FixFilter.WINDOW_SIZE

    /**
     * R8 (angle): si la ruta giró de verdad (Δ bearing de segmentos ≥ 15° con
     * pata ≥ 8 m y velocidad implícita ≥ 1.5 m/s) se pide un fix extra
     * inmediato para trazar la curva; rate-limit 10 s. Sin bearing válido o sin
     * pata suficiente no hace nada (el bearing del GPS es ruidoso).
     */
    private fun maybeRequestTurnSample(previous: FixFilter.RecentFix?, current: Location) {
        if (previous == null) return
        val segmentM = FixFilter.distanceMeters(
            previous.lat, previous.lon, current.latitude, current.longitude,
        )
        val dtS = ((current.time - previous.timeMs).coerceAtLeast(1L)) / 1000.0
        val impliedSpeed = (segmentM / dtS).toFloat()
        val segmentBearing = FixFilter.bearingDeg(
            previous.lat, previous.lon, current.latitude, current.longitude,
        )
        val last = lastSegmentBearingDeg
        lastSegmentBearingDeg = segmentBearing
        if (!last.isFinite() || !segmentBearing.isFinite()) return
        val delta = FixFilter.angleDiffDeg(last, segmentBearing)
        if (!com.dmujeres.traccar.location.RouteSamplePolicy.shouldForceSample(delta, segmentM, impliedSpeed)) {
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastTurnSampleAtMs < com.dmujeres.traccar.location.RouteSamplePolicy.MIN_INTERVAL_BETWEEN_MS) {
            return
        }
        lastTurnSampleAtMs = now
        runCatching { engine.requestOneShotFix() }
        runCatching {
            SentryLog.breadcrumb("location", "turn_sample", "delta=${delta.toInt()} grados")
        }
    }

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
                        "motion=${com.dmujeres.traccar.sensors.MotionSensor.currentState()} " +
                        sensors.gyroTag() +
                        "confidence=${confidenceFor(this, elapsed, nowElapsedNanos)}",
                )
            }
            is FixFilter.Decision.Accept -> Log.d(
                TAG,
                "POSITION_EVALUATED verdict=accept reason=none " +
                    "motion=${com.dmujeres.traccar.sensors.MotionSensor.currentState()} " +
                    sensors.gyroTag() +
                    "confidence=${confidenceFor(this, elapsed, nowElapsedNanos)}",
            )
        }
        return decision
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
            "LOCATION_RECEIVED provider=${NetworkStatePolicy.providerLabel(location.provider)} " +
                "fixAge=${FixTime.ageSeconds(fixElapsedNanos, nowElapsed, location.time, System.currentTimeMillis())?.toInt() ?: -1}s " +
                "acc=${location.accuracy} elapsed=${fixElapsedNanos > 0L}",
        )
        val rawDecision = location.isPlausibleFix(nowElapsed)
        val decision: FixFilter.Decision = if (rawDecision is FixFilter.Decision.Reject) {
            if (recentFixes.isEmpty() && rawDecision.reason == "first_fix_bad") {
                // Anti-livelock R1: ventana vacía + todo >=150 = cero capturas.
                emptyWindowRejects++
                val elapsedSinceStart = System.currentTimeMillis() - runStartedAtMs
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
        val previousAccepted = recentFixes.lastOrNull()
        recordRecentFix(location)
        // R8 (angle): giro real → fix extra inmediato (regla OR del muestreo).
        runCatching { maybeRequestTurnSample(previousAccepted, location) }
        runCatching { config.lastAcceptedAt = System.currentTimeMillis() }
        // Guard anti-OEM: cada fix aceptado re-afirma el foreground (barato e
        // idempotente); si el sistema demoteó el FGS con pantalla apagada, así
        // se re-promueve sin getter público que lo detecte.
        notifier.reassertForeground("fix_aceptado")
        // Contador de la prueba de CONTINUIDAD: un fix aceptado con pantalla
        // apagada es la evidencia principal del DeviceReadinessGate.
        runCatching { ContinuityTracker.onFixAccepted(this@TrackingService) }
        // R9: en movimiento y con jornada activa, mantener CPU despierta
        // (ventana acotada que se renueva con cada fix aceptado). Cubre el caso
        // "sin internet": sin red no llega FCM y solo los canales locales
        // pueden trazar el tramo. Rate-limit 30 s para no reabrir en bucle.
        if (config.trackingEnabled && engine.adaptiveMoving &&
            System.currentTimeMillis() - lastMovingWindowAtMs >= 30_000L
        ) {
            lastMovingWindowAtMs = System.currentTimeMillis()
            runCatching {
                com.dmujeres.traccar.recovery.RescueWindow.open(
                    this@TrackingService,
                    com.dmujeres.traccar.recovery.RescueWindowPolicy.MOVING_WINDOW_MS,
                )
            }
        }
        // R7: renovar la ventana del wakelock con cada fix aceptado (bounded).
        if (config.wakeLockExperimentEnabled) {
            runCatching {
                JourneyWakeLock.acquire(
                    this@TrackingService,
                    WakeLockPolicy.effectiveWindowMs(ForegroundGuardPolicy.keeperPeriodMs(moving = false)),
                )
            }
        }
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
        val fixSpeed = SpeedEstimator.choose(doppler, dopplerAccuracy, implied, config.maxImpliedSpeedMps)?.mps
        // El motor adaptativo usa la velocidad EFECTIVA del fix aceptado y la
        // posición real (P3: evidencia de inicio de movimiento sin inventar).
        engine.noteFixSpeed(fixSpeed, location)
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
        if ((fixSpeed ?: 0f) >= 1.5f || movedSinceLast >= 8.0) {
            lastMovementMs = nowMs
        }
        // 1.1.8: cadena anti-OEM según evidencia REAL del fix. Un fix ralo con
        // desplazamiento real (>=100 m en >=90 s: vehículo con proceso
        // congelado) reprograma el guardián a 2 min; quieto vuelve a 15 min.
        // Es la palanca medida: joseph/miguel despertaban cada 15 min exactos.
        runCatching {
            val dtMs = if (prevSpeedRefTimeMs > 0) nowMs - prevSpeedRefTimeMs else 0L
            val journeyActive = config.journeyStartAt > 0L
            val keeperFast = journeyActive &&
                ForegroundGuardPolicy.keeperFastByDisplacement(dtMs, movedSinceLast)
            val desired = ForegroundGuardPolicy.keeperPeriodMs(
                moving = keeperFast || engine.adaptiveMoving,
                journeyActive = journeyActive,
            )
            if (desired != lastKeeperPeriodMs) {
                lastKeeperPeriodMs = desired
                com.dmujeres.traccar.recovery.SessionKeeper.schedule(this, desired)
            }
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
        runCatching { engine.applyAdaptiveMode(hasRecentFix = true) }
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
                    if (NetworkStatePolicy.providerLabel(location.provider) == NetworkStatePolicy.PROVIDER_NETWORK && config.journeyHasLastLocation &&
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
                            frequencyMs = engine.currentIntervalSeconds * 1000L,
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
                        notifier.setState(TrackingState.BUFFER_FULL)
                        return@withLock
                    }
                    val sequence = withContext(Dispatchers.IO) {
                        dao.nextSequence(config.sequence)
                    }
                    config.sequence = sequence
                    val messageId = Envelope.newMessageId(deviceId, sequence)
                    val observedAt = observedAt(location)
                    val battery = sampler.batteryLevel()
                    val network = sampler.networkLabel()
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
                        provider = NetworkStatePolicy.providerLabel(location.provider),
                        fixAgeSec = fixAgeSeconds(location),
                        speedSource = speedSource,
                        sessionId = config.sessionId.takeIf { it.isNotBlank() },
                        bootId = config.bootIdRefresh(SystemClock.elapsedRealtime()).takeIf { it.isNotBlank() },
                        journeyId = config.journeyStartAt.takeIf { it > 0L },
                        speedAccuracyMps = dopplerAccuracy,
                        confidenceScore = confidence,
                        gnssUsed = GnssState.satsUsed.takeIf { gnssHasData },
                        gnssTotal = GnssState.satsTotal.takeIf { gnssHasData },
                        qualityClass = qualityClass.name,
                        motionState = com.dmujeres.traccar.sensors.MotionSensor.currentState().name,
                        // R5: evidencia del primer fix tras un CAPTURE_GAP. Los
                        // fixes intermedios NO existen: esto lo declara, no lo
                        // rellena. movementDuringGap usa evidencia real (sensor
                        // MOVING durante el hueco, vía MotionStateV2 del periodo).
                        gapMs = run {
                            val prev = config.lastFixAt.takeIf { it > 0L }
                            val nowMs = System.currentTimeMillis()
                            val age = prev?.let { nowMs - it } ?: 0L
                            if (age > MovementRescuePolicy.STALE_FIX_MS) age else 0L
                        },
                        gapMovementDuring = run {
                            val prev = config.lastFixAt.takeIf { it > 0L }
                            val age = prev?.let { System.currentTimeMillis() - it } ?: 0L
                            if (age > 5 * 60_000L) {
                                com.dmujeres.traccar.sensors.MotionSensor.isMoving() == true
                            } else {
                                null
                            }
                        },
                        gapRescueAttempted = if (movementRescue.state in setOf(
                                MovementRescuePolicy.RescueState.RESCUE,
                                MovementRescuePolicy.RescueState.FIX_RECOVERED,
                            )
                        ) true else null,
                        gapRescueRecovered = if (movementRescue.state == MovementRescuePolicy.RescueState.FIX_RECOVERED) true else null,
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
                        notifier.setState(TrackingState.BUFFER_FULL)
                        return@withLock
                    }
                    // lastFixAt DESPUÉS de insert OK: si el buffer/DB falla (-1)
                    // no avanza y el heartbeat sigue avisando sin GPS útil.
                    // El monotónico avanza junto a él y resetea el backoff del polling.
                    config.lastFixAt = System.currentTimeMillis()
                    movementRescue.onRealFix()
                    engine.noteFixEnqueued()
                    runCatching { config.incFixEnqueued() }
                    // Traza estructurada por posición (§15): con esto + los
                    // POSITION_REJECTED se reconstruye CAPTURE→PERSIST→SEND→ACK.
                    Log.i(TAG, "POSITION_ACCEPTED+STORED provider=${NetworkStatePolicy.providerLabel(location.provider)} "
                        + "acc=${location.accuracy} speed=${"%.1f".format(speedKmh)}kmh($speedSource) "
                        + "motion=${com.dmujeres.traccar.sensors.MotionSensor.currentState()} "
                        + sensors.gyroTag()
                        + "quality=$qualityClass "
                        + "seq=$sequence sessionId=${config.sessionId} journey=${config.journeyStartAt} "
                        + "observed=$observedAt received=${Envelope.nowIso()} confidence=$confidence")
                    // Fix aceptado: el acelerómetro vuelve a medir si estaba
                    // en pausa por STATIONARY (re-register on fix).
                    runCatching { sensors.reRegisterOnFix() }
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
                    session.persistJourneyElapsed()
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
                        notifier.setState(TrackingState.TRACKING_ACTIVE)
                    } else if (MqttStatus.status == MqttStatus.DISCONNECTED) {
                        notifier.setState(TrackingState.MQTT_DISCONNECTED)
                    }
                }
                notifier.refreshStateAndNotify()
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
                notifier.refreshStateAndNotify()
            }
        }
    }

    /** Subida de snapshots de salud pendientes (FASE 7), silenciosa si falla. */
    private suspend fun uploadHealthPending() {
        val profile = com.dmujeres.traccar.oem.DeviceCapabilityProfile.read(this)
        com.dmujeres.traccar.health.HealthUploader.uploadPending(
            dao = (application as DmujeresApp).database.healthSnapshotDao(),
            config = config,
            profile = profile,
        )
    }

    private fun stopTracking() {
        if (stopping) return
        stopping = true
        started.set(false)
        connectivity.stop()
        Notifications.alert(this, getString(R.string.jornada_finalizada), getString(R.string.jornada_finalizada_body))
        // FASE 3: el motor quita request FLP, GNSS status y fallback GPS.
        runCatching { engine.stop() }
        runCatching { movementRescue.stop() }
        runCatching { com.dmujeres.traccar.platform.SentryLog.breadcrumb("journey", "tracking_stopped", "jornada cerrada") }
        runCatching { sensors.unregister() }
        runCatching { com.dmujeres.traccar.sensors.SignificantMotion.unregister() }
        runCatching { JourneyWakeLock.release() }
        notifier.publishState(TrackingState.TRACKING_DISABLED_BY_USER)
        Notifications.update(this, getString(R.string.app_name), getString(R.string.notif_journey_finished))
        // Drena primero las posiciones ya capturadas. Si MQTT está conectado pero no entrega
        // ACK, el cierre no puede dejar la cola abandonada al cancelar el servicio.
        // La secuencia completa vive en [JourneyStopCoordinator] (con test JVM).
        val closingScope = serviceScope
        val closingMqtt = mqtt
        stopJob = stopCoordinator.begin(closingScope, closingMqtt)
    }

    private fun finishStopping(closingScope: CoroutineScope, closingMqtt: MqttManager?) {
        if (!stopping) return
        closingMqtt?.disconnect()
        if (mqtt === closingMqtt) mqtt = null
        closingScope.cancel()
        val finalText = summaryPresenter.summarizeAndReset()
        notifier.releaseForeground()
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
            val claimError = notifier.claimForeground()
            if (claimError == null) {
                startTracking()
            } else {
                Log.w(TAG, "No se pudo reafirmar el foreground al retomar jornada", claimError)
                config.trackingEnabled = false
                config.lastStartError = "No se pudo reiniciar la jornada"
                notifier.publishState(TrackingState.TRACKING_DISABLED_BY_USER)
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
        connectivity.stop()
        unregisterScreenReceiver()
        runCatching { sensors.unregister() }
        if (this::config.isInitialized && config.trackingEnabled && !stopping) {
            notifier.publishState(TrackingState.SERVICE_RECOVERY)
        }
        stopJob?.cancel()
        stopControllerScope.cancel()
        serviceScope.cancel()
        runCatching { engine.stop() }
        runCatching { movementRescue.stop() }
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
