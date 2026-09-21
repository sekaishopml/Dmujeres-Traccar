package com.dmujeres.traccar.oem

/**
 * FASE 9 (§23): proveedor de guía OEM data-driven. Decide QUÉ acción de
 * usuario corresponde a partir del perfil de capacidad, sin `if Xiaomi/Samsung/
 * ZTE` repartidos por la app: la lógica vive aquí y en VendorSettings.
 *
 * Acciones (ordenadas por intervención requerida):
 * - NONE: sin gate OEM conocido; nada que pedir.
 * - CONFIGURE: hay guía del fabricante sin completar → mostrar pasos.
 * - VERIFY: guía completada; la app NO puede verificar que el OEM dejó de
 *   matar el proceso (pantallas protegidas) → "configurado, por verificar".
 * - MANUAL: hay restricción dura detectada (appops/bucket RESTRICTED) que la
 *   guía no arregla sola → acción manual explícita.
 */
object OemGuidanceProvider {

    const val ACTION_NONE = "NONE"
    const val ACTION_CONFIGURE = "CONFIGURE"
    const val ACTION_VERIFY = "VERIFY"
    const val ACTION_MANUAL = "MANUAL"

    fun actionFor(
        profile: DeviceCapabilityProfile,
        guideDone: Boolean,
        batteryExempt: Boolean,
    ): String = when {
        profile.backgroundRestricted || profile.standbyBucket == DeviceCapabilityProfile.STANDBY_RESTRICTED ->
            ACTION_MANUAL
        !profile.supportsVendorSettings && !profile.knownFreezerBehavior && batteryExempt -> ACTION_NONE
        !guideDone && profile.supportsVendorSettings -> ACTION_CONFIGURE
        guideDone -> ACTION_VERIFY
        !batteryExempt -> ACTION_CONFIGURE
        else -> ACTION_NONE
    }

    /**
     * Resumen corto y honesto para UI/diagnóstico. Nunca promete que la app
     * puede "vencer" al OEM: describe el estado real.
     */
    fun summary(profile: DeviceCapabilityProfile): String = when {
        profile.hardRestricted() && profile.knownFreezerBehavior ->
            "Fabricante con congelamiento conocido (freezer): requiere configuración y recuperación"
        profile.hardRestricted() -> "Restricción de segundo plano activa del sistema"
        profile.requiresGuidance() -> "Requiere configuración del fabricante para segundo plano"
        else -> "Sin restricciones OEM detectadas"
    }
}
