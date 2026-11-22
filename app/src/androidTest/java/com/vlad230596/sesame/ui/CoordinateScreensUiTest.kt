package com.vlad230596.sesame.ui

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.percentOffset
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vlad230596.sesame.data.entity.Barrier
import com.vlad230596.sesame.data.prefs.SesameSettings
import com.vlad230596.sesame.habits.Habit
import com.vlad230596.sesame.ui.common.MapPickerTags
import com.vlad230596.sesame.ui.habits.HabitEditContent
import com.vlad230596.sesame.ui.settings.BarrierContent
import com.vlad230596.sesame.ui.settings.HomePointContent
import com.vlad230596.sesame.ui.theme.SesameBarrierAccents
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek

/** Экраны с координатой: шлагбаум, дом, место привычки — и выбор точки на карте. */
@RunWith(AndroidJUnit4::class)
class CoordinateScreensUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val barrier = Barrier(
        id = 1,
        label = "Шлагбаум A",
        phoneNumber = "+7 900 000-00-14",
        lat = 55.75124,
        lon = 37.61841,
        name = "Северный въезд со стороны парковки",
    )

    private fun barrierScreen(stress: Stress, onSave: (Barrier) -> Unit = {}) = rule.setContent {
        StressFrame(stress) {
            BarrierContent(
                barrier = barrier,
                accent = SesameBarrierAccents.barrier(0),
                position = 0,
                onPickCurrent = {},
                onSave = onSave,
                onTestCall = {},
            )
        }
    }

    @Test
    fun barrierTextsDoNotOverlap() {
        barrierScreen(Stress.NORMAL)
        rule.assertNoOverlappingTexts()
    }

    @Test
    fun barrierTextsDoNotOverlapOnNarrowScreenWithLargeFont() {
        barrierScreen(Stress.NARROW_LARGE_FONT)
        rule.assertNoOverlappingTexts()
    }

    @Test
    fun homePointTextsDoNotOverlapOnNarrowScreenWithLargeFont() {
        rule.setContent {
            StressFrame(Stress.NARROW_LARGE_FONT) {
                HomePointContent(
                    settings = SesameSettings(homeLat = 55.75124, homeLon = 37.61841),
                    onPickCurrent = {},
                    onSave = { _, _, _ -> },
                )
            }
        }
        rule.assertNoOverlappingTexts()
    }

    @Test
    fun habitTextsDoNotOverlapOnNarrowScreenWithLargeFont() {
        rule.setContent {
            StressFrame(Stress.NARROW_LARGE_FONT) {
                HabitEditContent(
                    habit = Habit(
                        id = "h1",
                        name = "Бассейн",
                        lat = 55.7,
                        lon = 37.5,
                        days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
                        fromMinutes = 18 * 60 + 30,
                        toMinutes = 19 * 60 + 30,
                    ),
                    onPickCurrent = {},
                    onSave = {},
                    onDelete = {},
                )
            }
        }
        rule.assertNoOverlappingTexts()
    }

    /** Задача TaskRadar: точку шлагбаума ставят тапом по карте. */
    @Test
    fun barrierPointIsPickedOnMap() {
        var saved: Barrier? = null
        barrierScreen(Stress.NORMAL) { saved = it }

        rule.onNodeWithText("На карте").performScrollTo().performClick()
        rule.onNodeWithTag(MapPickerTags.COORDINATE).assertTextContains("55.75124", substring = true)
        rule.assertNoOverlappingTexts()

        // Тап выше и правее центра: точка должна сдвинуться на северо-восток.
        rule.onNodeWithTag(MapPickerTags.MAP).performTouchInput { click(percentOffset(0.8f, 0.3f)) }
        // Тот же текст есть и на экране под диалогом, поэтому смотрим только на строку диалога.
        rule.waitUntil(5_000) {
            val shown = rule.onNodeWithTag(MapPickerTags.COORDINATE).fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text }
            "55.75124" !in shown
        }
        rule.onNodeWithText("Выбрать").performClick()
        rule.onNodeWithText("Сохранить").performScrollTo().performClick()

        val point = saved
        assertTrue("Сохранения не было", point != null)
        assertTrue("Широта не изменилась: ${point!!.lat}", point.lat!! > 55.75124)
        assertTrue("Долгота не изменилась: ${point.lon}", point.lon!! > 37.61841)
    }
}
