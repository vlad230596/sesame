package com.vlad230596.sesame.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vlad230596.sesame.data.entity.Barrier
import com.vlad230596.sesame.parking.ParkedCar
import com.vlad230596.sesame.parking.ParkingSource
import com.vlad230596.sesame.parking.TrackPoint
import com.vlad230596.sesame.ui.car.CarScreenContent
import com.vlad230596.sesame.ui.car.CarScreenTags
import com.vlad230596.sesame.ui.car.CarUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CarScreenUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val now = 1_763_900_000_000L

    private fun state(collectorRunning: Boolean = true) = CarUiState(
        car = ParkedCar(
            at = now - 3 * 3_600_000L,
            lat = 55.75020,
            lon = 37.62000,
            accuracyMeters = 12f,
            source = ParkingSource.TAIL,
            bearingDeg = 40f,
            track = (0 until 10).map {
                TrackPoint(now - 3_700_000L + it * 10_000L, 55.7490 + it * 0.00012, 37.6190 + it * 0.0001, 8f)
            },
        ),
        barriers = listOf(
            Barrier(id = 1, label = "Шлагбаум A", phoneNumber = null, lat = 55.75000, lon = 37.62000, name = "Северный въезд"),
            Barrier(id = 2, label = "Шлагбаум B", phoneNumber = null, lat = 55.75060, lon = 37.62030, name = "Южный въезд"),
        ),
        now = now,
        loaded = true,
        collectorRunning = collectorRunning,
    )

    private fun show(stress: Stress = Stress.NORMAL, collectorRunning: Boolean = true) {
        rule.setContent {
            StressFrame(stress) {
                CarScreenContent(
                    state = state(collectorRunning),
                    locationPermitted = false,
                    onBack = {},
                    onStartCollector = {},
                    onCorrectPosition = { _, _ -> },
                    onAddAnchor = { _, _, _ -> },
                    onRenameAnchor = { _, _ -> },
                    onDeleteAnchor = {},
                )
            }
        }
    }

    /**
     * Регрессия: при щипке osmdroid масштабирует холст целиком, и без обрезки
     * тайлы ложились поверх ответа «у Ш1», времени и точности.
     */
    @Test
    fun mapDoesNotDrawOverSpotCardWhileZooming() {
        show()
        rule.waitForIdle()
        val card = rule.onNodeWithTag(CarScreenTags.SPOT_CARD)
        val before = card.captureToImage()

        rule.onNodeWithTag(CarScreenTags.MAP).performTouchInput {
            val c = center
            down(0, c - Offset(20f, 20f))
            down(1, c + Offset(20f, 20f))
            for (step in 1..12) {
                val d = 20f + step * 35f
                updatePointerTo(0, c - Offset(d, d))
                updatePointerTo(1, c + Offset(d, d))
                move()
            }
        }
        rule.waitForIdle()
        val during = card.captureToImage()
        rule.onNodeWithTag(CarScreenTags.MAP).performTouchInput {
            up(0)
            up(1)
        }

        val changed = differentPixelShare(before, during)
        assertTrue(
            "Карточка изменилась на ${"%.1f".format(changed * 100)} % пикселей во время зума",
            changed < 0.005,
        )
    }

    @Test
    fun textsDoNotOverlap() {
        show()
        rule.assertNoOverlappingTexts()
    }

    @Test
    fun textsDoNotOverlapOnNarrowScreenWithLargeFont() {
        show(Stress.NARROW_LARGE_FONT, collectorRunning = false)
        rule.assertNoOverlappingTexts()
    }

    private fun differentPixelShare(a: ImageBitmap, b: ImageBitmap): Double {
        val pa = a.toPixelMap()
        val pb = b.toPixelMap()
        val w = minOf(pa.width, pb.width)
        val h = minOf(pa.height, pb.height)
        var diff = 0
        for (y in 0 until h) for (x in 0 until w) if (pa[x, y] != pb[x, y]) diff++
        return diff.toDouble() / (w * h)
    }
}
