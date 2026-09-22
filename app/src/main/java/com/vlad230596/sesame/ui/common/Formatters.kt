package com.vlad230596.sesame.ui.common

import com.vlad230596.sesame.data.CallOutcome
import com.vlad230596.sesame.data.Direction
import com.vlad230596.sesame.data.LabelSource
import com.vlad230596.sesame.data.SessionLabel
import com.vlad230596.sesame.data.TravelMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Форматирование для UI. Все подписи на русском и живут в коде рядом с местом
 * использования: строк немного, приложение одноязычное и в Play не публикуется,
 * а разъезд между `strings.xml` и enum'ами дороже экономии.
 */

private val RU: Locale = Locale.forLanguageTag("ru")

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", RU)
private val dateFormatter = DateTimeFormatter.ofPattern("d MMMM", RU)
private val dateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm", RU)

fun formatTime(epochMillis: Long): String =
    timeFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

fun formatDateTime(epochMillis: Long): String =
    dateTimeFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

/** Заголовок дня в ленте истории: «Сегодня», «Вчера» или дата. */
fun formatDayHeader(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when (day) {
        today -> "Сегодня"
        today.minusDays(1) -> "Вчера"
        else -> dateFormatter.format(day).let { if (day.year == today.year) it else "$it ${day.year}" }
    }
}

/**
 * «сегодня 18:42» — день и время в одной строке, для шапки правки метки.
 *
 * День словом, а не датой: правят метку почти всегда в тот же день или на
 * следующий, и «сегодня» отвечает на вопрос быстрее, чем «22 сентября».
 */
fun formatDayTime(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    val day = formatDayHeader(epochMillis, now)
    val separator = if (day.first().isDigit()) ", " else " "
    return day.replaceFirstChar { it.lowercase() } + separator + formatTime(epochMillis)
}

/** Координата для экрана шлагбаума и дома. Пять знаков — это ~1 м. */
fun formatCoordinate(lat: Double?, lon: Double?): String? {
    if (lat == null || lon == null) return null
    return "%.5f, %.5f".format(Locale.ROOT, lat, lon)
}

/**
 * «N минут назад» для строки последнего фонового события (§4.7). Это главный
 * индикатор того, что сбор не умер, поэтому давность показывается словами,
 * а не временем: «14:02» ничего не говорит, пока не посмотришь на часы.
 */
fun formatAgo(epochMillis: Long?, now: Long = System.currentTimeMillis()): String {
    if (epochMillis == null || epochMillis <= 0) return "событий ещё не было"
    val minutes = ((now - epochMillis) / 60_000L).coerceAtLeast(0)
    return when {
        minutes < 1 -> "только что"
        minutes < 60 -> "$minutes ${plural(minutes, "минуту", "минуты", "минут")} назад"
        minutes < 60 * 24 -> {
            val hours = minutes / 60
            "$hours ${plural(hours, "час", "часа", "часов")} назад"
        }
        else -> {
            val days = minutes / (60 * 24)
            "$days ${plural(days, "день", "дня", "дней")} назад"
        }
    }
}

/**
 * То же самое для шапки экрана и виджета: «4 мин назад».
 *
 * Короткая форма нужна там, где строка стоит рядом с заголовком и набрана
 * моноширинным: «4 минуты назад» и «15 минут назад» разной длины дёргали бы
 * шапку при каждом обновлении.
 */
fun formatAgoShort(epochMillis: Long?, now: Long = System.currentTimeMillis()): String {
    if (epochMillis == null || epochMillis <= 0) return "нет событий"
    val minutes = ((now - epochMillis) / 60_000L).coerceAtLeast(0)
    return when {
        minutes < 1 -> "только что"
        minutes < 60 -> "$minutes мин назад"
        minutes < 60 * 24 -> "${minutes / 60} ч назад"
        else -> "${minutes / (60 * 24)} дн назад"
    }
}

fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "%d:%02d:%02d".format(hours, minutes, seconds)
        else -> "%d:%02d".format(minutes, seconds)
    }
}

/**
 * Объём для строки «занято 2,3 из 10 ГБ».
 *
 * Дробная часть отбрасывается, когда она нулевая: лимит хранилища — ровно 10 ГБ,
 * и «10,00 ГБ» рядом с «2,3 ГБ» читается как измеренная величина, хотя это
 * константа. Запятая, а не точка: строка русская и набрана рядом с обычным
 * текстом.
 */
fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes Б"
    bytes < 1024L * 1024 -> "%.0f КБ".format(RU, bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f МБ".format(RU, bytes / (1024.0 * 1024)).trimZero()
    else -> "%.1f ГБ".format(RU, bytes / (1024.0 * 1024 * 1024)).trimZero()
}

private fun String.trimZero(): String = replace(",0 ", " ")

/**
 * Номер шлагбаума для главного экрана и виджета: `+7 ··· ·· 14`.
 *
 * Домашний экран и виджет видно посторонним — через плечо, на столе, на чужом
 * скриншоте, — а номер шлагбаума это фактически ключ от двора. Хвоста в две
 * цифры хватает, чтобы отличить один шлагбаум от другого глазами; целиком номер
 * остаётся только в настройках, где его правят.
 */
fun maskPhone(phone: String?): String? {
    val digits = phone?.filter { it.isDigit() } ?: return null
    if (digits.length < 3) return null
    val country = if (phone.trimStart().startsWith("+")) "+${digits.first()} " else ""
    return "$country··· ·· ${digits.takeLast(2)}"
}

fun plural(value: Long, one: String, few: String, many: String): String {
    val mod100 = value % 100
    val mod10 = value % 10
    return when {
        mod100 in 11..14 -> many
        mod10 == 1L -> one
        mod10 in 2..4 -> few
        else -> many
    }
}

fun Direction.title(): String = when (this) {
    Direction.IN -> "Во двор"
    Direction.OUT -> "Из двора"
    Direction.UNKNOWN -> "Неизвестно"
}

fun TravelMode.title(): String = when (this) {
    TravelMode.DRIVER -> "За рулём"
    TravelMode.PASSENGER -> "Пассажир"
    TravelMode.FOOT -> "Пешком"
    TravelMode.UNKNOWN -> "Неизвестно"
}

fun LabelSource.title(): String = when (this) {
    LabelSource.WIDGET -> "виджет"
    LabelSource.APP -> "приложение"
    LabelSource.MANUAL -> "вручную"
}

fun CallOutcome.title(): String = when (this) {
    CallOutcome.CALLED -> "звонок ушёл"
    CallOutcome.DIALER_FALLBACK -> "открыта звонилка"
    CallOutcome.NO_NUMBER -> "номер не задан"
    CallOutcome.CANCELLED_BY_USER -> "отменено"
}

fun SessionLabel.title(): String = when (this) {
    SessionLabel.GOING_HOME -> "Еду домой"
    SessionLabel.LEAVING_HOME -> "Еду из дома"
    SessionLabel.ON_FOOT -> "Пешком"
    SessionLabel.OTHER -> "Другое"
    SessionLabel.NONE -> "Без метки"
}

/** Порядок кнопок на экране выбора метки сессии (§4.3). */
val SESSION_LABEL_CHOICES = listOf(
    SessionLabel.GOING_HOME,
    SessionLabel.LEAVING_HOME,
    SessionLabel.ON_FOOT,
    SessionLabel.OTHER,
)

internal fun localDateOf(epochMillis: Long): LocalDate =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
