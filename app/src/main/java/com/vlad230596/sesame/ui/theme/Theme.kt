package com.vlad230596.sesame.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
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
 *
 * Главное правило раскладки ролей: **в схеме Material нет красного**. `primary` в
 * Material 3 — это цвет по умолчанию почти для всего (Button, Switch, Slider,
 * Checkbox, фокус TextField, LinearProgressIndicator, метка TextButton и
 * OutlinedButton, `surfaceTint` у приподнятых поверхностей). Стоит положить туда
 * цвет-смысл — и «открыть шлагбаум» оказывается написано на ползунке настройки
 * частоты барометра. Поэтому вся схема — спокойная сталь, а оба смысловых цвета
 * живут отдельно: красный/малиновый в [SesameAccents] (только кнопки шлагбаумов),
 * янтарный в `tertiary` (только «идёт запись»).
 */
private object Palette {
    // Корпус иконки и производные от него поверхности.
    val Ink = Color(0xFF0E1820)
    val SurfaceLowest = Color(0xFF0A1218)
    val Surface = Color(0xFF16242F)
    val SurfaceContainer = Color(0xFF1B2C38)
    val SurfaceHigh = Color(0xFF22343F)
    val SurfaceHighest = Color(0xFF2A3E4A)
    val SurfaceBright = Color(0xFF32424E)

    // Стойка и шарнир шлагбаума — из них вся нейтральная механика интерфейса.
    val Steel = Color(0xFF7C8C98)
    val SteelLight = Color(0xFFC3CFD7)
    val SteelControl = Color(0xFFA6BAC7)
    val SteelContainer = Color(0xFF2F4553)
    val SteelContainerBright = Color(0xFF32485A)
    val SteelDark = Color(0xFF3F5463)

    val Outline = Color(0xFF3A4A57)
    val OutlineVariant = Color(0xFF283845)
    val TextOnDark = Color(0xFFE6EDF2)
    val MutedOnDark = Color(0xFFA8B8C4)

    // «Идёт запись». Единственная роль янтарного.
    val RecordAmber = Color(0xFFE8A33D)
    val RecordAmberDeep = Color(0xFF6E4A12)

    val Danger = Color(0xFFFF6B5B)
    val DangerInk = Color(0xFF3A0A05)
}

/**
 * Цвет одной кнопки открытия шлагбаума.
 *
 * Вынесен из [androidx.compose.material3.ColorScheme] специально: это не «акцент
 * приложения», а смысл конкретной кнопки. В схеме его быть не должно, иначе он
 * расползётся по всем контролам Material.
 */
@Immutable
data class BarrierAccent(
    /** Заливка кнопки. */
    val container: Color,
    /** Подпись и прогресс поверх заливки. */
    val onContainer: Color,
)

/**
 * Смысловые цвета «Сезама», которых нет в схеме Material.
 *
 * Кнопок шлагбаумов две, и различаться они обязаны цветом, а не только подписью:
 * за рулём подпись не читают, целятся в пятно. Пара выбрана как два шага одного
 * горячего ряда — красная стрела шлагбаума (H≈4°) и малиновый (H≈329°). Обе
 * одинаково насыщены и одинаково светлы, поэтому читаются как пара, а не как два
 * случайных цвета; при этом они разведены по тону с янтарным «идёт запись»
 * (H≈36°) и со стальной механикой интерфейса (H≈205°).
 *
 * Разница между ними лежит в синем канале (0x22 против 0x78), поэтому пара
 * переживает и самый частый дефицит цветовосприятия — красно-зелёный.
 */
@Immutable
data class SesameAccents(
    val barriers: List<BarrierAccent>,
) {
    /**
     * Цвет по порядковому номеру кнопки на экране. Цвет закреплён за позицией, а
     * не за id: верхняя кнопка всегда красная — это то, что запоминает рука.
     */
    fun barrier(index: Int): BarrierAccent = barriers[index.mod(barriers.size)]
}

/**
 * Пара одна на обе схемы. Смысловой цвет не имеет права меняться от того,
 * включил ли пользователь ночную тему: рука целится в то же пятно.
 *
 * Контраст белой подписи: красная 5.50:1, малиновая 5.57:1 (WCAG требует 4.5:1).
 * Контраст самой кнопки с фоном: 3.26:1 и 3.22:1 в тёмной теме, 5.22:1 и 5.28:1
 * в светлой.
 */
private val BarrierAccents = SesameAccents(
    barriers = listOf(
        BarrierAccent(container = Color(0xFFC62E22), onContainer = Color.White),
        BarrierAccent(container = Color(0xFFB23A78), onContainer = Color.White),
    ),
)

val LocalSesameAccents = staticCompositionLocalOf { BarrierAccents }

/** Доступ к смысловым цветам: `SesameAccentColors.current.barrier(0)`. */
object SesameAccentColors {
    val current: SesameAccents
        @Composable
        @ReadOnlyComposable
        get() = LocalSesameAccents.current
}

/**
 * Тёмная схема. Роли `surfaceContainer*`, `inverse*` и `surfaceTint` заданы
 * явно: незаданные они остаются из базовой палитры Material — фиолетово-серые,
 * и ими покрашены как раз те поверхности, которые рисует не наш код
 * (NavigationBar, AlertDialog, ModalBottomSheet, Snackbar).
 */
private val DarkColors = darkColorScheme(
    // Вся механика интерфейса — сталь, а не смысл.
    primary = Palette.SteelControl,
    onPrimary = Color(0xFF102028),
    primaryContainer = Palette.SteelContainer,
    onPrimaryContainer = Color(0xFFD3E2EE),
    inversePrimary = Palette.SteelDark,

    secondary = Palette.SteelLight,
    onSecondary = Palette.Surface,
    // Заметно светлее фона: этим цветом Material красит выбранный чип и
    // индикатор выбранной вкладки — на уровне фона они просто исчезали.
    secondaryContainer = Palette.SteelContainerBright,
    onSecondaryContainer = Color(0xFFD6E4EF),

    // Единственный смысловой цвет внутри схемы — «идёт запись».
    tertiary = Palette.RecordAmber,
    onTertiary = Palette.Ink,
    tertiaryContainer = Palette.RecordAmberDeep,
    onTertiaryContainer = Color(0xFFFFE6C2),

    background = Palette.Ink,
    onBackground = Palette.TextOnDark,
    surface = Palette.Surface,
    onSurface = Palette.TextOnDark,
    surfaceVariant = Palette.SurfaceContainer,
    onSurfaceVariant = Palette.MutedOnDark,
    surfaceTint = Palette.SteelControl,

    surfaceDim = Palette.Ink,
    surfaceBright = Palette.SurfaceBright,
    surfaceContainerLowest = Palette.SurfaceLowest,
    surfaceContainerLow = Palette.Surface,
    surfaceContainer = Palette.SurfaceContainer,
    surfaceContainerHigh = Palette.SurfaceHigh,
    surfaceContainerHighest = Palette.SurfaceHighest,

    inverseSurface = Palette.TextOnDark,
    inverseOnSurface = Palette.Surface,

    outline = Palette.Outline,
    outlineVariant = Palette.OutlineVariant,
    scrim = Color(0xFF000000),

    error = Palette.Danger,
    onError = Palette.DangerInk,
    errorContainer = Color(0xFF5C1A13),
    onErrorContainer = Color(0xFFFFDAD4),
)

/** Светлая схема — тот же расклад ролей, те же смыслы, другой конец шкалы. */
private val LightColors = lightColorScheme(
    primary = Palette.SteelDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E1EC),
    onPrimaryContainer = Color(0xFF16242F),
    inversePrimary = Palette.SteelControl,

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
    surfaceTint = Palette.SteelDark,

    surfaceDim = Color(0xFFD9E1E8),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F6F9),
    surfaceContainer = Color(0xFFECF1F5),
    surfaceContainerHigh = Color(0xFFE6EDF2),
    surfaceContainerHighest = Color(0xFFE0E8EE),

    inverseSurface = Color(0xFF16242F),
    inverseOnSurface = Color(0xFFF2F6F9),

    outline = Color(0xFF6C7A85),
    outlineVariant = Color(0xFFC3CFD7),
    scrim = Color(0xFF000000),

    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD4),
    onErrorContainer = Color(0xFF410002),
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
    CompositionLocalProvider(LocalSesameAccents provides BarrierAccents) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = SesameTypography,
            content = content,
        )
    }
}
