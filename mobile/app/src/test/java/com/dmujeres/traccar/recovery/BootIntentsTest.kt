package com.dmujeres.traccar.recovery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ARRANQUE PRIORITARIO: el receiver debe reaccionar a los 4 broadcasts de
 * arranque y degradar sin crashear si falta FSL en Android 14.
 * Puro (JVM, sin Android).
 */
class BootIntentsTest {

    @Test
    fun recognizesAllBootActions() {
        assertTrue(BootIntents.isBootAction("android.intent.action.BOOT_COMPLETED"))
        assertTrue(BootIntents.isBootAction("android.intent.action.MY_PACKAGE_REPLACED"))
        assertTrue(BootIntents.isBootAction("android.intent.action.USER_UNLOCKED"))
        assertTrue(BootIntents.isBootAction("android.intent.action.QUICKBOOT_POWERON"))
    }

    @Test
    fun rejectsNullAndUnknownActions() {
        assertFalse(BootIntents.isBootAction(null))
        assertFalse(BootIntents.isBootAction(""))
        assertFalse(BootIntents.isBootAction("android.intent.action.PACKAGE_REPLACED"))
        assertFalse(BootIntents.isBootAction("android.intent.action.LOCKED_BOOT_COMPLETED"))
    }

    @Test
    fun bootActionsSetContainsQuickboot() {
        // Si alguien quita QUICKBOOT del set, este test lo caza.
        assertTrue(BootIntents.BOOT_ACTIONS.contains(BootIntents.ACTION_QUICKBOOT_POWERON))
        assertTrue(BootIntents.BOOT_ACTIONS.size == 4)
    }

    @Test
    fun missingForegroundLocationOnlyOnApi34() {
        // API 34+ sin permiso → falta (degradar con aviso).
        assertTrue(BootIntents.missingForegroundLocation(sdkInt = 34, fslGranted = false))
        assertTrue(BootIntents.missingForegroundLocation(sdkInt = 35, fslGranted = false))
        // Con permiso → no falta.
        assertFalse(BootIntents.missingForegroundLocation(sdkInt = 34, fslGranted = true))
        assertFalse(BootIntents.missingForegroundLocation(sdkInt = 35, fslGranted = true))
        // API < 34 nunca falta (el permiso no existe).
        assertFalse(BootIntents.missingForegroundLocation(sdkInt = 33, fslGranted = false))
        assertFalse(BootIntents.missingForegroundLocation(sdkInt = 26, fslGranted = false))
        assertFalse(BootIntents.missingForegroundLocation(sdkInt = 33, fslGranted = true))
    }
}
