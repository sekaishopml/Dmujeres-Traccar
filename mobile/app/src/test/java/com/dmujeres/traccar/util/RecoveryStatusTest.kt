package com.dmujeres.traccar.util

import com.dmujeres.traccar.util.RecoveryJournal.RESULT_BLOCKED
import com.dmujeres.traccar.util.RecoveryJournal.RESULT_OK
import com.dmujeres.traccar.util.RecoveryJournal.RESULT_PENDING
import com.dmujeres.traccar.util.RecoveryJournal.PreviousVerdict
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests JVM de RecoveryJournal.classifyRecovery (cada rama + histéresis) y de
 * la verificación honesta (verdictForPreviousAttempt / shouldConfirm).
 */
class RecoveryStatusTest {

    private val now = 1_800_000_000_000L

    private fun classify(
        journeyActive: Boolean,
        isRunning: Boolean,
        attempts24h: Int = 0,
        lastOutcome: String? = "",
        lastRecoveryAtMs: Long = 0L,
        nowMs: Long = now,
    ): RecoveryStatus =
        RecoveryJournal.classifyRecovery(
            journeyActive = journeyActive,
            isRunning = isRunning,
            attempts24h = attempts24h,
            lastOutcome = lastOutcome,
            nowMs = nowMs,
            lastRecoveryAtMs = lastRecoveryAtMs,
        ).status

    @Test
    fun r1_sinJornadaNadaQueRecuperar() {
        // Jornada cerrada: sin riesgo aunque el servicio esté muerto y el
        // último veredicto haya sido blocked.
        assertEquals(
            RecoveryStatus.TRACKING_ACTIVE,
            classify(
                journeyActive = false,
                isRunning = false,
                attempts24h = 3,
                lastOutcome = RESULT_BLOCKED,
                lastRecoveryAtMs = now - 90 * 60_000L,
            ),
        )
    }

    @Test
    fun r2_jornadaActivaConServicioVivo() {
        assertEquals(
            RecoveryStatus.TRACKING_ACTIVE,
            classify(true, isRunning = true, lastOutcome = RESULT_PENDING, lastRecoveryAtMs = now - 60_000L),
        )
    }

    @Test
    fun r3_veredictoBlockedGanaSobreFrescura() {
        // El veredicto "blocked" persiste aunque el intento sea viejo (> 20 min):
        // es la evidencia de posible bloqueo OEM hasta nueva confirmación.
        assertEquals(
            RecoveryStatus.RECOVERY_BLOCKED_BY_OEM,
            classify(true, isRunning = false, lastOutcome = RESULT_BLOCKED, lastRecoveryAtMs = now - 100 * 60_000L),
        )
        assertEquals(
            RecoveryStatus.RECOVERY_BLOCKED_BY_OEM,
            classify(true, isRunning = false, lastOutcome = RESULT_BLOCKED, lastRecoveryAtMs = now - 60_000L),
        )
    }

    @Test
    fun r4_intentoRecienteEsPending() {
        // Intento lanzado hace 5 min sin confirmar: el FGS puede consolidar aún.
        assertEquals(
            RecoveryStatus.RECOVERY_PENDING,
            classify(true, isRunning = false, lastOutcome = RESULT_PENDING, lastRecoveryAtMs = now - 5 * 60_000L),
        )
    }

    @Test
    fun r5_bordeHisteresis20Min() {
        // Histéresis: < 20 min → PENDING; exactamente 20 min → ya no (MISSING).
        assertEquals(
            RecoveryStatus.RECOVERY_PENDING,
            classify(true, isRunning = false, lastOutcome = RESULT_PENDING, lastRecoveryAtMs = now - (20 * 60_000L - 1)),
        )
        assertEquals(
            RecoveryStatus.SERVICE_MISSING,
            classify(true, isRunning = false, lastOutcome = RESULT_PENDING, lastRecoveryAtMs = now - 20 * 60_000L),
        )
    }

    @Test
    fun r6_sinIntentoOIntentoViejoEsServiceMissing() {
        // Jornada activa, servicio muerto, sin intentos registrados.
        assertEquals(
            RecoveryStatus.SERVICE_MISSING,
            classify(true, isRunning = false, lastOutcome = "", lastRecoveryAtMs = 0L),
        )
        // Intento viejo (> 20 min) sin veredicto de bloqueo: el guardián dejó
        // de disparar (posible OEM saltando la alarma) → MISSING.
        assertEquals(
            RecoveryStatus.SERVICE_MISSING,
            classify(
                true,
                isRunning = false,
                attempts24h = 2,
                lastOutcome = RESULT_PENDING,
                lastRecoveryAtMs = now - 30 * 60_000L,
            ),
        )
    }

    @Test
    fun r7_histéresisSecuenciaCompleta() {
        // Secuencia honesta de una recuperación: intento → pendiente → blocked
        // → (servicio vivo) → OK.
        var outcome: String = ""
        var attemptAt = 0L
        fun statusAt(nowMs: Long): RecoveryStatus =
            RecoveryJournal.classifyRecovery(true, false, 1, outcome, nowMs, attemptAt).status

        attemptAt = now
        outcome = RecoveryJournal.RESULT_PENDING
        assertEquals(RecoveryStatus.RECOVERY_PENDING, statusAt(now + 5 * 60_000L))
        // Fire siguiente: sigue muerto dentro de la ventana → blocked.
        assertEquals(RecoveryStatus.RECOVERY_PENDING, statusAt(now + 15 * 60_000L))
        outcome = RESULT_BLOCKED
        assertEquals(RecoveryStatus.RECOVERY_BLOCKED_BY_OEM, statusAt(now + 16 * 60_000L))
        // Servicio vivo de nuevo: TODO se limpia a TRACKING_ACTIVE.
        assertEquals(
            RecoveryStatus.TRACKING_ACTIVE,
            RecoveryJournal.classifyRecovery(true, true, 2, outcome, now + 16 * 60_000L, now + 16 * 60_000L).status,
        )
        assertEquals(
            RecoveryStatus.TRACKING_ACTIVE,
            RecoveryJournal.classifyRecovery(true, true, 2, RESULT_OK, now + 16 * 60_000L, now + 16 * 60_000L).status,
        )
    }

    @Test
    fun r8_verdictForPreviousAttemptSucedeBloqueado() {
        // Sin jornada o sin intento previo → nada que evaluar.
        assertEquals(PreviousVerdict.NONE, RecoveryJournal.verdictForPreviousAttempt(false, false, now, 0L, ""))
        assertEquals(PreviousVerdict.NONE, RecoveryJournal.verdictForPreviousAttempt(true, false, now, 0L, RESULT_PENDING))
        // Servicio vivo con intento pendiente → SUCCESS (consolidó).
        assertEquals(
            PreviousVerdict.SUCCESS,
            RecoveryJournal.verdictForPreviousAttempt(true, true, now, now - 15 * 60_000L, RESULT_PENDING),
        )
        // Servicio vivo con veredicto "ok" (ya confirmado por el watchdog) → NONE.
        assertEquals(
            PreviousVerdict.NONE,
            RecoveryJournal.verdictForPreviousAttempt(true, true, now, now - 15 * 60_000L, RESULT_OK),
        )
        // Servicio muerto con intento pendiente <= 20 min → BLOCKED (posible OEM).
        assertEquals(
            PreviousVerdict.BLOCKED,
            RecoveryJournal.verdictForPreviousAttempt(true, false, now, now - 15 * 60_000L, RESULT_PENDING),
        )
        // Servicio muerto con intento ya marcado blocked → NONE (no repetir).
        assertEquals(
            PreviousVerdict.NONE,
            RecoveryJournal.verdictForPreviousAttempt(true, false, now, now - 15 * 60_000L, RESULT_BLOCKED),
        )
        // Servicio muerto con intento viejo (> 20 min, alarma no disparó) → NONE.
        assertEquals(
            PreviousVerdict.NONE,
            RecoveryJournal.verdictForPreviousAttempt(true, false, now, now - 45 * 60_000L, RESULT_PENDING),
        )
    }

    @Test
    fun r9_resultAfterAttemptPreservaBlocked() {
        // El "ok-pending" nuevo NO borra el veredicto "blocked" previo.
        assertEquals(RESULT_BLOCKED, RecoveryJournal.resultAfterAttempt(RESULT_BLOCKED, RESULT_PENDING))
        assertEquals(RESULT_PENDING, RecoveryJournal.resultAfterAttempt(RESULT_PENDING, RESULT_PENDING))
        assertEquals(RESULT_PENDING, RecoveryJournal.resultAfterAttempt("", RESULT_PENDING))
        assertEquals(RESULT_OK, RecoveryJournal.resultAfterAttempt(RESULT_BLOCKED, RESULT_OK))
    }

    @Test
    fun r10_confirmacionUnaVezPorIntento() {
        // Watchdog (30 s): confirma solo si confirmAt < último intento.
        val attemptAt = now - 15 * 60_000L
        assertEquals(
            true,
            RecoveryJournal.shouldConfirmOnServiceAlive(
                trackingEnabled = true,
                serviceRunning = true,
                lastRecoveryAtMs = attemptAt,
                confirmAtMs = 0L,
            ),
        )
        // Ya confirmado (marker >= intento) → no repetir.
        assertEquals(
            false,
            RecoveryJournal.shouldConfirmOnServiceAlive(true, true, attemptAt, attemptAt),
        )
        // Sin intento previo o sin servicio/jornada → nada que confirmar.
        assertEquals(false, RecoveryJournal.shouldConfirmOnServiceAlive(true, true, 0L, 0L))
        assertEquals(false, RecoveryJournal.shouldConfirmOnServiceAlive(false, true, attemptAt, 0L))
        assertEquals(false, RecoveryJournal.shouldConfirmOnServiceAlive(true, false, attemptAt, 0L))
    }
}
