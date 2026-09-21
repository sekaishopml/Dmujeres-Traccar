package com.dmujeres.traccar.tracking

import com.dmujeres.traccar.core.TrackingState

import android.content.Context
import android.util.Log
import com.dmujeres.traccar.R
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.data.OutboxRetentionPolicy
import com.dmujeres.traccar.data.PositionDao
import com.dmujeres.traccar.diagnostics.LinkState
import com.dmujeres.traccar.diagnostics.MqttLink
import com.dmujeres.traccar.health.TrackingHealthMonitor
import com.dmujeres.traccar.location.LocationEngine
import com.dmujeres.traccar.location.LocationEnginePolicy
import com.dmujeres.traccar.location.LocationSensorFusion
import com.dmujeres.traccar.outbox.OutboxCoordinator
import com.dmujeres.traccar.outbox.PendingAlertPolicy
import com.dmujeres.traccar.outbox.PositionOutboxDispatcher
import com.dmujeres.traccar.platform.Notifications
import com.dmujeres.traccar.platform.SentryLog
import com.dmujeres.traccar.recovery.RecoveryJournal
import com.dmujeres.traccar.sensors.GyroSensor
import com.dmujeres.traccar.sensors.MotionSensor
import com.dmujeres.traccar.sensors.SensorCoordinator
import com.dmujeres.traccar.transport.MqttManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * FASE R3: watchdog de 30 s extraído de `TrackingService` SIN cambio de
 * comportamiento. Es el latido de orquestación periódica:
 *
 * 1. salud (heartbeat local + subida async + confirmación de recovery),
 * 2. sensores (pausa del acelerómetro quieto) y elapsed de jornada,
 * 3. heartbeat de presencia si el fix está rancio,
 * 4. cola Room (conteo + más viejo) y estado del enlace en 4 dominios,
 * 5. reconexión MQTT / pausa-reanudación por saturación del outbox,
 * 6. alertas operativas (conexión perdida, batería baja) y wake de pantalla,
 * 7. goteo HTTP del outbox y cascada de estado ([TrackingStatePolicy]).
 *
 * Las DECISIONES puras viven en sus políticas (con tests); este bucle solo las
 * secuencia. Los umbrales temporales (30 s, 5 min, 10/15 min, 60 min) no
 * cambian.
 */
class TrackingWatchdog(
    private val context: Context,
    private val config: AppConfig,
    private val session: TrackingSessionController,
    private val engine: LocationEngine,
    private val health: TrackingHealthMonitor,
    private val outbox: OutboxCoordinator,
    private val notifier: TrackingNotificationController,
    private val sampler: DeviceTelemetrySampler,
    private val presence: PresenceController,
    private val sensors: SensorCoordinator,
    private val dao: () -> PositionDao,
    private val scopeProvider: () -> CoroutineScope,
    private val mqttProvider: () -> MqttManager?,
    /** Estado de salud consolidado ([com.dmujeres.traccar.health.HealthStateProvider]). */
    private val healthStateNow: () -> String,
    private val isStarted: () -> Boolean,
    private val isStopping: () -> Boolean,
    private val isCapturePaused: () -> Boolean,
    private val pauseCapture: () -> Unit,
    private val resumeCapture: () -> Unit,
    private val uploadHealthPending: suspend () -> Unit,
) {

    /** Última razón de la fusión de sensores (anti-spam de logs). */
    @Volatile private var lastFusionReason = ""

    @Volatile private var connectionUnavailableSince = 0L
    @Volatile private var lastConnectionAlertAt = 0L
    @Volatile private var lastBatteryAlertAt = 0L
    @Volatile private var wakeAlertActive = false
    @Volatile private var connectionAlertActive = false
    /** Throttle (1 h) del aviso de permiso "Permitir siempre" faltante. */
    @Volatile private var lastBackgroundPermAlertAt = 0L

    fun start() {
        scopeProvider().launch { loop() }
    }

    private suspend fun loop() {
        var lastHeartbeatAt = 0L
        var lastHealthSnapshotAt = 0L
        while (scopeProvider().isActive) {
            delay(30_000)
            if (!config.trackingEnabled) {
                notifier.setState(TrackingState.TRACKING_DISABLED_BY_USER)
                continue
            }
            val now = System.currentTimeMillis()
            // Anti-pasos-de-reloj: compara wall vs monotónico en la ventana del
            // tick (30 s) y apunta el anclaje para el siguiente.
            runCatching { session.maybeDetectClockStep() }
            // F0: embudo del bucket (movimiento por sensor) — el heartbeat de
            // 5 min lo consume y sube el delta con el snapshot.
            runCatching { health.tickFunnel(moving = sensors.motionStateName() == "MOVING", nowMs = now) }
            // Heartbeat local (CAPA 5): evidencia de proceso vivo con Room, sin
            // tráfico de red. Periódico y de bajo costo: 1 fila cada 5 min.
            if (now - lastHealthSnapshotAt >= TrackingHealthMonitor.SNAPSHOT_PERIOD_MS) {
                lastHealthSnapshotAt = now
                runCatching {
                    health.persistHeartbeat(
                        motion = sensors.motionStateName(),
                        network = config.netLabel,
                        healthState = healthStateNow(),
                    )
                }
                // FASE 7: subida async (no bloquea el watchdog si no hay red).
                scopeProvider().launch { runCatching { uploadHealthPending() } }
            }
            // Fase 6: verificación honesta del intento del guardián. Servicio
            // vivo + intento sin confirmar → "ok" UNA vez (marker persistido
            // KEY_RECOVERY_CONFIRM_AT evita repetir en cada tick).
            runCatching {
                if (RecoveryJournal.shouldConfirmOnServiceAlive(
                        trackingEnabled = config.trackingEnabled,
                        serviceRunning = isStarted(),
                        lastRecoveryAtMs = config.lastRecoveryAt,
                        confirmAtMs = config.recoveryConfirmAt,
                    )
                ) {
                    config.lastRecoveryResult = RecoveryJournal.RESULT_OK
                    config.recoveryConfirmAt = System.currentTimeMillis()
                    config.attemptsSinceLastSuccess = 0
                    Log.i(TAG, "RECOVERY_SUCCESS (servicio vivo tras intento del guardián)")
                    scopeProvider().launch {
                        runCatching {
                            health.persistCritical(
                                "RECOVERY_SUCCESS",
                                motion = sensors.motionStateName(),
                                network = config.netLabel,
                                healthState = healthStateNow(),
                            )
                        }
                    }
                }
            }
            // Acelerómetro: STATIONARY estable >= 5 min → desregistrar (batería).
            // 1.1.8: con jornada activa y GPS sin fix fresco NO se pausa — es la
            // única evidencia de movimiento mientras el GPS no entrega (rescate
            // R5). Un fix aceptado lo re-registra (re-register on fix).
            val fixStaleForSensors = System.currentTimeMillis() - config.lastFixAt > 5 * 60_000L
            if (config.journeyStartAt > 0L && fixStaleForSensors) {
                runCatching { sensors.reRegisterOnFix() }
            } else {
                runCatching { sensors.maybePauseIfStationary() }
            }
            // Heartbeat ≥30 s: refresca el par (elapsed monotónico, ancla wall)
            // aunque no lleguen fixes (GPS muerto/nocturno) para que la UI y un
            // hipotético proc nuevo partan de un estado fresco e inmune a NTP.
            if (isStarted() && !isStopping() && config.journeyStartAt > 0L) {
                session.persistJourneyElapsed()
            }
            if (now - lastHeartbeatAt > 60_000) {
                lastHeartbeatAt = now
                presence.heartbeat()
            }
            val pendingInfo = try {
                withContext(Dispatchers.IO) {
                    dao().count() to dao().oldestEnqueuedAt()
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
            val gpsWithoutFix = LocationEnginePolicy.gpsWithoutFix(
                nowMs = now,
                startedTrackingAtMs = session.startedTrackingAt,
                lastFixAtMs = lastFixAt,
                intervalSeconds = engine.effectiveIntervalSeconds(),
            )

            // Fase A: densidad constante — adaptación por batería deshabilitada.
            // Se mantiene telemetría de batería pero no se altera frecuencia de
            // captura.

            // FASE 8 (§9): fusión GPS + sensores como EVIDENCIA (nunca genera
            // coordenadas). Cuando el movimiento es probable y el GPS no está
            // sano, deja traza estructurada; la re-adquisición real la decide el
            // motor de ubicación con sus timeouts (sin hacks nuevos).
            runCatching {
                val fusion = LocationSensorFusion.fromTelemetry(
                    lastFixAtMs = config.lastFixAt,
                    nowMs = now,
                    accuracyM = null,
                    motionState = sensors.motionStateName(),
                    gyroAvailable = GyroSensor.available,
                    gyroState = GyroSensor.currentState().name,
                    speedMps = engine.lastFixSpeedMps,
                )
                if (fusion.reason != lastFusionReason) {
                    lastFusionReason = fusion.reason
                    Log.i(
                        TAG,
                        "SENSOR_FUSION gps=${fusion.gpsHealth} motion=${fusion.movement} " +
                            "reacquire=${fusion.reacquisitionRequired} reason=${fusion.reason}",
                    )
                    if (fusion.reacquisitionRequired) {
                        SentryLog.breadcrumb("gps", "fusion_reacquire", fusion.reason)
                    }
                }
            }

            // FASE 3: re-registro, recreación del cliente, GNSS forzado y polling
            // one-shot viven en [LocationEngine] con la misma semántica temporal
            // (2 min re-registro, >10 min sin callbacks → re-init máx 1/15 min,
            // fallback GPS_PROVIDER con main looper, alerta GNSS con throttle).
            val batteryNow = sampler.batteryLevel()
            runCatching {
                engine.onWatchdogTick(
                    nowMs = now,
                    startedTrackingAtMs = session.startedTrackingAt,
                    gpsWithoutFix = gpsWithoutFix,
                )
            }.onFailure { Log.w(TAG, "Tick del motor de ubicación falló", it) }

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
            val mgr = mqttProvider()
            val link = LinkState.current(
                transport = sampler.transport(),
                validatedInternet = sampler.isNetworkAvailable(),
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
                pauseCapture()
            } else if (isCapturePaused() &&
                com.dmujeres.traccar.data.BufferPausePolicy.shouldResume(pendingCount, retentionMax)
            ) {
                // R8: antes 80 % del tope = ~horas de captura apagada tras drenar.
                resumeCapture()
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
                        dao(),
                        PositionOutboxDispatcher.HttpTransport,
                        outbox.dispatchContext(),
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
                    context,
                    context.getString(R.string.wake_title),
                    context.getString(R.string.wake_mqtt_body),
                )
            } else if (!connectionUnavailable) {
                wakeAlertActive = false
            }
            // FASE 6 (§26): FINE y BACKGROUND son MANDATORIOS. Android 10+
            // puede revocar "Permitir siempre" tras una actualización o por el
            // OEM: sin ella el FGS pierde la ubicación al apagar pantalla. Se
            // avisa (throttle 1 h) y se marca el estado honesto.
            val fineGranted = sampler.hasFineLocation()
            val backgroundGranted = sampler.hasBackgroundLocation()
            if (!backgroundGranted && fineGranted && now - lastBackgroundPermAlertAt > 60 * 60_000L) {
                lastBackgroundPermAlertAt = now
                Notifications.alert(
                    context,
                    context.getString(R.string.background_permission_title),
                    context.getString(R.string.background_permission_body),
                )
            }
            notifier.setState(
                TrackingStatePolicy.next(
                    fineGranted = fineGranted,
                    backgroundGranted = backgroundGranted,
                    stopCaptureFull = config.bufferPolicy == AppConfig.POLICY_STOP_CAPTURE &&
                        pendingCount >= OutboxRetentionPolicy.effectiveMax(config.bufferMax),
                    gpsWithoutFix = gpsWithoutFix,
                    networkAvailable = networkAvailable,
                    mqttUnavailable = mqttUnavailable,
                    pendingWithoutAck = pendingWithoutAck,
                    batteryLow = DeviceTelemetryPolicy.isBatteryLow(batteryNow),
                ),
            )
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
                    context,
                    context.getString(R.string.connection_lost_title),
                    context.getString(R.string.connection_lost_body),
                    Notifications.CONNECTION_ALERT_ID,
                )
            }
        } else {
            connectionAlertActive = false
        }
        if (DeviceTelemetryPolicy.isBatteryLow(battery) &&
            (lastBatteryAlertAt == 0L || now - lastBatteryAlertAt >= 60 * 60_000L)
        ) {
            lastBatteryAlertAt = now
            Notifications.alert(
                context,
                context.getString(R.string.battery_low_title),
                context.getString(R.string.battery_low_body, battery),
                Notifications.BATTERY_ALERT_ID,
            )
        }
    }

    private companion object {
        const val TAG = "TrackingWatchdog"
    }
}
