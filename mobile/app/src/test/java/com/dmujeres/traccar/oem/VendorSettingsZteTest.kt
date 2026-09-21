package com.dmujeres.traccar.oem

import com.dmujeres.traccar.oem.VendorSettings
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
        assertTrue(guide.title.contains("control de IA", ignoreCase = true))
        assertTrue(guide.steps.any { it.contains("Gestión inteligente", ignoreCase = true) })
        assertTrue(guide.steps.any { it.contains("Pausar actividad", ignoreCase = true) })
        // ZTE: doble botón (Batería/Gestión inteligente + página de la app).
        assertTrue(guide.secondaryIntent != null)
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
