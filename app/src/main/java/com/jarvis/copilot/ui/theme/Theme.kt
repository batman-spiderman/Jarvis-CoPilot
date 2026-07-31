package com.jarvis.copilot.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val HudBackground = Color(0xFF0D1117)
val NeonCyan = Color(0xFF00E5FF)
val HudSurface = Color(0xFF161B22)
val HudTextPrimary = Color(0xFFE6EDF3)
val HudTextSecondary = Color(0xFF8B949E)
val HudError = Color(0xFFFF5C5C)

private val JarvisColorScheme = darkColorScheme(
    primary = NeonCyan,
    background = HudBackground,
    surface = HudSurface,
    onBackground = HudTextPrimary,
    onSurface = HudTextPrimary,
    secondary = HudTextSecondary,
    error = HudError
)

@Composable
fun JarvisCoPilotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = JarvisColorScheme,
        content = content
    )
}
