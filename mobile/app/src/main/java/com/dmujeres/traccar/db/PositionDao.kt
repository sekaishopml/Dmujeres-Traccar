package com.dmujeres.traccar.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PositionDao {

    @Insert
    suspend fun insert(position: PendingPosition)

    @Query("SELECT * FROM pending_positions ORDER BY sequence ASC")
    suspend fun allOrdered(): List<PendingPosition>

    @Query("UPDATE pending_positions SET attempts = :attempts WHERE messageId = :messageId")
    suspend fun updateAttempts(messageId: String, attempts: Int)

    @Query("DELETE FROM pending_positions WHERE messageId IN (SELECT messageId FROM pending_positions ORDER BY sequence ASC LIMIT :count)")
    suspend fun deleteOldest(count: Int)

    @Query("DELETE FROM pending_positions WHERE messageId = :messageId")
    suspend fun delete(messageId: String)

    @Query("SELECT COUNT(*) FROM pending_positions")
    fun countFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM pending_positions")
    suspend fun count(): Int

    @Query("DELETE FROM pending_positions")
    suspend fun clear()
}
