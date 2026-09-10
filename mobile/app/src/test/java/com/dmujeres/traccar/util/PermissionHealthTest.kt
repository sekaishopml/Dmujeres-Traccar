package com.dmujeres.traccar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mapeo puro permiso→paso de onboarding para la reparación post-OTA.
 * Indices reales del modelo de OnboardingActivity: 0=ubicación (FINE +
 * BACKGROUND + FSL en Android 14+), 1=avisos, 2=batería, 3=GPS (no es
 * permiso, nunca se repara). JVM, sin Android.
 */
class PermissionHealthTest {

    @Test
    fun batteryOnlyMapsToBatteryStep() {
        assertEquals(
            listOf(2),
            PermissionHealth.repairStepsFor(listOf(CriticalPermission.BATTERY_EXEMPT)),
        )
    }

    @Test
    fun notificationsMapsToStepOne() {
        assertEquals(
            listOf(1),
            PermissionHealth.repairStepsFor(listOf(CriticalPermission.NOTIFICATIONS)),
        )
    }

    @Test
    fun allLocationPermissionsDedupeToStepZero() {
        assertEquals(
            listOf(0),
            PermissionHealth.repairStepsFor(
                listOf(
                    CriticalPermission.FINE_LOCATION,
                    CriticalPermission.BACKGROUND_LOCATION,
                    CriticalPermission.FSL,
                ),
            ),
        )
    }

    @Test
    fun mixedMissingIsDedupedAndSortedAscending() {
        assertEquals(
            listOf(0, 1, 2),
            PermissionHealth.repairStepsFor(
                listOf(
                    CriticalPermission.BATTERY_EXEMPT,
                    CriticalPermission.NOTIFICATIONS,
                    CriticalPermission.BACKGROUND_LOCATION,
                    CriticalPermission.FINE_LOCATION,
                ),
            ),
        )
    }

    @Test
    fun emptyMissingIsEmpty() {
        assertTrue(PermissionHealth.repairStepsFor(emptyList()).isEmpty())
    }

    @Test
    fun missingIsDerivedInStableOrder() {
        val status = PermissionStatus(setOf(CriticalPermission.BATTERY_EXEMPT))
        assertEquals(
            listOf(
                CriticalPermission.NOTIFICATIONS,
                CriticalPermission.FINE_LOCATION,
                CriticalPermission.BACKGROUND_LOCATION,
                CriticalPermission.FSL,
            ),
            status.missing,
        )
    }

    @Test
    fun fullGrantHasNoMissing() {
        val status = PermissionStatus(CriticalPermission.entries.toSet())
        assertTrue(status.missing.isEmpty())
    }
}
