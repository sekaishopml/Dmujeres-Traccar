package com.dmujeres.traccar.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class PositionDao {

    @Insert
    abstract suspend fun insert(position: PendingPosition)

    @Query("SELECT * FROM pending_positions ORDER BY sequence ASC")
    abstract suspend fun allOrdered(): List<PendingPosition>

    @Query("SELECT * FROM pending_positions ORDER BY sequence ASC LIMIT :limit")
    abstract suspend fun allOrdered(limit: Int): List<PendingPosition>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun ensureSequence(state: SequenceState)

    @Query("UPDATE sequence_state SET sequence = sequence + 1 WHERE id = 1")
    abstract suspend fun incrementSequence()

    @Query("SELECT sequence FROM sequence_state WHERE id = 1")
    abstract suspend fun currentSequence(): Long

    @Query("SELECT MAX(sequence) FROM pending_positions")
    abstract suspend fun maxPendingSequence(): Long?

    @Query("UPDATE sequence_state SET sequence = CASE WHEN sequence < :minimum THEN :minimum ELSE sequence END WHERE id = 1")
    abstract suspend fun raiseSequenceTo(minimum: Long)

    /** Reserva la secuencia en Room para que nunca se reutilice tras un apagado. */
    @Transaction
    open suspend fun nextSequence(initial: Long): Long {
        ensureSequence(SequenceState(sequence = initial))
        raiseSequenceTo(maxOf(initial, maxPendingSequence() ?: 0L))
        incrementSequence()
        return currentSequence()
    }

    @Query("UPDATE pending_positions SET attempts = :attempts WHERE messageId = :messageId")
    abstract suspend fun updateAttempts(messageId: String, attempts: Int)

    @Query("UPDATE pending_positions SET retryAt = :retryAt WHERE messageId = :messageId")
    abstract suspend fun updateRetryAt(messageId: String, retryAt: Long)

    @Query("SELECT * FROM pending_positions WHERE retryAt <= :now OR retryAt = 0 ORDER BY sequence ASC LIMIT 1")
    abstract suspend fun nextDue(now: Long): PendingPosition?

    @Query("SELECT * FROM pending_positions WHERE retryAt <= :now OR retryAt = 0 ORDER BY sequence ASC")
    abstract suspend fun allDue(now: Long): List<PendingPosition>

    @Query("DELETE FROM pending_positions WHERE messageId IN (SELECT messageId FROM pending_positions WHERE isControl = 0 AND payload NOT LIKE '%\"journeyStarted\":true%' AND payload NOT LIKE '%\"journeyEnded\":true%' ORDER BY sequence ASC LIMIT :count)")
    abstract suspend fun deleteOldestNonControl(count: Int): Int

    @Query("DELETE FROM pending_positions WHERE messageId = :messageId")
    abstract suspend fun delete(messageId: String): Int

    @Query("SELECT COUNT(*) FROM pending_positions")
    abstract fun countFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM pending_positions")
    abstract suspend fun count(): Int

    @Query("SELECT MIN(NULLIF(enqueuedAt, 0)) FROM pending_positions")
    abstract suspend fun oldestEnqueuedAt(): Long?

    @Query("DELETE FROM pending_positions")
    abstract suspend fun clear()

    /** Elimina lo necesario e inserta en una única transacción para no superar el límite. */
    @Transaction
    open suspend fun insertWithinLimit(position: PendingPosition, maximum: Int): Int {
        val toDiscard = PositionBufferPolicy.discardCount(count(), maximum)
        val discarded = if (toDiscard > 0) deleteOldestNonControl(toDiscard) else 0
        // Los eventos de control (started/ended) se conservan aunque la cola tenga
        // que superar el límite por unos pocos registros.
        if (count() >= maximum && !position.isControl) return -1
        insert(position)
        return discarded
    }
}
