package com.dmujeres.traccar.ui

import com.dmujeres.traccar.oem.VendorSettings
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

    // ==== Selección DINÁMICA de pasos (1 de N según estado real) ====

    @Test
    fun dynamicStepsEmptyWhenFullyConfigured() {
        // Samsung bien configurado: sin onboarding innecesario.
        val steps = OnboardingPolicy.dynamicSteps(
            locationEnabled = true,
            locationOk = true,
            notificationsOk = true,
            batteryOk = true,
            vendorPending = false,
        )
        assertTrue(steps.isEmpty())
    }

    @Test
    fun dynamicStepsOnlyPendingOnes() {
        // Redmi con batería + autostart (vendor) pendientes: solo 2 pasos,
        // nunca una lista fija de 5.
        val steps = OnboardingPolicy.dynamicSteps(
            locationEnabled = true,
            locationOk = true,
            notificationsOk = true,
            batteryOk = false,
            vendorPending = true,
        )
        assertTrue(steps == listOf(2, 4))
    }

    @Test
    fun dynamicStepsFullListInPriorityOrder() {
        // Todo pendiente: GPS → ubicación → avisos → batería → OEM.
        val steps = OnboardingPolicy.dynamicSteps(
            locationEnabled = false,
            locationOk = false,
            notificationsOk = false,
            batteryOk = false,
            vendorPending = true,
        )
        assertTrue(steps == listOf(3, 0, 1, 2, 4))
    }

    @Test
    fun dynamicStepsGpsFirstWhenLocationOff() {
        // Con el interruptor de ubicación apagado, ese paso va primero.
        val steps = OnboardingPolicy.dynamicSteps(
            locationEnabled = false,
            locationOk = true,
            notificationsOk = true,
            batteryOk = true,
            vendorPending = false,
        )
        assertTrue(steps == listOf(3))
    }

    @Test
    fun dynamicStepsSinglePermissionMissing() {
        // Solo permisos de ubicación pendientes: "1 de 1".
        val steps = OnboardingPolicy.dynamicSteps(
            locationEnabled = true,
            locationOk = false,
            notificationsOk = true,
            batteryOk = true,
            vendorPending = false,
        )
        assertTrue(steps == listOf(0))
    }

    @Test
    fun dynamicStepsVendorOnlyWhenKnownAndNotConfirmed() {
        // OEM pendiente como único paso: se ofrece una sola vez (guía).
        val steps = OnboardingPolicy.dynamicSteps(
            locationEnabled = true,
            locationOk = true,
            notificationsOk = true,
            batteryOk = true,
            vendorPending = true,
        )
        assertTrue(steps == listOf(4))
    }

    // ==== Paso 5: CONTINUIDAD (criterio principal del DeviceReadinessGate) ====

    

    

    // ==== Gate de readiness: los pasos EMERGEN de la evaluación ====

    

    @Test
    fun gateStepsAddsPendingOnlyOnce() {
        // Sin duplicados: lo que dynamicSteps ya cubre no se repite.
        val steps = OnboardingPolicy.gateSteps(
            base = listOf(2, 4),
            batteryGatePending = true,
            oemGatePending = true,
            )
        assertTrue(steps == listOf(2, 4))
    }

    @Test
    fun gateStepsAddsBatteryWhenBucketBlocked() {
        // bucket RESTRICTED / fondo restringido: el paso 2 lo cubre aunque el
        // resto esté OK (la exención de batería es la mitigación existente).
        val steps = OnboardingPolicy.gateSteps(
            base = listOf(0),
            batteryGatePending = true,
            oemGatePending = false,
            )
        assertTrue(steps == listOf(0, 2))
    }

    @Test
    fun gateStepsEmptyWhenNothingPending() {
        val steps = OnboardingPolicy.gateSteps(
            base = emptyList(),
            batteryGatePending = false,
            oemGatePending = false,
            )
        assertTrue(steps.isEmpty())
    }
}
