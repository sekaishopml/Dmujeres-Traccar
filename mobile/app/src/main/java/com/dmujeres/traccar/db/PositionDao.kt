package com.dmujeres.traccar.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class PositionDao {

    @Insert
    abstract suspend fun insert(position: PendingPosition)

    @Query("SELECT * FROM pending_positions ORDER BY sequence ASC")
    abstract suspend fun allOrdered(): List<PendingPosition>

    @Query("UPDATE pending_positions SET attempts = :attempts WHERE messageId = :messageId")
    abstract suspend fun updateAttempts(messageId: String, attempts: Int)

    @Query("DELETE FROM pending_positions WHERE messageId IN (SELECT messageId FROM pending_positions ORDER BY sequence ASC LIMIT :count)")
    abstract suspend fun deleteOldest(count: Int): Int

    @Query("DELETE FROM pending_positions WHERE messageId = :messageId")
    abstract suspend fun delete(messageId: String)

    @Query("SELECT COUNT(*) FROM pending_positions")
    abstract fun countFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM pending_positions")
    abstract suspend fun count(): Int

    @Query("SELECT MIN(enqueuedAt) FROM pending_positions")
    abstract suspend fun oldestEnqueuedAt(): Long?

    @Query("DELETE FROM pending_positions")
    abstract suspend fun clear()

    /** Elimina lo necesario e inserta en una única transacción para no superar el límite. */
    @Transaction
    open suspend fun insertWithinLimit(position: PendingPosition, maximum: Int): Int {
        val toDiscard = PositionBufferPolicy.discardCount(count(), maximum)
        val discarded = if (toDiscard > 0) deleteOldest(toDiscard) else 0
        insert(position)
        return discarded
    }
}
