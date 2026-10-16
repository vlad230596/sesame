package com.vlad230596.sesame.parking

/**
 * Модель «где машина» (NEXT-notification-and-car.md, §4.1). Чистые данные без
 * Android: их пишет [com.vlad230596.sesame.prompt.PromptController] при
 * отключении машины, а читает экран карты.
 */

/** Откуда взялась точка парковки. */
enum class ParkingSource {
    /** Среднее фиксов стоянки по хвосту трека — основной путь у дома. */
    TAIL,

    /** Одна точная фиксация после отключения машины — когда хвоста нет. */
    FIX,

    /** Человек сам переставил пин на карте. */
    MANUAL,
}

/** Точка трека подъезда для карты. */
data class TrackPoint(val t: Long, val lat: Double, val lon: Double, val accuracyMeters: Float)

/**
 * Последняя парковка.
 *
 * @param at момент отключения машины.
 * @param accuracyMeters погрешность оценки (радиус круга на карте), а не сырая точность фикса.
 * @param bearingDeg курс машины в момент остановки, 0 — север, по часовой; `null` — неизвестен.
 * @param track хвост езды перед остановкой, старые точки первыми; пуст для [ParkingSource.FIX].
 * @param rawLat сырая фиксация после отключения — для сравнения с оценкой; `null`, если не пришла.
 */
data class ParkedCar(
    val at: Long,
    val lat: Double,
    val lon: Double,
    val accuracyMeters: Float,
    val source: ParkingSource,
    val bearingDeg: Float? = null,
    val track: List<TrackPoint> = emptyList(),
    val rawLat: Double? = null,
    val rawLon: Double? = null,
    val rawAccuracyMeters: Float? = null,
)

/** Именованная метка места на парковке: «Ш1», «центр», «гипермаркет». */
data class ParkingAnchor(val id: String, val name: String, val lat: Double, val lon: Double)
