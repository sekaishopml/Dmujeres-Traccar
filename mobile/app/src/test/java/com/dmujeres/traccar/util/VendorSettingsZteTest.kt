package com.dmujeres.traccar.util

import com.dmujeres.traccar.util.VendorSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VendorSettingsZteTest {

    @Test
    fun zteSeDetectaPorFabricanteOBrand() {
        assertNotNull(VendorSettings.guideFor("zte"))
    }

    @Test
    fun guiaZteIncluyeControlDeIaYAutostart() {
        val guide = VendorSettings.guideFor("zte")
        requireNotNull(guide)
        assertEquals("ZTE", guide.vendorName)
        assertTrue(guide.steps.any { it.contains("control de IA", ignoreCase = true) })
        assertTrue(guide.steps.any { it.contains("Inicio automático", ignoreCase = true) })
        assertNotNull(guide.settingsIntent)
    }

    @Test
    fun zteNoExigeSettingsParaBackground() {
        // El diálogo del sistema en ZTE funciona: la guía vendor es el paso extra.
        assertFalse(VendorSettings.requiresSettingsForBackground("zte"))
    }

    @Test
    fun otrasMarcasSinGuiaNoMuestranPaso() {
        assertEquals(null, VendorSettings.guideFor("motorola"))
        assertEquals(null, VendorSettings.guideFor(null))
    }
}
