package org.traccar.client.cronograma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HoraInterpretarTest {
    private fun leer(texto: String, pm: Boolean) = HoraCronograma.interpretar(texto, pm)

    @Test
    fun formato24DetectaPmSolo() {
        assertEquals(HoraCronograma.Lectura("14:30", true), leer("14:30", false))
        assertEquals(HoraCronograma.Lectura("23:05", true), leer("2305", false))
        assertEquals(HoraCronograma.Lectura("00:15", false), leer("0:15", true))
    }

    @Test
    fun formato12UsaElAmPmElegido() {
        assertEquals(HoraCronograma.Lectura("09:30", false), leer("9:30", false))
        assertEquals(HoraCronograma.Lectura("21:30", true), leer("930", true))
        assertEquals(HoraCronograma.Lectura("12:00", true), leer("12", true))
        assertEquals(HoraCronograma.Lectura("00:00", false), leer("12", false))
        assertEquals(HoraCronograma.Lectura("08:05", false), leer("8.05", false))
    }

    @Test
    fun rechazaLoQueNoEsHora() {
        assertNull(leer("25:00", false))
        assertNull(leer("9:75", false))
        assertNull(leer("abc", false))
        assertNull(leer("12345", false))
    }

    @Test
    fun muestraEn12Horas() {
        assertEquals("02:30", HoraCronograma.a12("14:30"))
        assertEquals("12:00", HoraCronograma.a12("00:00"))
        assertEquals("2:30 p. m.", HoraCronograma.legible("14:30"))
        assertEquals("9:00 a. m. – 11:30 a. m.", HoraCronograma.rango("09:00", "11:30"))
    }

    private data class A(val hora: String, val fin: String?)
    private val dia = listOf(A("09:00", "11:30"), A("12:30", null), A("15:00", "16:00"))
    private fun curso(t: String) = HoraCronograma.enCurso(dia, t, { it.hora }, { it.fin })

    @Test
    fun actividadEnCursoYSiguiente() {
        assertEquals(dia[0], curso("10:15"))
        assertNull(curso("11:45"))
        assertEquals(dia[1], HoraCronograma.siguiente(dia, "11:45") { it.hora })
        assertEquals(dia[1], curso("13:00"))
        assertEquals(dia[2], curso("15:30"))
        assertNull(curso("16:10"))
        assertNull(HoraCronograma.siguiente(dia, "16:10") { it.hora })
    }
}
