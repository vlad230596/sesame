package com.vlad230596.sesame.parking

import com.vlad230596.sesame.data.BarrierRepository
import com.vlad230596.sesame.data.entity.Barrier
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Где на парковке стоит машина — словами (NEXT-notification-and-car.md, §4.1).
 *
 * Ответ строится от меток мест, а не от фиксированной сетки: человек ставит
 * «Ш1», «центр», «Ш2», «гипермаркет» там, где ему удобно, и деление само
 * подстраивается под геометрию двора.
 */
sealed interface SpotResult {
    /** Ближе всего к [anchor], и вторая метка заметно дальше погрешности. */
    data class Near(val anchor: ParkingAnchor, val distanceMeters: Double) : SpotResult

    /** До [a] и [b] почти одинаково — в пределах погрешности; [a] — ближняя. */
    data class Between(val a: ParkingAnchor, val b: ParkingAnchor) : SpotResult

    /** Дальше порога от любой метки; [nearest] — ближайшая. */
    data class Outside(val nearest: ParkingAnchor, val distanceMeters: Double) : SpotResult

    /** Меток нет — отвечать не от чего. */
    data object None : SpotResult
}

object SpotClassifier {

    /** Префикс id метки, взятой из шлагбаума: у своих меток id — UUID. */
    const val BARRIER_ANCHOR_PREFIX = "barrier-"

    /** Дальше этого от любой метки — «вне отмеченных мест». */
    const val DEFAULT_OUTSIDE_METERS = 120.0

    /**
     * Правило:
     * - ближайшая метка A (dA), вторая по близости B (dB);
     * - dA > [outsideMeters] — [SpotResult.Outside];
     * - (dB − dA) / 2 < погрешность — [SpotResult.Between]: середина между A и B
     *   ближе к машине, чем она сама знает своё место;
     * - иначе [SpotResult.Near].
     *
     * @param accuracyMeters погрешность оценки точки, не сырая точность фикса.
     */
    fun classify(
        lat: Double,
        lon: Double,
        accuracyMeters: Double,
        anchors: List<ParkingAnchor>,
        outsideMeters: Double = DEFAULT_OUTSIDE_METERS,
    ): SpotResult {
        if (anchors.isEmpty()) return SpotResult.None
        val sorted = anchors
            .map { it to distanceMeters(lat, lon, it.lat, it.lon) }
            .sortedBy { it.second }
        val (a, dA) = sorted[0]
        if (dA > outsideMeters) return SpotResult.Outside(a, dA)
        val second = sorted.getOrNull(1) ?: return SpotResult.Near(a, dA)
        val (b, dB) = second
        return if ((dB - dA) / 2 < accuracyMeters) SpotResult.Between(a, b) else SpotResult.Near(a, dA)
    }

    /**
     * Метки по умолчанию, пока человек не поставил свои, — шлагбаумы с
     * координатами. Имя — подпись шлагбаума, а заводские «Шлагбаум A/B»
     * сокращаются до «Ш1/Ш2»: «у Шлагбаум A» не читается, а карточка ответа
     * короткая.
     */
    fun anchorsFromBarriers(barriers: List<Barrier>): List<ParkingAnchor> =
        barriers
            .sortedBy { it.orderIndex }
            .mapIndexedNotNull { index, barrier ->
                val lat = barrier.lat ?: return@mapIndexedNotNull null
                val lon = barrier.lon ?: return@mapIndexedNotNull null
                val name = if (barrier.label.isBlank() || barrier.label in BarrierRepository.DEFAULT_LABELS) {
                    "Ш${index + 1}"
                } else {
                    barrier.label
                }
                ParkingAnchor(id = "$BARRIER_ANCHOR_PREFIX${barrier.id}", name = name, lat = lat, lon = lon)
            }

    /** Расстояние по сфере (гаверсинус), метры. На сотнях метров точнее сантиметра. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val h = sin(dp / 2).let { it * it } + cos(p1) * cos(p2) * sin(dl / 2).let { it * it }
        return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(h)))
    }

    private const val EARTH_RADIUS_METERS = 6_371_008.8
}
