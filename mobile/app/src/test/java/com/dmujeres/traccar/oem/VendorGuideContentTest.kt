package com.dmujeres.traccar.oem

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Congela las palabras clave nuevas de las guías OEM, alineadas con
 * docs/audit/OS_DEVICE_*.md y PERMS_RESEARCH_*.md (solo textos de
 * [VendorSettings.guideFor]; los intents los cubre VendorIntentChainTest).
 */
class VendorGuideContentTest {

    private fun guideText(vendor: String): String {
        val guide = VendorSettings.guideFor(vendor)
        assertNotNull("Sin guía para $vendor", guide)
        val g = requireNotNull(guide)
        return (listOf(g.title) + g.steps).joinToString("\n")
    }

    @Test
    fun honorContieneMagicOSInicioManualYConexionEnReposo() {
        val text = guideText("honor")
        assertTrue(text.contains("MagicOS", ignoreCase = true))
        assertTrue(text.contains("Gestión del inicio de aplicaciones", ignoreCase = true))
        assertTrue(text.contains("Gestionar manualmente", ignoreCase = true))
        assertTrue(text.contains("Inicio secundario", ignoreCase = true))
        assertTrue(text.contains("Mantener la conexión durante el sueño", ignoreCase = true))
        assertTrue(text.contains("página de la app", ignoreCase = true))
    }

    @Test
    fun zteContieneSinControlYDesactivaPausarActividad() {
        val text = guideText("zte")
        assertTrue(text.contains("Gestión inteligente", ignoreCase = true))
        assertTrue(text.contains("Sin control", ignoreCase = true))
        assertTrue(text.contains("Pausar actividad", ignoreCase = true))
    }

    @Test
    fun infinixContieneHiberFreezerYSleepMode() {
        val text = guideText("infinix")
        assertTrue(text.contains("Hiber", ignoreCase = true))
        assertTrue(text.contains("AddFreezeApp", ignoreCase = true))
        assertTrue(text.contains("congelar", ignoreCase = true))
        assertTrue(text.contains("Sleep Mode", ignoreCase = true))
        assertTrue(text.contains("ahorro de datos", ignoreCase = true))
    }

    @Test
    fun tecnoComparteLaGuiaTranssion() {
        val text = guideText("tecno")
        assertTrue(text.contains("AddFreezeApp", ignoreCase = true))
        assertTrue(text.contains("congelar", ignoreCase = true))
        assertTrue(text.contains("ahorro de datos", ignoreCase = true))
    }

    @Test
    fun samsungContieneAutoRevokeYPermisosSinUsar() {
        val text = guideText("samsung")
        assertTrue(text.contains("auto-revoke", ignoreCase = true))
        assertTrue(text.contains("Eliminar permisos si la app no se usa", ignoreCase = true))
        assertTrue(text.contains("Aplicaciones sin autosuspensión", ignoreCase = true))
    }
}
