package com.dmujeres.traccar.tracking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Caracteriza la condición de encolado de presencia del bloque original. */
class PresencePolicyTest {

    @Test
    fun normalSignalRequiresStartedNotStopping() {
        assertTrue(PresencePolicy.shouldEnqueue(null, started = true, stopping = false))
        assertFalse(PresencePolicy.shouldEnqueue(null, started = false, stopping = false))
        assertFalse(PresencePolicy.shouldEnqueue(null, started = true, stopping = true))
        assertFalse(PresencePolicy.shouldEnqueue(null, started = false, stopping = true))
    }

    @Test
    fun startedSignalOnlyRequiresNotStopping() {
        assertTrue(PresencePolicy.shouldEnqueue("started", started = true, stopping = false))
        assertTrue(PresencePolicy.shouldEnqueue("started", started = false, stopping = false))
        assertFalse(PresencePolicy.shouldEnqueue("started", started = true, stopping = true))
    }

    @Test
    fun endedSignalAlwaysGoesOut() {
        assertTrue(PresencePolicy.shouldEnqueue("ended", started = false, stopping = true))
        assertTrue(PresencePolicy.shouldEnqueue("ended", started = true, stopping = false))
    }
}
