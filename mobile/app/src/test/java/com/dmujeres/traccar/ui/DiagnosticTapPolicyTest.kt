package com.dmujeres.traccar.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** UX §18: 5 toques → Diagnóstico; 1-4 no; separación/ventana reinician. */
class DiagnosticTapPolicyTest {

    private val policy = DiagnosticTapPolicy(maxGapMs = 1_200L, windowMs = 4_000L)

    @Test
    fun `cuatro toques no abren`() {
        var state: DiagnosticTapPolicy.State? = null
        repeat(4) { i ->
            val r = policy.registerTap(state, 1_000L + i * 300L)
            state = r.state
            assertFalse("tap ${i + 1} no debe abrir", r.triggered)
        }
        assertNotNull(state)
        assertEquals(4, state!!.count)
    }

    @Test
    fun `cinco toques abren y reinician contador`() {
        var state: DiagnosticTapPolicy.State? = null
        var triggered = false
        repeat(5) { i ->
            val r = policy.registerTap(state, 1_000L + i * 300L)
            state = r.state
            triggered = r.triggered
        }
        assertTrue(triggered)
        assertNull(state)
    }

    @Test
    fun `toques demasiado separados reinician`() {
        var state: DiagnosticTapPolicy.State? = null
        state = policy.registerTap(state, 0L).state
        state = policy.registerTap(state, 300L).state
        assertEquals(2, state!!.count)
        // 1.3 s > maxGap (1.2 s): se reinicia a 1
        val r = policy.registerTap(state, 1_600L)
        assertEquals(1, r.state!!.count)
        assertFalse(r.triggered)
    }

    @Test
    fun `secuencia fuera de ventana reinicia`() {
        var state: DiagnosticTapPolicy.State? = null
        // 4 toques a 1 s exacto (gap válido), el 5º cae > 4 s desde el primero.
        listOf(0L, 1_000L, 2_000L, 3_000L).forEach { t ->
            state = policy.registerTap(state, t).state
        }
        val r = policy.registerTap(state, 4_200L) // gap 1.2 s exacto OK pero ventana 4.2 s > 4 s
        assertEquals(1, r.state!!.count)
        assertFalse(r.triggered)
    }

    @Test
    fun `toque en el mismo instante no rompe`() {
        var state: DiagnosticTapPolicy.State? = null
        repeat(5) {
            val r = policy.registerTap(state, 500L)
            state = r.state
        }
        // gap 0 no es válido (1..maxGap) → cada toque reinicia a 1: nunca abre.
        assertEquals(1, state!!.count)
    }
}
