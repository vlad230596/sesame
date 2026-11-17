package com.vlad230596.sesame.habits

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * Привычка — повторяющаяся поездка: куда, в какие дни недели и в какое время.
 *
 * Пока задаётся только вручную (docs/RESEARCH-habits.md, этап «ручные правила»).
 * Автоматически привычки не ищутся: их подбирают по выгрузке журнала оффлайн и
 * заводят руками. Время — минуты от полуночи, окно не переходит через полночь.
 */
data class Habit(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val days: Set<DayOfWeek>,
    val fromMinutes: Int,
    val toMinutes: Int,
    val enabled: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("lat", lat)
        put("lon", lon)
        put("days", JSONArray(days.sorted().map { it.value }))
        put("from", fromMinutes)
        put("to", toMinutes)
        put("enabled", enabled)
    }

    companion object {
        fun fromJson(o: JSONObject): Habit? = runCatching {
            val daysJson = o.getJSONArray("days")
            Habit(
                id = o.getString("id"),
                name = o.getString("name"),
                lat = o.getDouble("lat"),
                lon = o.getDouble("lon"),
                days = (0 until daysJson.length()).map { DayOfWeek.of(daysJson.getInt(it)) }.toSet(),
                fromMinutes = o.getInt("from"),
                toMinutes = o.getInt("to"),
                enabled = o.optBoolean("enabled", true),
            )
        }.getOrNull()

        fun listToJson(habits: List<Habit>): String =
            JSONArray(habits.map { it.toJson() }).toString()

        fun listFromJson(raw: String?): List<Habit> {
            if (raw.isNullOrBlank()) return emptyList()
            val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let(::fromJson)
            }
        }
    }
}

/** Что показать на главной: привычка и насколько она «сейчас». */
data class HabitSuggestion(
    val habit: Habit,
    /** 0 — окно уже идёт; больше нуля — через столько минут начнётся. */
    val startsInMinutes: Int,
)

/**
 * Подбор привычек для главного экрана. Чистая функция — проверяется тестами.
 *
 * Показываем привычку, если сегодня её день и сейчас внутри окна, или окно
 * начнётся в ближайшие [leadMinutes]: собираться выезжать начинают заранее.
 * Сначала идущие, потом ближайшие.
 */
object HabitSuggester {
    const val DEFAULT_LEAD_MINUTES = 60

    fun suggest(
        habits: List<Habit>,
        now: LocalDateTime,
        leadMinutes: Int = DEFAULT_LEAD_MINUTES,
    ): List<HabitSuggestion> {
        val minute = now.hour * 60 + now.minute
        return habits
            .asSequence()
            .filter { it.enabled && now.dayOfWeek in it.days }
            .mapNotNull { habit ->
                when {
                    minute in habit.fromMinutes..habit.toMinutes -> HabitSuggestion(habit, 0)
                    habit.fromMinutes > minute && habit.fromMinutes - minute <= leadMinutes ->
                        HabitSuggestion(habit, habit.fromMinutes - minute)
                    else -> null
                }
            }
            .sortedWith(compareBy({ it.startsInMinutes }, { it.habit.fromMinutes }))
            .toList()
    }
}
