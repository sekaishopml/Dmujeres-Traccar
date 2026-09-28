package org.traccar.client.capture

/**
 * Colapso en parado (puro, testeable en JVM).
 *
 * Con el equipo detenido el GPS deriva (±25 m) y cada fix se almacenaba punto
 * a punto (caso real: 17:14-17:17 y 17:20-17:23, velocidades 0.0). En quietud
 * solo se almacena si hay avance real desde el último almacenado (≥15 m) o si
 * pasó el latido (≥5 min): el ruido parado deja de guardarse, pero el punto
 * vivo sigue existiendo (latido) y un arranque real (≥15 m) se guarda.
 *
 * Cuándo aplica: estado STATIONARY, o velocidad ~0 sostenida aunque la máquina
 * aún esté en ACTIVE (p. ej. semáforo de 2 min: aún no hay 3 min de evidencia
 * para STATIONARY pero ya no hace falta guardar cada fix). Un pico aislado de
 * velocidad 0 no colapsa: la quietud debe sostenerse 60 s.
 *
 * Nunca inventa ni mueve puntos: solo decide guardar o no el fix tal cual.
 */
object StationaryCollapse {

    /** Avance real desde el último almacenado que siempre se guarda (m). */
    const val MIN_DISTANCE_M = 15.0

    /** Latido en parado: aunque no haya avance, un punto cada 5 min. */
    const val HEARTBEAT_MS = 5 * 60_000L

    /** Velocidad reportada que cuenta como "~0" (nudos, ≈1,85 km/h). */
    const val STILL_SPEED_KN = 1.0

    /** Cuánto debe sostenerse el ~0 en ACTIVE para colapsar (ms). */
    const val STILL_SUSTAINED_MS = 60_000L

    /**
     * ¿Se almacena el fix?
     *
     * @param stationary la máquina ya declaró STATIONARY.
     * @param stillSustained velocidad ~0 sostenida (la calcula [CaptureGate]).
     * @param speedKn velocidad reportada del fix actual.
     * @param distFromStoredM desplazamiento desde el último ALMACENADO.
     * @param msSinceStored tiempo desde el último ALMACENADO.
     * @param hasReference false en el primer fix (sin referencia se guarda).
     */
    fun shouldStore(
        stationary: Boolean,
        stillSustained: Boolean,
        speedKn: Double,
        distFromStoredM: Double,
        msSinceStored: Long,
        hasReference: Boolean,
    ): Boolean {
        if (!hasReference) return true
        val quiet = stationary || (stillSustained && speedKn < STILL_SPEED_KN)
        if (!quiet) return true
        if (distFromStoredM >= MIN_DISTANCE_M) return true
        if (msSinceStored >= HEARTBEAT_MS) return true
        return false
    }
}
