package com.dmujeres.traccar.ui

/**
 * UX: acceso oculto a Diagnóstico con 5 toques consecutivos sobre el texto
 * "Actualización" (función avanzada de soporte; regla 16 del runbook).
 *
 * Pura y testeable: no abre nada por sí sola; el llamador decide con [triggered].
 * - Se reinicia si pasa demasiado tiempo entre toques (> [maxGapMs]).
 * - Se reinicia si la secuencia completa excede [windowMs].
 * - Nunca muestra mensajes técnicos en los toques 1-4.
 */
class DiagnosticTapPolicy(
    private val maxGapMs: Long = 1_200L,
    private val windowMs: Long = 4_000L,
    private val requiredTaps: Int = 5,
) {

    data class State(val count: Int, val firstTapAt: Long, val lastTapAt: Long)

    data class Result(val state: State?, val triggered: Boolean)

    fun registerTap(previous: State?, nowMs: Long): Result {
        val valid = previous != null &&
            nowMs - previous.lastTapAt in 1..maxGapMs &&
            nowMs - previous.firstTapAt <= windowMs
        val next = if (valid) previous!!.count + 1 else 1
        return if (next >= requiredTaps) {
            Result(null, triggered = true)
        } else {
            Result(
                State(
                    count = next,
                    firstTapAt = if (valid) previous!!.firstTapAt else nowMs,
                    lastTapAt = nowMs,
                ),
                triggered = false,
            )
        }
    }
}
