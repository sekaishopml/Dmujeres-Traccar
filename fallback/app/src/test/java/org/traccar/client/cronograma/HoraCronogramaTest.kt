package org.traccar.client.cronograma

import org.junit.Assert.assertEquals
import org.junit.Test

class HoraCronogramaTest {
    @Test
    fun suma_sin_pasar_de_medianoche() {
        assertEquals("17:30", HoraCronograma.sumar("17:00", 30))
        assertEquals("23:55", HoraCronograma.sumar("23:50", 60))
        assertEquals("00:00", HoraCronograma.sumar("00:10", -15))
    }

    @Test
    fun hora_ocupada_salta_a_la_siguiente_libre() {
        assertEquals("09:10", HoraCronograma.libreDesde("09:00", setOf("09:00", "09:05")))
        assertEquals("09:00", HoraCronograma.libreDesde("09:00", setOf("08:00")))
    }
}
