package com.dmujeres.traccar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Separación transporte / Internet validada / MQTT / servidor.
 * Pura JVM.
 */
class LinkStateTest {

    private val now = 1_000_000_000L

    @Test
    fun mqttSoloConInternetValidadaYSesionNoLista() {
        val ready = LinkState.current(Transport.WIFI, true, true, true, 5, now - 1_000L, now - 1_000L, now)
        assertFalse(ready.shouldAttemptMqtt())
        val down = LinkState.current(Transport.WIFI, true, false, false, 5, now - 1_000L, now - 1_000L, now)
        assertTrue(down.shouldAttemptMqtt())
        // WiFi sin validar NO es Internet: no se intenta MQTT.
        val captive = LinkState.current(Transport.WIFI, false, false, false, 5, 0L, null, now)
        assertFalse(captive.shouldAttemptMqtt())
        assertTrue(captive.isUnavailable())
    }

    @Test
    fun tcpConectadoSinSubscribeNoEntrega() {
        val s = LinkState.current(Transport.CELLULAR, true, false, true, 5, now - 1_000L, now - 1_000L, now)
        assertEquals(MqttLink.CONNECTING, s.mqtt)
        assertTrue(s.isUnavailable())
        assertTrue(s.shouldAttemptMqtt())
    }

    @Test
    fun reachabilityDesconocidaSinPendientes() {
        val s = LinkState.current(Transport.WIFI, true, true, true, 0, 0L, null, now)
        assertEquals(ServerReach.UNKNOWN, s.serverReachability)
    }

    @Test
    fun reachabilityInalcanzableSinAck() {
        val s = LinkState.current(Transport.WIFI, true, true, true, 10, 0L, now - 1_000L, now)
        assertEquals(ServerReach.UNREACHABLE, s.serverReachability)
    }

    @Test
    fun presenceRecienteNoOcultaBacklogViejo() {
        // ACK hace 30 s (heartbeat) pero el pendiente más viejo tiene 20 min:
        // el servidor está mudo para posiciones → UNREACHABLE (dispara fallback).
        val s = LinkState.current(
            Transport.WIFI, true, true, true, 10, now - 30_000L, now - 20 * 60_000L, now,
        )
        assertEquals(ServerReach.UNREACHABLE, s.serverReachability)
    }

    @Test
    fun reachabilitySanaConAckRecienteYBacklogFresco() {
        val s = LinkState.current(
            Transport.WIFI, true, true, true, 10, now - 30_000L, now - 60_000L, now,
        )
        assertEquals(ServerReach.REACHABLE, s.serverReachability)
    }
}
