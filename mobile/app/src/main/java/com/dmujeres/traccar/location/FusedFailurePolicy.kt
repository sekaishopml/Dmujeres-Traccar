package com.dmujeres.traccar.location

/**
 * R9: política pura del fallback por fallos del proveedor fused (Google).
 *
 * Evidencia de campo (ZTE Z2450, macias, 20-sep): el request del fused falla
 * en bucle durante la jornada (5 eventos GPS_DISABLED en la ruta) y la app se
 * queda sin proveedor. Traccar Client SDK expone esto como
 * `preferPlatformProviders` para ROMs donde el fused no es fiable.
 *
 * Regla: tras [LIMIT] fallos en la sesión, se usa el GPS del sistema (AOSP)
 * como proveedor primario hasta terminar la jornada.
 */
object FusedFailurePolicy {

    /** Fallos del fused que disparan el cambio a proveedor de plataforma. */
    const val LIMIT = 3

    fun shouldPreferPlatform(failures: Int): Boolean = failures >= LIMIT
}
