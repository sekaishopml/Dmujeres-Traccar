package com.dmujeres.traccar.mqtt

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvelopeTest {

    @Test
    fun messageIdKeepsSequenceAndDoesNotCollide() {
        val first = Envelope.newMessageId("worker-01", 18452)
        val second = Envelope.newMessageId("worker-01", 18453)

        assertNotEquals(first, second)
        assertTrue(first.contains("4152c2b0"))
        assertTrue(first.contains("000000004814"))
        assertTrue(second.contains("000000004815"))
        assertTrue(first.length <= 64)
    }

    @Test
    fun messageIdsRemainUniqueAcrossAQueueBatch() {
        val ids = (1L..10_000L).map { Envelope.newMessageId("worker-01", it) }

        assertTrue(ids.toSet().size == ids.size)
        assertTrue(ids.all { it.length in 16..64 })
    }
}
