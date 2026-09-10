package com.dmujeres.traccar.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = White,
    primaryContainer = Primary,
    onPrimaryContainer = White,
    secondary = Accent,
    onSecondary = White,
    secondaryContainer = Accent,
    onSecondaryContainer = White,
    background = Background,
    onBackground = Ink,
    surface = White,
    onSurface = Ink,
    surfaceVariant = Background,
    onSurfaceVariant = Ink,
    error = Primary,
    onError = White,
)

@Composable
fun DmujeresTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    // Diseño siempre claro: nada de fondos negros aunque el teléfono use modo oscuro.
    MaterialTheme(
        colorScheme = LightColorScheme,
        content = content,
    )
}
