package com.dmujeres.traccar.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Desglose de rechazos del filtro (causa raíz en vez de total ciego).
 * Puro (companion): sin Android, testeable en JVM.
 */
class RejectBreakdownTest {

    @Test
    fun reasonKeyMapeaMotivosConocidos() {
        assertEquals("fix_rej_accuracy", AppConfig.reasonKey("accuracy_ceiling=512.0"))
        assertEquals("fix_rej_accuracy", AppConfig.reasonKey("invalid_lat=NaN"))
        assertEquals("fix_rej_firstfix", AppConfig.reasonKey("first_fix_bad"))
        assertEquals("fix_rej_stale", AppConfig.reasonKey("stale"))
        assertEquals("fix_rej_relay", AppConfig.reasonKey("stale_relay"))
        assertEquals("fix_rej_netrelay", AppConfig.reasonKey("network_relay"))
        assertEquals("fix_rej_implied", AppConfig.reasonKey("implied_speed"))
        assertEquals("fix_rej_degraded", AppConfig.reasonKey("degraded"))
        assertEquals("fix_rej_rule", AppConfig.reasonKey("rule_deferred"))
        assertNull(AppConfig.reasonKey("otro-motivo"))
    }

    @Test
    fun formatBreakdownOrdenEstableYSoloPositivos() {
        val counts = mapOf(
            "fix_rej_rule" to 5L,
            "fix_rej_implied" to 10L,
            "fix_rej_accuracy" to 3L,
            "fix_rej_stale" to 0L,
        )
        assertEquals("accuracy:3|implied:10|rule:5", AppConfig.formatBreakdown(counts))
        assertEquals("", AppConfig.formatBreakdown(emptyMap()))
    }
}
