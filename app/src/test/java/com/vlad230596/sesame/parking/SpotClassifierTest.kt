package com.vlad230596.sesame.parking

import com.vlad230596.sesame.data.entity.Barrier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotClassifierTest {

    private val baseLat = 55.75
    private val baseLon = 37.62

    /** Точка в [north] метрах к северу и [east] метрах к востоку от базы. */
    private fun at(north: Double, east: Double = 0.0): Pair<Double, Double> {
        val lat = baseLat + north / 111_195.0
        val lon = baseLon + east / (111_195.0 * Math.cos(Math.toRadians(baseLat)))
        return lat to lon
    }

    private fun anchor(name: String, north: Double, east: Double = 0.0): ParkingAnchor {
        val (lat, lon) = at(north, east)
        return ParkingAnchor(name, name, lat, lon)
    }

    // Ш1 — база, Ш2 — 77 м к северу, как во дворе (§4.1).
    private val sh1 = anchor("Ш1", 0.0)
    private val sh2 = anchor("Ш2", 77.0)
    private val anchors = listOf(sh1, sh2)

    private fun classify(north: Double, accuracy: Double, east: Double = 0.0, list: List<ParkingAnchor> = anchors) =
        at(north, east).let { (lat, lon) -> SpotClassifier.classify(lat, lon, accuracy, list) }

    @Test
    fun noAnchorsIsNone() {
        assertEquals(SpotResult.None, classify(0.0, 5.0, list = emptyList()))
    }

    @Test
    fun closeToOneBarrierIsNear() {
        val result = classify(north = 10.0, accuracy = 5.0)
        assertTrue(result is SpotResult.Near)
        assertEquals(sh1, (result as SpotResult.Near).anchor)
        assertEquals(10.0, result.distanceMeters, 0.2)
    }

    @Test
    fun nearTheOtherBarrier() {
        val result = classify(north = 70.0, accuracy = 5.0)
        assertEquals(sh2, (result as SpotResult.Near).anchor)
    }

    @Test
    fun middleIsBetweenWithNearestFirst() {
        // dA = 36, dB = 41: (41 − 36) / 2 = 2,5 < 5.
        val result = classify(north = 36.0, accuracy = 5.0)
        assertTrue(result is SpotResult.Between)
        result as SpotResult.Between
        assertEquals(sh1, result.a)
        assertEquals(sh2, result.b)
    }

    @Test
    fun betweenDependsOnAccuracy() {
        // dA = 25, dB = 52: (52 − 25) / 2 = 13,5.
        assertTrue(classify(north = 25.0, accuracy = 13.0) is SpotResult.Near)
        assertTrue(classify(north = 25.0, accuracy = 14.0) is SpotResult.Between)
    }

    @Test
    fun farFromEveryAnchorIsOutside() {
        val result = classify(north = -300.0, accuracy = 5.0)
        assertTrue(result is SpotResult.Outside)
        result as SpotResult.Outside
        assertEquals(sh1, result.nearest)
        assertEquals(300.0, result.distanceMeters, 0.5)
    }

    @Test
    fun outsideThresholdIsAParameter() {
        assertTrue(classify(north = -100.0, accuracy = 5.0) is SpotResult.Near)
        val (lat, lon) = at(-100.0)
        assertTrue(SpotClassifier.classify(lat, lon, 5.0, anchors, outsideMeters = 80.0) is SpotResult.Outside)
    }

    @Test
    fun singleAnchorIsNearOrOutside() {
        assertTrue(classify(north = 30.0, accuracy = 50.0, list = listOf(sh1)) is SpotResult.Near)
        assertTrue(classify(north = 200.0, accuracy = 50.0, list = listOf(sh1)) is SpotResult.Outside)
    }

    @Test
    fun picksNearestAmongMany() {
        val hyper = anchor("гипермаркет", 0.0, 200.0)
        val result = classify(north = 0.0, east = 190.0, accuracy = 5.0, list = anchors + hyper)
        assertEquals(hyper, (result as SpotResult.Near).anchor)
    }

    @Test
    fun distanceIsHaversine() {
        val (lat, lon) = at(77.0)
        assertEquals(77.0, SpotClassifier.distanceMeters(baseLat, baseLon, lat, lon), 0.1)
    }

    @Test
    fun barriersBecomeDefaultAnchors() {
        val barriers = listOf(
            Barrier(id = 7, label = "Шлагбаум B", phoneNumber = null, lat = 2.0, lon = 2.0, orderIndex = 1),
            Barrier(id = 5, label = "Шлагбаум A", phoneNumber = null, lat = 1.0, lon = 1.0, orderIndex = 0),
            Barrier(id = 9, label = "Задний", phoneNumber = null, lat = 3.0, lon = 3.0, orderIndex = 2),
            Barrier(id = 11, label = "Без координат", phoneNumber = null, lat = null, lon = null, orderIndex = 3),
        )
        val result = SpotClassifier.anchorsFromBarriers(barriers)
        assertEquals(listOf("Ш1", "Ш2", "Задний"), result.map { it.name })
        assertEquals(listOf("barrier-5", "barrier-7", "barrier-9"), result.map { it.id })
        assertEquals(1.0, result[0].lat, 0.0)
    }
}
