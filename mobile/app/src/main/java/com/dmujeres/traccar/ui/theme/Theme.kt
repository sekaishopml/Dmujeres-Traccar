package com.dmujeres.traccar.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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
    error = StatusError,
)

private val DarkColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = White,
    background = Color(0xFF121212),
    onBackground = White,
    surface = Color(0xFF1E1E1E),
    onSurface = White,
    surfaceVariant = Color(0xFF2B2B2B),
    onSurfaceVariant = Color(0xFFE0E0E0),
    error = StatusError,
)

@Composable
fun DmujeresTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColorScheme else LightColorScheme,
        content = content,
    )
}
