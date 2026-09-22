package com.vlad230596.sesame.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B3A57),
    secondary = Color(0xFF4A6B85),
    tertiary = Color(0xFFB98A00),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9FC6E8),
    secondary = Color(0xFFB4C9DB),
    tertiary = Color(0xFFFFD166),
)

/**
 * Material 3. minSdk 34, поэтому динамическая палитра доступна всегда;
 * статические схемы остаются фолбэком.
 */
@Composable
fun SesameTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor -> dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
