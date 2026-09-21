package com.dmujeres.traccar.readiness

import com.dmujeres.traccar.recovery.RecoveryJournal

/**
 * Política PURA (JVM) de la prueba de RECUPERACIÓN del readiness: es
 * OBSERVACIONAL (nunca se mata el proceso artificialmente). Lee el estado
 * persistido del mecanismo real de recuperación (SessionKeeper + RecoveryJournal)
 * y emite un veredicto honesto basado solo en evidencia.
 *
 * Es el respaldo del sistema: NUNCA sustituye a la continuidad
 * (CONTINUITY_FAIL + RECOVERY_PASS = DEVICE_READY NO).
 */
object RecoveryTestPolicy {

    /** Resultado observacional de la recuperación (usa los códigos de RecoveryJournal). */
    data class Outcome(
        val state: String, // PASS | FAILED | NOT_RUN
        val attempts: Int,
        val note: String,
    )

    private const val STATE_PASS = "PASS"
    private const val STATE_FAILED = "FAILED"
    private const val STATE_NOT_RUN = "NOT_RUN"

    /**
     * @param lastRecoveryResult constante de RecoveryJournal: RESULT_OK ("ok"),
     *        RESULT_BLOCKED ("blocked"), RESULT_PENDING ("ok-pending") o ""
     * @param attemptsSinceLastSuccess intentos registrados (>=0)
     * @param serviceRunning el FGS está vivo ahora
     * @param journeyActive jornada activa ahora
     */
    fun evaluate(
        lastRecoveryResult: String,
        attemptsSinceLastSuccess: Int,
        serviceRunning: Boolean,
        journeyActive: Boolean,
    ): Outcome = when {
        // Respaldo verificado: el guardián revivió el servicio y este confirmó.
        lastRecoveryResult == RecoveryJournal.RESULT_OK && serviceRunning ->
            Outcome(
                state = STATE_PASS,
                attempts = attemptsSinceLastSuccess,
                note = "recuperación verificada por RecoveryJournal",
            )
        // Bloqueo verificado con servicio muerto y jornada activa: posible OEM.
        lastRecoveryResult == RecoveryJournal.RESULT_BLOCKED &&
            !serviceRunning && journeyActive ->
            Outcome(
                state = STATE_FAILED,
                attempts = attemptsSinceLastSuccess,
                note = "RECOVERY_BLOCKED: el guardián no pudo revivir (posible OEM)",
            )
        // Bloqueo verificado aunque el servicio esté vivo ahora (estado honesto:
        // el último intento fue bloqueado; la vida actual no borra la evidencia).
        lastRecoveryResult == RecoveryJournal.RESULT_BLOCKED && serviceRunning ->
            Outcome(
                state = STATE_FAILED,
                attempts = attemptsSinceLastSuccess,
                note = "bloqueado previo; servicio vivo ahora",
            )
        // PENDING o vacío: no hay muerte real verificada todavía.
        else ->
            Outcome(
                state = STATE_NOT_RUN,
                attempts = attemptsSinceLastSuccess,
                note = "sin muerte real observada",
            )
    }

    /** Veredicto integrado para el readiness: la recuperación NUNCA sustituye la continuidad. */
    fun riskNote(outcome: Outcome): String = when (outcome.state) {
        STATE_FAILED -> "riesgo: recuperación bloqueada (respaldo degradado)"
        STATE_PASS -> "respaldo de recuperación verificado"
        else -> "respaldo no ejercitado"
    }
}
