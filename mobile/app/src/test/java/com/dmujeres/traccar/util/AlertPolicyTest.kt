package com.dmujeres.traccar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tabla pura de AlertPolicy.shouldAlert(now, lastSameKeyAt, lastAnyAt):
 * dedupe misma clave 30 min + gap global 60 s (umbrales exactos incluidos).
 */
class AlertPolicyTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun firstAlertAlwaysPasses() {
        assertTrue(AlertPolicy.shouldAlert(t0, 0L, 0L))
    }

    @Test
    fun sameKeyBlockedWithinCooldown() {
        assertFalse(AlertPolicy.shouldAlert(t0 + 1_000L, t0, 0L))
        assertFalse(
            "1 min: bloqueado",
            AlertPolicy.shouldAlert(t0 + 60_000L, t0, t0),
        )
        assertFalse(
            "29:59 mismo key: bloqueado por cooldown de clave",
            AlertPolicy.shouldAlert(t0 + AlertPolicy.SAME_KEY_COOLDOWN_MS - 1L, t0, 0L),
        )
    }

    @Test
    fun sameKeyPassesAtCooldownBoundary() {
        assertTrue(AlertPolicy.shouldAlert(t0 + AlertPolicy.SAME_KEY_COOLDOWN_MS, t0, 0L))
    }

    @Test
    fun differentKeyStillBlockedByGlobalGap() {
        // lastSameKeyAt=0 (otra clave) pero alerta global hace 30 s: gap manda.
        assertFalse(
            AlertPolicy.shouldAlert(t0 + 30_000L, 0L, t0),
        )
        assertFalse(
            "justo antes del gap global",
            AlertPolicy.shouldAlert(t0 + AlertPolicy.GLOBAL_MIN_GAP_MS - 1L, 0L, t0),
        )
    }

    @Test
    fun differentKeyPassesAtGapBoundary() {
        assertTrue(AlertPolicy.shouldAlert(t0 + AlertPolicy.GLOBAL_MIN_GAP_MS, 0L, t0))
    }

    @Test
    fun flapOfDifferentKeysIsRateLimited() {
        // warning(A)→ok(B)→warning(A) en 20 s: la segunda A cae por gap global y la
        // tercera A cae además por cooldown de clave. El flap A↔B queda a 1/min.
        var lastAny = t0
        var lastKeyAt = t0
        val second = AlertPolicy.shouldAlert(t0 + 20_000L, lastKeyAt, lastAny)
        assertFalse("segunda alerta a los 20 s: gap global la frena", second)
        // A los 61 s pasa la siguiente (clave distinta → lastSameKeyAt no aplica):
        assertTrue(AlertPolicy.shouldAlert(t0 + 61_000L, 0L, lastAny))
        lastAny = t0 + 61_000L
        lastKeyAt = lastAny
        assertFalse(
            "y la otra justo después vuelve a caer",
            AlertPolicy.shouldAlert(lastAny + 10_000L, lastKeyAt, lastAny),
        )
    }

    @Test
    fun thresholdsAreExactlyTheSpec() {
        assertEquals(30 * 60_000L, AlertPolicy.SAME_KEY_COOLDOWN_MS)
        assertEquals(60_000L, AlertPolicy.GLOBAL_MIN_GAP_MS)
    }

    @Test
    fun keyOfSeparatesTitleFromBody() {
        // Evita colisiones: ("a", "b") != ("a\nb", "").
        assertEquals(AlertPolicy.keyOf("A", "B"), AlertPolicy.keyOf("A", "B"))
        assertFalse(AlertPolicy.keyOf("A", "B") == AlertPolicy.keyOf("A\nB", ""))
    }
}
