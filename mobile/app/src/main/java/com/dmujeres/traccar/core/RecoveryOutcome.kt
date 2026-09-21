package com.dmujeres.traccar.core

/**
 * FASE R4: veredicto persistido del pipeline de recuperación (mismo contrato
 * que `RecoveryJournal.RESULT_*`). Vive en `core` para que la capa de
 * preferencias (`config`) no dependa del paquete `recovery`.
 */
object RecoveryOutcome {
    const val OK = "ok"
    const val BLOCKED = "blocked"
    const val PENDING = "ok-pending"
    const val UNKNOWN = "unknown"

    /**
     * Regla de histéresis al registrar un veredicto nuevo: un "blocked"
     * NUNCA se pisa con un resultado no confirmado (solo un "ok" lo limpia).
     */
    fun afterAttempt(previousResult: String?, newResult: String): String =
        if (previousResult == BLOCKED && newResult == PENDING) BLOCKED else newResult
}
