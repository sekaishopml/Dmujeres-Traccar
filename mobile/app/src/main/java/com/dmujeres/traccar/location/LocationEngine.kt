package com.dmujeres.traccar.location

import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.core.TrackingState
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * FASE 3 (prompt maestro §8): frontera del motor de ubicación extraída de
 * `TrackingService` SIN cambio de comportamiento. El servicio deja de
 * registrar callbacks, manejar timeouts, recrear el cliente FLP, forzar GNSS
 * o disparar polling: eso vive aquí. El pipeline (filtro de plausibilidad,
 * calidad, persistencia) sigue en el servicio vía [Callbacks.onFix].
 *
 * Contrato anti-hacks:
 * - NO fabrica coordenadas. Solo entrega lo que el sistema produce, incluso
 *   cuando usa el fallback GPS_PROVIDER.
 * - Sigue la escalera del watchdog original: re-solicitud cada 2 min,
 *   recreación del cliente si no hay callbacks en 10 min (máx 1/15 min),
 *   fallback directo de GPS_PROVIDER y polling one-shot con backoff.
 */
class LocationEngine(
    private val context: Context,
    private val config: AppConfig,
    private val scopeProvider: () -> CoroutineScope,
    private val lastMovementAtMs: () -> Long,
    private val mqttReady: () -> Boolean,
    private val mqttDisconnected: () -> Boolean,
    private val callbacks: Callbacks,
) {

    interface Callbacks {
        /** Fix crudo (ya ordenado cronológicamente) hacia el pipeline. */
        fun onFix(location: Location)

        /** Transición de estado del servicio (equivale a setState: alerta+dedupe). */
        fun onState(state: TrackingState)

        /** Estado suave de recuperación (equivale a publishState+refresh, sin alerta). */
        fun onSoftRecovery()

        /** Aviso legítimo de "sin satélites GNSS" (jornada madura, throttle). */
        fun onNoGnssAlert()

        /** Cambió el modo adaptativo: el guardián de sesión se re-agenda. */
        fun onAdaptiveModeChanged(moving: Boolean)

        /** Captura permitida por el ciclo de vida (started, no stopping, no pausa). */
        fun shouldCapture(): Boolean

        /** started.get() exacto (para los callbacks de éxito/fallo del request). */
        fun isStarted(): Boolean
    }

    val timeout = LocationTimeoutController()

    private var fused: FusedLocationProviderClient? = null
    @Volatile private var gnssCallback: GnssStatus.Callback? = null
    /**
     * R9: tras varios fallos del fused (Google), se pasa al GPS del sistema
     * (AOSP) como primario hasta el fin de la jornada (ROMs/chip no fiables).
     */
    @Volatile
    private var preferPlatformProvider = false

    private var gnssFallbackState = GnssFallbackPolicy.State(
        registered = false,
        fineLocationGranted = true,
    )

    /**
     * Adquisición GPS activa (reloj monotónico, nunca wall-clock para decidir):
     * - trackingStartElapsedNanos: arranque de la jornada (referencia si aún no hay fix).
     * - lastFixElapsedNanos: último fix encolado OK (avanza junto a config.lastFixAt).
     * - lastPollAttemptElapsedNanos/pollFailures: backoff 90 s → 3 min → 5 min.
     * - pollInFlight: one-shot en vuelo (viaja en presence como `pollActive`).
     */
    @Volatile private var trackingStartElapsedNanos = 0L
    @Volatile var lastFixElapsedNanos = 0L
        private set
    @Volatile private var lastPollAttemptElapsedNanos = 0L
    @Volatile private var pollFailures = 0
    @Volatile var pollInFlight = false
        private set

    /** Velocidad EFECTIVA del último fix (para la min-distance adaptativa). */
    @Volatile var lastFixSpeedMps: Float? = null
        private set

    // P3 (R3.5-C): inicio de movimiento con evidencia real (política pura).
    private var movementState = MovementStartPolicy.State.STATIONARY
    private var movementCandidateStreak = 0

    /** Ancla fijada en STATIONARY (último fix aceptado quieto). */
    @Volatile private var stationaryAnchor: Location? = null

    /** Último fix aceptado (para medir desplazamiento real desde el ancla). */
    @Volatile private var lastAcceptedLocation: Location? = null

    /** R5: ancla estacionaria para la política de rescate (solo lectura). */
    fun stationaryAnchorOrNull(): Location? = stationaryAnchor

    /** R5: último fix aceptado (solo lectura; NUNCA es un fix nuevo). */
    fun lastAcceptedLocation(): Location? = lastAcceptedLocation

    /** Modo adaptativo vigente y parámetros del request actual. */
    @Volatile var adaptiveMoving = false
        private set
    @Volatile var currentMinDistanceM = AdaptiveDistancePolicy.DISTANCE_STATIONARY_M

    /** R8: último estado de batería baja aplicado (para re-evaluar el intervalo). */
    @Volatile private var lowBatteryActive = false

    /** R8.1: último fix-inmediato pedido por arranque de movimiento (rate 10 s). */
    @Volatile private var lastWakeFixAtNanos = 0L

    /** Espacio mínimo entre fixes inmediatos por arranque de movimiento. */
    private val WAKE_FIX_SPACING_NANOS = 10_000_000_000L
    @Volatile var currentIntervalSeconds = 10L
        private set

    private val gnssFallbackListener = android.location.LocationListener { location ->
        // La misma vía que el FLP: filtro, cola y telemetría sin duplicar lógica.
        if (!callbacks.shouldCapture()) return@LocationListener
        runCatching { config.incFixReceived() }
        callbacks.onFix(location)
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            // FLP puede agrupar varios fixes mientras el proceso estaba ocupado o dormido.
            // Reloj por capa (§13): callback crudo, haya o no fix válido después.
            runCatching { config.lastLocationCallbackAt = System.currentTimeMillis() }
            orderFixes(result.locations).forEach {
                runCatching { config.incFixReceived() }
                callbacks.onFix(it)
            }
        }
    }

    private fun orderFixes(locations: List<Location>): List<Location> {
        if (locations.size < 2) return locations
        val elapsed = locations.map { runCatching { it.elapsedRealtimeNanos }.getOrDefault(0L) }
        val times = locations.map { it.time }
        val indices = FixTime.orderIndices(elapsed, times)
        return if (indices == elapsed.indices.toList()) locations else indices.map { locations[it] }
    }

    /**
     * Arranque de jornada: crea el cliente, registra el callback GNSS y pide
     * updates. `recoveringJourney` hereda la referencia de fix previo (el
     * monotónico no sobrevive a la muerte del proceso).
     */
    fun start(recoveringJourney: Boolean) {
        // R9: cada jornada evalúa de nuevo la fiabilidad del fused.
        GnssState.resetFusedFailures()
        preferPlatformProvider = false
        fused = LocationServices.getFusedLocationProviderClient(context)
        val bootNowElapsed = SystemClock.elapsedRealtimeNanos()
        trackingStartElapsedNanos = bootNowElapsed
        lastFixElapsedNanos = 0L
        lastPollAttemptElapsedNanos = 0L
        pollFailures = 0
        pollInFlight = false
        lastFixSpeedMps = null
        movementState = MovementStartPolicy.State.STATIONARY
        movementCandidateStreak = 0
        stationaryAnchor = null
        lastAcceptedLocation = null
        adaptiveMoving = false
        currentMinDistanceM = AdaptiveDistancePolicy.DISTANCE_STATIONARY_M
        currentIntervalSeconds = config.intervalSeconds.coerceAtLeast(1L)
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
        requestLocationUpdates()
        registerGnssCallback()
    }

    /** Parada de jornada: quita todo registro de ubicación. */
    fun stop() {
        try {
            fused?.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            // ignorar
        }
        unregisterGnssCallback()
        unregisterGnssFallback()
        timeout.exitGnssForce()
    }

    /**
     * Tick del watchdog (30 s): réplica exacta de la sección de ubicación del
     * `watchdogLoop` original. Decide sin tocar la DB.
     */
    fun onWatchdogTick(nowMs: Long, startedTrackingAtMs: Long, gpsWithoutFix: Boolean) {
        val captureOk = callbacks.shouldCapture()
        if (gpsWithoutFix && captureOk) {
            if (timeout.shouldReregister(nowMs)) {
                timeout.noteReregister(nowMs)
                Log.i(TAG, "GPS sin fix: re-solicitando actualizaciones de ubicación")
                runCatching { fused?.removeLocationUpdates(locationCallback) }
                requestLocationUpdates()
            }
            // Escalera anti-hambruna (caso Joseph: 28 callbacks en 7 h con cielo
            // abierto): si NI SIQUIERA hay callbacks crudos del FLP en 10 min,
            // el cliente fused puede estar atascado/muerto (Doze/OEM/Play). Se
            // recrea el cliente y se re-solicita, como máximo 1 vez cada
            // 15 min (sin loops: time-gated, con log y breadcrumb).
            if (timeout.shouldReinitEngine(nowMs, config.lastLocationCallbackAt)) {
                timeout.noteEngineReinit(nowMs)
                Log.w(TAG, "FLP sin callbacks en >10 min: re-inicializando motor de ubicación")
                runCatching {
                    com.dmujeres.traccar.platform.SentryLog.breadcrumb(
                        "gps", "engine_reinit", "Sin callbacks FLP >10 min, recreo cliente",
                    )
                }
                runCatching { fused?.removeLocationUpdates(locationCallback) }
                runCatching {
                    fused = LocationServices.getFusedLocationProviderClient(context)
                }
                requestLocationUpdates()
            }
        } else if (!gpsWithoutFix) {
            timeout.noteFixRecovered()
        }

        // GNSS forzado: sin fix fresco sostenido, re-solicitar y enganchar
        // listener directo de GPS_PROVIDER (no existe API pública para forzar
        // proveedor en el request del FLP).
        if (gpsWithoutFix && captureOk) {
            if (timeout.enterGnssForce()) {
                Log.i(TAG, "GNSS forzado: sin fix fresco >60 s")
                runCatching { fused?.removeLocationUpdates(locationCallback) }
                requestLocationUpdates()
            } else {
                registerGnssFallback()
            }
            if (timeout.noGnssAlertDue(nowMs, startedTrackingAtMs, GnssState.hasData(), GnssState.satsUsed)) {
                timeout.noteNoGnssAlert(nowMs)
                Log.w(TAG, "Jornada activa sin satélites GNSS (used=${GnssState.satsUsed})")
                callbacks.onNoGnssAlert()
            }
        } else if (!gpsWithoutFix && timeout.exitGnssForce()) {
            unregisterGnssFallback()
            Log.i(TAG, "Fix fresco recuperado: se levanta el GNSS forzado")
            runCatching { fused?.removeLocationUpdates(locationCallback) }
            requestLocationUpdates()
        }

        // Adquisición activa (SIN timers nuevos: corre en este watchdog de 30 s):
        // - Sin fix reciente → modo quieto (0 m) para recibir todo lo que el
        //   FLP dé (el re-registro lo hace applyAdaptiveMode al cambiar de modo).
        // - Sin fix válido en > 90 s → one-shot getCurrentLocation con backoff.
        val hasRecentFix = LocationEnginePolicy.isFixRecent(
            lastFixElapsedNanos, SystemClock.elapsedRealtimeNanos(), effectiveIntervalSeconds(),
        )
        applyAdaptiveMode(hasRecentFix)
        maybeActivePoll()
    }

    /**
     * Aplica la min-distance adaptativa. Actualiza el modo siempre (para que
     * `resumeAfterBuffer` use el valor vigente) pero re-registra el request
     * SOLO al cambiar de modo. Devuelve true si re-registró.
     *
     * Stop detection (perfil oculto Traccar, ON): quieto más de 60 s →
     * heartbeat de 60 s (GPS ciclado, jornada viva); al moverse vuelve la
     * cadencia normal por el propio cambio de modo.
     */
    fun applyAdaptiveMode(hasRecentFix: Boolean): Boolean {
        val current = if (adaptiveMoving) {
            AdaptiveDistancePolicy.Mode.MOVING
        } else {
            AdaptiveDistancePolicy.Mode.STATIONARY
        }
        // P3: evidencia real de inicio de movimiento (velocidad efectiva +
        // desplazamiento desde el ancla). La política nunca produce posición:
        // solo adelanta la estrategia de adquisición (burst) al arrancar.
        val anchor = stationaryAnchor
        val last = lastAcceptedLocation
        val displacement = if (anchor != null && last != null) anchor.distanceTo(last) else null
        val decision = MovementStartPolicy.next(
            movementState,
            MovementStartPolicy.Evidence(
                gnssSpeedMps = lastFixSpeedMps,
                displacementFromAnchorM = displacement,
                // 1.1.8: el acelerómetro (histéresis propia, no flapea con un
                // pico) autoriza adquisición densa cuando la velocidad GNSS no
                // basta (tráfico lento, fixes fused con speed 0). Nunca produce
                // coordenadas: solo cambia la estrategia de captura.
                sensorMovement = com.dmujeres.traccar.sensors.MotionSensor.isMoving() == true,
                hasFreshFix = hasRecentFix,
            ),
            movementCandidateStreak,
        )
        movementCandidateStreak = if (decision.state == MovementStartPolicy.State.MOVEMENT_CANDIDATE) {
            movementCandidateStreak + 1
        } else {
            0
        }
        // R9: geofence ENCADENADA — canal exento por el OS que despierta el
        // servicio sin internet. Se re-centra en la ÚLTIMA posición (quieto o en
        // movimiento): cada salida de 150 m despierta la app aunque esté
        // congelada y sin datos, y el siguiente fix la vuelve a encadenar.
        val journeyNow = config.journeyStartAt > 0L
        val chainCenter = last ?: stationaryAnchor
        if (journeyNow && chainCenter != null && hasRecentFix) {
            if (MovementGeofencePolicy.shouldRegister(
                    journeyActive = true,
                    hasFreshFix = true,
                ) && MovementGeofencePolicy.shouldReRegister(
                    MovementGeofence.isRegistered(),
                    MovementGeofence.registeredCenter()?.first,
                    MovementGeofence.registeredCenter()?.second,
                    chainCenter.latitude,
                    chainCenter.longitude,
                ) { lat1, lon1, lat2, lon2 -> FixFilter.distanceMeters(lat1, lon1, lat2, lon2) }
            ) {
                runCatching { MovementGeofence.register(context, chainCenter) }
            }
        } else if (!journeyNow && MovementGeofence.isRegistered()) {
            // Jornada terminada: se retira la geocerca encadenada.
            runCatching { MovementGeofence.remove(context) }
        }
        val previousState = movementState
        movementState = decision.state
        // R8.1: al primer indicio real de movimiento, pedir un fix INMEDIATO
        // (one-shot) en vez de esperar al próximo tick del heartbeat. Con eso
        // la ruta arranca en segundos, no minutos, tras una parada.
        if (previousState == MovementStartPolicy.State.STATIONARY &&
            decision.state != MovementStartPolicy.State.STATIONARY &&
            SystemClock.elapsedRealtimeNanos() - lastWakeFixAtNanos >= WAKE_FIX_SPACING_NANOS
        ) {
            lastWakeFixAtNanos = SystemClock.elapsedRealtimeNanos()
            runCatching { requestOneShotFix() }
            Log.i(TAG, "Arranque de movimiento → fix inmediato (one-shot)")
        }
        // 1.1.8: el ancla SOLO se actualiza con fix fresco. Antes, un tick del
        // watchdog con fix ralo (!hasRecentFix) reseteaba el ancla al último fix
        // y el desplazamiento real nunca acumulaba (macias en tráfico lento).
        if (decision.state == MovementStartPolicy.State.STATIONARY && last != null && hasRecentFix) {
            stationaryAnchor = Location(last)
        }
        val next = if (MovementStartPolicy.usesBurstCapture(decision.state)) {
            AdaptiveDistancePolicy.Mode.MOVING
        } else {
            AdaptiveDistancePolicy.Mode.STATIONARY
        }
        // R8: perfil de batería baja (una sola vez por cambio de estado, barato).
        val lowBattery = ActivePollPolicy.isLowBattery(batteryLevel())
        var changed = false
        if (next != current || lowBattery != lowBatteryActive) {
            lowBatteryActive = lowBattery
            adaptiveMoving = next == AdaptiveDistancePolicy.Mode.MOVING
            currentMinDistanceM = AdaptiveDistancePolicy.distanceFor(next)
            currentIntervalSeconds = AdaptiveDistancePolicy.intervalFor(next, config.intervalSeconds, lowBattery)
            // Keeper según el modo (guard anti-OEM, solo en transición): en
            // marcha la alarma se acelera a 2 min; en quietud vuelve a 15 min.
            if (next != current && callbacks.shouldCapture()) {
                callbacks.onAdaptiveModeChanged(adaptiveMoving)
            }
            changed = true
        }
        // Heartbeat de parada: prima sobre la cadencia del modo.
        if (FixFilter.heartbeatDue(lastMovementAtMs(), System.currentTimeMillis()) &&
            currentIntervalSeconds != FixFilter.STOP_HEARTBEAT_SECONDS
        ) {
            currentIntervalSeconds = FixFilter.STOP_HEARTBEAT_SECONDS
            Log.i(TAG, "Stop detection: quietud prolongada → heartbeat ${FixFilter.STOP_HEARTBEAT_SECONDS}s")
            changed = true
        }
        if (!changed) return false
        if (!callbacks.shouldCapture()) return false
        Log.i(TAG, "Adaptativo → ${currentMinDistanceM}m cada ${currentIntervalSeconds}s (modo $next, speed=$lastFixSpeedMps)")
        runCatching { fused?.removeLocationUpdates(locationCallback) }
        requestLocationUpdates()
        timeout.noteReregister(System.currentTimeMillis())
        return true
    }

    /** Velocidad efectiva del fix aceptado (alimenta la min-distance). */
    fun noteFixSpeed(mps: Float?, location: Location? = null) {
        lastFixSpeedMps = mps
        if (location != null) {
            lastAcceptedLocation = Location(location)
        }
    }

    /**
     * Refresco manual (UX "ACTUALIZAR"): re-solicita el proveedor activo sin
     * tocar políticas ni fabricar fixes. Solo si el servicio está capturando.
     */
    fun nudgeRefresh() {
        if (!callbacks.shouldCapture()) return
        runCatching { fused?.removeLocationUpdates(locationCallback) }
        requestLocationUpdates()
        timeout.noteReregister(System.currentTimeMillis())
    }

    /**
     * R5 (MovementRescue): solicitud one-shot HIGH_ACCURACY a través del
     * one-shot EXISTENTE (mismo [maybeActivePoll], misma vía onFix; no crea
     * callbacks ni vías alternas). Devuelve true si disparó el intento.
     */
    fun requestOneShotFix(): Boolean {
        if (!callbacks.shouldCapture() || !config.trackingEnabled) return false
        maybeActivePoll()
        return true
    }

    /** Fix encolado OK: avanza el reloj monotónico y resetea el backoff del polling. */
    fun noteFixEnqueued() {
        lastFixElapsedNanos = SystemClock.elapsedRealtimeNanos()
        pollFailures = 0
    }

    private fun requestLocationUpdates() {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            callbacks.onState(TrackingState.PERMISSION_MISSING)
            return
        }
        // R9: proveedor de plataforma como PRIMARIO si el fused resultó no
        // fiable en esta jornada (evita el bucle de fallos GPS_DISABLED).
        if (preferPlatformProvider) {
            registerGnssFallback()
            if (callbacks.isStarted()) {
                if (mqttReady()) {
                    callbacks.onState(TrackingState.TRACKING_ACTIVE)
                } else if (mqttDisconnected()) {
                    callbacks.onState(TrackingState.MQTT_DISCONNECTED)
                } else {
                    callbacks.onSoftRecovery()
                }
            }
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
                onFusedFailure()
                return
            }
            task.addOnSuccessListener {
                if (callbacks.isStarted()) {
                    if (mqttReady()) {
                        callbacks.onState(TrackingState.TRACKING_ACTIVE)
                    } else if (mqttDisconnected()) {
                        callbacks.onState(TrackingState.MQTT_DISCONNECTED)
                    } else {
                        callbacks.onSoftRecovery()
                    }
                }
            }
            task.addOnFailureListener { error ->
                Log.e(TAG, "No se pudieron solicitar actualizaciones de ubicación", error)
                if (callbacks.isStarted()) onFusedFailure()
            }
            task.addOnCanceledListener {
                Log.w(TAG, "La solicitud de actualizaciones de ubicación fue cancelada")
                if (callbacks.isStarted()) onFusedFailure()
            }
        } catch (e: SecurityException) {
            callbacks.onState(TrackingState.PERMISSION_MISSING)
        } catch (e: Exception) {
            Log.e(TAG, "Error al solicitar actualizaciones de ubicación", e)
            onFusedFailure()
        }
    }

    /**
     * R9: fallo del proveedor fused (Google). Cuenta el fallo, registra el GPS
     * del sistema INMEDIATAMENTE (no en el siguiente ciclo) y tras
     * [FusedFailurePolicy.LIMIT] fallos lo adopta como primario. El estado
     * distingue "GPS del sistema apagado" (real) de "fused no disponible".
     */
    private fun onFusedFailure() {
        GnssState.noteFusedFailure()
        runCatching { registerGnssFallback() }
        if (FusedFailurePolicy.shouldPreferPlatform(GnssState.fusedFailures)) {
            if (!preferPlatformProvider) {
                preferPlatformProvider = true
                Log.w(TAG, "Fused no fiable (${GnssState.fusedFailures} fallos): GPS del sistema como primario")
                runCatching { unregisterGnssFallback() } // re-registrar como primario
                runCatching { registerGnssFallback() }
            }
        }
        if (callbacks.isStarted()) {
            callbacks.onState(if (isSystemLocationEnabled()) {
                TrackingState.GPS_FALLBACK
            } else {
                TrackingState.NO_FRESH_FIX
            })
        }
    }

    /** ¿La ubicación del SISTEMA está encendida? (distingue toggle real). */
    private fun isSystemLocationEnabled(): Boolean = runCatching {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
            || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }.getOrDefault(false)

    /** GNSS real (API 24+, minSdk 26): cuenta satélites en vista/usados en fix. */
    private fun registerGnssCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        if (gnssCallback != null) return
        if (!hasFineLocation()) return
        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
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
                    ContextCompat.getMainExecutor(context), callback,
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
            (context.getSystemService(Context.LOCATION_SERVICE) as LocationManager)
                .unregisterGnssStatusCallback(callback)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo desregistrar GnssStatus", e)
        }
    }

    /**
     * Fallback GPS_PROVIDER con Looper EXPLÍCITO (main): evita el Handler
     * interno sobre el thread llamador (corutina sin Looper → NPE real
     * "invalid null looper" tras screen-off) y entrega los callbacks al
     * thread principal, como el FLP.
     */
    private fun registerGnssFallback() {
        if (!GnssFallbackPolicy.shouldRegister(gnssFallbackState)) return
        gnssFallbackState = gnssFallbackState.copy(
            fineLocationGranted = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED,
        )
        if (!GnssFallbackPolicy.shouldRegister(gnssFallbackState)) return
        runCatching {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            @Suppress("DEPRECATION")
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                config.intervalSeconds * 1000L,
                0f,
                gnssFallbackListener,
                context.mainLooper,
            )
            gnssFallbackState = GnssFallbackPolicy.registered(gnssFallbackState)
            Log.i(TAG, "Fallback GPS_PROVIDER registrado (main looper)")
        }.onFailure { error ->
            Log.w(TAG, "No se pudo registrar el fallback GPS", error)
            gnssFallbackState = GnssFallbackPolicy.failure(
                gnssFallbackState, error.message ?: error.javaClass.simpleName,
            )
        }
    }

    private fun unregisterGnssFallback() {
        if (!GnssFallbackPolicy.shouldUnregister(gnssFallbackState)) return
        runCatching {
            (context.getSystemService(Context.LOCATION_SERVICE) as LocationManager)
                .removeUpdates(gnssFallbackListener)
        }
        gnssFallbackState = GnssFallbackPolicy.unregistered(gnssFallbackState)
    }

    /**
     * Polling activo one-shot: si la jornada sigue activa y el FLP pasivo no
     * entrega fixes, busca activamente el fix con `getCurrentLocation`
     * (HIGH_ACCURACY, timeout 30 s). El resultado entra por el MISMO
     * `onFix`/filtro (no hay vía alterna). Backoff 90 s → 3 min → 5 min.
     */
    private fun maybeActivePoll() {
        if (!callbacks.shouldCapture() || !config.trackingEnabled) return
        if (!hasFineLocation()) return
        val shouldFire = ActivePollPolicy.shouldPoll(
            trackingActive = true,
            nowElapsedNanos = SystemClock.elapsedRealtimeNanos(),
            lastFixElapsedNanos = lastFixElapsedNanos,
            startElapsedNanos = trackingStartElapsedNanos,
            lastPollAttemptElapsedNanos = lastPollAttemptElapsedNanos,
            consecutiveFailures = pollFailures,
            batteryPct = batteryLevel(),
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
        scopeProvider().launch {
            delay(ActivePollPolicy.POLL_TIMEOUT_MS)
            if (pollInFlight) runCatching { cts.cancel() }
        }
        try {
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnSuccessListener { location ->
                    if (location != null) {
                        runCatching { config.incFixReceived() }
                        pollInFlight = false
                        callbacks.onFix(location)
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

    /** Pausa la captura por buffer lleno (STOP_CAPTURE): quita el request. */
    fun pauseForBuffer() {
        runCatching { fused?.removeLocationUpdates(locationCallback) }
    }

    /** Reanuda la captura tras drenar el buffer. */
    fun resumeAfterBuffer() {
        requestLocationUpdates()
    }

    fun effectiveIntervalSeconds(): Long = currentIntervalSeconds.coerceAtLeast(1L)

    private fun hasFineLocation(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun batteryLevel(): Int =
        (context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager)
            .getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private companion object {
        const val TAG = "LocationEngine"
    }
}
