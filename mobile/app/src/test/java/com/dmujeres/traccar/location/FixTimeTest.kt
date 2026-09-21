package com.dmujeres.traccar.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.dmujeres.traccar.location.FixTime

class FixTimeTest {

    private val second = 1_000_000_000L

    @Test
    fun time1_dtByElapsedIsCorrect() {
        // 10 s entre fixes por el reloj monotónico.
        assertEquals(10.0, FixTime.dtSeconds(1_000 * second, 0L, 1_010 * second, 0L)!!, 1e-9)
        // Aun cuando location.time miente (salto NTP), manda el monotónico.
        assertEquals(
            10.0,
            FixTime.dtSeconds(1_000 * second, 5_000_000L, 1_010 * second, 6_000_000L)!!,
            1e-9,
        )
        // dt negativo por reorden de llegada → null.
        assertNull(FixTime.dtSeconds(1_010 * second, 0L, 1_000 * second, 0L))
        // Hueco > 3600 s → null.
        assertNull(FixTime.dtSeconds(0 * second, 0L, 3601 * second, 0L))
    }

    @Test
    fun time2_orderIndicesSortsBatch() {
        val second1 = 1_000_000_000L
        // 5 fixes desordenados, todos con elapsed → orden ASC por elapsed.
        val elapsed = listOf(
            50L * second1, 10L * second1, 30L * second1, 20L * second1, 40L * second1,
        )
        val times = listOf(500L, 100L, 300L, 200L, 400L)
        assertEquals(listOf(1, 3, 2, 4, 0), FixTime.orderIndices(elapsed, times))
        // Mezclado (uno sin elapsed Y otro sin time) → identidad.
        val mixedElapsed = listOf(30L * second1, 0L, 10L * second1)
        assertEquals(listOf(0, 1, 2), FixTime.orderIndices(mixedElapsed, listOf(30L, 0L, 20L)))
        // Sin elapsed en todos pero con times → orden por time.
        assertEquals(
            listOf(1, 0, 2),
            FixTime.orderIndices(listOf(0L, 0L, 0L), listOf(20L, 10L, 30L)),
        )
        // Identidad con lista vacía/un elemento.
        assertEquals(emptyList<Int>(), FixTime.orderIndices(emptyList(), emptyList()))
        assertEquals(listOf(0), FixTime.orderIndices(listOf(5L), listOf(5L)))
    }

    @Test
    fun time3_dtByLocationTime() {
        // Sin elapsed → fallback a location.time de ambos.
        assertEquals(
            8.0,
            FixTime.dtSeconds(0L, 1_000_000L, 0L, 1_008_000L)!!,
            1e-9,
        )
        assertNull(FixTime.dtSeconds(0L, 1_008_000L, 0L, 1_000_000L))
    }

    @Test
    fun time4_invalidInputsGiveNull() {
        // elapsed inválido/0 → fallback a time.
        assertEquals(5.0, FixTime.dtSeconds(0L, 1_000L, 0L, 6_000L)!!, 1e-9)
        // Ambos inválidos → null (el llamador decide, nunca wall de llegada).
        assertNull(FixTime.dtSeconds(0L, 0L, 0L, 6_000L))
        assertNull(FixTime.dtSeconds(0L, 0L, 0L, 0L))
        // Fallback time también fuera de rango → null.
        assertNull(FixTime.dtSeconds(0L, 6_000L, 0L, 1_000L))
        assertNull(FixTime.dtSeconds(0L, 1_000L, 0L, 3_600_999L + 1_000L))
    }

    @Test
    fun ageSecondsPrefersElapsedAndFallsBack() {
        // Edad por monotónico.
        assertEquals(30.0, FixTime.ageSeconds(1_000 * second, 1_030 * second, 0L, 0L)!!, 1e-9)
        // Fallback wall si elapsed desconocido.
        assertEquals(12.0, FixTime.ageSeconds(0L, 0L, 1_000_000L, 1_012_000L)!!, 1e-9)
        // Edad negativa (fix del futuro) → null.
        assertNull(FixTime.ageSeconds(1_050 * second, 1_000 * second, 0L, 0L))
        assertNull(FixTime.ageSeconds(0L, 0L, 1_012_000L, 1_000_000L))
        // Nada conocido → null.
        assertNull(FixTime.ageSeconds(0L, 0L, 0L, 0L))
    }
}
