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

    @Query("SELECT * FROM pending_positions WHERE retryAt <= :now OR retryAt = 0 ORDER BY sequence ASC LIMIT :limit")
    abstract suspend fun allDue(now: Long, limit: Int): List<PendingPosition>

    @Query("SELECT * FROM pending_positions WHERE isControl = 1 AND (retryAt <= :now OR retryAt = 0) ORDER BY sequence ASC LIMIT :limit")
    abstract suspend fun dueControls(now: Long, limit: Int): List<PendingPosition>

    @Query("SELECT MIN(retryAt) FROM pending_positions WHERE retryAt > :now")
    abstract suspend fun minFutureRetryAt(now: Long): Long?

    @Query("DELETE FROM pending_positions WHERE messageId IN (SELECT messageId FROM pending_positions WHERE isControl = 0 AND payload NOT LIKE '%\"journeyStarted\":true%' AND payload NOT LIKE '%\"journeyEnded\":true%' ORDER BY sequence ASC LIMIT :count)")
    abstract suspend fun deleteOldestNonControl(count: Int): Int

    /**
     * Purga por edad de la retención dura ([OutboxRetentionPolicy.MAX_AGE_MS]):
     * posiciones y heartbeats (`isControl = 0`) encolados hace más de 7 días.
     * El servidor los rechazaría con `expired` (terminal) igualmente; purgarlos
     * aquí ahorra radio/batería. Los controles de jornada (`started/ended`)
     * están exentos (1-2 filas, críticas para abrir/cerrar la jornada).
     */
    @Query("DELETE FROM pending_positions WHERE isControl = 0 AND enqueuedAt > 0 AND enqueuedAt < :cutoffMs")
    abstract suspend fun deleteExpiredNonControl(cutoffMs: Long): Int

    @Query("DELETE FROM pending_positions WHERE messageId = :messageId")
    abstract suspend fun delete(messageId: String): Int

    // ---- Cuarentena (dead-letter): NACK terminal nunca borra en silencio ----

    /**
     * Inserta en cuarentena ignorando duplicados (si la evidencia ya existe de
     * un traslado parcial previo, se conserva la original).
     * @return rowId o -1 si ya existía.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertDeadLetter(deadLetter: DeadLetter): Long

    @Query("SELECT COUNT(*) FROM dead_letters")
    abstract suspend fun deadLetterCount(): Int

    @Query("SELECT * FROM dead_letters ORDER BY quarantinedAt DESC LIMIT :limit")
    abstract suspend fun deadLetters(limit: Int): List<DeadLetter>

    /**
     * Traslada un pendiente a cuarentena en una única transacción. El insert es
     * IGNORE: si la evidencia ya existe de un traslado parcial previo, se
     * conserva la original y solo sale el pendiente (idempotente). Si el insert
     * falla por error real, la transacción revierte y el pendiente se conserva:
     * nunca se pierde ni se duplica evidencia.
     */
    @Transaction
    open suspend fun moveToDeadLetter(deadLetter: DeadLetter) {
        insertDeadLetter(deadLetter)
        delete(deadLetter.messageId)
    }

    @Query("SELECT COUNT(*) FROM pending_positions")
    abstract fun countFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM pending_positions")
    abstract suspend fun count(): Int

    @Query("SELECT MIN(NULLIF(enqueuedAt, 0)) FROM pending_positions")
    abstract suspend fun oldestEnqueuedAt(): Long?

    @Query("DELETE FROM pending_positions")
    abstract suspend fun clear()

    /**
     * Inserta respetando la retención dura ([OutboxRetentionPolicy]): primero
     * purga expirados (>7 días, que el servidor rechazaría con `expired`), y
     * solo después desaloja por overflow contra el tope efectivo
     * ([OutboxRetentionPolicy.effectiveMax], 100 000 como mínimo: sanea el
     * default antiguo de 5 000 ≈ 14 h, insuficiente para 24-72 h offline).
     *
     * Dentro de la retención NUNCA se pierde en silencio: solo el ACK de
     * aplicación terminal borra, o la purga de retención (con alerta + log en
     * el llamador cuando `discarded > 0`). Los controles (`started/ended`)
     * están exentos y pueden superar el tope por unas filas.
     *
     * @return nº de filas purgadas por retención, o -1 si no hay espacio ni
     * desalojando (cola llena solo de controles: prácticamente inalcanzable).
     */
    @Transaction
    open suspend fun insertWithinLimit(position: PendingPosition, maximum: Int): Int {
        val now = System.currentTimeMillis()
        val expired = deleteExpiredNonControl(OutboxRetentionPolicy.expiredCutoffMs(now))
        val effectiveMax = OutboxRetentionPolicy.effectiveMax(maximum)
        val toDiscard = PositionBufferPolicy.discardCount(count(), effectiveMax)
        val overflow = if (toDiscard > 0) deleteOldestNonControl(toDiscard) else 0
        // Los eventos de control (started/ended) se conservan aunque la cola tenga
        // que superar el límite por unos pocos registros.
        if (count() >= effectiveMax && !position.isControl) return -1
        insert(position)
        return expired + overflow
    }
}
