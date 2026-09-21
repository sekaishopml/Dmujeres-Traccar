package com.dmujeres.traccar.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Congela el contrato de protocolo con server/dashboard: topics, endpoints y
 * estados de jornada. Cambiar un valor aquí sin migración del server rompe la
 * flota entera.
 */
class MobileProtocolTest {

    @Test
    fun telemetryAndAckTopicsMatchServerContract() {
        assertEquals("dmj/v1/devices/qa-f0/telemetry", MobileProtocol.telemetryTopic("qa-f0"))
        assertEquals("dmj/v1/devices/qa-f0/ack", MobileProtocol.ackTopic("qa-f0"))
    }

    @Test
    fun httpPathsAreTheMobileApiContract() {
        assertEquals("/api/mobile/v1/positions", MobileProtocol.PATH_POSITIONS)
        assertEquals("/api/mobile/v1/health", MobileProtocol.PATH_HEALTH)
        assertEquals("/api/mobile/v1/diagnostics", MobileProtocol.PATH_DIAGNOSTICS)
        assertEquals("/api/mobile/v1/fcm-token", MobileProtocol.PATH_FCM_TOKEN)
        assertEquals("/api/mobile/v1/recovery-ack", MobileProtocol.PATH_RECOVERY_ACK)
        assertEquals("/api/mobile/v1/config", MobileProtocol.PATH_CONFIG)
        assertEquals("/api/mobile/v1/ota", MobileProtocol.PATH_OTA)
    }

    @Test
    fun journeyStatusesAreStable() {
        assertEquals("started", MobileProtocol.JOURNEY_STATUS_STARTED)
        assertEquals("ended", MobileProtocol.JOURNEY_STATUS_ENDED)
        assertEquals(999, MobileProtocol.WEB_PORT)
    }
}
