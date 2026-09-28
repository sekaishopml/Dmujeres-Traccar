/*
 * Copyright 2015 - 2021 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.client

import android.content.Context
import org.traccar.client.ProtocolFormatter.formatRequest
import org.traccar.client.RequestManager.sendRequestAsync
import org.traccar.client.PositionProvider.PositionListener
import org.traccar.client.NetworkManager.NetworkHandler
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceManager
import android.util.Log
import org.traccar.client.DatabaseHelper.DatabaseHandler
import org.traccar.client.RequestManager.RequestHandler
import org.traccar.client.journey.JourneyManager
import org.traccar.client.capture.CaptureGate
import org.traccar.client.movement.MovementStateMachine
import org.traccar.client.recovery.DozeAlarmReceiver
import org.traccar.client.sync.UploadQueue

/**
 * Única autoridad del tracking (§3): captura, máquina de estados, cola y
 * recuperación pasan por aquí. Ningún otro componente manda sobre la cadencia.
 */
class TrackingController(private val context: Context) :
    PositionListener, NetworkHandler, MotionMonitor.TurnListener, UploadQueue.Listener {

    private val handler = Handler(Looper.getMainLooper())
    private val preferences = PreferenceManager.getDefaultSharedPreferences(context)
    private var positionProvider = PositionProviderFactory.create(context, this)
    private val watchdog = LocationWatchdog()

    /**
     * Máquina de estados de movimiento (ADR-006): la única que decide la
     * cadencia. MotionMonitor pasa a ser UN input más (pulso IMU), no el que
     * decide; la velocidad GPS y el desplazamiento mandan.
     */
    private val machine = MovementStateMachine()
    private val journeyManager = JourneyManager(context)
    private var uploadQueue: UploadQueue? = null
    /**
     * Filtros de captura (teleport, colapso en parado, giro): deciden si cada
     * fix se almacena, sin tocar cadencia, protocolo, jornada ni recuperación.
     * Con estado propio (último almacenado/aceptado), sembrado del historial.
     */
    private val captureGate = CaptureGate()

    /** Cadencia fina vigente (se aplica al proveedor solo al cambiar). */
    private var fineCadence = true
    private var platformFallback = false

    private var lastFixLat = 0.0
    private var lastFixLon = 0.0
    private var lastFixAtMs = 0L
    /**
     * Último fix ALMACENADO (captured_at + coords exactas) para el
     * [DuplicateFixGuard]. OJO: no es el último RECIBIDO (`lastFix*`, tiempo
     * de pared para la máquina): solo avanza cuando el fix entra al almacén,
     * nunca con un duplicado descartado.
     */
    private var lastStoredFix: DuplicateFixGuard.Fix? = null
    /** Aviso significant-motion pendiente de consumir en el próximo fix. */
    private var significantMotionPending = false
    private val databaseHelper = DatabaseHelper(context)
    private val networkManager = NetworkManager(context, this)

    private val url: String = preferences.getString(Prefs.URL, context.getString(R.string.settings_url_default_value))!!
    private val buffer: Boolean = preferences.getBoolean(Prefs.BUFFER, true)

    /** Interruptor del wake lock por envío (la preferencia sigue mandando). */
    private val wakeLockEnabled: Boolean = preferences.getBoolean(Prefs.WAKELOCK, true)

    private var isOnline = networkManager.isOnline

    fun start() {
        // Arranque nuevo = captura abierta: un cierre limpio previo (solo
        // debug) pudo dejar el congelamiento puesto si el proceso murió.
        captureFrozen = false
        // boot_id: UUID por arranque de proceso (las filas viejas conservan el
        // suyo: la identidad es por evento, no por instalación).
        runCatching { databaseHelper.rotateBootId() }
        // Jornada: prefs→meta, reconstrucción del estado y reconciliación.
        runCatching { journeyManager.syncFromPrefs() }
        restoreMovementState()
        journeyManager.reconcileAtStartup()
        // Rescate de Doze: la alarma se rearma en cada arranque del servicio.
        runCatching { DozeAlarmReceiver.schedule(context) }
        uploadQueue = UploadQueue(context, databaseHelper, this)
        if (isOnline) {
            uploadQueue?.kick(true)
        }
        try {
            positionProvider.startUpdates()
        } catch (e: SecurityException) {
            Log.w(TAG, e)
        }
        MotionMonitor.register(context)
        MotionMonitor.setTurnListener(this)
        MotionMonitor.setSignificantMotionListener { onSignificantMotion() }
        watchdog.start(System.currentTimeMillis())
        handler.postDelayed(watchdogTick, WATCHDOG_PERIOD_MS)
        handler.postDelayed(motionTick, MOTION_CHECK_PERIOD_MS)
        networkManager.start()
    }

    /**
     * Reconstrucción desde el almacén tras recreación: los últimos fixes dicen
     * en qué estado arrancar (nunca "en memoria = válido"). Si el boot dejó
     * marca de recuperación pendiente, se entra en RECOVERING.
     */
    private fun restoreMovementState() {
        // Un fallo aquí (base corrupta, prefs rotas) no puede matar el
        // arranque: se arranca en modo seguro (STARTING → primer fix → ACTIVE,
        // cadencia fina) y se registra. Ante la duda, continuidad.
        runCatching { restoreMovementStateOrThrow() }
            .onFailure {
                Log.e(TAG, "restauración fallida, modo seguro", it)
                runCatching { machine.onJourneyStarted(System.currentTimeMillis()) }
            }
    }

    private fun restoreMovementStateOrThrow() {
        val now = System.currentTimeMillis()
        val journeyOpen = journeyManager.local()?.open == true ||
            preferences.getBoolean(DmujeresApi.KEY_JOURNEY_OPEN, false)
        val history = runCatching {
            databaseHelper.selectRecentPositions(RESTORE_HISTORY)
        }.getOrDefault(emptyList())
        // Siembra del antiduplicado con el último almacenado (el primero de
        // la lista: viene en ORDER BY id DESC): cero queries extra en régimen.
        lastStoredFix = history.firstOrNull()?.let {
            DuplicateFixGuard.Fix(it.time.time, it.latitude, it.longitude)
        }
        // Misma siembra para los filtros de captura (distancia, latido, rumbo
        // y referencia fresca del teleport): el primer fix siempre se guarda.
        history.firstOrNull()?.let {
            captureGate.seed(it.time.time, it.latitude, it.longitude, it.course, it.accuracy)
        }
        val samples = history.map {
            MovementStateMachine.FixSample(it.time.time, it.speed, 0.0)
        }
        // El desplazamiento entre fixes se recalcula en vivo; el historial
        // aporta velocidad y frescura (suficiente para no asumir quietud).
        machine.restore(now, journeyOpen, samples)
        if (journeyOpen && runCatching {
                databaseHelper.getMeta(DatabaseHelper.KEY_RECOVERY_PENDING)
            }.getOrNull() == "1"
        ) {
            runCatching { databaseHelper.putMeta(DatabaseHelper.KEY_RECOVERY_PENDING, "0") }
            machine.onRecoveryTriggered(now)
            Log.i(TAG, "recuperación pendiente del boot: RECOVERING")
        }
        applyCadence()
        persistMovementState()
    }

    /**
     * Pulso del sensor (10 s): el IMU es UN input más de la máquina, no el que
     * decide. Solo se toca la cadencia cuando la máquina cambia de opinión
     * (evita flapeo de la petición Fused).
     */
    private val motionTick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            machine.onImuHint(now, MotionMonitor.isMoving())
            machine.onTick(now)
            applyCadence(requestFixOnFine = false)
            persistMovementState()
            handler.postDelayed(this, MOTION_CHECK_PERIOD_MS)
        }
    }

    /**
     * Cada minuto: vigilante de GPS (re-solicitar y, si sigue colgado, pasar al
     * GPS del sistema) + reloj de la máquina (4 min sin fix → RECOVERING).
     * Nunca inventa posiciones.
     */
    private val watchdogTick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            machine.onTick(now)
            applyCadence(requestFixOnFine = false)
            persistMovementState()
            when (watchdog.tick(now)) {
                LocationWatchdog.Action.RE_REQUEST -> {
                    Log.w(TAG, "GPS sin fix: re-solicitando actualizaciones")
                    runCatching {
                        positionProvider.stopUpdates()
                        positionProvider.startUpdates()
                    }
                }
                LocationWatchdog.Action.FALLBACK -> switchToPlatformProvider()
                LocationWatchdog.Action.NONE -> Unit
            }
            handler.postDelayed(this, WATCHDOG_PERIOD_MS)
        }
    }

    /** Aplica la cadencia de la máquina al proveedor (solo al cambiar). */
    private fun applyCadence(requestFixOnFine: Boolean = true) {
        val fine = machine.wantsFineCadence()
        if (fine == fineCadence) return
        fineCadence = fine
        positionProvider.applyMotionState(fine)
        Log.i(TAG, "cadencia de la máquina: fina=$fine (${machine.state})")
        if (fine && requestFixOnFine) {
            // Arranque de ruta: un fix inmediato en vez de esperar la ventana.
            runCatching { positionProvider.requestSingleLocation() }
        }
    }

    /** El estado vigente queda en prefs para el latido (sin RAM de por medio). */
    private fun persistMovementState() {
        runCatching {
            preferences.edit()
                .putString(Prefs.MOVEMENT_STATE, machine.state.name)
                .putBoolean(Prefs.MOVEMENT_WALKING, machine.isWalking)
                .putString(
                    Prefs.MOVEMENT_MODE,
                    if (machine.isWalking) Prefs.MODE_WALK else Prefs.MODE_NORMAL,
                )
                .apply()
        }
    }

    /** Cambia al GPS del sistema (AOSP) cuando el fused no responde. */
    private fun switchToPlatformProvider() {
        if (platformFallback) return
        platformFallback = true
        Log.w(TAG, "GPS del sistema como respaldo (fused sin fixes)")
        StatusActivity.addMessage(context.getString(R.string.status_platform_gps))
        machine.onPositionUnavailable()
        persistMovementState()
        runCatching {
            positionProvider.stopUpdates()
            positionProvider = AndroidPositionProvider(context, this)
            positionProvider.applyMotionState(fineCadence)
            positionProvider.startUpdates()
        }.onFailure { Log.w(TAG, "no se pudo activar el GPS del sistema", it) }
    }

    /**
     * Refresco manual (botón ACTUALIZAR del home): reanuda la cola (por si
     * quedó pausada por 401 y ya se corrigió la clave), vacía pendientes y
     * pide un fix inmediato. Seguro: no reinicia nada.
     */
    fun refreshNow() {
        runCatching {
            uploadQueue?.resume()
            uploadQueue?.kick(isOnline)
            positionProvider.requestSingleLocation()
        }.onFailure { Log.w(TAG, "refresco manual falló", it) }
    }

    /**
     * Despertar de recuperación (alarma/FCM): la máquina entra en RECOVERING,
     * se pide fix FRESCO (nunca last-known) y se vacía la cola. El fix que
     * llegue y la cola fluyendo cierran la recuperación.
     */
    fun onRecoveryWakeup(): Boolean {
        machine.onRecoveryTriggered(System.currentTimeMillis())
        persistMovementState()
        runCatching { positionProvider.requestFreshLocation() }
            .onFailure { Log.w(TAG, "fix fresco de recuperación falló", it) }
        uploadQueue?.kick(isOnline)
        return true
    }

    fun stop() {
        // El apagado nunca debe lanzar: si el arranque quedó a medias (o stop
        // se llama dos veces), un crash aquí convierte cualquier fallo en un
        // bucle de reinicios. Cada pieza se defiende sola y esto es el seguro.
        runCatching { networkManager.stop() }.onFailure { Log.w(TAG, "al detener red", it) }
        runCatching { positionProvider.stopUpdates() }.onFailure { Log.w(TAG, "al detener proveedor", it) }
        MotionMonitor.setTurnListener(null)
        MotionMonitor.setSignificantMotionListener(null)
        runCatching { MotionMonitor.unregister(context) }.onFailure { Log.w(TAG, "al liberar sensores", it) }
        runCatching { handler.removeCallbacksAndMessages(null) }.onFailure { Log.w(TAG, "al limpiar handler", it) }
    }

    override fun onPositionUpdate(position: Position) {
        // Cierre limpio de sesión en curso (solo debug): no se acepta ningún
        // fix nuevo a la cola mientras se vacía. No se toca la máquina ni el
        // vigilante: solo se deja de almacenar.
        if (captureFrozen) return
        val now = System.currentTimeMillis()
        // Guardia de teleport (rechazo de error, no pérdida): ANTES de que el
        // salto contamine la máquina, el último fix o el vigilante. El fix
        // falso se tira y no se toca nada más.
        if (captureGate.isTeleport(
                position.latitude, position.longitude,
                position.time.time, position.accuracy, position.speed,
            )
        ) {
            Log.w(TAG, "fix teleport descartado " +
                "(lat=${position.latitude} lon=${position.longitude} v=${position.speed} kn)")
            return
        }
        // La velocidad reportada (nudos) alimenta el detector de giros.
        MotionMonitor.lastSpeedKnots = position.speed
        // Desplazamiento desde el último fix aceptado (el GPS manda sobre el
        // IMU: suple una velocidad nula cuando el equipo sí se movió).
        val legM = if (lastFixAtMs > 0) {
            legMeters(lastFixLat, lastFixLon, position.latitude, position.longitude)
        } else {
            0.0
        }
        lastFixLat = position.latitude
        lastFixLon = position.longitude
        lastFixAtMs = now
        // El último fix con GPS falso (mock) queda para el diagnostico.
        runCatching {
            preferences.edit().putBoolean(Prefs.LAST_MOCK, position.mock).apply()
        }
        // La máquina decide (GPS > caminata > distancia > significant > IMU);
        // el forzado por velocidad anterior (holdMovingUntilMs) se elimina: era
        // una segunda autoridad de cadencia fuera de la máquina. Se alimenta
        // con coordenadas para que el avance sostenido a pie cuente como
        // movimiento aunque la instantánea sea <3 kn.
        val significant = significantMotionPending
        significantMotionPending = false
        val before = machine.state
        machine.onFixWithPosition(
            now, position.speed, legM,
            position.latitude, position.longitude,
            MotionMonitor.isMoving(), significant,
        )
        if (machine.state != before) {
            Log.i(TAG, "movimiento: $before -> ${machine.state} " +
                "(v=${position.speed} kn, d=${legM.toInt()} m, caminando=${machine.isWalking})")
        }
        applyCadence()
        persistMovementState()
        watchdog.noteFix(now)
        StatusActivity.addMessage(context.getString(R.string.status_location_update))
        // Filtros de captura: colapso en parado (tira el ruido) y giro (obliga
        // la esquina). Lo que no se almacena aquí no es dato perdido: es ruido
        // colapsado o esquina ya cubierta; el teleport ya se rechazó arriba.
        val outcome = captureGate.evaluate(
            position.latitude, position.longitude, position.course, position.accuracy,
            position.speed, position.time.time,
            machine.state == MovementStateMachine.State.STATIONARY, now,
        )
        if (!outcome.store) {
            Log.i(TAG, "fix colapsado en parado, no se almacena (${outcome.reason})")
            return
        }
        if (outcome.reason == CaptureGate.Reason.STORE_TURN) {
            Log.i(TAG, "giro: esquina almacenada")
        }
        if (buffer) {
            // El evento lleva proveedor y estado para el lote e idempotencia.
            write(position.copy(provider = positionProvider.providerName, movementState = machine.state.name))
        } else {
            send(position)
        }
    }

    /** Distancia (m) entre dos coordenadas, para la velocidad implícita. */
    private fun legMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val result = FloatArray(1)
        android.location.Location.distanceBetween(lat1, lon1, lat2, lon2, result)
        return result[0].toDouble()
    }

    /**
     * Arranque de movimiento por el sensor de bajo consumo (significant motion):
     * entra a la máquina como evidencia y pide un fix ya, sin esperar al
     * acelerómetro.
     */
    fun onSignificantMotion() {
        Log.i(TAG, "movimiento por sensor significativo")
        significantMotionPending = true
        machine.onSignificantMotion(System.currentTimeMillis())
        applyCadence()
        persistMovementState()
        runCatching { positionProvider.requestSingleLocation() }
    }

    /** Giro fuerte (giroscopio): captura la esquina sin subir la cadencia base. */
    override fun onTurn() {
        Log.i(TAG, "giro fuerte: fix inmediato y refuerzo a los $TURN_REINFORCE_DELAY_MS ms")
        runCatching { positionProvider.requestSingleLocation() }
        handler.removeCallbacks(turnReinforce)
        handler.postDelayed(turnReinforce, TURN_REINFORCE_DELAY_MS)
    }

    private val turnReinforce = Runnable {
        runCatching { positionProvider.requestSingleLocation() }
    }

    override fun onPositionError(error: Throwable) {}
    override fun onNetworkUpdate(isOnline: Boolean) {
        val message = if (isOnline) R.string.status_network_online else R.string.status_network_offline
        StatusActivity.addMessage(context.getString(message))
        if (!this.isOnline && isOnline) {
            uploadQueue?.kick(true)
        }
        this.isOnline = isOnline
    }

    // --- UploadQueue.Listener --------------------------------------------------
    //
    // La subida es `write -> kick -> lote -> ack -> delete -> kick` en serie
    // dentro de UploadQueue; aquí solo se reacciona a sus avisos.

    /** La cola confirmó eventos: si estábamos en rescate, se cierra. */
    override fun onQueueFlowing(confirmed: Int) {
        ConnectionState.noteSuccess(System.currentTimeMillis())
        val before = machine.state
        machine.onRecovered(System.currentTimeMillis(), queueFlowing = true)
        if (machine.state != before) {
            Log.i(TAG, "recuperación cerrada por cola fluyendo ($confirmed confirmados)")
            applyCadence()
        }
        persistMovementState()
    }

    override fun onEventsDead(count: Int) {
        ConnectionState.noteFailure(System.currentTimeMillis())
        StatusActivity.addMessage(context.getString(R.string.status_send_fail))
        Log.w(TAG, "$count eventos DEAD (no reintentados, ya reportados)")
    }

    override fun onAuthPaused() {
        StatusActivity.addMessage(context.getString(R.string.status_send_fail))
    }

    private fun log(action: String, position: Position?) {
        var formattedAction: String = action
        if (position != null) {
            formattedAction +=
                    " (id:" + position.id +
                    " time:" + position.time.time / 1000 +
                    " lat:" + position.latitude +
                    " lon:" + position.longitude + ")"
        }
        Log.d(TAG, formattedAction)
    }

    /**
     * Captura local primero (disco), subida después: el fix se persiste con su
     * identidad y la cola lo drena en serie. La captura nunca depende de la red.
     *
     * Antiduplicado del fused ([DuplicateFixGuard]): si el fix es la misma
     * observación ya almacenada (mismo captured_at Y mismas coords, o mismo
     * punto con dt < 5 s), se descarta ANTES del INSERT. Es el único punto por
     * el que todo fix aceptado entra al almacén (periódico, suelto y rescate,
     * fused y GPS del sistema), así que un solo guard cubre todos los caminos
     * sin tocar la cadencia (la máquina ya vio el fix) ni el protocolo.
     */
    private fun write(position: Position) {
        log("write", position)
        val candidate = DuplicateFixGuard.Fix(position.time.time, position.latitude, position.longitude)
        val last = lastStoredFix ?: runCatching {
            // Solo si el arranque no sembró (arranque a medias o restauración
            // fallida): UNA fila, y solo para fixes que pasaron los filtros.
            databaseHelper.selectRecentPositions(1).firstOrNull()?.let {
                DuplicateFixGuard.Fix(it.time.time, it.latitude, it.longitude)
            }
        }.getOrNull()
        if (last != null && DuplicateFixGuard.isDuplicate(candidate, last)) {
            Log.i(TAG, "fix duplicado del fused descartado " +
                "(captured_at=${position.time.time} lat=${position.latitude} lon=${position.longitude})")
            return
        }
        // Se marca ANTES del insert asíncrono: dos entregas del mismo fix en
        // el mismo hilo verían el mismo "último" y ambas pasarían (el caso
        // real: seq 35/36 con el mismo ms). Si el insert fallara se perdería
        // un fix aislado; aceptado frente a duplicar sistemáticamente.
        lastStoredFix = candidate
        databaseHelper.insertPositionAsync(position, object : DatabaseHandler<Unit?> {
            override fun onComplete(success: Boolean, result: Unit?) {
                if (success) {
                    uploadQueue?.kick(isOnline)
                }
            }
        })
    }

    /**
     * Modo sin búfer (preferencia): envío directo legacy, sin identidad ni
     * reintento. Se conserva para no cambiar el contrato de esa preferencia;
     * el modo con búfer (default) es el que implementa la arquitectura.
     */
    private fun send(position: Position) {
        log("send", position)
        val request = formatRequest(url, position)
        // Wake lock SOLO durante el envío: antes era permanente y gastaba
        // 834 mAh/24 h; ahora la CPU se despierta para el POST y se suelta en
        // el callback (éxito o error), con timeout de seguridad de 60 s.
        if (wakeLockEnabled) SendWakeLock.acquire(context)
        runCatching {
            sendRequestAsync(request, object : RequestHandler {
                override fun onComplete(success: Boolean) {
                    if (wakeLockEnabled) SendWakeLock.release()
                    if (success) {
                        ConnectionState.noteSuccess(System.currentTimeMillis())
                    } else {
                        ConnectionState.noteFailure(System.currentTimeMillis())
                        StatusActivity.addMessage(context.getString(R.string.status_send_fail))
                    }
                }
            })
        }.onFailure {
            if (wakeLockEnabled) SendWakeLock.release()
            Log.w(TAG, "no se pudo encolar el envío", it)
        }
    }

    companion object {
        private val TAG = TrackingController::class.java.simpleName

        /**
         * Congelamiento de captura para el cierre limpio de sesión (solo
         * debug): mientras está activo, [onPositionUpdate] descarta los fixes
         * antes de almacenarlos. Lo pone [SessionCloser] al empezar y lo quita
         * al terminar (o al arrancar el servicio / entrar de nuevo).
         */
        @Volatile
        var captureFrozen: Boolean = false

        /** Revisión del vigilante de GPS (re-solicitud / respaldo AOSP). */
        private const val WATCHDOG_PERIOD_MS = 60_000L

        /** Pulso del IMU como input de la máquina (no decide solo). */
        private const val MOTION_CHECK_PERIOD_MS = 10_000L

        /** Refuerzo del fix por giro: uno solo, 3 s después del giro. */
        private const val TURN_REINFORCE_DELAY_MS = 3_000L

        /** Fixes del historial usados para reconstruir tras recreación. */
        private const val RESTORE_HISTORY = 20
    }

}
