package com.vlad230596.sesame.parking

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Фикс локации для оценки точки парковки: из хвоста трека (зона у дома и
 * пассивная локация) или разовая точная фиксация после отключения машины.
 *
 * @param accuracyMeters заявленная точность фикса.
 * @param speedMps скорость по GNSS; `null` — фикс её не несёт.
 * @param bearingDeg курс, 0 — север, по часовой; `null` — фикс его не несёт.
 */
data class TailFix(
    val t: Long,
    val lat: Double,
    val lon: Double,
    val accuracyMeters: Float,
    val speedMps: Float? = null,
    val bearingDeg: Float? = null,
)

/**
 * Пороги оценки (NEXT-notification-and-car.md, §4.1). Дефолты — из ТЗ; на
 * следующей выгрузке их можно подстроить по ручным правкам пина.
 */
data class EstimatorParams(
    /** Сколько хвоста трека до отключения машины берётся в расчёт. */
    val windowMillis: Long = 5 * 60_000L,
    /** Фиксы хуже этой точности в хвост не попадают. */
    val maxAccuracyMeters: Float = 50f,
    /** Быстрее — ещё едем: последний такой фикс — конец езды. */
    val drivingSpeedMps: Float = 3f,
    /** Медленнее — стоим. */
    val stopSpeedMps: Float = 0.7f,
    /**
     * Фикс без скорости: стоим, если смещение от предыдущего меньше этой
     * скорости за прошедшее время. Порог выше [stopSpeedMps]: смещение между
     * фиксами шумит сильнее, чем доплеровская скорость.
     */
    val stopDisplacementSpeedMps: Float = 2f,
    /** Отошли от первого фикса стоянки дальше — человек пошёл пешком. */
    val walkAwayMeters: Double = 15.0,
    /** Погрешность оценки не меньше этого. */
    val minAccuracyMeters: Float = 5f,
    /** Курс по двум фиксам езды — только если они дальше этого друг от друга. */
    val bearingMinBaseMeters: Double = 10.0,
    /** Длина трека езды для карты. */
    val trackLengthMeters: Double = 400.0,
    /** Шаг прореживания трека. */
    val trackStepMeters: Double = 5.0,
)

/**
 * Результат оценки: сама парковка и служебные поля для журнала `CAR_PARKED`.
 *
 * @param tailFixes сколько фиксов хвоста прошло фильтр точности и окна.
 * @param stopToDisconnectMillis от первого фикса стоянки до отключения машины; `null` — стоянки не нашли.
 * @param rawToEstMeters расстояние от сырой фиксации до оценки; `null` — сырой фиксации нет.
 */
data class ParkingEstimate(
    val car: ParkedCar,
    val tailFixes: Int,
    val stopToDisconnectMillis: Long?,
    val rawToEstMeters: Double?,
)

/**
 * Точка парковки по хвосту трека (§4.1). Чистый Kotlin без Android: вход —
 * фиксы, накопленные контроллером, пока машина была подключена, и разовая
 * точная фиксация после отключения.
 *
 * Одна фиксация в момент отключения — слабое место: Bluetooth машины держится
 * ещё 30–40 с после остановки, фиксация приходит с задержкой, и человек может
 * уже идти. Поэтому:
 * - конец езды — последний фикс быстрее [EstimatorParams.drivingSpeedMps];
 * - стоянка — фиксы после него медленнее [EstimatorParams.stopSpeedMps] (без
 *   скорости — по смещению от соседнего фикса), пока смещение от первого фикса
 *   стоянки не превысит [EstimatorParams.walkAwayMeters];
 * - точка — среднее фиксов стоянки с весом 1/точность², погрешность — половина
 *   медианной точности, не меньше [EstimatorParams.minAccuracyMeters]: фикс
 *   заявляет точность хуже реальной;
 * - курс — по последнему фиксу езды с курсом, иначе по двум последним фиксам
 *   езды дальше [EstimatorParams.bearingMinBaseMeters] друг от друга;
 * - трек — последние ~[EstimatorParams.trackLengthMeters] езды с шагом не
 *   меньше [EstimatorParams.trackStepMeters] и точка стоянки в конце.
 *
 * Нет конца езды или стоянки (вдали от дома зона не включается) — точка по
 * сырой фиксации ([ParkingSource.FIX]), её точность и есть погрешность; нет и
 * её — `null`.
 */
class ParkingEstimator(private val params: EstimatorParams = EstimatorParams()) {

    fun estimate(tail: List<TailFix>, disconnectedAt: Long, raw: TailFix?): ParkingEstimate? {
        val fixes = tail
            .filter { it.accuracyMeters <= params.maxAccuracyMeters }
            .filter { it.t <= disconnectedAt && disconnectedAt - it.t <= params.windowMillis }
            .sortedBy { it.t }
        return fromTail(fixes, disconnectedAt, raw) ?: fromRaw(fixes.size, disconnectedAt, raw)
    }

    private fun fromTail(fixes: List<TailFix>, disconnectedAt: Long, raw: TailFix?): ParkingEstimate? {
        val driveEnd = fixes.indices.lastOrNull { isDriving(fixes, it) } ?: return null

        // Между концом езды и стоянкой бывают фиксы торможения — их пропускаем.
        val stopStart = (driveEnd + 1 until fixes.size).firstOrNull { isStopped(fixes, it) } ?: return null
        val first = fixes[stopStart]
        val stop = mutableListOf<TailFix>()
        for (i in stopStart until fixes.size) {
            val f = fixes[i]
            if (distanceMeters(first.lat, first.lon, f.lat, f.lon) > params.walkAwayMeters) break
            if (isStopped(fixes, i)) stop += f
        }

        val weights = stop.map { 1.0 / sq(max(it.accuracyMeters, 1f).toDouble()) }
        val sum = weights.sum()
        val lat = stop.indices.sumOf { stop[it].lat * weights[it] } / sum
        val lon = stop.indices.sumOf { stop[it].lon * weights[it] } / sum
        val accuracy = max(median(stop.map { it.accuracyMeters }) / 2f, params.minAccuracyMeters)

        val approach = fixes.subList(0, stopStart)
        val track = thinTrack(approach) + TrackPoint(first.t, lat, lon, accuracy)

        val car = ParkedCar(
            at = disconnectedAt,
            lat = lat,
            lon = lon,
            accuracyMeters = accuracy,
            source = ParkingSource.TAIL,
            bearingDeg = bearing(fixes, driveEnd),
            track = track,
            rawLat = raw?.lat,
            rawLon = raw?.lon,
            rawAccuracyMeters = raw?.accuracyMeters,
        )
        return ParkingEstimate(
            car = car,
            tailFixes = fixes.size,
            stopToDisconnectMillis = disconnectedAt - first.t,
            rawToEstMeters = raw?.let { distanceMeters(it.lat, it.lon, lat, lon) },
        )
    }

    private fun fromRaw(tailFixes: Int, disconnectedAt: Long, raw: TailFix?): ParkingEstimate? {
        raw ?: return null
        val car = ParkedCar(
            at = disconnectedAt,
            lat = raw.lat,
            lon = raw.lon,
            accuracyMeters = raw.accuracyMeters,
            source = ParkingSource.FIX,
            rawLat = raw.lat,
            rawLon = raw.lon,
            rawAccuracyMeters = raw.accuracyMeters,
        )
        return ParkingEstimate(car, tailFixes, stopToDisconnectMillis = null, rawToEstMeters = 0.0)
    }

    private fun isDriving(fixes: List<TailFix>, i: Int): Boolean {
        val speed = fixes[i].speedMps
        if (speed != null) return speed > params.drivingSpeedMps
        val derived = derivedSpeed(fixes, i) ?: return false
        return derived > params.drivingSpeedMps
    }

    private fun isStopped(fixes: List<TailFix>, i: Int): Boolean {
        val speed = fixes[i].speedMps
        if (speed != null) return speed < params.stopSpeedMps
        val derived = derivedSpeed(fixes, i) ?: return false
        return derived < params.stopDisplacementSpeedMps
    }

    /** Скорость по смещению от предыдущего фикса; `null` — сравнивать не с чем. */
    private fun derivedSpeed(fixes: List<TailFix>, i: Int): Double? {
        if (i == 0) return null
        val a = fixes[i - 1]
        val b = fixes[i]
        val dt = (b.t - a.t) / 1_000.0
        if (dt <= 0) return null
        return distanceMeters(a.lat, a.lon, b.lat, b.lon) / dt
    }

    private fun bearing(fixes: List<TailFix>, driveEnd: Int): Float? {
        (driveEnd downTo 0).firstOrNull { fixes[it].bearingDeg != null && isDriving(fixes, it) }
            ?.let { return fixes[it].bearingDeg }
        val last = fixes[driveEnd]
        val from = (driveEnd - 1 downTo 0).map { fixes[it] }.firstOrNull {
            distanceMeters(it.lat, it.lon, last.lat, last.lon) > params.bearingMinBaseMeters
        } ?: return null
        return initialBearing(from.lat, from.lon, last.lat, last.lon)
    }

    /** Последние ~[EstimatorParams.trackLengthMeters] пути, точки не ближе шага друг к другу. */
    private fun thinTrack(path: List<TailFix>): List<TrackPoint> {
        if (path.isEmpty()) return emptyList()
        var start = path.size - 1
        var length = 0.0
        while (start > 0 && length < params.trackLengthMeters) {
            length += distanceMeters(path[start - 1].lat, path[start - 1].lon, path[start].lat, path[start].lon)
            start--
        }
        val kept = mutableListOf<TailFix>()
        for (f in path.subList(start, path.size)) {
            val prev = kept.lastOrNull()
            if (prev == null || distanceMeters(prev.lat, prev.lon, f.lat, f.lon) >= params.trackStepMeters) kept += f
        }
        return kept.map { TrackPoint(it.t, it.lat, it.lon, it.accuracyMeters) }
    }

    companion object {
        fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dp = p2 - p1
            val dl = Math.toRadians(lon2 - lon1)
            val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * EARTH_RADIUS_METERS * asin(sqrt(a))
        }

        /** Начальный курс от первой точки ко второй, 0–360°, 0 — север. */
        fun initialBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dl = Math.toRadians(lon2 - lon1)
            val y = sin(dl) * cos(p2)
            val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
            val deg = Math.toDegrees(atan2(y, x))
            return ((deg + 360.0) % 360.0).toFloat()
        }

        private fun median(values: List<Float>): Float {
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
        }

        private fun sq(x: Double) = x * x

        private const val EARTH_RADIUS_METERS = 6_371_000.0
    }
}
