package com.dmujeres.traccar.outbox

import com.dmujeres.traccar.core.DispatchLock
import com.dmujeres.traccar.data.PositionDao
import com.dmujeres.traccar.transport.DispatchPolicy
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Single-flight MQTT/HTTP + DAO paginado.
 * Puro JVM (runBlocking disponible en coroutines-core, sin Android).
 */
class DispatchRobustnessTest {

    @Test
    fun dispatchLockSerializaSeccionCritica() = runBlocking {
        var counter = 0
        // 20 coroutines x 50 incrementos bajo el mismo Mutex => 1000 sin carreras.
        val jobs = (1..20).map {
            async {
                repeat(50) {
                    DispatchLock.mutex.withLock {
                        val v = counter
                        // Cede para forzar intercalado si no hubiera lock.
                        delay(1)
                        counter = v + 1
                    }
                }
            }
        }
        jobs.awaitAll()
        assertEquals(1000, counter)
    }

    @Test
    fun dispatchLockEsUnicoMutex() {
        // Mismo objeto => no hay orden de locks => no hay deadlock.
        assertTrue(DispatchLock.mutex === DispatchLock.mutex)
    }

    @Test
    fun daoTieneAllDueConLimit() {
        val allDueOverloads = PositionDao::class.java.declaredMethods.filter { it.name == "allDue" }
        // allDue(now) y allDue(now, limit)
        assertTrue("falta allDue(now) / allDue(now, limit)", allDueOverloads.size >= 2)
        assertTrue(
            "falta allDue(now, limit) paginado",
            allDueOverloads.any { m -> m.parameterTypes.contains(Integer.TYPE) }
        )
    }

    @Test
    fun daoTieneDueControlsYMinFuture() {
        val names = PositionDao::class.java.declaredMethods.map { it.name }.toSet()
        assertTrue("falta dueControls(now, limit)", names.contains("dueControls"))
        assertTrue("falta minFutureRetryAt(now)", names.contains("minFutureRetryAt"))
    }

    @Test
    fun seleccionPaginadaEsFifoEstricto() {
        // Lote paginado de 100: 99 posiciones + 1 control nuevo.
        // FIFO estricto: gana la posición más antigua (p1), el control espera
        // su turno para que el replay salga completo y en orden.
        val now = 1_000_000L
        val items = (1..99).map {
            DispatchPolicy.QueueItem("p$it", it.toLong(), 0L, null, false)
        } + DispatchPolicy.QueueItem("end", 10_000L, 0L, null, true)
        assertEquals("p1", DispatchPolicy.selectNext(items, now)?.messageId)
    }

    @Test
    fun loteVacioSinFuturoEsperaWake() {
        // Sin vencidos y sin futuro -> nextRetryAt null (el loop hace receive()).
        val items = listOf(
            DispatchPolicy.QueueItem("a", 1, retryAtDb = 9_000L, retryAtMem = null, isControl = false)
        )
        assertEquals(null, DispatchPolicy.nextRetryAt(items, now = 10_000L))
    }

    @Test
    fun loteConFuturoDaEsperaLigera() {
        val items = listOf(
            DispatchPolicy.QueueItem("a", 1, retryAtDb = 20_000L, retryAtMem = null, isControl = false)
        )
        assertEquals(20_000L, DispatchPolicy.nextRetryAt(items, now = 10_000L))
    }
}
