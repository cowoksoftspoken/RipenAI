package com.ripenai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = AgriPrimary,
    primaryContainer = AgriPrimaryContainer,
    onPrimaryContainer = AgriOnPrimaryContainer,
    secondary = AgriSecondary,
    background = AgriOnSurface, // dark background for dark theme
    surface = AgriOnSurface,
    onBackground = AgriBackground,
    onSurface = AgriBackground
)

private val LightColorScheme = lightColorScheme(
    primary = AgriPrimary,
    onPrimary = AgriBackground,
    primaryContainer = AgriPrimaryContainer,
    onPrimaryContainer = AgriOnPrimaryContainer,
    secondary = AgriSecondary,
    background = AgriBackground,
    surface = AgriSurface,
    onBackground = AgriOnSurface,
    onSurface = AgriOnSurface,
    outline = AgriOutline
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
