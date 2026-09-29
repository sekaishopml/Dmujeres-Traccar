package org.traccar.client.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Agrupamiento de la cola por equipo de captura (puro JVM).
 *
 * El servidor dedupea por (equipo, tiempo, boot, secuencia): un lote jamás
 * puede mezclar equipos ni enviarse con el identificador actual si la fila se
 * capturó con otro, o la misma captura termina guardada bajo dos equipos
 * (bug real: la misma ruta bajo macias y desarrolladro).
 */
class UploadDeviceGroupingTest {

    private data class Fila(val id: Long, val equipo: String)

    @Test
    fun `filas de dos equipos en cola se parten en dos envios con su identificador`() {
        val cola = listOf(
            Fila(1, "macias"),
            Fila(2, "macias"),
            Fila(3, "desarrolladro"),
        )
        val grupos = UploadPolicy.groupByCaptureDevice(cola, "desarrolladro") { it.equipo }
        assertEquals(2, grupos.size)
        // Primer grupo = fila más vieja; se envía con SU equipo, no con el actual.
        assertEquals("macias", grupos[0].deviceId)
        assertEquals(listOf(1L, 2L), grupos[0].items.map { it.id })
        assertEquals("desarrolladro", grupos[1].deviceId)
        assertEquals(listOf(3L), grupos[1].items.map { it.id })
    }

    @Test
    fun `fila sin device_id usa el identificador actual`() {
        val cola = listOf(
            Fila(1, ""),
            Fila(2, "  MACIAS "),
        )
        val grupos = UploadPolicy.groupByCaptureDevice(cola, "Desarrolladro") { it.equipo }
        assertEquals(2, grupos.size)
        assertEquals("desarrolladro", grupos[0].deviceId)
        assertEquals(listOf(1L), grupos[0].items.map { it.id })
        // El equipo de captura se normaliza: misma fila, un solo grupo.
        assertEquals("macias", grupos[1].deviceId)
        assertEquals(listOf(2L), grupos[1].items.map { it.id })
    }

    @Test
    fun `el grupo mas viejo va primero y el orden interno de la cola se conserva`() {
        val cola = listOf(
            Fila(1, "b"),
            Fila(2, "a"),
            Fila(3, "b"),
        )
        val grupos = UploadPolicy.groupByCaptureDevice(cola, "actual") { it.equipo }
        assertEquals(listOf("b", "a"), grupos.map { it.deviceId })
        assertEquals(listOf(1L, 3L), grupos[0].items.map { it.id })
        assertEquals(listOf(2L), grupos[1].items.map { it.id })
    }

    @Test
    fun `sin equipo de captura ni actual queda un solo grupo sin identificador`() {
        val cola = listOf(Fila(1, ""), Fila(2, ""))
        val grupos = UploadPolicy.groupByCaptureDevice(cola, "") { it.equipo }
        assertEquals(1, grupos.size)
        assertEquals("", grupos[0].deviceId)
        assertEquals(listOf(1L, 2L), grupos[0].items.map { it.id })
    }
}
