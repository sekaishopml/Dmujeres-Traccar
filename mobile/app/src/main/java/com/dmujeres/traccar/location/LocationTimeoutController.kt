package com.dmujeres.traccar.location

/**
 * Controlador de tiempos del motor de ubicación (FASE 3): mantiene el estado
 * temporal (re-solicitud, recreación del cliente, GNSS forzado, aviso sin
 * satélites) y delega la decisión en [LocationEnginePolicy] (pura y testeada).
 *
 * Extraído de `TrackingService` SIN cambio de comportamiento: los contadores
 * viven aquí con exactamente la misma semántica (se SiNCRONIZAN solo cuando
 * ocurre la acción; `noteFixRecovered()` replica el reseteo de
 * `lastGpsReregisterAt = 0` cuando vuelve el fix).
 */
class LocationTimeoutController {

    @Volatile var lastReregisterAtMs: Long = 0L
        private set
    @Volatile var lastEngineReinitAtMs: Long = 0L
        private set
    @Volatile var lastNoGpsAlertAtMs: Long = 0L
        private set

    /** GNSS forzado: sin fix fresco sostenido se re-solicita y se engancha GPS_PROVIDER. */
    @Volatile var gnssForced: Boolean = false
        private set

    fun gpsWithoutFix(
        nowMs: Long,
        startedTrackingAtMs: Long,
        lastFixAtMs: Long,
        intervalSeconds: Long,
    ): Boolean = LocationEnginePolicy.gpsWithoutFix(nowMs, startedTrackingAtMs, lastFixAtMs, intervalSeconds)

    /** true una vez por ventana de 2 min; el llamador debe llamar [noteReregister]. */
    fun shouldReregister(nowMs: Long): Boolean =
        LocationEnginePolicy.shouldReregister(nowMs, lastReregisterAtMs)

    fun noteReregister(nowMs: Long) {
        lastReregisterAtMs = nowMs
    }

    /** Fix recuperado: réplica de `lastGpsReregisterAt = 0` del watchdog. */
    fun noteFixRecovered() {
        lastReregisterAtMs = 0L
    }

    fun shouldReinitEngine(nowMs: Long, lastCallbackAtMs: Long): Boolean =
        LocationEnginePolicy.shouldReinitEngine(nowMs, lastCallbackAtMs, lastEngineReinitAtMs)

    fun noteEngineReinit(nowMs: Long) {
        lastEngineReinitAtMs = nowMs
    }

    /**
     * GNSS forzado: entra si no estaba forzado; al estar forzado el llamador
     * registra el fallback directo de GPS_PROVIDER.
     */
    fun enterGnssForce(): Boolean {
        if (gnssForced) return false
        gnssForced = true
        return true
    }

    /** Fix fresco recuperado: réplica de `gnssForced = false` + unregister. */
    fun exitGnssForce(): Boolean {
        if (!gnssForced) return false
        gnssForced = false
        return true
    }

    fun noGnssAlertDue(
        nowMs: Long,
        startedTrackingAtMs: Long,
        gnssHasData: Boolean,
        gnssUsed: Int?,
    ): Boolean = LocationEnginePolicy.noGnssAlertDue(
        nowMs, startedTrackingAtMs, lastNoGpsAlertAtMs, gnssHasData, gnssUsed,
    )

    fun noteNoGnssAlert(nowMs: Long) {
        lastNoGpsAlertAtMs = nowMs
    }
}
