package com.dmujeres.traccar.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * DAO del heartbeat local de salud. Inserta con IGNORE sobre el índice único
 * (sessionId, wallBucket): idempotencia sin transacciones extra — el mismo
 * bucket re-escrito no duplica fila.
 */
@Dao
interface HealthSnapshotDao {

    /** Insert idempotente: ignora el snapshot si el bucket ya existe. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(snapshot: HealthSnapshot): Long

    /** Último snapshot persistido (evidencia más reciente de proceso vivo). */
    @Query("SELECT * FROM health_snapshots ORDER BY id DESC LIMIT 1")
    suspend fun last(): HealthSnapshot?

    /** Snapshots de una sesión, por orden de tiempo. */
    @Query("SELECT * FROM health_snapshots WHERE sessionId = :sessionId ORDER BY wallMs ASC")
    suspend fun bySession(sessionId: String): List<HealthSnapshot>

    /** Snapshots recientes (retención manual fuera de la política del outbox). */
    @Query("SELECT * FROM health_snapshots WHERE wallMs >= :sinceMs ORDER BY wallMs ASC")
    suspend fun since(sinceMs: Long): List<HealthSnapshot>

    /** Retención: borra snapshots más viejos que el corte indicado. */
    @Query("DELETE FROM health_snapshots WHERE wallMs < :beforeMs")
    suspend fun deleteBefore(beforeMs: Long): Int

    /** Snapshots pendientes de subir, más viejos primero (FIFO acotado). */
    @Query("SELECT * FROM health_snapshots WHERE uploadedAt = 0 ORDER BY wallMs ASC LIMIT :limit")
    suspend fun unsent(limit: Int): List<HealthSnapshot>

    /** Marca snapshots como aceptados por el servidor (epoch ms del ACK). */
    @Query("UPDATE health_snapshots SET uploadedAt = :at WHERE id IN (:ids)")
    suspend fun markUploaded(ids: List<Long>, at: Long)
}
