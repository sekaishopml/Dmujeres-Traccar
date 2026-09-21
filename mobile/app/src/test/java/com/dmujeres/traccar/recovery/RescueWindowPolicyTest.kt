package com.dmujeres.traccar.recovery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R8: la ventana de rescate cubre los rechecks + enganche GPS, con cota dura. */
class RescueWindowPolicyTest {

    @Test
    fun ventanaCubreRechecksYEngancheGps() {
        assertTrue(RescueWindowPolicy.WINDOW_MS >= RescueWindowPolicy.RECHECKS_MS + RescueWindowPolicy.GPS_WARMUP_MS)
    }

    @Test
    fun ventanaDentroDeLaCotaDeBateria() {
        assertTrue(RescueWindowPolicy.isBounded())
        assertTrue(RescueWindowPolicy.WINDOW_MS <= RescueWindowPolicy.MAX_WINDOW_MS)
    }

    @Test
    fun fueraDeCotaSeRechaza() {
        assertFalse(RescueWindowPolicy.isBounded(0L))
        assertFalse(RescueWindowPolicy.isBounded(-1L))
        assertFalse(RescueWindowPolicy.isBounded(RescueWindowPolicy.MAX_WINDOW_MS + 1))
    }

    @Test
    fun ventanaDelGuardianTambienEsAcotada() {
        assertTrue(RescueWindowPolicy.isBounded(RescueWindowPolicy.KEEPER_WINDOW_MS))
        assertTrue(RescueWindowPolicy.KEEPER_WINDOW_MS < RescueWindowPolicy.WINDOW_MS)
    }

    @Test
    fun ventanaDeMovimientoEsMayorQueLaDeRescateYCabeEnLaCota() {
        // En marcha (sin red) la ventana sostiene el trazado: más larga que la
        // de rescate estándar, pero dentro de la cota de batería (3 min).
        assertTrue(RescueWindowPolicy.MOVING_WINDOW_MS > RescueWindowPolicy.WINDOW_MS)
        assertTrue(RescueWindowPolicy.isBounded(RescueWindowPolicy.MOVING_WINDOW_MS))
        assertTrue(RescueWindowPolicy.MOVING_WINDOW_MS <= 180_000L)
    }

    @Test
    fun soloProbesValidosAbrenVentana() {
        assertTrue(RescueWindowPolicy.shouldOpen(validProbe = true))
        assertFalse(RescueWindowPolicy.shouldOpen(validProbe = false))
    }
}
