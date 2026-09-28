package org.traccar.client.capture

/**
 * Guardia de teleport (pura, testeable en JVM).
 *
 * Rechaza el fix cuando implica una velocidad imposible contra el último fix
 * aceptado fresco (<60 s): es rechazo de error, no pérdida de dato (el punto
 * falso nunca debió existir). Sin referencia o con referencia vieja se acepta:
 * sin contexto no se puede juzgar.
 *
 * Dos sub-reglas:
 * - T1 velocidad absurda: la implícita supera 200 km/h.
 * - T2 incoherencia en parado: el GPS reporta ~0 pero la posición saltó rápido
 *   (caso real dispositivo 50, 17:18:02: ~70 m en 13 s con velocidad 0.0, unos
 *   19 km/h que no llegan a 200 km/h pero son imposibles quieto). Sin esta
 *   sub-regla ese salto se almacenaría tal cual.
 *
 * Excepción común (la precisión lo justifica): si el salto cabe dentro del
 * elipsoide de error combinado (accuracy nueva + última), no es un teleport,
 * es ruido con mala señal: se acepta y lo tratan el resto de filtros
 * (colapso en parado). Con accuracy desconocida (0.0, el GPS del sistema no la
 * informa) no hay justificación posible y manda el rechazo.
 */
object TeleportGuard {

    /** Velocidad implícita máxima creíble: 200 km/h en m/s. */
    const val MAX_SPEED_MPS = 55.56

    /** Solo se juzga contra referencia fresca (<60 s). */
    const val FRESH_WINDOW_MS = 60_000L

    /** Velocidad reportada que cuenta como "~0" (nudos, ≈1,85 km/h). */
    const val STILL_SPEED_KN = 1.0

    /**
     * Implícita mínima para la incoherencia en parado (km/h): muy por encima
     * de la caminata (2-8 km/h) y muy por debajo de 200 km/h. El salto real de
     * 70 m en 13 s (≈19,4 km/h con vel 0) cae aquí.
     */
    const val INCOHERENT_MIN_KMH = 15.0

    /** Último fix aceptado (pasó el teleport, se haya almacenado o no). */
    data class Reference(
        val capturedAtMs: Long,
        val lat: Double,
        val lon: Double,
        val accuracyM: Double,
    )

    /** Fix candidato a juzgar. */
    data class Candidate(
        val capturedAtMs: Long,
        val lat: Double,
        val lon: Double,
        val accuracyM: Double,
        val speedKn: Double,
    )

    enum class Verdict { ACCEPT, REJECT }

    fun evaluate(candidate: Candidate, last: Reference?): Verdict {
        if (last == null) return Verdict.ACCEPT
        val dtMs = candidate.capturedAtMs - last.capturedAtMs
        // Sin orden temporal o referencia vieja no se juzga: se acepta.
        if (dtMs <= 0L || dtMs > FRESH_WINDOW_MS) return Verdict.ACCEPT
        val distM = Geo.distanceM(last.lat, last.lon, candidate.lat, candidate.lon)
        if (distM <= 0.0) return Verdict.ACCEPT
        val impliedMps = distM / (dtMs / 1000.0)
        val absurd = impliedMps > MAX_SPEED_MPS
        val incoherent = !absurd &&
            candidate.speedKn < STILL_SPEED_KN &&
            impliedMps * 3.6 > INCOHERENT_MIN_KMH
        if (!absurd && !incoherent) return Verdict.ACCEPT
        // La precisión lo justifica: el salto cabe en el error combinado.
        if (candidate.accuracyM > 0.0 && last.accuracyM > 0.0 &&
            distM <= candidate.accuracyM + last.accuracyM
        ) {
            return Verdict.ACCEPT
        }
        return Verdict.REJECT
    }
}
