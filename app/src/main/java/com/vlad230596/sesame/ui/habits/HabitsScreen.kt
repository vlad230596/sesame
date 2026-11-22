package com.vlad230596.sesame.ui.habits

import com.vlad230596.sesame.ui.common.MapPickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vlad230596.sesame.habits.Habit
import com.vlad230596.sesame.habits.HabitRepository
import com.vlad230596.sesame.habits.HabitSuggestion
import com.vlad230596.sesame.ui.common.FilterPill
import com.vlad230596.sesame.ui.common.FootNote
import com.vlad230596.sesame.ui.common.GroupDivider
import com.vlad230596.sesame.ui.common.GroupRow
import com.vlad230596.sesame.ui.common.NavGroupRow
import com.vlad230596.sesame.ui.common.SesameButton
import com.vlad230596.sesame.ui.common.SesameCard
import com.vlad230596.sesame.ui.common.SesameField
import com.vlad230596.sesame.ui.common.SettingsGroup
import com.vlad230596.sesame.ui.common.Tones
import com.vlad230596.sesame.ui.theme.Dimens
import com.vlad230596.sesame.ui.theme.Palette
import com.vlad230596.sesame.ui.theme.SesameText
import java.time.DayOfWeek
import java.util.Locale

private val DAY_NAMES = listOf(
    DayOfWeek.MONDAY to "Пн",
    DayOfWeek.TUESDAY to "Вт",
    DayOfWeek.WEDNESDAY to "Ср",
    DayOfWeek.THURSDAY to "Чт",
    DayOfWeek.FRIDAY to "Пт",
    DayOfWeek.SATURDAY to "Сб",
    DayOfWeek.SUNDAY to "Вс",
)

internal fun formatMinutes(minutes: Int): String =
    String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60)

/** «18:45» → 1125; «7:5» не принимаем — только ЧЧ:ММ или Ч:ММ. */
internal fun parseMinutes(text: String): Int? {
    val match = Regex("""^\s*(\d{1,2})[:.](\d{2})\s*$""").find(text) ?: return null
    val h = match.groupValues[1].toInt()
    val m = match.groupValues[2].toInt()
    if (h > 23 || m > 59) return null
    return h * 60 + m
}

/** «55.75124, 37.61841» — так координату отдают Яндекс Карты по «Скопировать». */
internal fun parseCoordinates(text: String): Pair<Double, Double>? {
    val parts = text.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.size != 2) return null
    val lat = parts[0].toDoubleOrNull() ?: return null
    val lon = parts[1].toDoubleOrNull() ?: return null
    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
    return lat to lon
}

private fun daysLabel(days: Set<DayOfWeek>): String =
    DAY_NAMES.filter { it.first in days }.joinToString(" ") { it.second }.ifEmpty { "без дней" }

/** Список привычек в настройках. */
@Composable
internal fun HabitsListContent(
    habits: List<Habit>,
    onOpen: (Habit?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = Dimens.SpaceXl),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM),
    ) {
        SettingsGroup {
            if (habits.isEmpty()) {
                GroupRow {
                    Text("Привычек пока нет", style = SesameText.Caption, color = Palette.TextMuted)
                }
            }
            habits.forEachIndexed { index, habit ->
                if (index > 0) GroupDivider()
                NavGroupRow(
                    title = habit.name,
                    value = "${daysLabel(habit.days)} · ${formatMinutes(habit.fromMinutes)}–${formatMinutes(habit.toMinutes)}" +
                        if (habit.enabled) "" else " · выкл",
                    onClick = { onOpen(habit) },
                )
            }
        }
        SesameButton(text = "Добавить привычку", modifier = Modifier.fillMaxWidth(), onClick = { onOpen(null) })
        FootNote(
            "В свои дни и часы привычка появляется на главной кнопкой «Маршрут» — " +
                "она открывает Яндекс Карты с маршрутом от текущего места. Сама по себе " +
                "ничего не открывает.",
        )
    }
}

/** Редактор одной привычки; [habit] == null — новая. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HabitEditContent(
    habit: Habit?,
    onPickCurrent: ((Double?, Double?) -> Unit) -> Unit,
    onSave: (Habit) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember(habit?.id) { mutableStateOf(habit?.name.orEmpty()) }
    var coords by remember(habit?.id) {
        mutableStateOf(habit?.let { String.format(Locale.US, "%.6f, %.6f", it.lat, it.lon) }.orEmpty())
    }
    var from by remember(habit?.id) { mutableStateOf(habit?.let { formatMinutes(it.fromMinutes) } ?: "18:30") }
    var to by remember(habit?.id) { mutableStateOf(habit?.let { formatMinutes(it.toMinutes) } ?: "19:30") }
    var days by remember(habit?.id) { mutableStateOf(habit?.days ?: emptySet()) }
    var enabled by remember(habit?.id) { mutableStateOf(habit?.enabled ?: true) }
    var pickingOnMap by remember { mutableStateOf(false) }

    val point = parseCoordinates(coords)
    val fromMinutes = parseMinutes(from)
    val toMinutes = parseMinutes(to)
    val valid = name.isNotBlank() && point != null && fromMinutes != null && toMinutes != null &&
        fromMinutes <= toMinutes && days.isNotEmpty()

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = Dimens.SpaceXl),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM),
    ) {
        SesameField(value = name, onValueChange = { name = it }, label = "Куда", placeholder = "Бассейн")
        SesameField(
            value = coords,
            onValueChange = { coords = it },
            label = "Координата",
            placeholder = "55.751240, 37.618410",
            hint = if (coords.isNotEmpty() && point == null) {
                "Не похоже на «широта, долгота»"
            } else {
                "Скопируйте точку в Яндекс Картах или возьмите текущее место"
            },
            mono = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
            SesameButton(
                text = "Текущее место",
                modifier = Modifier.weight(1f),
                container = Palette.SurfaceHigh,
                content = Tones.TextSoft,
                border = Palette.BorderStrong,
                onClick = {
                    onPickCurrent { lat, lon ->
                        if (lat != null && lon != null) coords = String.format(Locale.US, "%.6f, %.6f", lat, lon)
                    }
                },
            )
            SesameButton(
                text = "На карте",
                modifier = Modifier.weight(1f),
                container = Palette.SurfaceHigh,
                content = Tones.TextSoft,
                border = Palette.BorderStrong,
                onClick = { pickingOnMap = true },
            )
        }
        if (pickingOnMap) {
            MapPickerDialog(
                title = name.ifBlank { "Место привычки" },
                initial = point,
                onPick = { lat, lon ->
                    coords = String.format(Locale.US, "%.6f, %.6f", lat, lon)
                    pickingOnMap = false
                },
                onDismiss = { pickingOnMap = false },
            )
        }

        Text("Дни", style = SesameText.Caption.copy(fontWeight = FontWeight.SemiBold), color = Palette.TextMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DAY_NAMES.forEach { (day, label) ->
                FilterPill(
                    text = label,
                    selected = day in days,
                    onClick = { days = if (day in days) days - day else days + day },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
            SesameField(
                value = from,
                onValueChange = { from = it },
                label = "С",
                mono = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            SesameField(
                value = to,
                onValueChange = { to = it },
                label = "До",
                mono = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterPill(text = "Включена", selected = enabled, onClick = { enabled = true })
            FilterPill(text = "Выключена", selected = !enabled, onClick = { enabled = false })
        }

        SesameButton(
            text = "Сохранить",
            modifier = Modifier.fillMaxWidth(),
            enabled = valid,
            onClick = {
                val (lat, lon) = point ?: return@SesameButton
                onSave(
                    Habit(
                        id = habit?.id ?: HabitRepository.newId(),
                        name = name.trim(),
                        lat = lat,
                        lon = lon,
                        days = days,
                        fromMinutes = fromMinutes ?: return@SesameButton,
                        toMinutes = toMinutes ?: return@SesameButton,
                        enabled = enabled,
                    ),
                )
            },
        )
        if (habit != null) {
            SesameButton(
                text = "Удалить",
                modifier = Modifier.fillMaxWidth(),
                container = Palette.SurfaceHigh,
                content = Tones.TextSoft,
                border = Palette.BorderStrong,
                onClick = { onDelete(habit.id) },
            )
        }
        FootNote("Окно — когда обычно выезжаете. Подсказка появляется за час до начала и держится до конца окна.")
    }
}

/** Карточка на главной: подходящие сейчас привычки с кнопкой маршрута. */
@Composable
fun HabitSuggestionsCard(
    suggestions: List<HabitSuggestion>,
    onRoute: (HabitSuggestion) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (suggestions.isEmpty()) return
    SesameCard(modifier = modifier) {
        suggestions.forEach { suggestion ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.StackGap),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        suggestion.habit.name,
                        style = SesameText.RowTitle,
                        color = Palette.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (suggestion.startsInMinutes == 0) {
                            "обычно сейчас · до ${formatMinutes(suggestion.habit.toMinutes)}"
                        } else {
                            "через ${suggestion.startsInMinutes} мин · с ${formatMinutes(suggestion.habit.fromMinutes)}"
                        },
                        style = SesameText.Caption,
                        color = Palette.TextMuted,
                    )
                }
                SesameButton(
                    text = "Маршрут",
                    container = Palette.Teal,
                    content = Palette.OnTeal,
                    height = 44.dp,
                    corner = 14.dp,
                    onClick = { onRoute(suggestion) },
                )
            }
        }
    }
}
