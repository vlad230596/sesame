package com.vlad230596.sesame.parking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Точка парковки по хвосту трека (NEXT-notification-and-car.md, §4.1):
 * обычная стоянка, человек пошёл пешком до отключения Bluetooth, фиксы без
 * скорости, нет хвоста — одна фиксация, нет ничего, курс.
 */
class ParkingEstimatorTest {

    private val estimator = ParkingEstimator()

    /** Фикс в [north] м к северу и [east] м к востоку от точки отсчёта, в момент [sec] секунд. */
    private fun at(
        sec: Int,
        north: Double,
        east: Double = 0.0,
        accuracy: Float = 8f,
        speed: Float? = 0f,
        bearing: Float? = null,
    ) = TailFix(
        t = T0 + sec * 1_000L,
        lat = BASE_LAT + north / METERS_PER_DEG_LAT,
        lon = BASE_LON + east / METERS_PER_DEG_LON,
        accuracyMeters = accuracy,
        speedMps = speed,
        bearingDeg = bearing,
    )

    /** Подъезд с юга на север: 10 м/с раз в секунду с −[fromMeters] до 0. */
    private fun driveNorth(fromMeters: Int = 500, withSpeed: Boolean = true, withBearing: Boolean = true) =
        (0..fromMeters / 10).map { i ->
            at(
                sec = i,
                north = -fromMeters + i * 10.0,
                speed = if (withSpeed) 10f else null,
                bearing = if (withBearing) 0f else null,
            )
        }

    /** Стоянка у 0 с дрожанием GNSS около метра. */
    private fun stopAt(fromSec: Int, seconds: Int, withSpeed: Boolean = true) = (1..seconds).map { i ->
        val jitter = listOf(0.0, 0.6, -0.4, 0.8, -0.8, 0.2)[i % 6]
        at(sec = fromSec + i, north = jitter, east = -jitter / 2, speed = if (withSpeed) 0.2f else null)
    }

    private fun north(lat: Double) = (lat - BASE_LAT) * METERS_PER_DEG_LAT
    private fun east(lon: Double) = (lon - BASE_LON) * METERS_PER_DEG_LON

    @Test
    fun `обычная стоянка — среднее фиксов стоянки, трек и курс`() {
        val drive = driveNorth()
        val endSec = drive.size - 1
        val tail = drive + stopAt(endSec, 40)
        val disconnectedAt = T0 + (endSec + 40) * 1_000L
        val raw = at(endSec + 45, north = 12.0, accuracy = 16f, speed = null)

        val est = requireNotNull(estimator.estimate(tail, disconnectedAt, raw))
        val car = est.car
        assertEquals(ParkingSource.TAIL, car.source)
        assertEquals(0.0, north(car.lat), 1.0)
        assertEquals(0.0, east(car.lon), 1.0)
        // Половина медианной точности (8 м) — 4 м, но не меньше 5.
        assertEquals(5f, car.accuracyMeters, 0.01f)
        assertEquals(0f, car.bearingDeg!!, 0.01f)
        assertEquals(disconnectedAt, car.at)
        assertEquals(40 * 1_000L - 1_000L, est.stopToDisconnectMillis)
        assertEquals(tail.size, est.tailFixes)
        assertEquals(12.0, est.rawToEstMeters!!, 1.5)
        assertEquals(raw.lat, car.rawLat!!, 1e-9)
        assertEquals(16f, car.rawAccuracyMeters!!, 0.01f)

        // Трек — ~400 м езды до остановки с шагом ≥ 5 м и точка стоянки в конце.
        val track = car.track
        assertEquals(car.lat, track.last().lat, 1e-9)
        val driveTrack = track.dropLast(1)
        assertTrue(north(driveTrack.first().lat) in -420.0..-390.0)
        assertEquals(0.0, north(driveTrack.last().lat), 0.5)
        driveTrack.zipWithNext().forEach { (a, b) ->
            assertTrue(ParkingEstimator.distanceMeters(a.lat, a.lon, b.lat, b.lon) >= 5.0)
        }
    }

    @Test
    fun `вес фикса — 1 на точность в квадрате, погрешность — половина медианы`() {
        val drive = driveNorth(fromMeters = 100)
        val endSec = drive.size - 1
        val stop = listOf(
            at(endSec + 1, north = 0.0, accuracy = 10f),
            at(endSec + 2, north = 10.0, accuracy = 40f),
            at(endSec + 3, north = 0.0, accuracy = 30f),
        )
        val est = requireNotNull(estimator.estimate(drive + stop, T0 + (endSec + 5) * 1_000L, raw = null))
        // Веса 1/100, 1/1600, 1/900: точка почти там, где точный фикс.
        val w = listOf(1.0 / 100, 1.0 / 1600, 1.0 / 900)
        assertEquals(10.0 * w[1] / w.sum(), north(est.car.lat), 0.05)
        assertEquals(15f, est.car.accuracyMeters, 0.01f)
        assertNull(est.rawToEstMeters)
        assertNull(est.car.rawLat)
    }

    @Test
    fun `человек пошёл пешком до отключения Bluetooth — точка там, где встала машина`() {
        val drive = driveNorth()
        val endSec = drive.size - 1
        val stop = stopAt(endSec, 15)
        // Идём на север 1,4 м/с; GNSS иногда занижает скорость, но дальше 15 м — уже не стоянка.
        val walk = (1..30).map { i ->
            at(sec = endSec + 15 + i, north = 2.0 + i * 1.4, speed = if (i % 12 == 0) 0.5f else 1.4f)
        }
        val disconnectedAt = T0 + (endSec + 45) * 1_000L
        val raw = at(endSec + 50, north = 45.0, accuracy = 12f, speed = null)

        val est = requireNotNull(estimator.estimate(drive + stop + walk, disconnectedAt, raw))
        assertEquals(ParkingSource.TAIL, est.car.source)
        assertEquals(0.0, north(est.car.lat), 1.5)
        assertEquals(45.0, est.rawToEstMeters!!, 2.0)
        assertEquals(44 * 1_000L, est.stopToDisconnectMillis)
    }

    @Test
    fun `фиксы без скорости — езда и стоянка по смещению между фиксами`() {
        val drive = driveNorth(withSpeed = false, withBearing = false)
        val endSec = drive.size - 1
        val tail = drive + stopAt(endSec, 30, withSpeed = false)
        val est = requireNotNull(estimator.estimate(tail, T0 + (endSec + 30) * 1_000L, raw = null))
        assertEquals(ParkingSource.TAIL, est.car.source)
        assertEquals(0.0, north(est.car.lat), 1.5)
        // Курса в фиксах нет — по двум последним фиксам езды: на север.
        val bearing = est.car.bearingDeg!!
        assertTrue("курс $bearing", bearing < 2f || bearing > 358f)
    }

    @Test
    fun `курс — по последнему фиксу езды с курсом, иначе по двум фиксам дальше 10 м`() {
        // Едем на восток; фиксы несут курс 90°.
        val east = (0..30).map { at(sec = it, north = 0.0, east = -300.0 + it * 10.0, speed = 10f, bearing = 88f + (it % 3)) }
        val stop = (1..20).map { at(sec = 30 + it, north = 0.0, east = 0.0, speed = 0f) }
        val withBearing = requireNotNull(estimator.estimate(east + stop, T0 + 50_000L, raw = null))
        assertEquals(east.last().bearingDeg!!, withBearing.car.bearingDeg!!, 0.01f)

        // Курса нет — по смещению между двумя последними фиксами езды.
        val noBearing = east.map { it.copy(bearingDeg = null) }
        val computed = requireNotNull(estimator.estimate(noBearing + stop, T0 + 50_000L, raw = null))
        assertEquals(90f, computed.car.bearingDeg!!, 1f)

        // Езда из одного фикса — опереться не на что: курс неизвестен.
        val single = listOf(at(sec = 0, north = 0.0, speed = 5f)) + stop
        assertNull(requireNotNull(estimator.estimate(single, T0 + 50_000L, raw = null)).car.bearingDeg)
    }

    @Test
    fun `нет хвоста — одна точная фиксация, её точность и есть погрешность`() {
        val raw = at(60, north = 3_000.0, accuracy = 17f, speed = null)
        val est = requireNotNull(estimator.estimate(emptyList(), T0 + 30_000L, raw))
        assertEquals(ParkingSource.FIX, est.car.source)
        assertEquals(raw.lat, est.car.lat, 1e-9)
        assertEquals(17f, est.car.accuracyMeters, 0.01f)
        assertTrue(est.car.track.isEmpty())
        assertNull(est.car.bearingDeg)
        assertNull(est.stopToDisconnectMillis)
        assertEquals(0, est.tailFixes)
    }

    @Test
    fun `хвост без конца езды, без стоянки или грубый и старый — тоже одна фиксация`() {
        val raw = at(400, north = 5.0, accuracy = 20f, speed = null)
        val disconnectedAt = T0 + 400_000L
        // Только стоянка: пассивные фиксы у дома, езды не видно.
        val onlyStop = (300..390 step 10).map { at(sec = it, north = 0.0, speed = 0f) }
        assertEquals(ParkingSource.FIX, estimator.estimate(onlyStop, disconnectedAt, raw)!!.car.source)
        // Только езда: машину отключили на ходу или стоянку не успели поймать.
        val onlyDrive = (380..399).map { at(sec = it, north = it * 10.0, speed = 10f) }
        assertEquals(ParkingSource.FIX, estimator.estimate(onlyDrive, disconnectedAt, raw)!!.car.source)
        // Хорошая стоянка, но точность фиксов хуже 50 м или старше 5 минут.
        val coarse = (driveNorth() + stopAt(50, 30)).map { it.copy(accuracyMeters = 80f) }
        val coarseEst = estimator.estimate(coarse, T0 + 80_000L, raw)!!
        assertEquals(ParkingSource.FIX, coarseEst.car.source)
        assertEquals(0, coarseEst.tailFixes)
        val old = driveNorth() + stopAt(50, 30)
        assertEquals(ParkingSource.FIX, estimator.estimate(old, T0 + 80_000L + 6 * 60_000L, raw)!!.car.source)
    }

    @Test
    fun `нет ни хвоста, ни фиксации — точки нет`() {
        assertNull(estimator.estimate(emptyList(), T0, raw = null))
        val onlyDrive = driveNorth()
        assertNull(estimator.estimate(onlyDrive, T0 + 60_000L, raw = null))
    }

    private companion object {
        const val T0 = 1_700_000_000_000L
        const val BASE_LAT = 55.0
        const val BASE_LON = 37.0
        const val METERS_PER_DEG_LAT = 111_195.0
        val METERS_PER_DEG_LON = 111_195.0 * Math.cos(Math.toRadians(BASE_LAT))
    }
}
