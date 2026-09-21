package com.dmujeres.traccar.tracking

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * UX §18: refresco manual seguro. El coordinador NO tiene puertos para
 * detener/borrar/crear jornadas: por interfaz no puede violar los invariantes
 * (no duplica posición, no pierde Outbox, no crea servicios).
 */
class ManualRefreshCoordinatorTest {

    private class FakePort(
        var network: Boolean = true,
        var hasJourney: Boolean = true,
        var serviceRunning: Boolean = true,
        var gpsOk: Boolean = true,
        var configOk: Boolean = true,
        var mqttOk: Boolean = true,
        var serverOk: Boolean = true,
        var pending: Int = 0,
        var healthOk: Boolean = true,
        /** bloquea ensureTracking hasta liberar (test de concurrencia) */
        var latch: CountDownLatch? = null,
    ) : RefreshPort {
        var ensureCalls = 0
        var resumedCalls = 0
        var drainCalls = 0

        override suspend fun isNetworkAvailable(): Boolean = network
        override suspend fun ensureTracking(): RefreshTrackingState {
            ensureCalls += 1
            latch?.await(5, TimeUnit.SECONDS)
            if (!hasJourney) return RefreshTrackingState(false, false, serviceRunning)
            if (!serviceRunning) {
                resumedCalls += 1
                serviceRunning = true
                return RefreshTrackingState(true, resumed = true, alreadyRunning = false)
            }
            return RefreshTrackingState(true, resumed = false, alreadyRunning = true)
        }
        override suspend fun syncConfig(): Boolean = configOk
        override suspend fun nudgeGps(): Boolean = gpsOk
        override suspend fun checkMqtt(): Boolean = mqttOk
        override suspend fun drainOutboxSignal(): Int {
            drainCalls += 1
            return pending
        }
        override suspend fun sendHealth(): Boolean = healthOk
        override suspend fun requestServerState(): Boolean = serverOk
    }

    @Test
    fun `refresh normal con todo OK es UPDATED`() = runBlocking {
        val port = FakePort()
        val r = ManualRefreshCoordinator(port).refresh()
        assertEquals(RefreshOutcome.UPDATED, r.outcome)
        assertEquals(1, port.ensureCalls)
        assertEquals(0, port.resumedCalls) // ya estaba activo: NO se toca
    }

    @Test
    fun `outbox con pendientes reporta SYNCED_PENDING`() = runBlocking {
        val port = FakePort(pending = 7)
        val r = ManualRefreshCoordinator(port).refresh()
        assertEquals(RefreshOutcome.SYNCED_PENDING, r.outcome)
        assertEquals(7, r.pending)
        assertTrue(port.drainCalls > 0)
    }

    @Test
    fun `sin red conserva datos y no miente`() = runBlocking {
        val port = FakePort(network = false, pending = 3)
        val r = ManualRefreshCoordinator(port).refresh()
        assertEquals(RefreshOutcome.OFFLINE_KEEPING_DATA, r.outcome)
        assertEquals(3, r.pending)
        assertFalse(port.resumedCalls > 0)
    }

    @Test
    fun `servidor no disponible con red tambien conserva datos`() = runBlocking {
        val port = FakePort(configOk = false, mqttOk = false, serverOk = false, pending = 2)
        val r = ManualRefreshCoordinator(port).refresh()
        assertEquals(RefreshOutcome.OFFLINE_KEEPING_DATA, r.outcome)
        assertEquals(2, r.pending)
    }

    @Test
    fun `sin jornada no inicia nada y avisa listo`() = runBlocking {
        val port = FakePort(hasJourney = false, serviceRunning = false)
        val r = ManualRefreshCoordinator(port).refresh()
        assertEquals(RefreshOutcome.NO_JOURNEY, r.outcome)
        assertEquals(0, port.resumedCalls) // NUNCA arranca servicio sin jornada
    }

    @Test
    fun `jornada activa con servicio caido se reanuda con el mecanismo existente`() = runBlocking {
        val port = FakePort(serviceRunning = false)
        val r = ManualRefreshCoordinator(port).refresh()
        assertEquals(1, port.resumedCalls) // ACTION_START existente, una sola vez
        assertEquals(RefreshOutcome.UPDATED, r.outcome)
    }

    @Test
    fun `gps no disponible reporta GPS_SEARCHING`() = runBlocking {
        val port = FakePort(gpsOk = false)
        val r = ManualRefreshCoordinator(port).refresh()
        assertEquals(RefreshOutcome.GPS_SEARCHING, r.outcome)
    }

    @Test
    fun `mqtt caido pero http ok sigue siendo UPDATED`() = runBlocking {
        val port = FakePort(mqttOk = false) // config/server OK → hay servidor
        val r = ManualRefreshCoordinator(port).refresh()
        assertEquals(RefreshOutcome.UPDATED, r.outcome)
    }

    @Test
    fun `doble refresh simultaneo se rechaza (single-flight)`() {
        val port = FakePort(latch = CountDownLatch(1))
        val coordinator = ManualRefreshCoordinator(port)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<RefreshResult> { runBlocking { coordinator.refresh() } }
            // Espera a que el primero esté dentro.
            while (port.ensureCalls == 0) Thread.sleep(10)
            val second = pool.submit<RefreshResult> { runBlocking { coordinator.refresh() } }
            val secondResult = second.get(3, TimeUnit.SECONDS)
            assertEquals(RefreshOutcome.ALREADY_RUNNING, secondResult.outcome)
            port.latch!!.countDown()
            assertEquals(RefreshOutcome.UPDATED, first.get(5, TimeUnit.SECONDS).outcome)
            assertEquals(1, port.ensureCalls) // el segundo no re-ejecutó el flujo
        } finally {
            port.latch?.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `recovery activo (servicio vivo) no detiene nada`() = runBlocking {
        // El puerto no expone stop/delete: por diseño no puede destruir sesión.
        val port = FakePort(serviceRunning = true, configOk = false, mqttOk = false, serverOk = true)
        val r = ManualRefreshCoordinator(port).refresh()
        assertEquals(RefreshOutcome.UPDATED, r.outcome)
        assertEquals(0, port.resumedCalls)
    }
}
