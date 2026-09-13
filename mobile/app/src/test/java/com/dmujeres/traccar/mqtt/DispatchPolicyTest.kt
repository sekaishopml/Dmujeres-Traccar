package com.dmujeres.traccar.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DispatchPolicyTest {

    private fun item(
        id: String,
        seq: Long,
        retryDb: Long = 0L,
        retryMem: Long? = null,
        control: Boolean = false,
    ) = DispatchPolicy.QueueItem(id, seq, retryDb, retryMem, control)

    @Test
    fun fifoSinControl() {
        val items = listOf(item("a", 1), item("b", 2), item("c", 3))
        assertEquals("a", DispatchPolicy.selectNext(items, 1000L)?.messageId)
    }

    @Test
    fun fifoEstrictoElControlNuevoEspera() {
        // Replay en orden: el control con sequence mayor sale DESPUÉS de las
        // posiciones pendientes, no salta la cola.
        val items = listOf(item("p1", 1), item("p2", 2), item("end", 3, control = true))
        assertEquals("p1", DispatchPolicy.selectNext(items, 1000L)?.messageId)
    }

    @Test
    fun fifoEstrictoControlAntiguoSalePrimero() {
        // journeyStarted (sequence menor) sí sale primero: es el más antiguo.
        val items = listOf(
            item("p1", 2),
            item("start", 1, control = true),
            item("end", 5, control = true),
        )
        assertEquals("start", DispatchPolicy.selectNext(items, 1000L)?.messageId)
    }

    @Test
    fun fifoEstrictoEndedSaleTrasDrenarPosiciones() {
        // Con ended pendiente y posiciones viejas, el orden de replay es p1, p2, end.
        val now = 1000L
        val remaining = mutableListOf(
            item("p1", 1), item("p2", 2), item("end", 3, control = true),
        )
        val order = mutableListOf<String>()
        while (remaining.isNotEmpty()) {
            val next = DispatchPolicy.selectNext(remaining, now) ?: break
            order += next.messageId
            remaining.removeIf { it.messageId == next.messageId }
        }
        assertEquals(listOf("p1", "p2", "end"), order)
    }

    @Test
    fun controlEnBackoffNoSalta() {
        val items = listOf(
            item("p1", 1),
            item("end", 3, retryDb = 5000L, control = true),
        )
        assertEquals("p1", DispatchPolicy.selectNext(items, 1000L)?.messageId)
    }

    @Test
    fun todoEnBackoffEsNull() {
        val items = listOf(item("a", 1, retryDb = 5000L), item("b", 2, retryMem = 6000L))
        assertNull(DispatchPolicy.selectNext(items, 1000L))
    }

    @Test
    fun memoriaYDbTomanElMaximo() {
        // db=5000, mem=9000 -> efectivo 9000 > now -> no vence
        val items = listOf(item("a", 1, retryDb = 5000L, retryMem = 9000L))
        assertNull(DispatchPolicy.selectNext(items, 6000L))
        // now=9500 -> vence
        assertEquals("a", DispatchPolicy.selectNext(items, 9500L)?.messageId)
    }

    @Test
    fun primerControlVencidoGana() {
        // Entre controles vencidos gana el más antiguo por sequence.
        val items = listOf(
            item("end2", 5, control = true),
            item("end1", 2, control = true),
            item("p0", 1),
        )
        // p0 es más antiguo que ambos: FIFO estricto lo elige primero.
        assertEquals("p0", DispatchPolicy.selectNext(items, 1000L)?.messageId)
    }

    @Test
    fun nextRetryAtEsElMinimoFuturo() {
        val items = listOf(
            item("a", 1, retryDb = 9000L),
            item("b", 2, retryMem = 5000L),
            item("c", 3),
        )
        assertEquals(5000L, DispatchPolicy.nextRetryAt(items, 1000L))
    }

    @Test
    fun nextRetryAtNullSiNadaFuturo() {
        val items = listOf(item("a", 1), item("b", 2, retryDb = 500L))
        assertNull(DispatchPolicy.nextRetryAt(items, 1000L))
    }

    @Test
    fun sinAckNuncaSeDescartaPosicion() {
        // Ruta sin pérdida: ni tras 2x maxRetries ni tras miles de intentos.
        assertEquals(false, DispatchPolicy.shouldDiscardUnacked(false, 61, 30))
        assertEquals(false, DispatchPolicy.shouldDiscardUnacked(false, 10_000, 30))
        assertEquals(false, DispatchPolicy.shouldDiscardUnacked(false, Int.MAX_VALUE, 3))
    }

    @Test
    fun sinAckNuncaSeDescartaControl() {
        // started/ended tampoco: sin ACK no se sabe si el servidor los vio.
        assertEquals(false, DispatchPolicy.shouldDiscardUnacked(true, 61, 30))
        assertEquals(false, DispatchPolicy.shouldDiscardUnacked(true, 10_000, 200))
    }

    @Test
    fun classifyHttp429Y408SonThrottled() {
        assertEquals(DispatchPolicy.HttpFailure.THROTTLED, DispatchPolicy.classifyHttpFailure(429))
        assertEquals(DispatchPolicy.HttpFailure.THROTTLED, DispatchPolicy.classifyHttpFailure(408))
    }

    @Test
    fun classifyHttpTerminalNuncaReintenta() {
        // Credenciales/ruta/payload: reintentar no cambia nada.
        listOf(401, 403, 404, 405, 413, 414, 422).forEach {
            assertEquals("$it", DispatchPolicy.HttpFailure.TERMINAL, DispatchPolicy.classifyHttpFailure(it))
        }
    }

    @Test
    fun classifyHttpRestoEsTransient() {
        // 5xx, 0 (sin respuesta), <100 inválido, 3xx, 4xx desconocidos y >499.
        listOf(0, 99, 301, 418, 426, 451, 499, 500, 502, 503, 504, 599, 700).forEach {
            assertEquals("$it", DispatchPolicy.HttpFailure.TRANSIENT, DispatchPolicy.classifyHttpFailure(it))
        }
    }

    @Test
    fun classifyHttp2xxNoSeInvocaPeroEsTransient() {
        // La clasificación SOLO aplica al no-2xx global; 200 queda en el else.
        assertEquals(DispatchPolicy.HttpFailure.TRANSIENT, DispatchPolicy.classifyHttpFailure(200))
    }
}
