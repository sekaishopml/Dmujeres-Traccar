package com.dmujeres.traccar.mqtt

import com.dmujeres.traccar.db.DeadLetter
import com.dmujeres.traccar.db.PendingPosition
import com.dmujeres.traccar.db.PositionDao
import com.dmujeres.traccar.db.SequenceState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Propietario único HTTP-first: FIFO por sequence, ACK de negocio por item,
 * cuarentena en NACK terminal (nunca borrado silencioso), backoff con jitter
 * en reintento y transporte fallido sin mutaciones. Puro JVM con DAO y
 * transporte falsos (misma semántica SQL que Room).
 */
class PositionOutboxDispatcherTest {

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
    ) : PositionOutboxDispatcher.Transport {
        val postedBatches = mutableListOf<List<String>>()

        override suspend fun post(
            ctx: PositionOutboxDispatcher.DispatchContext,
            items: List<PendingPosition>,
        ): PositionOutboxDispatcher.PostResult {
            postedBatches.add(items.map { it.messageId })
            failWith?.let { throw it }
            if (!transportOk) {
                return PositionOutboxDispatcher.PostResult(emptyMap(), transportOk = false, httpCode = 503)
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
        messageId = "dmj-t-$seq",
        deviceId = "juan-001",
        sequence = seq,
        payload = if (control) {
            "{\"schema\":1,\"type\":\"presence\",\"messageId\":\"dmj-t-$seq\",\"payload\":{}}"
        } else {
            "{\"schema\":1,\"type\":\"position\",\"messageId\":\"dmj-t-$seq\"," +
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

    @Test
    fun fifoAcceptedDeletesInOrder() = runBlocking {
        val dao = FakeDao()
        (5L downTo 1L).forEach { dao.insert(position(it)) }
        val confirmed = mutableListOf<String>()
        val transport = FakeTransport()
        val outcome = PositionOutboxDispatcher.flushOnce(
            dao, transport, ctx(confirmed), includePresence = true,
        )
        assertEquals(5, outcome.confirmed)
        assertEquals(0, dao.count())
        // Lote posteado en orden ascendente de sequence (FIFO).
        assertEquals(
            listOf("dmj-t-1", "dmj-t-2", "dmj-t-3", "dmj-t-4", "dmj-t-5"),
            transport.postedBatches.single(),
        )
        assertEquals(listOf("dmj-t-1", "dmj-t-2", "dmj-t-3", "dmj-t-4", "dmj-t-5"), confirmed)
    }

    @Test
    fun duplicateDeletesWithoutLoss() = runBlocking {
        val dao = FakeDao()
        dao.insert(position(1))
        dao.insert(position(2))
        val outcome = PositionOutboxDispatcher.flushOnce(
            dao,
            FakeTransport(statuses = mapOf("dmj-t-1" to "duplicate", "dmj-t-2" to "accepted")),
            ctx(),
            includePresence = true,
        )
        assertEquals(2, outcome.confirmed)
        assertEquals(0, dao.count())
        assertEquals(0, dao.deadLetterCount())
    }

    @Test
    fun terminalNackQuarantinesNeverDeletesSilently() = runBlocking {
        val dao = FakeDao()
        dao.insert(position(1))
        dao.insert(position(2))
        dao.insert(position(3))
        val quarantined = mutableListOf<String>()
        val outcome = PositionOutboxDispatcher.flushOnce(
            dao,
            FakeTransport(statuses = mapOf(
                "dmj-t-1" to "rejected", "dmj-t-2" to "invalid", "dmj-t-3" to "expired",
            )),
            ctx(quarantined = quarantined),
            includePresence = true,
        )
        assertEquals(0, outcome.confirmed)
        assertEquals(3, outcome.quarantined)
        assertEquals(0, dao.count())
        assertEquals(3, dao.deadLetterCount())
        assertEquals(listOf("dmj-t-1", "dmj-t-2", "dmj-t-3"), quarantined)
        val reasons = dao.deadLetters(10).associate { it.messageId to it.reason }
        assertEquals("rejected", reasons["dmj-t-1"])
        assertEquals("invalid", reasons["dmj-t-2"])
        assertEquals("expired", reasons["dmj-t-3"])
        // Evidencia preservada: identidad + tiempos originales.
        val kept = dao.deadLetters(10).first { it.messageId == "dmj-t-1" }
        assertEquals(1L, kept.sequence)
        assertEquals("2026-01-01T00:00:00Z", kept.observedAt)
    }

    @Test
    fun pendingSchedulesBackoffWithoutDelete() = runBlocking {
        val dao = FakeDao()
        dao.insert(position(1, attempts = 2))
        val before = System.currentTimeMillis()
        val outcome = PositionOutboxDispatcher.flushOnce(
            dao, FakeTransport(defaultStatus = "pending"), ctx(), includePresence = true,
        )
        assertEquals(0, outcome.confirmed)
        assertEquals(1, outcome.retryScheduled)
        assertEquals(1, dao.count())
        val row = dao.allOrdered().single()
        assertEquals(3, row.attempts)
        // Backoff 5s * 2^3 = 40 s ±25% jitter.
        val wait = row.retryAt - before
        assertTrue("backoff $wait", wait in 30_000L..50_000L)
    }

    @Test
    fun transportFailureKeepsRoomIntactWithoutBumpingAttempts() = runBlocking {
        val dao = FakeDao()
        dao.insert(position(1, attempts = 4))
        val outcome = PositionOutboxDispatcher.flushOnce(
            dao, FakeTransport(failWith = RuntimeException("red caída")), ctx(), includePresence = true,
        )
        assertEquals(false, outcome.transportOk)
        assertEquals(1, dao.count())
        val row = dao.allOrdered().single()
        assertEquals(4, row.attempts)
        assertEquals(0L, row.retryAt)
    }

    @Test
    fun httpErrorKeepsRoomIntact() = runBlocking {
        val dao = FakeDao()
        dao.insert(position(1))
        val outcome = PositionOutboxDispatcher.flushOnce(
            dao, FakeTransport(transportOk = false), ctx(), includePresence = true,
        )
        assertEquals(false, outcome.transportOk)
        assertEquals(1, dao.count())
        assertEquals(0, dao.deadLetterCount())
    }

    @Test
    fun presenceSkippedWhenMqttHealthy() = runBlocking {
        val dao = FakeDao()
        dao.insert(position(1))
        dao.insert(position(2, control = true))
        val transport = FakeTransport()
        val outcome = PositionOutboxDispatcher.flushOnce(
            dao, transport, ctx(), includePresence = false,
        )
        assertEquals(1, outcome.confirmed)
        assertEquals(listOf("dmj-t-1"), transport.postedBatches.single())
        // La presencia queda intacta para MQTT en vivo (sin tocar attempts).
        val left = dao.allOrdered().single()
        assertEquals("dmj-t-2", left.messageId)
        assertEquals(0, left.attempts)
    }

    @Test
    fun presenceIncludedWhenMqttDown() = runBlocking {
        val dao = FakeDao()
        dao.insert(position(2, control = true))
        val transport = FakeTransport()
        val outcome = PositionOutboxDispatcher.flushOnce(
            dao, transport, ctx(), includePresence = true,
        )
        assertEquals(1, outcome.confirmed)
        assertEquals(listOf("dmj-t-2"), transport.postedBatches.single())
        assertEquals(0, dao.count())
    }

    @Test
    fun batchRespectsCapAndBackoffItemsWait() = runBlocking {
        val dao = FakeDao()
        (1L..10L).forEach { dao.insert(position(it)) }
        // 3 en backoff futuro: no salen aunque haya cupo.
        val future = System.currentTimeMillis() + 300_000L
        dao.updateRetryAt("dmj-t-1", future)
        dao.updateRetryAt("dmj-t-2", future)
        dao.updateRetryAt("dmj-t-3", future)
        val transport = FakeTransport()
        val outcome = PositionOutboxDispatcher.flushOnce(
            dao, transport, ctx(), nowMs = System.currentTimeMillis(),
            batchSize = 5, includePresence = true,
        )
        assertEquals(5, outcome.confirmed)
        assertEquals(listOf("dmj-t-4", "dmj-t-5", "dmj-t-6", "dmj-t-7", "dmj-t-8"),
            transport.postedBatches.single())
        assertEquals(5, dao.count())
    }

    @Test
    fun payloadAndTimestampsUntouched() = runBlocking {
        val dao = FakeDao()
        dao.insert(position(9))
        val payloadBefore = dao.allOrdered().single().payload
        PositionOutboxDispatcher.flushOnce(
            dao, FakeTransport(defaultStatus = "throttled"), ctx(), includePresence = true,
        )
        val row = dao.allOrdered().single()
        assertEquals(payloadBefore, row.payload)
        assertEquals("2026-01-01T00:00:00Z", row.observedAt)
        assertEquals(9L, row.sequence)
    }

    @Test
    fun isCurrentJourneyPositionOnlyCurrentPositions() {
        val journey = 7L
        assertTrue(PositionOutboxDispatcher.isCurrentJourneyPosition(journey, position(1)))
        assertTrue(!PositionOutboxDispatcher.isCurrentJourneyPosition(journey, position(2, control = true)))
        assertTrue(!PositionOutboxDispatcher.isCurrentJourneyPosition(journey, position(3, journey = 999L)))
        assertTrue(!PositionOutboxDispatcher.isCurrentJourneyPosition(0L, position(4)))
    }
}
