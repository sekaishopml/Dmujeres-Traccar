package com.dmujeres.traccar.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Cuarentena (dead-letter) del outbox: mensajes con NACK terminal del servidor
 * (`rejected`/`invalid`/`expired`) que NUNCA se borran en silencio.
 *
 * Antes se eliminaban tras el NACK y la evidencia se perdía (¿payload corrupto?
 * ¿reloj desviado? ¿contrato desfasado?). Ahora se trasladan aquí con el motivo,
 * conservando identidad (messageId/sequence/deviceId), tiempos (fix/encolado)
 * y metadatos del payload para diagnóstico. Sin purga automática: si esta tabla
 * crece es señal de un bug o deriva de contrato que hay que investigar.
 */
@Entity(
    tableName = "dead_letters",
    indices = [
        Index(value = ["sequence"]),
        Index(value = ["reason"]),
        Index(value = ["quarantinedAt"])
    ]
)
data class DeadLetter(
    @PrimaryKey val messageId: String,
    val deviceId: String,
    val sequence: Long,
    /** Causa del servidor: rejected|invalid|expired. */
    val reason: String,
    /** observedAt original (hora GPS real). */
    val observedAt: String,
    @ColumnInfo(defaultValue = "0")
    val enqueuedAt: Long = 0L,
    /** Intentos acumulados antes del NACK terminal. */
    val attempts: Int = 0,
    /** Tamaño del payload (metadato, no el payload completo). */
    val payloadBytes: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val quarantinedAt: Long = System.currentTimeMillis()
) {
    companion object {
        /** Construye la cuarentena desde el pendiente + motivo, sin copiar el payload. */
        fun fromPending(position: PendingPosition, reason: String, nowMs: Long): DeadLetter =
            DeadLetter(
                messageId = position.messageId,
                deviceId = position.deviceId,
                sequence = position.sequence,
                reason = reason,
                observedAt = position.observedAt,
                enqueuedAt = position.enqueuedAt,
                attempts = position.attempts,
                payloadBytes = position.payload.toByteArray(Charsets.UTF_8).size,
                quarantinedAt = nowMs,
            )
    }
}
