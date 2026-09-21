package com.dmujeres.traccar.outbox

import com.dmujeres.traccar.data.PendingPosition
import com.dmujeres.traccar.data.PositionDao
import com.dmujeres.traccar.transport.ControlItem
import com.dmujeres.traccar.transport.ControlQueueStore

/**
 * R7/R11: adaptador Room del puerto [ControlQueueStore].
 *
 * Vive en `outbox` (dueño de la cola) para que `transport` no importe
 * `data`/Room. El mapeo es 1:1 campo a campo: no cambia ninguna consulta,
 * índice ni semántica de la cola.
 */
class RoomControlQueueStore(private val dao: PositionDao) : ControlQueueStore {

    private fun ControlItem.toPending(): PendingPosition = PendingPosition(
        messageId = messageId,
        deviceId = deviceId,
        sequence = sequence,
        payload = payload,
        observedAt = observedAt,
        enqueuedAt = enqueuedAt,
        isControl = isControl,
        journeyId = journeyId,
        attempts = attempts,
        retryAt = retryAt,
    )

    private fun PendingPosition.toItem(): ControlItem = ControlItem(
        messageId = messageId,
        deviceId = deviceId,
        sequence = sequence,
        payload = payload,
        observedAt = observedAt,
        enqueuedAt = enqueuedAt,
        isControl = isControl,
        journeyId = journeyId,
        attempts = attempts,
        retryAt = retryAt,
    )

    override suspend fun dueControls(now: Long, limit: Int): List<ControlItem> =
        dao.dueControls(now, limit).map { it.toItem() }

    override suspend fun minFutureRetryAt(now: Long): Long? = dao.minFutureRetryAt(now)

    override suspend fun delete(messageId: String): Int = dao.delete(messageId)

    override suspend fun updateAttempts(messageId: String, attempts: Int) =
        dao.updateAttempts(messageId, attempts)

    override suspend fun updateRetryAt(messageId: String, retryAt: Long) =
        dao.updateRetryAt(messageId, retryAt)

    override suspend fun quarantine(item: ControlItem, reason: String, nowMs: Long): Boolean =
        PositionOutboxDispatcher.quarantine(dao, item.toPending(), reason, nowMs)
}
