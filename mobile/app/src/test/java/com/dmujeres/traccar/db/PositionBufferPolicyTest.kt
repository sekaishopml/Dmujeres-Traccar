package com.dmujeres.traccar.db

import org.junit.Assert.assertEquals
import org.junit.Test

class PositionBufferPolicyTest {

    @Test
    fun doesNotDiscardBelowMaximumBeforeInsert() {
        assertEquals(0, PositionBufferPolicy.discardCount(currentCount = 4, maximum = 5))
    }

    @Test
    fun discardsOnlyEnoughRowsBeforeInsert() {
        assertEquals(1, PositionBufferPolicy.discardCount(currentCount = 5, maximum = 5))
        assertEquals(6, PositionBufferPolicy.discardCount(currentCount = 10, maximum = 5))
    }
}
