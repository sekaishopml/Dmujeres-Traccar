package org.traccar.client

import kotlin.math.max

/**
 * Consistencia de un fix (puro, testeable en JVM).
 *
 * Causa real de los "saltos" en el replay: el proveedor de red devuelve de vez
 * en cuando una posición equivocada con precisión declarada buena (p. ej.
 * 52 m en 6 s = 31 km/h implícitos mientras el equipo reportaba 3.5 km/h).
 * El cliente oficial no lo detecta: solo mira intervalo/distancia/ángulo.
 *
 * Regla: si el desplazamiento es considerable, la velocidad implícita no puede
 * superar la reportada por un factor + margen. Movimiento real (moto a 60 km/h)
 * pasa siempre; el salto falso se descarta.
 */
object PositionConsistencyPolicy {

    /** Por debajo de esta pata (m) no se juzga: es jitter normal. */
    const val MIN_LEG_M = 25.0

    /** Margen sobre la velocidad reportada. */
    const val FACTOR = 2.5
    const val MARGIN_MPS = 4.0

    fun isConsistent(legMeters: Double, dtSeconds: Double, reportedSpeedMps: Double): Boolean {
        if (legMeters < MIN_LEG_M) return true
        if (dtSeconds <= 0.0) return true
        val implied = legMeters / dtSeconds
        val reported = max(reportedSpeedMps, 0.0)
        return implied <= reported * FACTOR + MARGIN_MPS
    }
}
