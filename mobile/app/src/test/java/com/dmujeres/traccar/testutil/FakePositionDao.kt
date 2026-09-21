package com.dmujeres.traccar.testutil

import com.dmujeres.traccar.data.DeadLetter
import com.dmujeres.traccar.data.PositionDao
import com.dmujeres.traccar.data.PendingPosition
import com.dmujeres.traccar.data.SequenceState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * DAO falso en memoria con la MISMA semántica SQL observable que usa el código
 * de producción (FIFO por sequence, vencidos por retryAt, cuarentena
 * idempotente). Compartido por las suites de outbox para no duplicar el fake:
 * `PositionOutboxDispatcherTest` y `OutboxCoordinatorTest`.
 */
class FakePositionDao : PositionDao() {
    val rows = mutableListOf<PendingPosition>()
    val dead = mutableListOf<DeadLetter>()

    /** Override de test: si no es null, `countFlow()` lo emite (tests de encadenado). */
    var countOverride: Int? = null

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

    override fun countFlow(): Flow<Int> = flow { emit(countOverride ?: rows.size) }
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
