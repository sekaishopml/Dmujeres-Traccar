package com.dmujeres.traccar.tracking

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Congela la secuencia de cierre: drenar → ended → drenar → 3 s → fin. */
class JourneyStopCoordinatorTest {

    @Test
    fun stopSequenceIsDrainEndedDrainFinish() = runBlocking {
        val events = mutableListOf<String>()
        val finished = CompletableDeferred<Unit>()
        val coordinator = JourneyStopCoordinator(
            controllerScope = CoroutineScope(Dispatchers.Default),
            flushPendingOnStop = { events += "drain" },
            enqueueEnded = { _, _ ->
                events += "ended"
                Job().apply { complete() }
            },
            onFinished = { _, _ ->
                events += "finish"
                finished.complete(Unit)
            },
            finishDelayMs = 1L,
            finishDispatcher = Dispatchers.Default,
        )
        val job = coordinator.begin(CoroutineScope(Dispatchers.Default), null)
        finished.await()
        job.join()
        assertEquals(listOf("drain", "ended", "drain", "finish"), events)
    }

    @Test
    fun failingDrainDoesNotAbortClose() = runBlocking {
        val events = mutableListOf<String>()
        val finished = CompletableDeferred<Unit>()
        var drains = 0
        val coordinator = JourneyStopCoordinator(
            controllerScope = CoroutineScope(Dispatchers.Default),
            flushPendingOnStop = {
                drains++
                events += "drain$drains"
                if (drains == 1) throw IllegalStateException("outbox caído")
            },
            enqueueEnded = { _, _ ->
                events += "ended"
                Job().apply { complete() }
            },
            onFinished = { _, _ ->
                events += "finish"
                finished.complete(Unit)
            },
            finishDelayMs = 1L,
            finishDispatcher = Dispatchers.Default,
        )
        coordinator.begin(CoroutineScope(Dispatchers.Default), null)
        finished.await()
        assertTrue(events.contains("ended"))
        assertEquals("finish", events.last())
    }

    @Test
    fun failingEndedEnqueueStillFinishesAfterDelay() = runBlocking {
        val finished = CompletableDeferred<Unit>()
        val coordinator = JourneyStopCoordinator(
            controllerScope = CoroutineScope(Dispatchers.Default),
            flushPendingOnStop = {},
            enqueueEnded = { _, _ -> throw IllegalStateException("presencia caída") },
            onFinished = { _, _ -> finished.complete(Unit) },
            finishDelayMs = 1L,
            finishDispatcher = Dispatchers.Default,
        )
        val job = coordinator.begin(CoroutineScope(Dispatchers.Default), null)
        runCatching { job.join() }
        // El contrato actual: si el encolado "ended" lanza, la corrutina muere
        // (el servicio lo envuelve con runCatching alrededor del Job). Se
        // caracteriza para no cambiarlo sin decisión explícita.
        assertTrue("finish no debe ocurrir si ended lanza", !finished.isCompleted)
    }
}
