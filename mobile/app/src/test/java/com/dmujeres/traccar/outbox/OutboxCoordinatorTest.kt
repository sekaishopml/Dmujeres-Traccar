package com.dmujeres.traccar.outbox

import com.dmujeres.traccar.data.PositionDao
import com.dmujeres.traccar.outbox.PositionOutboxDispatcher
import com.dmujeres.traccar.testutil.FakePositionDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Caracterización del coordinador del outbox ANTES de moverlo de paquete:
 * - debounce de 10 s entre drenajes (flapping WiFi↔datos),
 * - single-flight (un drenaje a la vez; el tardío se omite),
 * - presencia solo cuando MQTT no está listo,
 * - auto-encadenado al confirmar con backlog restante,
 * - corte del bucle de lotes si el transporte falla,
 * - drenaje de cierre hasta vaciar o deadline (con progreso, sin espera).
 *
 * El flush y el reloj se inyectan (frontera real con el dispatcher), así que
 * la suite es determinista y no toca red/Android.
 */
class OutboxCoordinatorTest {

    private val dao = FakePositionDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun setUp() {
        dao.rows.clear()
        dao.countOverride = null
    }

    @After fun tearDown() {
        scope.cancel()
    }

    private fun ctx() = PositionOutboxDispatcher.DispatchContext(
        webBaseUrl = "http://127.0.0.1:9",
        apiKey = "test-key",
        journeyStartAt = 7L,
    )

    private fun outcome(
        confirmed: Int = 0,
        quarantined: Int = 0,
        transportOk: Boolean = true,
    ) = PositionOutboxDispatcher.FlushOutcome(confirmed, quarantined, 0, transportOk)

    /** Coordinador con flush/reloj inyectados; [mqttReady] controla presence. */
    private fun coordinator(
        now: () -> Long,
        mqttReady: Boolean = false,
        flush: suspend (PositionDao, PositionOutboxDispatcher.DispatchContext, Boolean) ->
            PositionOutboxDispatcher.FlushOutcome,
        onHttpConfirmed: () -> Unit = {},
    ) = OutboxCoordinator(
        dispatchContextProvider = { ctx() },
        dao = { dao },
        scopeProvider = { scope },
        mqttReady = { mqttReady },
        onHttpConfirmed = onHttpConfirmed,
        flush = flush,
        now = now,
        chainDelayMs = 0L,
    )

    private fun awaitUntil(timeoutMs: Long = 3_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(5)
        }
        throw AssertionError("Condición no cumplida en ${timeoutMs}ms")
    }

    @Test
    fun debounceSkipsSecondDrainWithinWindow() {
        var t = 1_000_000L
        var calls = 0
        val first = CountDownLatch(1)
        val second = CountDownLatch(1)
        val c = coordinator(now = { t }, flush = { _, _, _ ->
            calls++
            when (calls) {
                1 -> first.countDown()
                2 -> second.countDown()
            }
            outcome()
        })

        c.drainBacklog("a")
        assertTrue("el primer drenaje debe correr", first.await(3, TimeUnit.SECONDS))
        c.drainBacklog("b")
        Thread.sleep(100)
        assertEquals("dentro de la ventana de debounce no hay segundo drenaje", 1, calls)

        t += OutboxCoordinator.DRAIN_DEBOUNCE_MS + 1
        c.drainBacklog("c")
        assertTrue("tras expirar el debounce el drenaje corre", second.await(3, TimeUnit.SECONDS))
        assertEquals(2, calls)
    }

    @Test
    fun singleFlightOmitsDrainWhileOneInProgress() {
        var t = 1_000_000L
        var calls = 0
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val c = coordinator(now = { t }, flush = { _, _, _ ->
            calls++
            entered.countDown()
            release.await(3, TimeUnit.SECONDS)
            outcome()
        })

        c.drainBacklog("a")
        assertTrue(entered.await(3, TimeUnit.SECONDS))

        t += OutboxCoordinator.DRAIN_DEBOUNCE_MS + 1
        c.drainBacklog("b")
        Thread.sleep(60)
        assertEquals("el drenaje concurrente se omite (single-flight)", 1, calls)

        release.countDown()
        awaitUntil { calls == 1 }
        assertEquals(1, calls)
    }

    @Test
    fun presenceIncludedWhenMqttNotReady() {
        var presence: Boolean? = null
        val done = CountDownLatch(1)
        val c = coordinator(now = { 1_000_000L }, mqttReady = false, flush = { _, _, includePresence ->
            presence = includePresence
            done.countDown()
            outcome()
        })
        c.drainBacklog("red")
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertEquals(true, presence)
    }

    @Test
    fun presenceExcludedWhenMqttReady() {
        var presence: Boolean? = null
        val done = CountDownLatch(1)
        val c = coordinator(now = { 1_000_000L }, mqttReady = true, flush = { _, _, includePresence ->
            presence = includePresence
            done.countDown()
            outcome()
        })
        c.drainBacklog("mqtt")
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertEquals(false, presence)
    }

    @Test
    fun confirmedWithBacklogAutoChainsOneMoreDrain() {
        var calls = 0
        var confirmedCb = 0
        val second = CountDownLatch(1)
        dao.countOverride = 3
        val c = coordinator(
            now = { 1_000_000L },
            flush = { _, _, _ ->
                calls++
                if (calls == 1) {
                    outcome(confirmed = 2)
                } else {
                    second.countDown()
                    outcome()
                }
            },
            onHttpConfirmed = { confirmedCb++ },
        )
        c.drainBacklog("continuación")
        assertTrue("el encadenado corre el segundo drenaje", second.await(3, TimeUnit.SECONDS))
        awaitUntil { calls == 2 }
        Thread.sleep(50)
        assertEquals("sin progreso posterior la cadena termina", 2, calls)
        assertEquals("onHttpConfirmed una sola vez (la cadena no re-cuenta)", 1, confirmedCb)
    }

    @Test
    fun transportFailureStopsBatchLoop() {
        var calls = 0
        val done = CountDownLatch(1)
        dao.countOverride = 5
        val c = coordinator(now = { 1_000_000L }, flush = { _, _, _ ->
            calls++
            done.countDown()
            outcome(confirmed = 1, transportOk = false)
        })
        c.drainBacklog("fallo")
        assertTrue(done.await(3, TimeUnit.SECONDS))
        Thread.sleep(80)
        assertEquals("con transportOk=false no se encadena ni se repite", 1, calls)
    }

    @Test
    fun flushPendingOnStopDrainsUntilEmpty() = runBlocking {
        val c = coordinator(now = { 1_000_000L }, flush = { _, _, includePresence ->
            assertTrue("el cierre siempre incluye presencia", includePresence)
            dao.rows.removeFirstOrNull()
            outcome(confirmed = 1)
        })
        dao.insert(position(seq = 1L))
        dao.insert(position(seq = 2L))
        c.flushPendingOnStop()
        assertTrue("la cola queda vacía", dao.rows.isEmpty())
    }

    @Test
    fun flushPendingOnStopReturnsImmediatelyWhenEmpty() = runBlocking {
        var calls = 0
        val c = coordinator(now = { 1_000_000L }, flush = { _, _, _ ->
            calls++
            outcome()
        })
        c.flushPendingOnStop()
        assertEquals(0, calls)
    }

    private fun position(seq: Long) = com.dmujeres.traccar.data.PendingPosition(
        messageId = "m$seq",
        deviceId = "dev",
        sequence = seq,
        payload = """{"type":"position"}""",
        observedAt = "2026-01-01T00:00:00Z",
    )
}
