package com.dmujeres.traccar.readiness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas de la política pura de la prueba de continuidad (criterio principal
 * del DeviceReadinessGate): PASS solo si la captura continuó con pantalla
 * apagada; fallo con una sola causa por evidencia; INCONCLUSIVE cuando no se
 * puede confirmar.
 */
class ContinuityTestPolicyTest {

    private fun evaluate(
        fixesDuringOff: Int = 0,
        processAlive: Boolean = true,
        fgsAlive: Boolean = true,
        networkAvailable: Boolean = true,
        frozenSeconds: Int = 0,
        oemGuidePresent: Boolean = false,
        screenOffMs: Long = 1_000L,
        windowMs: Long = ContinuityTestPolicy.WINDOW_MS,
    ) = ContinuityTestPolicy.evaluate(
        fixesDuringOff = fixesDuringOff,
        processAlive = processAlive,
        fgsAlive = fgsAlive,
        networkAvailable = networkAvailable,
        frozenSeconds = frozenSeconds,
        oemGuidePresent = oemGuidePresent,
        screenOffMs = screenOffMs,
        windowMs = windowMs,
    )

    @Test
    fun passBasico() {
        // 2 fixes con pantalla apagada, proceso y FGS vivos, red OK.
        val o = evaluate(fixesDuringOff = 2)
        assertEquals("PASS", o.state)
        assertEquals(ContinuityTestPolicy.Cause.NONE, o.cause)
    }

    @Test
    fun failSinFixesSinFreezeSinGuia() {
        val o = evaluate(fixesDuringOff = 0, frozenSeconds = 0, oemGuidePresent = false)
        assertEquals("FAILED", o.state)
        assertEquals(ContinuityTestPolicy.Cause.NO_CALLBACK, o.cause)
    }

    @Test
    fun failOemFreezeConGuia() {
        // Caso ZTE: el ticker midió el congelamiento y hay guía OEM.
        val o = evaluate(fixesDuringOff = 0, frozenSeconds = 27, oemGuidePresent = true)
        assertEquals("FAILED", o.state)
        assertEquals(ContinuityTestPolicy.Cause.OEM_FREEZE, o.cause)
    }

    @Test
    fun failFreezeSinGuiaRotulaNoCallback() {
        // La evidencia de freeze existe, pero sin contexto OEM no se rotula
        // OEM_FREEZE: cae a NO_CALLBACK.
        val o = evaluate(fixesDuringOff = 0, frozenSeconds = 27, oemGuidePresent = false)
        assertEquals("FAILED", o.state)
        assertEquals(ContinuityTestPolicy.Cause.NO_CALLBACK, o.cause)
    }

    @Test
    fun failProcessDeadGanaAunqueHayaFixes() {
        // Caso imposible en la práctica: si el proceso murió después de
        // capturar, el PASS ya no vale; PROCESS_DEAD gana con note honesto.
        val o = evaluate(fixesDuringOff = 3, processAlive = false)
        assertEquals("FAILED", o.state)
        assertEquals(ContinuityTestPolicy.Cause.PROCESS_DEAD, o.cause)
    }

    @Test
    fun failFgsDeadConProcesoVivo() {
        val o = evaluate(fixesDuringOff = 0, processAlive = true, fgsAlive = false)
        assertEquals("FAILED", o.state)
        assertEquals(ContinuityTestPolicy.Cause.FGS_DEAD, o.cause)
    }

    @Test
    fun inconclusiveSinRedYSinFixes() {
        // Nunca PASS sin red y sin fixes: no se puede confirmar.
        val o = evaluate(fixesDuringOff = 0, networkAvailable = false)
        assertEquals("INCONCLUSIVE", o.state)
        assertEquals(ContinuityTestPolicy.Cause.NONE, o.cause)
    }

    @Test
    fun inconclusiveNoSeVuelvePassConFreeze() {
        // Con freeze medido la causa es LOCAL (no depende de la red): el
        // congelamiento demuestra que la captura se detuvo → FAILED, aunque
        // la red también estuviera caída. Nunca INCONCLUSIVE ni PASS.
        val o = evaluate(
            fixesDuringOff = 0,
            networkAvailable = false,
            frozenSeconds = 15,
            oemGuidePresent = true,
        )
        assertEquals("FAILED", o.state)
        assertEquals(ContinuityTestPolicy.Cause.OEM_FREEZE, o.cause)
    }

    @Test
    fun inconclusiveSinVentana() {
        val o = evaluate(fixesDuringOff = 2, windowMs = 0L)
        assertEquals("INCONCLUSIVE", o.state)
        assertEquals(ContinuityTestPolicy.Cause.NONE, o.cause)
        assertTrue(o.note.contains("sin ventana"))
        // También con ventana negativa.
        val o2 = evaluate(windowMs = -5L)
        assertEquals("INCONCLUSIVE", o2.state)
    }

    @Test
    fun passAunqueNoHayaRed() {
        // La captura no depende del ACK de red para el estado PASS.
        val o = evaluate(fixesDuringOff = 1, networkAvailable = false)
        assertEquals("PASS", o.state)
        assertEquals(ContinuityTestPolicy.Cause.NONE, o.cause)
    }

    @Test
    fun noteMencionaCongeladoConSegundos() {
        val o = evaluate(fixesDuringOff = 0, frozenSeconds = 42, oemGuidePresent = true)
        assertEquals(ContinuityTestPolicy.Cause.OEM_FREEZE, o.cause)
        assertTrue(o.note.contains("congelado"))
        assertTrue(o.note.contains("42"))
    }

    @Test
    fun ventanaEsNoventaSegundos() {
        assertEquals(90_000L, ContinuityTestPolicy.WINDOW_MS)
    }

    @org.junit.Test
    fun freezeConFixesTempranosEsFail() {
        // Caso real Z2450: 2 fixes en los primeros 41 s y luego cfreezer
        // congeló 50 s: la captura NO continuó → FAIL/OEM_FREEZE (no PASS).
        val outcome = com.dmujeres.traccar.readiness.ContinuityTestPolicy.evaluate(
            fixesDuringOff = 2, processAlive = true, fgsAlive = true,
            networkAvailable = true, frozenSeconds = 50, oemGuidePresent = true,
            screenOffMs = 1_000L, windowMs = 90_000L,
        )
        org.junit.Assert.assertEquals("FAILED", outcome.state)
        org.junit.Assert.assertEquals(
            com.dmujeres.traccar.readiness.ContinuityTestPolicy.Cause.OEM_FREEZE,
            outcome.cause,
        )
    }
}
