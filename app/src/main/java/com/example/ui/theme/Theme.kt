package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = AmberPrimary,
    onPrimary = Color.Black,
    primaryContainer = AmberDark,
    onPrimaryContainer = Color.White,
    secondary = AmberLight,
    onSecondary = Color.Black,
    background = SlateDark900,
    onBackground = TextPrimary,
    surface = SlateDark800,
    onSurface = TextPrimary,
    surfaceVariant = SlateDark700,
    onSurfaceVariant = TextSecondary,
    error = StatusEmergencyRed,
    onError = Color.White,
    outline = SlateDark500
)

private val LightColorScheme = darkColorScheme(
    // Group bike riding apps heavily favor dark, high-contrast HUD mode even in daylight to reduce glare and battery drain
    primary = AmberPrimary,
    onPrimary = Color.Black,
    background = SlateDark900,
    onBackground = TextPrimary,
    surface = SlateDark800,
    onSurface = TextPrimary,
    surfaceVariant = SlateDark700,
    onSurfaceVariant = TextSecondary,
    error = StatusEmergencyRed,
    onError = Color.White,
    outline = SlateDark500
)

@Composable
fun RideTogetherTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
