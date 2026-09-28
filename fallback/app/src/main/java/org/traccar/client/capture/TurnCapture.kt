package org.traccar.client.capture

/**
 * Captura en giro por rumbo GPS (pura, testeable en JVM).
 *
 * Con cadencia de 10 s las esquinas se cortan (a 15 km/h son ~40 m entre
 * fixes). Si el rumbo cambia ≥30° respecto al último ALMACENADO, el fix se
 * guarda de inmediato aunque el colapso en parado lo tiraría: la esquina queda
 * marcada. Limitado a un extra cada ≥3 s para que una rotonda no genere una
 * ráfaga.
 *
 * Antirruido: quieto el rumbo del GPS salta aleatoriamente, así que solo
 * cuenta en marcha (velocidad ≥2 kn, ≈3,7 km/h: cubre caminata y vehículo, deja
 * fuera el parado). Complementa al detector de giros por giroscopio (que pide
 * el fix inmediato): este garantiza que, una vez entregado, SE ALMACENA.
 */
object TurnCapture {

    /** Cambio de rumbo que marca esquina (grados). */
    const val ANGLE_DEG = 30.0

    /** Enfriamiento mínimo entre extras de giro (ms). */
    const val MIN_INTERVAL_MS = 3_000L

    /** Velocidad mínima para que el rumbo sea creíble (nudos, ≈3,7 km/h). */
    const val MIN_SPEED_KN = 2.0

    /**
     * ¿El fix marca un giro que obliga a almacenar?
     *
     * @param msSinceLastExtra tiempo desde el último extra de giro (para el
     * enfriamiento; pasar [Long.MAX_VALUE] si nunca hubo uno).
     */
    fun isTurn(
        lastCourseDeg: Double,
        candidateCourseDeg: Double,
        candidateSpeedKn: Double,
        msSinceLastExtra: Long,
        hasReference: Boolean,
    ): Boolean {
        if (!hasReference) return false
        if (msSinceLastExtra < MIN_INTERVAL_MS) return false
        if (candidateSpeedKn < MIN_SPEED_KN) return false
        return Geo.bearingDiffDeg(lastCourseDeg, candidateCourseDeg) >= ANGLE_DEG
    }
}
