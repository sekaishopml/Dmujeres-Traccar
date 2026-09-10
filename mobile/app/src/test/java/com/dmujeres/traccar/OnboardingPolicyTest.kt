package com.dmujeres.traccar

import com.dmujeres.traccar.util.VendorSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ONBOARDING robusto: paso 1 cubre FINE + BACKGROUND + FSL (Android 14+).
 * El flujo sigue en 4 pasos; solo se extiende el paso de ubicación.
 * Puro (JVM, sin Android).
 */
class OnboardingPolicyTest {

    @Test
    fun needsFslOnlyOnApi34() {
        assertFalse(OnboardingPolicy.needsForegroundLocationPermission(33))
        assertTrue(OnboardingPolicy.needsForegroundLocationPermission(34))
        assertTrue(OnboardingPolicy.needsForegroundLocationPermission(35))
    }

    @Test
    fun locationCompleteOnOldApiIgnoresFsl() {
        // API 33: FINE + BACKGROUND bastan aunque FSL venga false.
        assertTrue(
            OnboardingPolicy.isLocationComplete(
                fineGranted = true, backgroundGranted = true,
                fslGranted = false, sdkInt = 33,
            ),
        )
        assertTrue(
            OnboardingPolicy.isLocationComplete(
                fineGranted = true, backgroundGranted = true,
                fslGranted = true, sdkInt = 33,
            ),
        )
    }

    @Test
    fun locationRequiresFslOnApi34() {
        // API 34: sin FSL no está completo.
        assertFalse(
            OnboardingPolicy.isLocationComplete(
                fineGranted = true, backgroundGranted = true,
                fslGranted = false, sdkInt = 34,
            ),
        )
        assertTrue(
            OnboardingPolicy.isLocationComplete(
                fineGranted = true, backgroundGranted = true,
                fslGranted = true, sdkInt = 34,
            ),
        )
    }

    @Test
    fun locationRequiresFineAndBackgroundAlways() {
        // Sin FINE o sin BACKGROUND nunca está completo, en ninguna API.
        assertFalse(
            OnboardingPolicy.isLocationComplete(
                fineGranted = false, backgroundGranted = true,
                fslGranted = true, sdkInt = 33,
            ),
        )
        assertFalse(
            OnboardingPolicy.isLocationComplete(
                fineGranted = true, backgroundGranted = false,
                fslGranted = true, sdkInt = 33,
            ),
        )
        assertFalse(
            OnboardingPolicy.isLocationComplete(
                fineGranted = false, backgroundGranted = false,
                fslGranted = false, sdkInt = 34,
            ),
        )
        assertFalse(
            OnboardingPolicy.isLocationComplete(
                fineGranted = true, backgroundGranted = false,
                fslGranted = true, sdkInt = 34,
            ),
        )
    }

    @Test
    fun stepCompleteGatesEachStep() {
        // Paso 0 exige ubicación; 1 avisos; 2 batería; 3 siempre (usa Empezar).
        assertFalse(OnboardingPolicy.isStepComplete(0, false, true, true))
        assertTrue(OnboardingPolicy.isStepComplete(0, true, false, false))
        assertFalse(OnboardingPolicy.isStepComplete(1, true, false, true))
        assertTrue(OnboardingPolicy.isStepComplete(1, true, true, false))
        assertFalse(OnboardingPolicy.isStepComplete(2, true, true, false))
        assertTrue(OnboardingPolicy.isStepComplete(2, false, false, true))
        assertTrue(OnboardingPolicy.isStepComplete(3, false, false, false))
    }

    @Test
    fun settingsForBackgroundOnlyOnProblematicVendors() {
        assertTrue(VendorSettings.requiresSettingsForBackground("xiaomi"))
        assertTrue(VendorSettings.requiresSettingsForBackground("infinix"))
        assertTrue(VendorSettings.requiresSettingsForBackground("tecno"))
        assertFalse(VendorSettings.requiresSettingsForBackground("samsung"))
        assertFalse(VendorSettings.requiresSettingsForBackground("honor"))
        assertFalse(VendorSettings.requiresSettingsForBackground(null))
    }
}
