package com.dmujeres.traccar.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R9: tras varios fallos del fused se usa el GPS del sistema como primario. */
class FusedFailurePolicyTest {

    @Test
    fun pocosFallosNoCambianDeProveedor() {
        assertFalse(FusedFailurePolicy.shouldPreferPlatform(0))
        assertFalse(FusedFailurePolicy.shouldPreferPlatform(1))
        assertFalse(FusedFailurePolicy.shouldPreferPlatform(FusedFailurePolicy.LIMIT - 1))
    }

    @Test
    fun alLlegarAlLimiteSePrefiereLaPlataforma() {
        assertTrue(FusedFailurePolicy.shouldPreferPlatform(FusedFailurePolicy.LIMIT))
        assertTrue(FusedFailurePolicy.shouldPreferPlatform(FusedFailurePolicy.LIMIT + 5))
    }
}
