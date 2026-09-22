package com.vlad230596.sesame.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Палитра выведена из иконки приложения (см. design/icon/README.md): тёмно-синий
 * корпус, красно-белая стрела шлагбаума, стальные детали.
 *
 * Динамическая палитра Material You намеренно НЕ используется. Приложением
 * пользуются за рулём и в основном на ощупь-взгляд: кнопка открытия должна
 * выглядеть одинаково всегда, а не перекрашиваться вслед за обоями. Цвет здесь
 * несёт смысл — красный это «открыть шлагбаум», янтарный это «идёт запись», —
 * и отдавать его системе нельзя.
 */
private object Palette {
    val Ink = Color(0xFF0E1820)
    val Surface = Color(0xFF16242F)
    val SurfaceVariant = Color(0xFF1B2C38)
    val Outline = Color(0xFF3A4A57)

    val BarrierRed = Color(0xFFE23B2E)
    val BarrierRedDeep = Color(0xFF7A1F17)
    val RecordAmber = Color(0xFFE8A33D)
    val RecordAmberDeep = Color(0xFF6E4A12)

    val Steel = Color(0xFF7C8C98)
    val SteelLight = Color(0xFFC3CFD7)
    val TextOnDark = Color(0xFFE6EDF2)
    val MutedOnDark = Color(0xFFA8B8C4)
    val Danger = Color(0xFFFF6B5B)
}

private val DarkColors = darkColorScheme(
    primary = Palette.BarrierRed,
    onPrimary = Color.White,
    primaryContainer = Palette.BarrierRedDeep,
    onPrimaryContainer = Color(0xFFFFDAD4),

    secondary = Palette.SteelLight,
    onSecondary = Palette.Ink,
    secondaryContainer = Palette.SurfaceVariant,
    onSecondaryContainer = Palette.TextOnDark,

    tertiary = Palette.RecordAmber,
    onTertiary = Palette.Ink,
    tertiaryContainer = Palette.RecordAmberDeep,
    onTertiaryContainer = Color(0xFFFFE6C2),

    background = Palette.Ink,
    onBackground = Palette.TextOnDark,
    surface = Palette.Surface,
    onSurface = Palette.TextOnDark,
    surfaceVariant = Palette.SurfaceVariant,
    onSurfaceVariant = Palette.MutedOnDark,
    outline = Palette.Outline,
    outlineVariant = Color(0xFF283845),

    error = Palette.Danger,
    onError = Color.White,
    errorContainer = Color(0xFF5C1A13),
    onErrorContainer = Color(0xFFFFDAD4),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFC62E22),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD4),
    onPrimaryContainer = Color(0xFF410002),

    secondary = Color(0xFF4A5C68),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE6EE),
    onSecondaryContainer = Color(0xFF16242F),

    tertiary = Color(0xFF8A5A00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDB0),
    onTertiaryContainer = Color(0xFF2B1700),

    background = Color(0xFFF7F9FB),
    onBackground = Color(0xFF16242F),
    surface = Color.White,
    onSurface = Color(0xFF16242F),
    surfaceVariant = Color(0xFFE2E9EF),
    onSurfaceVariant = Color(0xFF47555F),
    outline = Color(0xFF8A99A4),

    error = Color(0xFFBA1A1A),
    onError = Color.White,
)

/**
 * Типографика крупнее стандартной Material: главный сценарий — попасть в кнопку
 * одним движением, не приглядываясь.
 */
private val SesameTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = TextStyle(
            fontSize = 18.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.SemiBold,
        ),
    )
}

@Composable
fun SesameTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = SesameTypography,
        content = content,
    )
}
