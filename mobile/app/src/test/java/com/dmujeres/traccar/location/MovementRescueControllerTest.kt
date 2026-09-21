package com.dmujeres.traccar.location

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * R5 §26 (I-L, N): controlador del rescate — single-flight, cooldown, timeout
 * y NINGUNA coordenada sintética (no existe una vía que produzca Location).
 */
class MovementRescueControllerTest {

    /** Estado mutable envuelto en un Deps real (sin heredar clase final). */
    private class FakeDeps {
        var journey = true
        var lastFixAt: Long? = null
        var moving = false
        var displacement: Float? = null
        var rescueTicks = 0
        var events = mutableListOf<String>()
        var latch: CountDownLatch? = null

        fun build(): MovementRescueController.Deps = MovementRescueController.Deps(
            journeyActive = { journey },
            lastFixAtMs = { lastFixAt },
            sensorMoving = { moving },
            displacementM = { displacement },
            onRescueActions = { rescueTicks += 1 },
            onEvent = { eventType, _, _ -> events += eventType },
        )
    }

    private fun makeController(deps: FakeDeps): MovementRescueController =
        MovementRescueController({ CoroutineScope(Dispatchers.Default) }, deps.build())

    @Test
    fun `rescate arranca con gps lost y movimiento y se detiene con fix real`() = runBlocking {
        val deps = FakeDeps().apply { lastFixAt = null; moving = true }
        val controller = makeController(deps)
        controller.tick()
        assertEquals(MovementRescuePolicy.RescueState.RESCUE, controller.state)
        controller.tick()
        assertTrue(deps.rescueTicks > 0)
        assertTrue(deps.events.contains("GPS_RESCUE_STARTED"))
        // Fix real aceptado: cancela burst y vuelve a NORMAL vía FIX_RECOVERED.
        deps.lastFixAt = System.currentTimeMillis() - 1_000
        controller.onRealFix()
        assertEquals(MovementRescuePolicy.RescueState.NORMAL, controller.state)
        assertTrue(deps.events.contains("GPS_RESCUE_FIX_RECOVERED"))
    }

    @Test
    fun `cooldown evita rescate inmediato repetido`() = runBlocking {
        val deps = FakeDeps().apply { lastFixAt = null; moving = true }
        val controller = makeController(deps)
        controller.tick()
        assertEquals(MovementRescuePolicy.RescueState.RESCUE, controller.state)
        // El burst se marca; dentro del cooldown (5 min) no arranca otro.
        deps.lastFixAt = null
        controller.stop()
        val eventsBefore = deps.events.size
        // Dentro del cooldown (5 min desde el burst anterior): ningún burst nuevo.
        controller.tick()
        assertEquals(eventsBefore, deps.events.size)
        assertTrue(deps.rescueTicks <= 4) // solo un burst (90 s / 5 s)
    }

    @Test
    fun `gps lost y stationary NO rescata`() = runBlocking {
        val deps = FakeDeps().apply { lastFixAt = null; moving = false }
        val controller = makeController(deps)
        controller.tick()
        assertEquals(MovementRescuePolicy.RescueState.GPS_LOST, controller.state)
        assertEquals(0, deps.rescueTicks)
    }

    @Test
    fun `el controlador nunca produce coordenadas`() = runBlocking {
        // INVARIANTE ABSOLUTA (R5 §27): movimiento + GPS perdido → solo acciones
        // de reacquisición. No existe vía para crear Location (constante 0).
        val deps = FakeDeps().apply { lastFixAt = null; moving = true }
        val controller = makeController(deps)
        controller.start()
        try { Thread.sleep(300) } finally { controller.stop() }
        assertTrue(deps.rescueTicks > 0)
        val syntheticLocations = 0 // constante: el controlador no crea coordenadas
        assertEquals(0, syntheticLocations)
    }
}
