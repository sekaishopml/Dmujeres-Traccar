package com.dmujeres.traccar.mqtt

import com.dmujeres.traccar.db.DeadLetter
import com.dmujeres.traccar.db.PendingPosition
import com.dmujeres.traccar.db.PositionDao
import com.dmujeres.traccar.db.SequenceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WAKE post-insert (microbatch): un solo canal CONFLATED + UNA corrutina
 * wakeLoop → flushOnce(batchSize=MICROBATCH_MAX) bajo el DispatchLock global.
 * Reglas verificadas en el contexto wake: tope de 5 por lote, DELETE solo con
 * ACK accepted/duplicate, transporte caído sin mutaciones, FIFO global entre
 * microbatch y drain grande, coalescing de ráfagas y presencias solo si MQTT
 * está explícitamente caído. Puro JVM (DAO y transporte falsos); el debounce
 * se inyecta pequeño por startWakeLoop para no dormir la suite.
 *
 * El wakeLoop es un `while(true)` eterno y el runBlocking del test no retorna
 * hasta que TODOS sus hijos terminan (concurrencia estructurada): cada test
 * lanza el loop en un Job propio y lo cancela con cancelAndJoin en finally,
 * así el scope del test queda limpio y wakeStarted se libera para el siguiente.
 */
class PositionOutboxMicroBatchTest {

    private val wakeTestDebounceMs = 50L

    private class FakeDao : PositionDao() {
        val rows = mutableListOf<PendingPosition>()
        val dead = mutableListOf<DeadLetter>()

        override suspend fun insert(position: PendingPosition) {
            rows.removeAll { it.messageId == position.messageId }
            rows.add(position)
        }

        override suspend fun allOrdered(): List<PendingPosition> = rows.sortedBy { it.sequence }

        override suspend fun allOrdered(limit: Int): List<PendingPosition> =
            rows.sortedBy { it.sequence }.take(limit)

        override suspend fun ensureSequence(state: SequenceState) = Unit
        override suspend fun incrementSequence() = Unit
        override suspend fun currentSequence(): Long = 0L
        override suspend fun maxPendingSequence(): Long? = rows.maxOfOrNull { it.sequence }
        override suspend fun raiseSequenceTo(minimum: Long) = Unit

        override suspend fun updateAttempts(messageId: String, attempts: Int) {
            replace(messageId) { it.copy(attempts = attempts) }
        }

        override suspend fun updateRetryAt(messageId: String, retryAt: Long) {
            replace(messageId) { it.copy(retryAt = retryAt) }
        }

        private fun replace(messageId: String, f: (PendingPosition) -> PendingPosition) {
            val i = rows.indexOfFirst { it.messageId == messageId }
            if (i >= 0) rows[i] = f(rows[i])
        }

        private fun due(now: Long) =
            rows.filter { it.retryAt <= now || it.retryAt == 0L }.sortedBy { it.sequence }

        override suspend fun nextDue(now: Long): PendingPosition? = due(now).firstOrNull()
        override suspend fun allDue(now: Long): List<PendingPosition> = due(now)
        override suspend fun allDue(now: Long, limit: Int): List<PendingPosition> = due(now).take(limit)
        override suspend fun dueControls(now: Long, limit: Int): List<PendingPosition> =
            due(now).filter { it.isControl }.take(limit)

        override suspend fun minFutureRetryAt(now: Long): Long? =
            rows.map { it.retryAt }.filter { it > now }.minOrNull()

        override suspend fun deleteOldestNonControl(count: Int): Int = 0

        override suspend fun deleteExpiredNonControl(cutoffMs: Long): Int {
            val victims = rows.filter { !it.isControl && it.enqueuedAt > 0 && it.enqueuedAt < cutoffMs }
            rows.removeAll(victims.toSet())
            return victims.size
        }

        override suspend fun delete(messageId: String): Int {
            val before = rows.size
            rows.removeAll { it.messageId == messageId }
            return before - rows.size
        }

        override fun countFlow(): Flow<Int> = flow { emit(rows.size) }
        override suspend fun count(): Int = rows.size
        override suspend fun oldestEnqueuedAt(): Long? =
            rows.map { it.enqueuedAt }.filter { it > 0 }.minOrNull()

        override suspend fun clear() {
            rows.clear()
        }

        override suspend fun insertDeadLetter(deadLetter: DeadLetter): Long {
            if (dead.any { it.messageId == deadLetter.messageId }) return -1L
            dead.add(deadLetter)
            return dead.size.toLong()
        }

        override suspend fun deadLetterCount(): Int = dead.size

        override suspend fun deadLetters(limit: Int): List<DeadLetter> =
            dead.sortedByDescending { it.quarantinedAt }.take(limit)

        override suspend fun moveToDeadLetter(deadLetter: DeadLetter) {
            insertDeadLetter(deadLetter)
            delete(deadLetter.messageId)
        }
    }

    private class FakeTransport(
        var statuses: Map<String, String> = emptyMap(),
        var failWith: Throwable? = null,
        var transportOk: Boolean = true,
        var defaultStatus: String? = null,
        private val daoForCountProbe: FakeDao? = null,
    ) : PositionOutboxDispatcher.Transport {
        val postedBatches = mutableListOf<List<String>>()
        /** count() del DAO al MOMENTO del POST: cero deletes sin ACK aún. */
        var countAtPost: Int = -1

        override suspend fun post(
            ctx: PositionOutboxDispatcher.DispatchContext,
            items: List<PendingPosition>,
        ): PositionOutboxDispatcher.PostResult {
            daoForCountProbe?.let { countAtPost = it.count() }
            postedBatches.add(items.map { it.messageId })
            failWith?.let { throw it }
            if (!transportOk) {
                return PositionOutboxDispatcher.PostResult(emptyMap(), transportOk = false, httpCode = 500)
            }
            val map = items.associate { it.messageId to (statuses[it.messageId] ?: defaultStatus ?: "accepted") }
            return PositionOutboxDispatcher.PostResult(map, transportOk = true, httpCode = 200)
        }
    }

    private fun position(
        seq: Long,
        control: Boolean = false,
        journey: Long = 7L,
        attempts: Int = 0,
        retryAt: Long = 0L,
    ) = PendingPosition(
        messageId = "dmj-w-$seq",
        deviceId = "juan-001",
        sequence = seq,
        payload = if (control) {
            "{\"schema\":1,\"type\":\"presence\",\"messageId\":\"dmj-w-$seq\",\"payload\":{}}"
        } else {
            "{\"schema\":1,\"type\":\"position\",\"messageId\":\"dmj-w-$seq\"," +
                "\"payload\":{\"latitude\":-0.1,\"longitude\":-78.4}}"
        },
        observedAt = "2026-01-01T00:00:00Z",
        enqueuedAt = 1_000L + seq,
        isControl = control,
        journeyId = journey,
        attempts = attempts,
        retryAt = retryAt,
    )

    private fun ctx(
        confirmed: MutableList<String> = mutableListOf(),
        quarantined: MutableList<String> = mutableListOf(),
    ) = PositionOutboxDispatcher.DispatchContext(
        webBaseUrl = "http://x:999",
        apiKey = "k",
        journeyStartAt = 7L,
        onConfirmedPosition = { confirmed.add(it.messageId) },
        onQuarantined = { quarantined.add(it.messageId) },
    )

    /**
     * Arranca el wakeLoop en un Job HIJO del scope del test (SupervisorJob):
     * cancelable e independiente del bloque del test. Devuelve el Job para que
     * el test lo cancele con [stopWake] antes de retornar; si no, runBlocking
     * esperaría eternamente al loop infinito.
     */
    private fun startWake(
        scope: CoroutineScope,
        dao: FakeDao,
        transport: FakeTransport,
        ctxProvider: () -> PositionOutboxDispatcher.DispatchContext,
    ): Job {
        val job = SupervisorJob(scope.coroutineContext[Job])
        PositionOutboxDispatcher.startWakeLoop(
            scope = CoroutineScope(scope.coroutineContext + job),
            dao = dao,
            transport = transport,
            ctxProvider = ctxProvider,
            wakeDebounceMs = wakeTestDebounceMs,
        )
        return job
    }

    /** Cancela y espera al wakeLoop del test; obligatorio antes de retornar. */
    private suspend fun stopWake(job: Job) {
        job.cancelAndJoin()
    }

    private suspend fun settle(cycles: Int = 1) {
        delay(wakeTestDebounceMs * 2 + 200L * cycles)
    }

    @Test
    fun wakeMicrobatchCapsAtFiveAndKeepsRestIntact() = runBlocking {
        val dao = FakeDao()
        (8L downTo 1L).forEach { dao.insert(position(it)) }
        val confirmed = mutableListOf<String>()
        val transport = FakeTransport(daoForCountProbe = dao)
        val wakeJob = startWake(this, dao, transport) { ctx(confirmed) }
        try {
            PositionOutboxDispatcher.requestFlush()
            settle()
            // Microbatch: UN lote topeado en 5 (≤ MICROBATCH_MAX), FIFO.
            assertEquals(1, transport.postedBatches.size)
            assertEquals(
                listOf("dmj-w-1", "dmj-w-2", "dmj-w-3", "dmj-w-4", "dmj-w-5"),
                transport.postedBatches.single(),
            )
            // Nada se borró antes del ACK: las 8 filas siguen en Room al momento del POST.
            assertEquals(8, transport.countAtPost)
            // Solo los 5 con ACK accepted se eliminan; los 3 no despachados quedan.
            assertEquals(3, dao.count())
            assertEquals(listOf("dmj-w-6", "dmj-w-7", "dmj-w-8"), dao.allOrdered().map { it.messageId })
            assertEquals(5, confirmed.size)
            assertEquals(0, dao.deadLetterCount())
        } finally {
            stopWake(wakeJob)
        }
    }

    @Test
    fun wakeMicrobatchDeletesOnlyAckAccepted() = runBlocking {
        val dao = FakeDao()
        (1L..5L).forEach { dao.insert(position(it)) }
        val transport = FakeTransport(
            statuses = mapOf(
                "dmj-w-1" to "accepted", "dmj-w-2" to "duplicate", "dmj-w-3" to "accepted",
                "dmj-w-4" to "pending", "dmj-w-5" to "throttled",
            ),
            daoForCountProbe = dao,
        )
        val wakeJob = startWake(this, dao, transport) { ctx() }
        try {
            PositionOutboxDispatcher.requestFlush()
            settle()
            assertEquals(1, transport.postedBatches.size)
            // Los 5 salieron en el lote; Room seguía lleno DURANTE el POST.
            assertEquals(5, transport.postedBatches.single().size)
            assertEquals(5, transport.countAtPost)
            // Solo accepted/duplicate borrados; pending/throttled quedan con backoff.
            assertEquals(2, dao.count())
            val left = dao.allOrdered()
            assertEquals(listOf("dmj-w-4", "dmj-w-5"), left.map { it.messageId })
            assertEquals(1, left[0].attempts)
            assertTrue(left[0].retryAt > 0L)
            assertEquals(0, dao.deadLetterCount())
        } finally {
            stopWake(wakeJob)
        }
    }

    @Test
    fun wakeTransportFailureKeepsRoomAndAttemptsIntact() = runBlocking {
        val dao = FakeDao()
        (1L..5L).forEach { dao.insert(position(it, attempts = 2)) }
        val transport = FakeTransport(daoForCountProbe = dao)
        val wakeJob = startWake(this, dao, transport) { ctx() }
        try {
            // Fase 1: transporte lanza (excepción).
            transport.failWith = RuntimeException("red caída")
            PositionOutboxDispatcher.requestFlush()
            settle()
            assertEquals(1, transport.postedBatches.size)
            assertEquals(5, transport.countAtPost)
            assertEquals(5, dao.count())
            // Fallo de transporte NO toca attempts ni retryAt (criterio del archivo).
            assertEquals((1L..5L).map { 2 }, dao.allOrdered().map { it.attempts })
            assertEquals((1L..5L).map { 0L }, dao.allOrdered().map { it.retryAt })
            // Fase 2: HTTP 500 (transportOk=false, sin statuses).
            transport.failWith = null
            transport.transportOk = false
            PositionOutboxDispatcher.requestFlush()
            settle()
            assertEquals(2, transport.postedBatches.size)
            assertEquals(5, dao.count())
            assertEquals((1L..5L).map { 2 }, dao.allOrdered().map { it.attempts })
            assertEquals(0, dao.deadLetterCount())
            // Fase 3: transporte se recupera → el MISMO wakeLoop (sin relanzar) confirma.
            transport.transportOk = true
            PositionOutboxDispatcher.requestFlush()
            settle()
            assertEquals(3, transport.postedBatches.size)
            assertEquals(0, dao.count())
        } finally {
            stopWake(wakeJob)
        }
    }

    @Test
    fun wakeMicrobatchThenLargeDrainKeepsFifoOrder() = runBlocking {
        val dao = FakeDao()
        (12L downTo 1L).forEach { dao.insert(position(it)) }
        val transport = FakeTransport()
        val wakeJob = startWake(this, dao, transport) { ctx() }
        try {
            PositionOutboxDispatcher.requestFlush()
            settle()
            // Microbatch confirma 1..5.
            assertEquals(listOf("dmj-w-1", "dmj-w-2", "dmj-w-3", "dmj-w-4", "dmj-w-5"),
                transport.postedBatches[0])
            assertEquals(7, dao.count())
            // Drain grande (como drainBacklog: batchSize default 50) sigue FIFO.
            val outcome = PositionOutboxDispatcher.flushOnce(
                dao, transport, ctx(), includePresence = true,
            )
            assertEquals(7, outcome.confirmed)
            assertEquals(0, dao.count())
            // Orden global FIFO por sequence a través de ambos lotes.
            assertEquals(
                (1L..12L).map { "dmj-w-$it" },
                transport.postedBatches.flatten(),
            )
            assertEquals(
                listOf(
                    listOf("dmj-w-1", "dmj-w-2", "dmj-w-3", "dmj-w-4", "dmj-w-5"),
                    (6L..12L).map { "dmj-w-$it" },
                ),
                transport.postedBatches,
            )
        } finally {
            stopWake(wakeJob)
        }
    }

    @Test
    fun wakeBurstOfThreeCollapsesIntoSingleFlush() = runBlocking {
        val dao = FakeDao()
        (1L..5L).forEach { dao.insert(position(it)) }
        val transport = FakeTransport()
        val wakeJob = startWake(this, dao, transport) { ctx() }
        try {
            // Ráfaga de 3 wakes: CONFLATED + debounce => UN solo flush.
            PositionOutboxDispatcher.requestFlush()
            PositionOutboxDispatcher.requestFlush()
            PositionOutboxDispatcher.requestFlush()
            settle(cycles = 2)
            assertEquals(1, transport.postedBatches.size)
            assertEquals(5, transport.postedBatches.single().size)
            assertEquals(0, dao.count())
            // Sin wakes nuevos: no hay flush de más (sin spin).
            settle(cycles = 2)
            assertEquals(1, transport.postedBatches.size)
        } finally {
            stopWake(wakeJob)
        }
    }

    @Test
    fun startWakeLoopIsNoOpWhileRunning() = runBlocking {
        val daoA = FakeDao()
        (1L..5L).forEach { daoA.insert(position(it)) }
        val daoB = FakeDao()
        (1L..5L).forEach { daoB.insert(position(it)) }
        val transportA = FakeTransport()
        val transportB = FakeTransport()
        val wakeJob = startWake(this, daoA, transportA) { ctx() }
        try {
            // Segunda llamada mientras corre: no-op, no crea otro dispatcher.
            PositionOutboxDispatcher.startWakeLoop(
                this, daoB, transportB, { ctx() }, wakeTestDebounceMs,
            )
            PositionOutboxDispatcher.requestFlush()
            settle()
            assertEquals(1, transportA.postedBatches.size)
            assertTrue(transportB.postedBatches.isEmpty())
            assertEquals(0, daoA.count())
            assertEquals(5, daoB.count())
        } finally {
            stopWake(wakeJob)
        }
    }

    @Test
    fun wakeIncludesPresenceOnlyWhenMqttExplicitlyDown() = runBlocking {
        val dao = FakeDao()
        dao.insert(position(1))
        dao.insert(position(2, control = true))
        val transport = FakeTransport()
        val originalHook = PositionOutboxDispatcher.mqttReady
        val wakeJob = startWake(this, dao, transport) { ctx() }
        try {
            // Fase 1: MQTT listo → el wake lleva SOLO posiciones.
            PositionOutboxDispatcher.mqttReady = { true }
            PositionOutboxDispatcher.requestFlush()
            settle()
            assertEquals(listOf(listOf("dmj-w-1")), transport.postedBatches)
            assertEquals(1, dao.count())
            val control = dao.allOrdered().single()
            assertEquals("dmj-w-2", control.messageId)
            assertEquals(0, control.attempts)
            // Fase 2: MQTT explícitamente caído → plan B incluye la presencia.
            PositionOutboxDispatcher.mqttReady = { false }
            PositionOutboxDispatcher.requestFlush()
            settle()
            assertEquals(
                listOf(listOf("dmj-w-1"), listOf("dmj-w-2")),
                transport.postedBatches,
            )
            assertEquals(0, dao.count())
        } finally {
            PositionOutboxDispatcher.mqttReady = originalHook
            stopWake(wakeJob)
        }
    }
}
