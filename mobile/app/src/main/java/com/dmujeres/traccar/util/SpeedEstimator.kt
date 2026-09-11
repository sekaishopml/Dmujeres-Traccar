package com.dmujeres.traccar.util

/**
 * Velocidad efectiva cuando el Doppler del teléfono miente.
 *
 * Medido en campo (ZTE Z2450): el Doppler reporta 0 m/s aun en marcha en
 * ~45% de los fixes. Sin fallback, la detección de movimiento, el intervalo
 * adaptativo y la velocidad mostrada quedan rotas. La geometría (distancia
 * entre fixes consecutivos / dt) no miente: se usa como respaldo cuando el
 * Doppler está en 0 o ausente.
 */
object SpeedEstimator {

    /** Doppler por encima de esto se considera medición real (no ruido). */
    const val DOPPLER_TRUST_MPS = 0.5f

    /** Implícita por encima de esto con Doppler en 0 = Doppler atascado. */
    const val STUCK_IMPL_MIN_MPS = 5f

    /** Fixes consecutivos así antes de declarar el atasco (evita un salto aislado). */
    const val STUCK_CONSECUTIVE = 3

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * earthRadius * Math.asin(minOf(1.0, Math.sqrt(a)))
    }

    /** Velocidad implícita (m/s) entre dos fixes; null si no hay dt válido. */
    fun impliedMps(
        lat1: Double, lon1: Double, timeMs1: Long,
        lat2: Double, lon2: Double, timeMs2: Long,
    ): Float? {
        val dtSeconds = (timeMs2 - timeMs1) / 1000.0
        if (!dtSeconds.isFinite() || dtSeconds <= 0) {
            return null
        }
        val dist = haversineMeters(lat1, lon1, lat2, lon2)
        if (!dist.isFinite()) {
            return null
        }
        return (dist / dtSeconds).toFloat()
    }

    /**
     * Velocidad efectiva: Doppler si es creíble (> umbral), si no la implícita,
     * si no hay nada null. En parado ambas tienden a ~0 (el jitter de 10 s no
     * llega a los 5 m/s que exige el modo MOVING), así que es seguro para la
     * detección de movimiento.
     */
    fun effectiveMps(dopplerMps: Float?, impliedMps: Float?): Float? {
        if (dopplerMps != null && dopplerMps.isFinite() && dopplerMps > DOPPLER_TRUST_MPS) {
            return dopplerMps
        }
        if (impliedMps != null && impliedMps.isFinite() && impliedMps >= 0f) {
            return impliedMps
        }
        return null
    }
}
