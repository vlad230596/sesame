package com.vlad230596.sesame.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Палитра утверждённого макета «Сезам — UI v0».
 *
 * Динамическая палитра Material You намеренно НЕ используется. Приложением
 * пользуются за рулём и в основном на ощупь-взгляд: кнопка открытия должна
 * выглядеть одинаково всегда, а не перекрашиваться вслед за обоями. Цвет здесь
 * несёт смысл — янтарный это «шлагбаум A», бирюзовый это «шлагбаум B», — и
 * отдавать его системе нельзя.
 */
internal object Palette {
    /** Фон экрана. */
    val Background = Color(0xFF0E0F12)

    /** Карточка, плашка, строка-кнопка. */
    val Surface = Color(0xFF191B20)

    /** Приподнятая поверхность и трек прогресса. */
    val SurfaceHigh = Color(0xFF22252B)

    /** Спокойная граница карточки. */
    val Border = Color(0xFF22252B)

    /** Заметная граница: строка-кнопка, плитка выбора. */
    val BorderStrong = Color(0xFF2C3037)

    /** Черта над нижней навигацией. */
    val NavBorder = Color(0xFF1E2126)

    val TextPrimary = Color(0xFFF4F5F7)
    val TextMuted = Color(0xFF9BA2AC)

    /** Неактивный пункт навигации, служебная подпись. */
    val TextDim = Color(0xFF7D858F)

    /** Шлагбаум A. */
    val Amber = Color(0xFFF2A63E)
    val OnAmber = Color(0xFF14100A)

    /** Вторая строка на янтарной кнопке: тот же цвет, но тише. */
    val OnAmberMuted = Color(0xFF4A3A18)

    /** Шлагбаум B. */
    val Teal = Color(0xFF56C3B2)
    val OnTeal = Color(0xFF07211E)
    val OnTealMuted = Color(0xFF12403A)

    /** Янтарная плашка: «N меток без подтверждения». */
    val AmberSurface = Color(0xFF2A2013)
    val AmberBorder = Color(0xFF4A3718)
    val AmberTextSoft = Color(0xFFC9A46A)

    /** Экран отсчёта перед звонком — тёплая подложка. */
    val CallBackground = Color(0xFF14100A)
    val CallRingTrack = Color(0xFF2C2415)
    val CallPhone = Color(0xFF9B8A6A)

    /** «Живой сбор»: зелёная точка в шапке. */
    val Live = Color(0xFF5ED39A)

    /** «Идёт запись»: точка, карточка сессии, кнопка «Стоп». */
    val Record = Color(0xFFFF6B5E)
    val OnRecord = Color(0xFF2A0B08)
    val RecordSurface = Color(0xFF221416)
    val RecordBorder = Color(0xFF4A2023)
    val RecordChip = Color(0xFF3A1B1E)
    val RecordChipText = Color(0xFFFF9B91)
    val RecordMuted = Color(0xFFC79A95)
    val RecordDim = Color(0xFF9E8B89)
    val RecordOutline = Color(0xFF5A2A2D)
    val RecordOutlineText = Color(0xFFFFD2CD)
}

/**
 * Цвет одной кнопки открытия шлагбаума.
 *
 * Вынесен из [androidx.compose.material3.ColorScheme] специально: это не «акцент
 * приложения», а смысл конкретной кнопки. Пара разведена по тону максимально
 * широко — янтарный H≈36°, бирюзовый H≈170°, — поэтому переживает и самый
 * частый дефицит цветовосприятия, красно-зелёный: различие несут и светлота, и
 * синий канал.
 */
@Immutable
data class BarrierAccent(
    /** Заливка кнопки. */
    val container: Color,
    /** Крупная подпись и иконка поверх заливки. */
    val onContainer: Color,
    /** Вторая строка кнопки: «шлагбаум A · номер». */
    val onContainerMuted: Color,
)

/**
 * Смысловые цвета «Сезама», которых нет в схеме Material.
 *
 * Кнопок шлагбаумов две, и различаться они обязаны цветом, а не только подписью:
 * за рулём подпись не читают, целятся в пятно.
 */
@Immutable
data class SesameAccents(
    val barriers: List<BarrierAccent>,
) {
    /**
     * Цвет по порядковому номеру кнопки на экране. Цвет закреплён за позицией, а
     * не за id: верхняя кнопка всегда янтарная — это то, что запоминает рука.
     * Тот же порядок действует в виджете и полоской в истории.
     */
    fun barrier(index: Int): BarrierAccent = barriers[index.mod(barriers.size)]
}

/**
 * Пара цветов кнопок. Одна на всё приложение: приложение, виджет, история.
 *
 * Контраст тёмной подписи: янтарная 10.8:1, бирюзовая 11.6:1 (WCAG требует
 * 4.5:1). Контраст самой кнопки с фоном #0E0F12: 9.5:1 и 10.2:1.
 */
val SesameBarrierAccents = SesameAccents(
    barriers = listOf(
        BarrierAccent(
            container = Palette.Amber,
            onContainer = Palette.OnAmber,
            onContainerMuted = Palette.OnAmberMuted,
        ),
        BarrierAccent(
            container = Palette.Teal,
            onContainer = Palette.OnTeal,
            onContainerMuted = Palette.OnTealMuted,
        ),
    ),
)

val LocalSesameAccents = staticCompositionLocalOf { SesameBarrierAccents }

/** Доступ к смысловым цветам: `SesameAccentColors.current.barrier(0)`. */
object SesameAccentColors {
    val current: SesameAccents
        @Composable
        @ReadOnlyComposable
        get() = LocalSesameAccents.current
}

/**
 * Схема Material поверх палитры макета.
 *
 * `primary` — янтарный: на макете это акцент всего приложения (активный пункт
 * навигации, «Открыть» в плашке, ссылки), и совпадение с цветом шлагбаума A там
 * сделано осознанно. Кнопки шлагбаумов при этом всё равно красятся не отсюда, а
 * из [SesameBarrierAccents]: из схемы кнопка B взяться не может, а рядом стоящие
 * кнопки обязаны быть разными.
 *
 * Роли `surfaceContainer*`, `inverse*` и `surfaceTint` заданы явно: незаданные
 * они остаются из базовой палитры Material — фиолетово-серые, и ими покрашены
 * как раз те поверхности, которые рисует не наш код (AlertDialog,
 * ModalBottomSheet, Snackbar).
 */
private val DarkColors = darkColorScheme(
    primary = Palette.Amber,
    onPrimary = Palette.OnAmber,
    primaryContainer = Palette.AmberSurface,
    onPrimaryContainer = Color(0xFFF7D9A8),
    inversePrimary = Color(0xFF8A5F1C),

    secondary = Palette.Teal,
    onSecondary = Palette.OnTeal,
    secondaryContainer = Color(0xFF17352F),
    onSecondaryContainer = Color(0xFFA9E6DB),

    tertiary = Palette.Live,
    onTertiary = Color(0xFF05231A),
    tertiaryContainer = Color(0xFF12352A),
    onTertiaryContainer = Color(0xFFB4EFD1),

    background = Palette.Background,
    onBackground = Palette.TextPrimary,
    surface = Palette.Surface,
    onSurface = Palette.TextPrimary,
    surfaceVariant = Palette.SurfaceHigh,
    onSurfaceVariant = Palette.TextMuted,
    // Тонировать приподнятые поверхности нечем: на макете они просто светлее
    // фона. Цвет, равный самой поверхности, гасит наложение Material.
    surfaceTint = Palette.Surface,

    surfaceDim = Palette.Background,
    surfaceBright = Color(0xFF2A2E35),
    surfaceContainerLowest = Palette.Background,
    surfaceContainerLow = Color(0xFF15171B),
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Color(0xFF1F2228),
    surfaceContainerHighest = Palette.SurfaceHigh,

    // Инверсная пара намеренно не инверсная: этими ролями Material красит
    // Snackbar, а светлая плашка на почти чёрном экране — вспышка в глаза,
    // особенно ночью, ради которой тёмная тема и выбрана.
    inverseSurface = Palette.SurfaceHigh,
    inverseOnSurface = Palette.TextPrimary,

    outline = Palette.BorderStrong,
    outlineVariant = Palette.Border,
    scrim = Color(0xFF000000),

    // «Идёт запись» и всё тревожное — один и тот же коралловый макета.
    error = Palette.Record,
    onError = Palette.OnRecord,
    errorContainer = Palette.RecordChip,
    onErrorContainer = Palette.RecordChipText,
)

/** Скругления макета: 18 у плашек, 22 у карточек, 26 у кнопок шлагбаумов. */
private val SesameShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(Dimens.CardCorner),
    large = RoundedCornerShape(Dimens.ButtonCorner),
    extraLarge = RoundedCornerShape(Dimens.BarrierCorner),
)

/**
 * Тема приложения.
 *
 * **Светлой схемы нет, и это решение, а не пропуск.** Весь макет построен на
 * тёмном фоне #0E0F12: на нём держится всё остальное — янтарная и бирюзовая
 * кнопки рассчитаны на тёмную подложку и на светлой перестают читаться как пара,
 * а «идёт запись» и «сбор жив» различаются свечением точки. Главный сценарий —
 * ночь и машина, где светлый экран слепит. Параметр [darkTheme] оставлен для
 * previews и осознанно ни на что не влияет.
 */
@Composable
fun SesameTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalSesameAccents provides SesameBarrierAccents) {
        MaterialTheme(
            colorScheme = DarkColors,
            typography = SesameTypography,
            shapes = SesameShapes,
            content = content,
        )
    }
}
