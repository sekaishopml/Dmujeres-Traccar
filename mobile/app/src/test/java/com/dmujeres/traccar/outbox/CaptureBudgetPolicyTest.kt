package com.dmujeres.traccar.outbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F1: presupuesto de captura (estimación declarada, no medición). */
class CaptureBudgetPolicyTest {

    @Test
    fun estimateBytesUsaLaEstimacionDeclarada() {
        assertEquals(0L, CaptureBudgetPolicy.estimateBytes(0L))
        assertEquals(22_000L, CaptureBudgetPolicy.estimateBytes(100L))
        assertEquals(220L, CaptureBudgetPolicy.BYTES_PER_POINT_ESTIMATE)
    }

    @Test
    fun isOverPointsEnElUmbralExacto() {
        assertFalse(CaptureBudgetPolicy.isOverPoints(11_999L))
        assertTrue(CaptureBudgetPolicy.isOverPoints(12_000L))
        assertTrue(CaptureBudgetPolicy.isOverPoints(12_001L))
    }

    @Test
    fun isOverBytesEnElPuntoDeCorteExacto() {
        // 8 MiB = 8_388_608 B; 38_130 pts × 220 B = 8_388_600 B (8 B por
        // debajo) y 38_131 pts = 8_388_820 B (por encima): corte exacto.
        val justoDebajo = CaptureBudgetPolicy.DAILY_BYTES_WARN /
            CaptureBudgetPolicy.BYTES_PER_POINT_ESTIMATE
        assertEquals(38_130L, justoDebajo)
        assertFalse(CaptureBudgetPolicy.isOverBytes(justoDebajo))
        assertTrue(CaptureBudgetPolicy.isOverBytes(justoDebajo + 1L))
    }

    @Test
    fun summaryIndicaOkOExcede() {
        // Ejemplo del KDoc: 3210 pts × 220 B = 706_200 B ≈ 706 KB.
        assertEquals("captura: 3210 pts ≈ 706 KB (ok)", CaptureBudgetPolicy.summary(3_210L))
        assertFalse(CaptureBudgetPolicy.summary(3_210L).contains("excede"))
        assertTrue(
            CaptureBudgetPolicy.summary(CaptureBudgetPolicy.DAILY_POINTS_WARN).contains("excede")
        )
        assertTrue(CaptureBudgetPolicy.summary(38_131L).contains("excede"))
    }
}
