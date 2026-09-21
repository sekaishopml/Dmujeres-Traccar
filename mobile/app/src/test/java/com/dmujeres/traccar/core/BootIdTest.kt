package com.dmujeres.traccar.core

import com.dmujeres.traccar.core.BootId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BootIdTest {

    @Test
    fun session1_refreshGeneratesWhenAbsent() {
        val (id, elapsed) = BootId.refresh(null, 0L, 5_000L)
        assertTrue(id.isNotBlank())
        assertEquals(16, id.length)
        assertTrue(id.none { it == '-' })
        assertEquals(5_000L, elapsed)
    }

    @Test
    fun boot1_sameBootKeepsIdStable() {
        // Reloj monotónico avanza normal → mismo bootId, elapsed actualizado.
        val first = BootId.refresh(null, 0L, 1_000L)
        val second = BootId.refresh(first.first, first.second, 5_000L)
        val third = BootId.refresh(second.first, second.second, 900_000L)
        assertEquals(first.first, second.first)
        assertEquals(second.first, third.first)
        assertEquals(900_000L, third.second)
    }

    @Test
    fun boot2_rebootDetectedWhenMonotonicGoesBack() {
        // persisted=100 s pero ahora elapsed=5 s: imposible en el mismo boot.
        val (newId, elapsed) = BootId.refresh("oldbootid", 100_000L, 5_000L)
        assertNotEquals("oldbootid", newId)
        assertTrue(newId.isNotBlank())
        assertEquals(5_000L, elapsed)
    }

    @Test
    fun boot3_toleranceAvoidsFalseReboot() {
        // Diferencia de 30 s (<= 60 s de tolerancia) NO es reboot: mismo id.
        val (id, _) = BootId.refresh("stableid", 60_000L, 30_000L)
        assertEquals("stableid", id)
        // Justo en el límite (60 s) tampoco.
        val (idLimit, _) = BootId.refresh("stableid", 90_000L, 30_000L)
        assertEquals("stableid", idLimit)
    }

    @Test
    fun boot4_generatedIdsAreUnique() {
        val ids = (1..100).map { BootId.newId() }
        assertEquals(100, ids.toSet().size)
    }
}
