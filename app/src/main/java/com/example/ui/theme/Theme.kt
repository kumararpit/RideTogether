package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * RideTogether is a dark-only product: the navigation surfaces are designed
 * for a motorcycle cockpit in daylight and at night, and a light theme would
 * wash out the map overlays. The "light" scheme is therefore the same dark
 * scheme (the reference has no light variant either).
 */
private val RideColorScheme = darkColorScheme(
    primary = AmberPrimary,
    onPrimary = Color(0xFF1A1205),
    primaryContainer = AmberDark,
    onPrimaryContainer = Color.White,
    secondary = AmberLight,
    onSecondary = Color.Black,
    secondaryContainer = SlateDark700,
    onSecondaryContainer = TextPrimary,
    tertiary = RouteBlue,
    onTertiary = Color.White,
    background = SlateDark900,
    onBackground = TextPrimary,
    surface = SlateDark800,
    onSurface = TextPrimary,
    surfaceVariant = SlateDark700,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = SlateDark900,
    surfaceContainerLow = SlateDark800,
    surfaceContainer = SlateDark800,
    surfaceContainerHigh = SlateDark700,
    surfaceContainerHighest = SlateDark600,
    error = StatusEmergencyRed,
    onError = Color.White,
    errorContainer = StatusEmergencyRedDark,
    onErrorContainer = Color.White,
    outline = SlateDark500,
    outlineVariant = SlateDark600,
    scrim = Color(0xCC000000),
    inverseSurface = TextPrimary,
    inverseOnSurface = SlateDark900
)

@Composable
fun RideTogetherTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = RideColorScheme,
        typography = Typography,
        content = content
    )
}
