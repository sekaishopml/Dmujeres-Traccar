package com.dmujeres.traccar.oem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fase 1: cadenas de intents OEM verificadas (docs/audit/PERMS_RESEARCH_*).
 * Congelan los componentes/extras exactos para que un cambio accidental no
 * rompa la puesta a punto por fabricante.
 */
class VendorIntentChainTest {

    @Test
    fun honorUsaMagicOSPrimeroYHuaweiLegacyComoFallback() {
        val chain = VendorSettings.intentChainFor("honor")
        assertTrue(chain.isNotEmpty())
        assertEquals("com.hihonor.systemmanager", chain.first().componentPackage)
        assertTrue(chain.any { it.componentPackage == "com.huawei.systemmanager" })
        // Entrada pública de batería agregada en fase 1.
        assertTrue(chain.any { it.componentClass?.contains("HwPowerManagerActivity") == true })
    }

    @Test
    fun infinixYTecnoUsanTranssionConPowerSavaCorregido() {
        for (v in listOf("infinix", "tecno")) {
            val chain = VendorSettings.intentChainFor(v)
            assertTrue(chain.isNotEmpty())
            assertTrue(chain.any { it.componentPackage == "com.transsion.phonemaster" })
            assertTrue(chain.any { it.componentPackage == "com.transsion.batterylab" })
            assertTrue(chain.any { it.componentPackage == "com.mediatek.autobootcontroller" })
            // Corrección de la investigación: PowerSava vive en batterylab.
            assertTrue(
                chain.any {
                    it.componentPackage == "com.transsion.batterylab" &&
                        it.componentClass?.contains("PowerSavaMainActivity") == true
                }
            )
            assertFalse(
                chain.any {
                    it.componentPackage == "com.transsion.phonemaster" &&
                        it.componentClass?.contains("PowerSavaMainActivity") == true
                }
            )
            assertTrue(chain.any { it.componentClass?.contains("PowerManagerActivity") == true })
        }
    }

    @Test
    fun zteUsaPowerSaveModeConDetalleYAltoConsumo() {
        val chain = VendorSettings.intentChainFor("zte")
        assertEquals("com.zte.powersavemode", chain.first().componentPackage)
        assertTrue(chain.any { it.componentClass?.contains("AppSmartOptimizeActivity") == true })
        assertTrue(chain.any { it.componentClass?.contains("HighPowerApplicationsActivity") == true })
    }

    @Test
    fun xiaomiUsaSecurityCenterConExtrasYFallbacksPorAccion() {
        val chain = VendorSettings.intentChainFor("xiaomi")
        assertTrue(chain.any { it.componentClass?.contains("AutoStartManagementActivity") == true })
        assertTrue(chain.any { it.componentPackage == "com.miui.powerkeeper" })
        // Fallback por acción si HyperOS renombra la activity clásica.
        assertTrue(chain.any { it.action == "miui.intent.action.OP_AUTO_START" })
        // Editor de "otros permisos" con el paquete de la app.
        assertTrue(
            chain.any {
                it.action == "miui.intent.action.APP_PERM_EDITOR" &&
                    it.extras["extra_pkgname"] == "{pkg}"
            }
        )
        // Batería por app con los dos nombres de extra que usan las ROMs.
        assertTrue(
            chain.any {
                it.componentClass?.contains("HiddenAppsConfigActivity") == true &&
                    it.extras["package_name"] == "{pkg}" &&
                    it.extras["packageName"] == "{pkg}"
            }
        )
    }

    @Test
    fun samsungUsaNeverSleepingConActivityType2YFallbacks() {
        val chain = VendorSettings.intentChainFor("samsung")
        assertTrue(chain.isNotEmpty())
        assertTrue(
            chain.any {
                it.action == "com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY" &&
                    it.extras["activity_type"] == "2"
            }
        )
        assertTrue(chain.any { it.componentClass?.contains("BatteryActivity") == true })
        assertTrue(chain.any { it.componentPackage == "com.samsung.android.sm_cn" })
    }

    @Test
    fun motorolaYDesconocidosNoTienenCadenaGenerica() {
        assertTrue(VendorSettings.intentChainFor("motorola").isEmpty())
        assertTrue(VendorSettings.intentChainFor(null).isEmpty())
    }

    @Test
    fun cadaSpecConComponenteTieneClaseValidaYLosExtrasUsanPlaceholder() {
        for (v in listOf("honor", "infinix", "tecno", "zte", "xiaomi", "samsung")) {
            for (spec in VendorSettings.intentChainFor(v)) {
                val pkg = spec.componentPackage
                if (pkg != null) {
                    assertTrue("$v: paquete vacío en ${spec.label}", pkg.isNotBlank())
                }
                val cls = spec.componentClass
                if (cls != null) {
                    assertTrue("$v: clase vacía en ${spec.label}", cls.isNotBlank())
                    assertTrue("$v: clase sin punto en ${spec.label}", cls.contains('.'))
                }
                for ((key, value) in spec.extras) {
                    assertTrue("$v: extra vacío $key", key.isNotBlank())
                    assertFalse("$v: valor sin reemplazo en $key", value.isEmpty())
                }
            }
        }
    }
}
