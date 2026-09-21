package com.dmujeres.traccar.location

/**
 * Último estado GNSS visto por el servicio (satélites en vista/usados en fix +
 * timestamp del último evento). Singleton thread-safe en memoria (no
 * SharedPreferences: los eventos llegan cada segundo y no deben hacer I/O).
 *
 * Lo lee la telemetría de `presence` (campos `gnssUsed`/`gnssTotal`, solo si
 * conocidos) y queda disponible para Diagnóstico sin tocar su Activity.
 */
object GnssState {

    @Volatile
    var satsUsed: Int? = null
        private set

    @Volatile
    var satsTotal: Int? = null
        private set

    /** Wall-clock (ms) del último `onSatelliteStatusChanged`, como `lastFixAt`. */
    @Volatile
    var lastEventAt: Long = 0L
        private set

    /**
     * R9: fallos del proveedor fused (Google) desde el arranque del servicio.
     * Un valor alto indica ROM/chip donde el fused no es fiable → el motor
     * pasa al GPS del sistema (AOSP) y la telemetría lo reporta al server.
     */
    @Volatile
    var fusedFailures: Int = 0
        private set

    @Synchronized
    fun noteFusedFailure() {
        fusedFailures += 1
    }

    @Synchronized
    fun resetFusedFailures() {
        fusedFailures = 0
    }

    @Synchronized
    fun update(used: Int, total: Int, eventAtMs: Long) {
        satsUsed = used
        satsTotal = total
        lastEventAt = eventAtMs
    }

    @Synchronized
    fun reset() {
        satsUsed = null
        satsTotal = null
        lastEventAt = 0L
    }

    fun hasData(): Boolean = lastEventAt > 0L && satsTotal != null && satsUsed != null
}
