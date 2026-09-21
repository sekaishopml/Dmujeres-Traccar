package com.dmujeres.traccar.transport

/**
 * R7/R11: frontera de persistencia de la cola de CONTROL (presencia).
 *
 * `transport` NO conoce `data`/Room: solo este puerto. El dueño real de la
 * cola (`outbox.RoomControlQueueStore`) lo implementa y el servicio lo inyecta.
 * Mismo comportamiento observable: mismas consultas, mismo orden y mismos
 * campos que la entidad de Room.
 */
data class ControlItem(
    val messageId: String,
    val deviceId: String,
    val sequence: Long,
    val payload: String,
    val observedAt: String,
    val enqueuedAt: Long,
    val isControl: Boolean,
    val journeyId: Long,
    val attempts: Int,
    val retryAt: Long,
)

interface ControlQueueStore {

    /** Controles vencidos (isControl=1, retryAt<=now o 0) en orden FIFO. */
    suspend fun dueControls(now: Long, limit: Int): List<ControlItem>

    /** MIN(retryAt) futuro de la cola (o null si no hay). */
    suspend fun minFutureRetryAt(now: Long): Long?

    /** Borra por messageId; devuelve filas borradas (0 = ya no estaba). */
    suspend fun delete(messageId: String): Int

    suspend fun updateAttempts(messageId: String, attempts: Int)

    suspend fun updateRetryAt(messageId: String, retryAt: Long)

    /** Mueve a cuarentena (NACK terminal). true si se movió a dead_letters. */
    suspend fun quarantine(item: ControlItem, reason: String, nowMs: Long): Boolean
}
