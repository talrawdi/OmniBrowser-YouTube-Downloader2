package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.ui.browser.ACCENT_THEMES

private val DarkColorScheme = darkColorScheme(
    primary = DarkCosmicPrimary,
    onPrimary = Color(0xFF001E3D),
    primaryContainer = DarkCosmicPrimaryContainer,
    onPrimaryContainer = DarkCosmicOnPrimaryContainer,
    secondary = DarkCosmicSecondary,
    onSecondary = Color(0xFF00364C),
    secondaryContainer = Color(0xFF004D6B),
    onSecondaryContainer = Color(0xFFBBE9FF),
    tertiary = DarkCosmicTertiary,
    background = DarkCosmicBackground,
    surface = DarkCosmicSurface,
    surfaceVariant = DarkCosmicSurfaceVariant,
    outline = DarkCosmicOutline,
    onBackground = Color(0xFFF1F5F9),
    onSurface = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF94A3B8)
)

private val LightColorScheme = lightColorScheme(
    primary = SapphirePrimary,
    onPrimary = Color.White,
    primaryContainer = SapphirePrimaryContainer,
    onPrimaryContainer = SapphireOnPrimaryContainer,
    secondary = SapphireSecondary,
    onSecondary = Color.White,
    tertiary = SapphireTertiary,
    background = LightCleanBackground,
    surface = LightCleanSurface,
    surfaceVariant = LightCleanSurfaceVariant,
    outline = LightCleanOutline,
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF0F172A),
    onSurfaceVariant = Color(0xFF475569)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accentIndex: Int = 0,
    content: @Composable () -> Unit,
) {
    val baseScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val selectedTheme = ACCENT_THEMES.getOrElse(accentIndex) { ACCENT_THEMES[0] }
    val accentColor = Color(selectedTheme.primaryColorHex)
    val isAmoled = selectedTheme.primaryColorHex == 0xFF000000L

    val customScheme = when {
        isAmoled -> DarkColorScheme.copy(
            primary = Color(0xFF60A5FA),
            background = Color(0xFF000000),
            surface = Color(0xFF07090E),
            surfaceVariant = Color(0xFF12151D),
            outline = Color(0xFF222632),
            onBackground = Color(0xFFF8FAFC),
            onSurface = Color(0xFFF8FAFC)
        )
        accentIndex > 0 -> baseScheme.copy(primary = accentColor)
        else -> baseScheme
    }

    MaterialTheme(
        colorScheme = customScheme,
        typography = Typography,
        content = content
    )
}
