package com.vlad230596.sesame.habits

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime

class HabitSuggesterTest {

    private val pool = Habit(
        id = "pool",
        name = "Бассейн",
        lat = 55.7,
        lon = 37.6,
        days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
        fromMinutes = 18 * 60 + 45,
        toMinutes = 19 * 60 + 30,
    )

    // 2026-11-16 — понедельник, 2026-11-17 — вторник.
    private fun mon(h: Int, m: Int) = LocalDateTime.of(2026, 11, 16, h, m)

    @Test
    fun insideWindowIsNow() {
        val s = HabitSuggester.suggest(listOf(pool), mon(19, 0))
        assertEquals(listOf(HabitSuggestion(pool, 0)), s)
    }

    @Test
    fun withinLeadIsSoon() {
        val s = HabitSuggester.suggest(listOf(pool), mon(18, 15))
        assertEquals(30, s.single().startsInMinutes)
    }

    @Test
    fun tooEarlyLateWrongDayOrDisabledIsHidden() {
        assertTrue(HabitSuggester.suggest(listOf(pool), mon(17, 30)).isEmpty())
        assertTrue(HabitSuggester.suggest(listOf(pool), mon(19, 31)).isEmpty())
        assertTrue(HabitSuggester.suggest(listOf(pool), LocalDateTime.of(2026, 11, 17, 19, 0)).isEmpty())
        assertTrue(HabitSuggester.suggest(listOf(pool.copy(enabled = false)), mon(19, 0)).isEmpty())
    }

    @Test
    fun activeGoesBeforeUpcoming() {
        val later = pool.copy(id = "later", fromMinutes = 19 * 60 + 20, toMinutes = 20 * 60)
        val s = HabitSuggester.suggest(listOf(later, pool), mon(19, 0))
        assertEquals(listOf("pool", "later"), s.map { it.habit.id })
    }
}
