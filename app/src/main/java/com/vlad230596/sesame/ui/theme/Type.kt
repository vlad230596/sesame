package com.vlad230596.sesame.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.vlad230596.sesame.R

/**
 * Шрифты макета: Golos Text для всего и JetBrains Mono для чисел.
 *
 * **Начертания лежат в APK, а не тянутся через downloadable fonts.** Провайдер
 * шрифтов — это Google Play Services: его может не быть на устройстве, он может
 * не ответить в момент первой отрисовки, и тогда экран молча уезжает на Roboto.
 * Приложением пользуются за рулём, и «иногда другой шрифт» здесь хуже лишних
 * 360 КБ. Ссылки на `R.font.*` проверяются компилятором: опечатка или пропавший
 * файл — это красная сборка, а не тихий системный шрифт у пользователя.
 *
 * Файлов при этом два, по одному на гарнитуру: оба шрифта переменные, а вес
 * задаётся осью `wght` в XML-обёртке (`res/font/golos_text_*.xml`). Отдельный
 * ресурс на каждый вес нужен потому, что Compose грузит ресурс `Font(id, weight)`
 * целиком: если в одном `font-family` лежат четыре начертания, вес внутри него
 * уже не выбирается.
 */
object SesameFonts {

    val Golos = FontFamily(
        Font(R.font.golos_text_regular, FontWeight.Normal),
        Font(R.font.golos_text_medium, FontWeight.Medium),
        Font(R.font.golos_text_semibold, FontWeight.SemiBold),
        Font(R.font.golos_text_bold, FontWeight.Bold),
    )

    val Mono = FontFamily(
        Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
        Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
        Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
    )
}

/**
 * Именованные стили макета.
 *
 * Роли Material (`titleLarge`, `bodyMedium`, …) остаются для экранов, написанных
 * на готовых компонентах, а здесь лежит то, что на макете подписано размером:
 * имя въезда на кнопке, отсчёт, «4 мин назад». Держать это инлайном в экране
 * значило бы развести одинаковые на вид строки по разным кеглям.
 */
object SesameText {

    /** «Сезам» в шапке экрана. */
    val ScreenTitle = TextStyle(
        fontFamily = SesameFonts.Golos,
        fontSize = 25.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp,
    )

    /** Заголовок окна поверх экрана: выбор метки, разрешения. */
    val DialogTitle = TextStyle(
        fontFamily = SesameFonts.Golos,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.6).sp,
    )

    /** Человеческое имя въезда на кнопке шлагбаума. */
    val BarrierName = TextStyle(
        fontFamily = SesameFonts.Golos,
        fontSize = 27.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.54).sp,
    )

    /** Вторая строка кнопки: «шлагбаум A · +7 ··· ·· 14». */
    val BarrierSub = TextStyle(
        fontFamily = SesameFonts.Golos,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium,
    )

    /** Подпись плитки в выборе метки сессии. */
    val TileTitle = TextStyle(
        fontFamily = SesameFonts.Golos,
        fontSize = 22.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.44).sp,
    )

    /** Строка-кнопка «Интенсивная запись». */
    val RowTitle = TextStyle(
        fontFamily = SesameFonts.Golos,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
    )

    /** Заголовок внутри карточки статуса. */
    val CardTitle = TextStyle(
        fontFamily = SesameFonts.Golos,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
    )

    /** Обычная строка карточки. */
    val Body = TextStyle(
        fontFamily = SesameFonts.Golos,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal,
    )

    /** Мелкая поясняющая строка. */
    val Caption = TextStyle(
        fontFamily = SesameFonts.Golos,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        fontWeight = FontWeight.Normal,
    )

    /** Подпись пункта нижней навигации. */
    val NavLabel = TextStyle(
        fontFamily = SesameFonts.Golos,
        fontSize = 12.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
    )

    /** Числа: объём, таймеры, «4 мин назад». */
    val Mono11 = mono(11)
    val Mono12 = mono(12)
    val Mono13 = mono(13)
    val Mono14 = mono(14)

    /** Оставшееся время идущей сессии. */
    val MonoTimer = TextStyle(
        fontFamily = SesameFonts.Mono,
        fontSize = 54.sp,
        lineHeight = 54.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-1.6).sp,
    )

    /** Секунды в отсчёте перед звонком. */
    val MonoCountdown = TextStyle(
        fontFamily = SesameFonts.Mono,
        fontSize = 104.sp,
        lineHeight = 104.sp,
        fontWeight = FontWeight.Bold,
    )

    private fun mono(size: Int) = TextStyle(
        fontFamily = SesameFonts.Mono,
        fontSize = size.sp,
        lineHeight = (size + 4).sp,
        fontWeight = FontWeight.Normal,
    )
}

/**
 * Типографика Material целиком переведена на Golos Text.
 *
 * Крупнее стандартной: главный сценарий — попасть в кнопку одним движением,
 * не приглядываясь. Экраны, которые рисует не этот пакет (настройки, история,
 * системные диалоги Material), получают шрифт макета отсюда и ничего для этого
 * не делают.
 */
internal val SesameTypography: Typography = Typography().run {
    fun TextStyle.golos(weight: FontWeight = fontWeight ?: FontWeight.Normal) =
        copy(fontFamily = SesameFonts.Golos, fontWeight = weight)

    copy(
        displayLarge = displayLarge.golos(FontWeight.Bold),
        displayMedium = displayMedium.golos(FontWeight.Bold),
        displaySmall = displaySmall.golos(FontWeight.Bold),
        headlineLarge = headlineLarge.golos(FontWeight.Bold),
        headlineMedium = headlineMedium.golos(FontWeight.Bold),
        headlineSmall = headlineSmall.golos(FontWeight.Bold),
        titleLarge = titleLarge.golos(FontWeight.Bold),
        titleMedium = titleMedium.golos(FontWeight.SemiBold),
        titleSmall = titleSmall.golos(FontWeight.SemiBold),
        bodyLarge = bodyLarge.golos(),
        bodyMedium = bodyMedium.golos(),
        bodySmall = bodySmall.golos(),
        labelLarge = TextStyle(
            fontFamily = SesameFonts.Golos,
            fontSize = 16.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        labelMedium = labelMedium.golos(FontWeight.Medium),
        labelSmall = labelSmall.golos(FontWeight.Medium),
    )
}
