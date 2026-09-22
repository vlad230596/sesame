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

fun formatMinutes(minutes: Int): String =
    "$minutes ${plural(minutes.toLong(), "минута", "минуты", "минут")}"

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes Б"
    bytes < 1024L * 1024 -> "%.0f КБ".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f МБ".format(bytes / (1024.0 * 1024))
    else -> "%.2f ГБ".format(bytes / (1024.0 * 1024 * 1024))
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
