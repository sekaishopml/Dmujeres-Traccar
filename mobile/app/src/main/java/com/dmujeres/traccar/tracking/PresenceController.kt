package com.dmujeres.traccar.tracking

import android.util.Log
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.data.PendingPosition
import com.dmujeres.traccar.data.PositionDao
import com.dmujeres.traccar.transport.Envelope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * FASE R3: encolado de señales de presencia extraído de `TrackingService` SIN
 * cambio de comportamiento. Comparte el `enqueueMutex` del servicio con las
 * posiciones (una sola serialización de secuencia) y delega el despertar de
 * MQTT y el refresco de estado por callbacks.
 *
 * La condición de encolado es [PresencePolicy] (pura, caracterizada).
 */
class PresenceController(
    private val config: AppConfig,
    private val dao: () -> PositionDao,
    private val sampler: DeviceTelemetrySampler,
    /** MISMA instancia que usa el pipeline de posiciones (nunca una nueva). */
    private val enqueueMutex: Mutex,
    private val scopeProvider: () -> CoroutineScope,
    private val isStarted: () -> Boolean,
    private val isStopping: () -> Boolean,
    /** Despierta el dispatch MQTT de la sesión viva. */
    private val wakeMqtt: () -> Unit,
    /** Refresco de notificación/estado tras encolar. */
    private val onEnqueued: () -> Unit,
    /** Intervalo efectivo de captura (LocationEngine) para el umbral de heartbeat. */
    private val effectiveIntervalSeconds: () -> Long,
) {

    /**
     * Encola una señal de presencia con la misma política y secuencia que una
     * posición. `targetScope`/`wake` se capturan al cierre de jornada para no
     * tocar la sesión nueva.
     */
    fun enqueue(
        journeyStatus: String? = null,
        targetScope: CoroutineScope = scopeProvider(),
        wake: () -> Unit = wakeMqtt,
    ): Job = targetScope.launch {
        try {
            enqueueMutex.withLock {
                val deviceId = config.deviceId
                if (deviceId.isBlank()) return@withLock
                if (!PresencePolicy.shouldEnqueue(journeyStatus, isStarted(), isStopping())) return@withLock
                val sequence = withContext(Dispatchers.IO) {
                    dao().nextSequence(config.sequence)
                }
                config.sequence = sequence
                val messageId = Envelope.newMessageId(deviceId, sequence)
                val telemetry = sampler.telemetry()
                sampler.noteReportedNetwork(telemetry.network)
                sampler.persistNetState(telemetry.shot, telemetry.cause)
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
                    // R9: toque del botón "Actualizar" (auditable en el panel).
                    otaManualPressedAt = config.otaManualPressedAt.takeIf { it > 0 } ?: 0L,
                    fusedFailures = telemetry.fusedFailures,
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
                    dao().insertWithinLimit(pending, config.bufferMax)
                }
                if (inserted < 0) {
                    Log.w(TAG, "No hay espacio para encolar presencia $journeyStatus")
                    return@withLock
                }
                config.lastEnqueuedAt = maxOf(config.lastEnqueuedAt, System.currentTimeMillis())
                wake.invoke()
            }
            onEnqueued()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo guardar la presencia en Room", e)
        }
    }

    /** Si no hay fix de GPS reciente (parking interior/plaza), envía 'presence' con telemetría. */
    fun heartbeat() {
        if (!isStarted() || isStopping()) return
        val deviceId = config.deviceId
        if (deviceId.isBlank()) return
        val fixStaleAfter = NetworkStatePolicy.fixStaleAfterMs(effectiveIntervalSeconds())
        val lastFixAt = config.lastFixAt
        val hasRecentFix = lastFixAt > 0 && System.currentTimeMillis() - lastFixAt <= fixStaleAfter
        if (hasRecentFix) return
        enqueue()
    }

    private companion object {
        const val TAG = "PresenceController"
    }
}
