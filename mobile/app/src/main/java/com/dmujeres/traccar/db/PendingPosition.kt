package com.dmujeres.traccar.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Posición pendiente de envío (cola offline). `messageId` es la clave de idempotencia:
 * no cambia si el servidor ya la aceptó y se reintenta.
 */
@Entity(tableName = "pending_positions")
data class PendingPosition(
    @PrimaryKey val messageId: String,
    val deviceId: String,
    val sequence: Long,
    val payload: String,
    val observedAt: String,
    @ColumnInfo(defaultValue = "0")
    val enqueuedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    val isControl: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val journeyId: Long = 0L,
    val attempts: Int = 0
)
