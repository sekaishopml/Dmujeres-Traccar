package com.dmujeres.traccar.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests JVM de las reglas puras de OemProtection (stateFor). */
class OemProtectionTest {

    @Test
    fun oem1_sinVendorEsOk() {
        // Sin gate conocido (p. ej. Motorola) → OK, haya o no guía completada.
        assertEquals(OemState.OK, OemProtection.stateFor(null, guideDone = false, batteryExempt = false))
        assertEquals(OemState.OK, OemProtection.stateFor(null, guideDone = true, batteryExempt = true))
    }

    @Test
    fun oem2_zteSinGuiaEsActionRequired() {
        assertEquals(
            OemState.ACTION_REQUIRED,
            OemProtection.stateFor("zte", guideDone = false, batteryExempt = false),
        )
    }

    @Test
    fun oem3_zteConGuiaEsConfiguredUnverifiable() {
        // Configurado, no verificable automáticamente: la app no puede leer el
        // ajuste del OEM (pantallas protegidas con permisos de sistema).
        assertEquals(
            OemState.CONFIGURED_UNVERIFIABLE,
            OemProtection.stateFor("zte", guideDone = true, batteryExempt = false),
        )
    }

    @Test
    fun oem4_vendorSinGuiaMapeadaEsUnsupported() {
        // Fabricante agresivo no cubierto por VendorSettings.guideFor.
        assertEquals(
            OemState.UNSUPPORTED,
            OemProtection.stateFor("oppo", guideDone = false, batteryExempt = false),
        )
        assertEquals(
            OemState.UNSUPPORTED,
            OemProtection.stateFor("oppo", guideDone = true, batteryExempt = true),
        )
    }

    @Test
    fun oem5_batteryExemptNoCambiaVeredicto() {
        // La exención de batería es solo un paso de la guía (auto-inicio,
        // candado...): no cambia el estado por sí sola.
        assertEquals(
            OemProtection.stateFor("xiaomi", false, false),
            OemProtection.stateFor("xiaomi", false, true),
        )
        assertEquals(
            OemProtection.stateFor("samsung", true, false),
            OemProtection.stateFor("samsung", true, true),
        )
    }

    @Test
    fun oem6_vendorsConocidosEspejoDeVendorSettings() {
        // Espejo puro de VendorSettings.guideFor: los seis vendorKey con guía.
        assertEquals(
            setOf("xiaomi", "samsung", "honor", "infinix", "tecno", "zte"),
            OemProtection.KNOWN_VENDORS,
        )
    }
}
