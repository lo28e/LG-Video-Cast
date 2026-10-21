package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val OledDarkColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = Color(0xFF00363D),
    primaryContainer = Color(0xFF004F58),
    onPrimaryContainer = Color(0xFF9CF0FF),
    secondary = NeonPurple,
    onSecondary = Color(0xFF280D5A),
    secondaryContainer = Color(0xFF3F207D),
    onSecondaryContainer = Color(0xFFEADBFF),
    tertiary = NeonGreen,
    background = OledBackground,
    onBackground = TextPrimary,
    surface = OledSurface,
    onSurface = TextPrimary,
    surfaceVariant = OledSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = BorderStroke,
    outlineVariant = Color(0xFF1B2433)
)

private val OledLightColorScheme = lightColorScheme(
    primary = Color(0xFF006877),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA1EFFF),
    onPrimaryContainer = Color(0xFF001F25),
    secondary = Color(0xFF6B4EB6),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEADBFF),
    onSecondaryContainer = Color(0xFF24005A),
    tertiary = Color(0xFF047857),
    background = Color(0xFF0E131B),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF161E2E),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFFCBD5E1),
    outline = Color(0xFF334155)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    // We prioritize the deep OLED theme tailored for LG OLED TVs
    val colorScheme = if (darkTheme) OledDarkColorScheme else OledDarkColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
