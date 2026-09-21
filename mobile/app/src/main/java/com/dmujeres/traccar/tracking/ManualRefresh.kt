package com.dmujeres.traccar.tracking

import kotlinx.coroutines.sync.Mutex

/**
 * UX: refresco manual de estado ("ACTUALIZAR"). NO reinicia nada: reutiliza los
 * mecanismos existentes (servicio, GPS, MQTT, Outbox, RemoteConfig, health) y
 * devuelve un resultado sencillo para la UI.
 *
 * Reglas duras (regla 6 del prompt):
 *  - nunca mata el proceso ni detiene el TrackingService,
 *  - nunca borra Room/Outbox ni cierra la jornada,
 *  - nunca crea una segunda jornada ni duplica posiciones,
 *  - sin ACK real no marca nada como enviado (lo garantiza el Outbox existente).
 *
 * Single-flight: una ejecución simultánea devuelve [RefreshOutcome.ALREADY_RUNNING].
 */
enum class RefreshOutcome {
    UPDATED,
    SYNCED_PENDING,
    OFFLINE_KEEPING_DATA,
    NO_JOURNEY,
    GPS_SEARCHING,
    ALREADY_RUNNING,
}

data class RefreshResult(val outcome: RefreshOutcome, val pending: Int = 0)

data class RefreshTrackingState(
    val hasJourney: Boolean,
    val resumed: Boolean,
    val alreadyRunning: Boolean,
)

/** Puertos reales (Android) o fakes (tests JVM). Reutilizan mecanismos existentes. */
interface RefreshPort {
    suspend fun isNetworkAvailable(): Boolean
    suspend fun ensureTracking(): RefreshTrackingState
    suspend fun syncConfig(): Boolean
    suspend fun nudgeGps(): Boolean
    suspend fun checkMqtt(): Boolean
    suspend fun drainOutboxSignal(): Int
    suspend fun sendHealth(): Boolean
    suspend fun requestServerState(): Boolean
}

class ManualRefreshCoordinator(private val port: RefreshPort) {

    private val gate = Mutex()

    suspend fun refresh(): RefreshResult {
        if (!gate.tryLock()) {
            return RefreshResult(RefreshOutcome.ALREADY_RUNNING)
        }
        try {
            val network = port.isNetworkAvailable()
            val tracking = port.ensureTracking()
            val gpsOk = port.nudgeGps()

            if (!tracking.hasJourney) {
                // Regla 9: sin jornada NO se inicia nada automáticamente.
                return RefreshResult(RefreshOutcome.NO_JOURNEY, pending = port.drainOutboxSignal())
            }

            if (!network) {
                // Regla 12: sin servidor no se miente. El Outbox conserva todo.
                return RefreshResult(RefreshOutcome.OFFLINE_KEEPING_DATA, pending = port.drainOutboxSignal())
            }

            val configOk = port.syncConfig()
            val mqttOk = port.checkMqtt()
            val pending = port.drainOutboxSignal()
            port.sendHealth()
            val serverOk = port.requestServerState()
            if (!configOk && !mqttOk && !serverOk) {
                return RefreshResult(RefreshOutcome.OFFLINE_KEEPING_DATA, pending)
            }
            if (!gpsOk) {
                return RefreshResult(RefreshOutcome.GPS_SEARCHING, pending)
            }
            return if (pending > 0) {
                RefreshResult(RefreshOutcome.SYNCED_PENDING, pending)
            } else {
                RefreshResult(RefreshOutcome.UPDATED, 0)
            }
        } finally {
            gate.unlock()
        }
    }
}
