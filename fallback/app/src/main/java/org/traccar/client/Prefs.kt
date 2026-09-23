package org.traccar.client

/**
 * Claves de la configuración interna del cliente de respaldo.
 *
 * Antes vivían en MainFragment (el panel de ajustes de Traccar). Ese panel se
 * eliminó: toda la configuración es interna y solo el menú de depuración
 * (5 toques en la versión) permite verla o ajustarla para soporte.
 */
object Prefs {

    const val DEVICE = "id"
    const val URL = "url"
    const val INTERVAL = "interval"
    const val DISTANCE = "distance"
    const val ANGLE = "angle"
    const val ACCURACY = "accuracy"
    const val STATUS = "status"
    const val ONBOARDED = "onboarded"
    const val BUFFER = "buffer"
    const val WAKELOCK = "wakelock"
}
