package com.dmujeres.traccar.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Sistema de 3 colores estricto DMujeres.
 *
 * - Rojo DMujeres #EB0045, Blanco #FFFFFF, Verde #2E7D32.
 * - En jornada: se permite rojo/blanco/verde.
 * - Fuera de jornada: SOLO rojo (fondo #EB0045 + texto blanco), sin excepción.
 *
 * Toda la UI de jornada debe usar exclusivamente estos tokens.
 * Excepción: ámbar solo en DiagnosticsActivity.kt con literal local.
 */
object JourneyColors {
    val Rojo: Color = Color(0xFFEB0045)
    val Blanco: Color = Color(0xFFFFFFFF)
    val Verde: Color = Color(0xFF2E7D32)
}

/** Colores del banner de jornada. Todos pertenecen a [JourneyColors]. */
data class JourneyBannerColors(
    val background: Color,
    val content: Color,
    val badge: Color,
    val onBadge: Color,
)

/**
 * Colores del banner según el estado de la jornada.
 *
 * - Fuera de jornada ([isStarted] = false): SOLO rojo (fondo rojo + texto
 *   blanco), sin excepción: se ignoran [trackingOk] y [hasError].
 * - En jornada con tracking OK: verde fondo + blanco texto.
 * - En jornada degradada: blanco fondo + texto verde (transitorio) o rojo
 *   (error) según [hasError].
 */
fun journeyBannerColors(
    isStarted: Boolean,
    trackingOk: Boolean = true,
    hasError: Boolean = false,
): JourneyBannerColors {
    if (!isStarted) {
        return JourneyBannerColors(
            background = JourneyColors.Rojo,
            content = JourneyColors.Blanco,
            badge = JourneyColors.Blanco,
            onBadge = JourneyColors.Rojo,
        )
    }
    if (trackingOk) {
        return JourneyBannerColors(
            background = JourneyColors.Verde,
            content = JourneyColors.Blanco,
            badge = JourneyColors.Blanco,
            onBadge = JourneyColors.Verde,
        )
    }
    val ink = if (hasError) JourneyColors.Rojo else JourneyColors.Verde
    return JourneyBannerColors(
        background = JourneyColors.Blanco,
        content = ink,
        badge = ink,
        onBadge = JourneyColors.Blanco,
    )
}
