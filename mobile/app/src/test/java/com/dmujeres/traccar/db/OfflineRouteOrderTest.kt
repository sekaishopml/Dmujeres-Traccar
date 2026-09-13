package com.dmujeres.traccar.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Verificación exigida: una ruta capturada horas offline conserva timestamp y
 * orden originales a través del outbox.
 *
 * Fake in-memory de [PositionDao] con la semántica SQL real (orden por
 * `sequence`, filtro `retryAt`, exención de controles en la purga) que ejecuta
 * el `insertWithinLimit` DE PRODUCCIÓN (purga de expirados + overflow contra
 * la retención dura). El drenaje simulado usa `allDue(now, 50)` + `delete`
 * (ACK `accepted`), igual que `HttpFallbackDispatcher` y `MqttManager`.
 */
class OfflineRouteOrderTest {

    private class FakeDao : PositionDao() {
        val rows = mutableListOf<PendingPosition>()
        private var seq: Long? = null

        override suspend fun insert(position: PendingPosition) {
            rows.removeAll { it.messageId == position.messageId }
            rows.add(position)
        }

        override suspend fun allOrdered(): List<PendingPosition> =
            rows.sortedBy { it.sequence }

        override suspend fun allOrdered(limit: Int): List<PendingPosition> =
            rows.sortedBy { it.sequence }.take(limit)

        override suspend fun ensureSequence(state: SequenceState) {
            if (seq == null) seq = state.sequence
        }

        override suspend fun incrementSequence() {
            seq = (seq ?: 0L) + 1
        }

        override suspend fun currentSequence(): Long = seq ?: 0L

        override suspend fun maxPendingSequence(): Long? =
            rows.maxOfOrNull { it.sequence }

        override suspend fun raiseSequenceTo(minimum: Long) {
            seq = maxOf(seq ?: 0L, minimum)
        }

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

        override suspend fun nextDue(now: Long): PendingPosition? =
            rows.filter { it.retryAt <= now || it.retryAt == 0L }.minByOrNull { it.sequence }

        override suspend fun allDue(now: Long): List<PendingPosition> =
            rows.filter { it.retryAt <= now || it.retryAt == 0L }.sortedBy { it.sequence }

        override suspend fun allDue(now: Long, limit: Int): List<PendingPosition> =
            allDue(now).take(limit)

        override suspend fun dueControls(now: Long, limit: Int): List<PendingPosition> =
            rows.filter { it.isControl && (it.retryAt <= now || it.retryAt == 0L) }
                .sortedBy { it.sequence }.take(limit)

        override suspend fun minFutureRetryAt(now: Long): Long? =
            rows.map { it.retryAt }.filter { it > now }.minOrNull()

        override suspend fun deleteOldestNonControl(count: Int): Int {
            val victims = rows.filter {
                !it.isControl &&
                    !it.payload.contains("\"journeyStarted\":true") &&
                    !it.payload.contains("\"journeyEnded\":true")
            }.sortedBy { it.sequence }.take(count)
            rows.removeAll(victims.toSet())
            return victims.size
        }

        override suspend fun deleteExpiredNonControl(cutoffMs: Long): Int {
            val victims = rows.filter {
                !it.isControl && it.enqueuedAt > 0 && it.enqueuedAt < cutoffMs
            }
            rows.removeAll(victims.toSet())
            return victims.size
        }

        override suspend fun delete(messageId: String): Int {
            val before = rows.size
            rows.removeAll { it.messageId == messageId }
            return before - rows.size
        }

        override fun countFlow(): Flow<Int> = flow { emit(rows.size) }

        override suspend fun count(): Int = rows.size

        override suspend fun clear() {
            rows.clear()
        }

        override suspend fun insertDeadLetter(deadLetter: DeadLetter): Long = 1L

        override suspend fun deadLetterCount(): Int = 0

        override suspend fun deadLetters(limit: Int): List<DeadLetter> = emptyList()

        override suspend fun oldestEnqueuedAt(): Long? =
            rows.map { it.enqueuedAt }.filter { it > 0 }.minOrNull()
    }

    private fun position(seq: Long, observedAt: String, enqueuedAt: Long): PendingPosition =
        PendingPosition(
            messageId = "dmj-test-$seq",
            deviceId = "juan-001",
            sequence = seq,
            payload = "{\"type\":\"position\",\"sequence\":$seq}",
            observedAt = observedAt,
            enqueuedAt = enqueuedAt,
        )

    @Test
    fun rutaLargaOfflineConservaOrdenYTimestamp() = runBlocking {
        val dao = FakeDao()
        val base = Instant.parse("2026-03-01T08:00:00Z")
        val enqueueBase = System.currentTimeMillis()
        val n = 1_000
        val observed = (0 until n).map { base.plusSeconds(it * 10L).toString() }
        // Captura offline: sequence durable + timestamp GPS real por fix.
        for (i in 0 until n) {
            val seq = dao.nextSequence(0L)
            val r = dao.insertWithinLimit(position(seq, observed[i], enqueueBase + i * 10L), 5_000)
            assertTrue("inserción $i debe conservarse (r=$r)", r >= 0)
        }
        assertEquals(n, dao.count())
        // Recovery: drain FIFO por lotes de 50 con ACK accepted.
        val now = System.currentTimeMillis()
        val deletedSeq = mutableListOf<Long>()
        val deletedObserved = mutableListOf<String>()
        while (dao.count() > 0) {
            val batch = dao.allDue(now, 50)
            assertTrue("lote no vacío con backlog", batch.isNotEmpty())
            for (p in batch) {
                deletedSeq.add(p.sequence)
                deletedObserved.add(p.observedAt)
                assertEquals("ACK accepted elimina", 1, dao.delete(p.messageId))
            }
        }
        // Orden original 1..N sin huecos ni duplicados; timestamps intactos.
        assertEquals((1L..n.toLong()).toList(), deletedSeq)
        assertEquals(observed, deletedObserved)
    }

    @Test
    fun purgaExpiradosPreservaOrdenDeSupervivientes() = runBlocking {        val dao = FakeDao()
        val now = System.currentTimeMillis()
        val old = now - OutboxRetentionPolicy.MAX_AGE_MS - 3_600_000L
        // 100 posiciones de hace >7 días (el servidor las rechazaría con
        // expired): backlog acumulado, insert directo sin política.
        for (i in 1..100) {
            val seq = dao.nextSequence(0L)
            dao.insert(position(seq, "2026-01-01T00:00:00Z", old + i))
        }
        assertEquals(100, dao.count())
        // 50 frescas vía política: la primera purga los 100 expirados.
        val freshObserved = mutableListOf<String>()
        var firstPurge = -1
        for (i in 1..50) {
            val seq = dao.nextSequence(0L)
            val obs = Instant.ofEpochMilli(now - 60_000L + i * 1_000L).toString()
            freshObserved.add(obs)
            val r = dao.insertWithinLimit(position(seq, obs, now - 60_000L + i * 1_000L), 5_000)
            assertTrue(r >= 0)
            if (firstPurge < 0) firstPurge = r
        }
        assertTrue("debió purgar expirados, purgó=$firstPurge", firstPurge >= 100)
        // Un insert más ya no purga nada y conserva el orden de supervivientes.
        val extra = dao.insertWithinLimit(
            position(dao.nextSequence(0L), Instant.ofEpochMilli(now).toString(), now), 5_000,
        )
        assertEquals(0, extra)
        val survivors = dao.allDue(now, 1_000)
        assertEquals(51, survivors.size)
        assertEquals((101L..151L).toList(), survivors.map { it.sequence })
        assertEquals(freshObserved, survivors.take(50).map { it.observedAt })
    }

    @Test
    fun controlesDeJornadaExentosDePurga() = runBlocking {
        val dao = FakeDao()
        val now = System.currentTimeMillis()
        val old = now - OutboxRetentionPolicy.MAX_AGE_MS - 3_600_000L
        // started antiguo: debe sobrevivir a la purga por edad.
        val startedSeq = dao.nextSequence(0L)
        dao.insert(
            PendingPosition(
                messageId = "dmj-started", deviceId = "juan-001", sequence = startedSeq,
                payload = "{\"type\":\"presence\",\"payload\":{\"journeyStarted\":true}}",
                observedAt = "2026-01-01T00:00:00Z", enqueuedAt = old,
                isControl = true, journeyId = 1L,
            ),
        )
        dao.insertWithinLimit(position(dao.nextSequence(0L), "2026-03-01T00:00:00Z", now), 5_000)
        assertEquals(2, dao.count())
        assertTrue(dao.allOrdered().any { it.messageId == "dmj-started" })
    }

    @Test
    fun regressionAdminOutageLargoSinPerdidaSilenciosa() = runBlocking {
        // REGRESSION ADMIN: outage de ~17 h a 5 s (≈12 000 fixes) con el default
        // antiguo de 5 000 se perdía por DROP_OLDEST silencioso. Con la retención
        // efectiva (100 000) todo se conserva y drena en orden.
        val dao = FakeDao()
        val base = Instant.parse("2026-03-01T08:00:00Z")
        val enqueueBase = System.currentTimeMillis()
        val n = 12_000
        for (i in 0 until n) {
            val seq = dao.nextSequence(0L)
            val r = dao.insertWithinLimit(position(seq, base.plusSeconds(i * 5L).toString(), enqueueBase), 5_000)
            assertTrue("inserción $i debe conservarse (r=$r)", r >= 0)
        }
        assertEquals(n, dao.count())
        // Drenaje completo FIFO: primero y último intactos.
        val now = System.currentTimeMillis()
        var first = -1L
        var last = -1L
        var count = 0
        while (dao.count() > 0) {
            val batch = dao.allDue(now, 50)
            assertTrue(batch.isNotEmpty())
            if (first < 0) first = batch.first().sequence
            last = batch.last().sequence
            count += batch.size
            batch.forEach { dao.delete(it.messageId) }
        }
        assertEquals(1L, first)
        assertEquals(n.toLong(), last)
        assertEquals(n, count)
    }
}
