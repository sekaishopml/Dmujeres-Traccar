package com.dmujeres.traccar.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** L1: acotado de parámetros y condiciones de uso del PendingIntent+batching. */
class L1CapturePolicyTest {

    @Test
    fun clampDelayLlevaCeroYNegativosAlMinimo() {
        assertEquals(
            L1CapturePolicy.MIN_MAX_UPDATE_DELAY_MS,
            L1CapturePolicy.clampDelay(0L),
        )
        assertEquals(
            L1CapturePolicy.MIN_MAX_UPDATE_DELAY_MS,
            L1CapturePolicy.clampDelay(-5_000L),
        )
    }

    @Test
    fun clampDelayAcotaDiezMinutosAlMaximo() {
        assertEquals(
            L1CapturePolicy.MAX_MAX_UPDATE_DELAY_MS,
            L1CapturePolicy.clampDelay(10 * 60_000L),
        )
    }

    @Test
    fun clampDelayDejaIntactoElValorMedioYElDefault() {
        assertEquals(60_000L, L1CapturePolicy.clampDelay(60_000L))
        assertEquals(
            L1CapturePolicy.DEFAULT_MAX_UPDATE_DELAY_MS,
            L1CapturePolicy.clampDelay(L1CapturePolicy.DEFAULT_MAX_UPDATE_DELAY_MS),
        )
    }

    @Test
    fun pendingIntentSoloConSwitchPermisoYJornada() {
        assertTrue(L1CapturePolicy.shouldUsePendingIntent(true, true, true))
        assertFalse(L1CapturePolicy.shouldUsePendingIntent(false, true, true))
        assertFalse(L1CapturePolicy.shouldUsePendingIntent(true, false, true))
        assertFalse(L1CapturePolicy.shouldUsePendingIntent(true, true, false))
        assertFalse(L1CapturePolicy.shouldUsePendingIntent(false, false, true))
        assertFalse(L1CapturePolicy.shouldUsePendingIntent(false, true, false))
        assertFalse(L1CapturePolicy.shouldUsePendingIntent(true, false, false))
        assertFalse(L1CapturePolicy.shouldUsePendingIntent(false, false, false))
    }

    @Test
    fun effectiveIntervalTomaElMayorEntreBaseYMinimo() {
        assertEquals(10L, L1CapturePolicy.effectiveIntervalSeconds(10L, 5L))
        assertEquals(60L, L1CapturePolicy.effectiveIntervalSeconds(10L, 60L))
    }

    @Test
    fun effectiveIntervalSeAcotaA10Y600Segundos() {
        assertEquals(600L, L1CapturePolicy.effectiveIntervalSeconds(700L, 5L))
        assertEquals(10L, L1CapturePolicy.effectiveIntervalSeconds(5L, 5L))
    }
}
