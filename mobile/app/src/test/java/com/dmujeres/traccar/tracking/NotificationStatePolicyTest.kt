package com.dmujeres.traccar.tracking

import com.dmujeres.traccar.core.TrackingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FASE 2/3: caracterización de la decisión de alerta por transición de estado
 * (antes vivía en `TrackingService.setState`).
 */
class NotificationStatePolicyTest {

    @Test
    fun `estados operativos nunca alertan por transicion`() {
        // Tienen su propia alerta retardada o no son estados de alerta.
        assertFalse(NotificationStatePolicy.shouldAlertOnTransition(TrackingState.NETWORK_OFFLINE, null))
        assertFalse(NotificationStatePolicy.shouldAlertOnTransition(TrackingState.MQTT_DISCONNECTED, null))
        assertFalse(NotificationStatePolicy.shouldAlertOnTransition(TrackingState.SERVER_UNAVAILABLE, null))
        assertFalse(NotificationStatePolicy.shouldAlertOnTransition(TrackingState.BATTERY_LOW, null))
    }

    @Test
    fun `estados silenciosos no alertan`() {
        assertFalse(NotificationStatePolicy.shouldAlertOnTransition(TrackingState.NO_FRESH_FIX, null))
        assertFalse(NotificationStatePolicy.shouldAlertOnTransition(TrackingState.PENDING_ACK_TIMEOUT, null))
    }

    @Test
    fun `activo, apagado y recuperacion no alertan`() {
        assertFalse(NotificationStatePolicy.shouldAlertOnTransition(TrackingState.TRACKING_ACTIVE, null))
        assertFalse(NotificationStatePolicy.shouldAlertOnTransition(TrackingState.TRACKING_DISABLED_BY_USER, null))
        assertFalse(NotificationStatePolicy.shouldAlertOnTransition(TrackingState.SERVICE_RECOVERY, null))
    }

    @Test
    fun `estados alertables alertan una vez y no repiten`() {
        assertTrue(NotificationStatePolicy.shouldAlertOnTransition(TrackingState.PERMISSION_MISSING, null))
        assertTrue(NotificationStatePolicy.shouldAlertOnTransition(TrackingState.BUFFER_FULL, null))
        assertFalse(
            NotificationStatePolicy.shouldAlertOnTransition(TrackingState.BUFFER_FULL, TrackingState.BUFFER_FULL),
        )
        assertTrue(
            NotificationStatePolicy.shouldAlertOnTransition(TrackingState.PERMISSION_MISSING, TrackingState.BUFFER_FULL),
        )
    }

    @Test
    fun `clasificacion de retardadas y silenciosas`() {
        assertTrue(NotificationStatePolicy.isDelayedAlert(TrackingState.NETWORK_OFFLINE))
        assertTrue(NotificationStatePolicy.isSilent(TrackingState.NO_FRESH_FIX))
        assertFalse(NotificationStatePolicy.isDelayedAlert(TrackingState.NO_FRESH_FIX))
        assertFalse(NotificationStatePolicy.isSilent(TrackingState.BATTERY_LOW))
    }
}
