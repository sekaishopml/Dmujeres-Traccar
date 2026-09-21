package com.dmujeres.traccar.readiness

import com.dmujeres.traccar.recovery.RecoveryJournal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tabla pura de la prueba de RECUPERACIÓN del readiness: observacional
 * (nunca mata el proceso artificialmente); lee la evidencia que dejó el
 * mecanismo real de recuperación (SessionKeeper + RecoveryJournal). Es el
 * respaldo del sistema y NUNCA sustituye a la continuidad.
 */
class RecoveryTestPolicyTest {

    @Test
    fun okConServicioVivoEsPass() {
        val outcome = RecoveryTestPolicy.evaluate(
            lastRecoveryResult = RecoveryJournal.RESULT_OK,
            attemptsSinceLastSuccess = 0,
            serviceRunning = true,
            journeyActive = true,
        )
        assertEquals("PASS", outcome.state)
        assertEquals("recuperación verificada por RecoveryJournal", outcome.note)
        assertEquals("respaldo de recuperación verificado", RecoveryTestPolicy.riskNote(outcome))
    }

    @Test
    fun blockedConServicioMuertoYJornadaEsFailedPorOem() {
        val outcome = RecoveryTestPolicy.evaluate(
            lastRecoveryResult = RecoveryJournal.RESULT_BLOCKED,
            attemptsSinceLastSuccess = 2,
            serviceRunning = false,
            journeyActive = true,
        )
        assertEquals("FAILED", outcome.state)
        assertEquals(
            "RECOVERY_BLOCKED: el guardián no pudo revivir (posible OEM)",
            outcome.note,
        )
        assertEquals(
            "riesgo: recuperación bloqueada (respaldo degradado)",
            RecoveryTestPolicy.riskNote(outcome),
        )
    }

    @Test
    fun blockedConServicioVivoSigueSiendoFailedConNotaDistinta() {
        val oemNote = "RECOVERY_BLOCKED: el guardián no pudo revivir (posible OEM)"
        val vivo = RecoveryTestPolicy.evaluate(
            lastRecoveryResult = RecoveryJournal.RESULT_BLOCKED,
            attemptsSinceLastSuccess = 1,
            serviceRunning = true,
            journeyActive = true,
        )
        // Estado honesto: el último intento fue bloqueado aunque ahora esté vivo.
        assertEquals("FAILED", vivo.state)
        assertEquals("bloqueado previo; servicio vivo ahora", vivo.note)
        assertNotEquals(oemNote, vivo.note)
        assertEquals(
            "riesgo: recuperación bloqueada (respaldo degradado)",
            RecoveryTestPolicy.riskNote(vivo),
        )
    }

    @Test
    fun pendingEsNotRun() {
        // Nunca PASS con PENDING: el resultado del arranque async no se conoce aún.
        val outcome = RecoveryTestPolicy.evaluate(
            lastRecoveryResult = RecoveryJournal.RESULT_PENDING,
            attemptsSinceLastSuccess = 1,
            serviceRunning = true,
            journeyActive = true,
        )
        assertEquals("NOT_RUN", outcome.state)
        assertEquals("sin muerte real observada", outcome.note)
        assertEquals("respaldo no ejercitado", RecoveryTestPolicy.riskNote(outcome))
    }

    @Test
    fun resultadoVacioEsNotRun() {
        val outcome = RecoveryTestPolicy.evaluate(
            lastRecoveryResult = "",
            attemptsSinceLastSuccess = 0,
            serviceRunning = false,
            journeyActive = false,
        )
        assertEquals("NOT_RUN", outcome.state)
        assertEquals("sin muerte real observada", outcome.note)
    }

    @Test
    fun attemptsSePreservaEnElOutcome() {
        val pass = RecoveryTestPolicy.evaluate(RecoveryJournal.RESULT_OK, 3, true, true)
        val failed = RecoveryTestPolicy.evaluate(RecoveryJournal.RESULT_BLOCKED, 7, false, true)
        val notRun = RecoveryTestPolicy.evaluate(RecoveryJournal.RESULT_PENDING, 5, true, true)
        assertEquals(3, pass.attempts)
        assertEquals(7, failed.attempts)
        assertEquals(5, notRun.attempts)
    }

    @Test
    fun recoveryFailedNoCambiaElVeredictoDelGate() {
        // El policy NO conoce el readiness (DeviceReadinessPolicy): no emite
        // ningún veredicto integrado, solo una nota de riesgo. RECOVERY nunca
        // sustituye a CONTINUITY: CONTINUITY_FAIL + RECOVERY_PASS = DEVICE_READY NO.
        val outcome = RecoveryTestPolicy.evaluate(
            lastRecoveryResult = RecoveryJournal.RESULT_BLOCKED,
            attemptsSinceLastSuccess = 4,
            serviceRunning = false,
            journeyActive = true,
        )
        assertEquals("FAILED", outcome.state)
        val nota = RecoveryTestPolicy.riskNote(outcome)
        // Es una nota informativa de riesgo, no un veredicto de readiness.
        assertTrue(nota.startsWith("riesgo:"))
        assertTrue(nota.contains("respaldo degradado"))
    }
}
