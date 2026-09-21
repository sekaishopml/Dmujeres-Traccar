package com.dmujeres.traccar.oem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FASE 9: la guía OEM se decide por CAPACIDAD, no por nombre de fabricante.
 * Un Android desconocido cae en NONE (sin inventar pasos); una restricción
 * dura real manda MANUAL sin importar la marca.
 */
class OemGuidanceProviderTest {

    private fun profile(
        vendorGuided: Boolean = false,
        freezer: Boolean = false,
        batteryExempt: Boolean = true,
        restricted: Boolean = false,
        bucket: Int = DeviceCapabilityProfile.STANDBY_ACTIVE,
    ) = DeviceCapabilityProfile(
        manufacturer = "X",
        model = "Y",
        device = "z",
        androidVersion = "14",
        sdk = 34,
        rom = "rom",
        hasGnss = true,
        hasAccelerometer = true,
        hasGyroscope = false,
        hasRotationVector = false,
        batteryOptimizationExempt = batteryExempt,
        standbyBucket = bucket,
        backgroundRestricted = restricted,
        supportsAutostartGuide = vendorGuided,
        supportsBatteryGuide = vendorGuided,
        supportsVendorSettings = vendorGuided,
        knownBackgroundRestriction = vendorGuided,
        knownFreezerBehavior = freezer,
    )

    @Test
    fun `restriccion dura manda MANUAL aunque no haya guia`() {
        assertEquals(
            OemGuidanceProvider.ACTION_MANUAL,
            OemGuidanceProvider.actionFor(profile(restricted = true), guideDone = false, batteryExempt = true),
        )
        assertEquals(
            OemGuidanceProvider.ACTION_MANUAL,
            OemGuidanceProvider.actionFor(
                profile(bucket = DeviceCapabilityProfile.STANDBY_RESTRICTED),
                guideDone = true,
                batteryExempt = true,
            ),
        )
    }

    @Test
    fun `android desconocido sin gate no pide nada`() {
        assertEquals(
            OemGuidanceProvider.ACTION_NONE,
            OemGuidanceProvider.actionFor(profile(), guideDone = false, batteryExempt = true),
        )
    }

    @Test
    fun `guia pendiente pide CONFIGURE`() {
        assertEquals(
            OemGuidanceProvider.ACTION_CONFIGURE,
            OemGuidanceProvider.actionFor(profile(vendorGuided = true), guideDone = false, batteryExempt = true),
        )
    }

    @Test
    fun `guia completada no se puede verificar - VERIFY`() {
        assertEquals(
            OemGuidanceProvider.ACTION_VERIFY,
            OemGuidanceProvider.actionFor(profile(vendorGuided = true), guideDone = true, batteryExempt = true),
        )
    }

    @Test
    fun `bateria no exenta pide configurar aunque no haya fabricante mapeado`() {
        assertEquals(
            OemGuidanceProvider.ACTION_CONFIGURE,
            OemGuidanceProvider.actionFor(profile(), guideDone = false, batteryExempt = false),
        )
    }

    @Test
    fun `resumen honesto por capacidad`() {
        assertTrue(OemGuidanceProvider.summary(profile(freezer = true)).contains("freezer"))
        assertTrue(OemGuidanceProvider.summary(profile(vendorGuided = true)).contains("fabricante"))
        assertTrue(OemGuidanceProvider.summary(profile()).contains("Sin restricciones"))
    }
}
