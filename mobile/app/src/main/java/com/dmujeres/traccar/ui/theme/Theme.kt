package com.dmujeres.traccar.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = White,
    primaryContainer = Primary,
    onPrimaryContainer = White,
    secondary = NeonViolet,
    onSecondary = White,
    secondaryContainer = NeonViolet,
    onSecondaryContainer = White,
    tertiary = NeonCyan,
    onTertiary = White,
    background = Background,
    onBackground = Ink,
    surface = White,
    onSurface = Ink,
    surfaceVariant = Background,
    onSurfaceVariant = Ink,
    error = StatusError,
    onError = White,
    outline = BorderGlass,
)

private val DarkColorScheme = darkColorScheme(
    primary = NeonPink,
    onPrimary = White,
    primaryContainer = Primary,
    onPrimaryContainer = White,
    secondary = NeonViolet,
    onSecondary = White,
    secondaryContainer = SurfaceGlassLight,
    onSecondaryContainer = TextPrimary,
    tertiary = NeonCyan,
    onTertiary = White,
    background = BgDark,
    onBackground = TextPrimary,
    surface = SurfaceGlass,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceGlassLight,
    onSurfaceVariant = TextSecondary,
    error = StatusError,
    onError = White,
    outline = BorderGlass,
    outlineVariant = BorderGlass,
    scrim = BgDark,
    inverseSurface = TextPrimary,
    inverseOnSurface = BgDark,
)

private val DmujeresTypography = Typography(
    displayLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.25).sp,
        color = TextPrimary,
    ),
    displayMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        color = TextPrimary,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        color = TextPrimary,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        color = TextPrimary,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
        color = TextPrimary,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        color = TextPrimary,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = TextSecondary,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
        color = TextSecondary,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
)

private val DmujeresShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun DmujeresTheme(
    darkTheme: Boolean = true,
    // Enterprise dark default: ignore system, stay premium dark unless caller opts in to follow system
    followSystem: Boolean = false,
    content: @Composable () -> Unit
) {
    val useDark = if (followSystem) isSystemInDarkTheme() else darkTheme
    val colorScheme = if (useDark) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = DmujeresTypography,
        shapes = DmujeresShapes,
        content = content,
    )
}
