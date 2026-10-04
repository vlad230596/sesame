package com.vlad230596.sesame.prompt

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Решение «пора показать уведомление со шлагбаумами» (NEXT-notification-and-car.md, §3–4).
 *
 * Чистая логика без Android: те же правила, что выиграли офлайн-реплей
 * (`analysis/notify_replay/`), чтобы поведение на телефоне и в реплее можно было
 * сравнивать один к одному. Всё Android-окружение — в [PromptController].
 *
 * Правила:
 * - **выезд** — подключилась машина, телефон у шлагбаумов, и машина не известна
 *   как стоящая за двором;
 * - **въезд** — машина подключена, расстояние до шлагбаума уменьшается и стало
 *   меньше порога; машина не известна как стоящая во дворе. Порог зависит от
 *   источника фикса: пассивная локация приходит раз в ~2 минуты, поэтому ей
 *   нужен запас в 800 м, точная в зоне у дома — раз в секунду, ей хватает 150 м;
 * - **зона у дома** — машина подключена и до шлагбаума меньше ~2 км: включается
 *   точная локация, при выходе из зоны или отключении машины — выключается;
 * - **где машина** — каждый проезд за рулём переворачивает «во дворе / за двором».
 *
 * Не потокобезопасен: вызывающий сериализует обращения сам.
 */
class PromptEngine(
    private val params: PromptParams,
    barriers: List<BarrierPoint>,
    carPlace: CarPlace,
) {

    var barriers: List<BarrierPoint> = barriers
        set(value) {
            field = value
            if (value.isEmpty()) lastFix = null
        }

    var carPlace: CarPlace = carPlace
        private set

    var carConnected: Boolean = false
        private set

    val zoneActive: Boolean get() = zoneSince != null

    val promptVisible: Boolean get() = episode != null

    private var lastFix: Fix? = null
    private var lastPreciseFixAt: Long? = null
    private var episode: Episode? = null
    private var lastUseAt: Long? = null
    private var dismissedAt: Long? = null
    private var zoneSince: Long? = null

    /** До какого момента ждём свежий фикс, чтобы решить про выезд. */
    private var departureCheckUntil: Long? = null

    /** Ближайший момент, когда что-то может смениться без новых событий. */
    val nextDeadline: Long?
        get() = listOfNotNull(
            episode?.end,
            zoneSince?.let { it + params.zoneMaxMillis },
            departureCheckUntil,
        ).minOrNull()

    // --- входы ---------------------------------------------------------------------

    fun onCarConnected(t: Long): List<PromptAction> = buildList {
        carConnected = true
        if (barriers.isEmpty()) return@buildList
        val fix = lastFix
        if (fix != null && t - fix.t <= params.fixMaxAgeMillis) {
            addAll(checkDeparture(t, fix))
            addAll(updateZone(t, fix))
        } else {
            // Свежего фикса нет — дома в покое пассивная локация молчит часами.
            // Просим одну точную фиксацию и решаем по ней.
            departureCheckUntil = t + params.departureFixWaitMillis
            add(PromptAction.RequestFix)
        }
    }

    fun onCarDisconnected(t: Long): List<PromptAction> = buildList {
        carConnected = false
        departureCheckUntil = null
        if (zoneSince != null) {
            zoneSince = null
            add(PromptAction.ZoneOff("car_disconnected"))
        }
        // Неиспользованное уведомление после парковки бесполезно: машина встала
        // рядом с двором, но не внутри.
        val ep = episode
        if (ep != null && !ep.used) {
            episode = null
            add(PromptAction.Hide("car_disconnected", used = false))
        }
        add(PromptAction.CarParked(carPlace))
    }

    fun onLocation(t: Long, lat: Double, lon: Double, accuracyMeters: Float, precise: Boolean): List<PromptAction> =
        buildList {
            if (barriers.isEmpty()) return@buildList
            val dist = distanceToNearest(lat, lon)
            val previous = lastFix
            val fix = Fix(t, dist, accuracyMeters)
            lastFix = fix
            if (precise && accuracyMeters <= params.preciseAccuracyMeters) lastPreciseFixAt = t

            if (departureCheckUntil != null && carConnected) {
                departureCheckUntil = null
                addAll(checkDeparture(t, fix))
            }
            addAll(updateZone(t, fix))
            if (!carConnected) return@buildList

            val radius = if (zoneActive && lastPreciseFixAt?.let { t - it <= params.preciseFreshMillis } == true) {
                params.arriveRadiusPreciseMeters
            } else {
                params.arriveRadiusPassiveMeters
            }
            val near = dist - min(accuracyMeters.toDouble(), MAX_ACCURACY_CREDIT_METERS) <= radius
            val ep = episode
            if (ep != null && near && !ep.used) {
                // Стоим у шлагбаума и ждём — уведомление не должно погаснуть.
                ep.end = maxOf(ep.end, t + params.ttlMillis)
                return@buildList
            }
            val approaching = previous != null && dist < previous.distance
            if (near && approaching && carPlace != CarPlace.INSIDE) {
                addAll(show(t, Trigger.ARRIVE, dist))
            }
        }

    /** Звонок на шлагбаум ушёл — из уведомления, виджета или приложения. */
    fun onBarrierUsed(t: Long): List<PromptAction> = buildList {
        val ep = episode
        if (ep != null) {
            ep.used = true
            ep.end = t + params.afterUseMillis
        }
        lastUseAt = t
        if (zoneSince != null) {
            // Шлагбаум открыт — точная локация своё отработала.
            zoneSince = null
            add(PromptAction.ZoneOff("used"))
        }
        if (carConnected) {
            val next = when (carPlace) {
                CarPlace.INSIDE -> CarPlace.OUTSIDE
                CarPlace.OUTSIDE -> CarPlace.INSIDE
                // Место ещё не знаем — подсказывает уведомление, по которому звонили.
                CarPlace.UNKNOWN -> when (ep?.trigger) {
                    Trigger.DEPART -> CarPlace.OUTSIDE
                    Trigger.ARRIVE -> CarPlace.INSIDE
                    null -> CarPlace.UNKNOWN
                }
            }
            if (next != carPlace) {
                carPlace = next
                add(PromptAction.CarPlaceChanged(next))
            }
        }
    }

    /** Уведомление смахнули: человек сказал «не сейчас». */
    fun onDismissed(t: Long): List<PromptAction> = buildList {
        if (episode != null) {
            episode = null
            dismissedAt = t
            add(PromptAction.Hide("dismissed", used = false))
        }
    }

    /** Человек сам поправил, где стоит машина. */
    fun setCarPlace(place: CarPlace) {
        carPlace = place
    }

    /**
     * Догадка о направлении для метки проезда (§4.5): спрашивается **до**
     * [onBarrierUsed]. Машина во дворе — значит, выезжаем, за двором — въезжаем;
     * место неизвестно — подсказывает текущее уведомление. Без машины — `null`:
     * пешком направление по этим данным не угадать.
     */
    fun directionHint(): Trigger? {
        if (!carConnected) return null
        return when (carPlace) {
            CarPlace.INSIDE -> Trigger.DEPART
            CarPlace.OUTSIDE -> Trigger.ARRIVE
            CarPlace.UNKNOWN -> episode?.trigger
        }
    }

    /** Функцию выключили или сменили машину: забыть всё, кроме места машины. */
    fun reset() {
        carConnected = false
        episode = null
        zoneSince = null
        departureCheckUntil = null
        lastFix = null
        lastPreciseFixAt = null
    }

    fun onTick(t: Long): List<PromptAction> = buildList {
        val ep = episode
        if (ep != null && t >= ep.end) {
            episode = null
            add(PromptAction.Hide(if (ep.used) "after_use" else "ttl", used = ep.used))
        }
        val since = zoneSince
        if (since != null && t - since >= params.zoneMaxMillis) {
            // Предохранитель: GNSS раз в секунду не должен жить вечно, даже если
            // мы застряли у дома с подключённой машиной.
            zoneSince = null
            add(PromptAction.ZoneOff("timeout"))
        }
        val until = departureCheckUntil
        if (until != null && t >= until) departureCheckUntil = null
    }

    // --- внутреннее ----------------------------------------------------------------

    private fun checkDeparture(t: Long, fix: Fix): List<PromptAction> {
        if (!carConnected || carPlace == CarPlace.OUTSIDE) return emptyList()
        val nearHome = fix.distance <= params.departRadiusMeters + fix.accuracy
        return if (nearHome) show(t, Trigger.DEPART, fix.distance) else emptyList()
    }

    private fun updateZone(t: Long, fix: Fix): List<PromptAction> {
        val inCooldown = lastUseAt?.let { t - it < params.cooldownMillis } == true
        return when {
            zoneSince == null && carConnected && !inCooldown && fix.distance <= params.zoneEnterMeters -> {
                zoneSince = t
                listOf(PromptAction.ZoneOn(fix.distance))
            }
            zoneSince != null && fix.distance > params.zoneExitMeters -> {
                zoneSince = null
                listOf(PromptAction.ZoneOff("left_zone"))
            }
            else -> emptyList()
        }
    }

    private fun show(t: Long, trigger: Trigger, distance: Double): List<PromptAction> {
        val ep = episode
        if (ep != null) {
            if (!ep.used) ep.end = maxOf(ep.end, t + params.ttlMillis)
            return emptyList()
        }
        if (lastUseAt?.let { t - it < params.cooldownMillis } == true) return emptyList()
        if (dismissedAt?.let { t - it < params.cooldownMillis } == true) return emptyList()
        episode = Episode(trigger, t + params.ttlMillis)
        return listOf(PromptAction.Show(trigger, distance))
    }

    private fun distanceToNearest(lat: Double, lon: Double): Double =
        barriers.minOf { haversineMeters(lat, lon, it.lat, it.lon) }

    private class Fix(val t: Long, val distance: Double, val accuracy: Float)

    private class Episode(val trigger: Trigger, var end: Long) {
        var used = false
    }

    companion object {
        /**
         * Точность фикса засчитывается в пользу «близко», но не больше 300 м:
         * фикс по вышкам с точностью 2 км иначе считался бы «у шлагбаума» откуда угодно.
         */
        const val MAX_ACCURACY_CREDIT_METERS = 300.0

        fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dp = p2 - p1
            val dl = Math.toRadians(lon2 - lon1)
            val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * EARTH_RADIUS_METERS * asin(sqrt(a))
        }

        private const val EARTH_RADIUS_METERS = 6_371_000.0
    }
}

/** Шлагбаум с координатой — единственное, что движку нужно знать о настройках. */
data class BarrierPoint(val id: Long, val lat: Double, val lon: Double)

/** Где стоит машина относительно двора (§4 NEXT-notification-and-car.md). */
enum class CarPlace { INSIDE, OUTSIDE, UNKNOWN }

enum class Trigger { DEPART, ARRIVE }

sealed interface PromptAction {
    data class Show(val trigger: Trigger, val distanceMeters: Double) : PromptAction
    data class Hide(val reason: String, val used: Boolean) : PromptAction
    data class ZoneOn(val distanceMeters: Double) : PromptAction
    data class ZoneOff(val reason: String) : PromptAction
    data class CarPlaceChanged(val place: CarPlace) : PromptAction

    /** Машину отключили — момент парковки; место узнаётся одной точной фиксацией. */
    data class CarParked(val place: CarPlace) : PromptAction

    /** Нужна одна свежая фиксация, чтобы принять решение. */
    data object RequestFix : PromptAction
}

/**
 * Параметры — из лучшего варианта реплея по данным 29.09–04.10
 * (docs/NEXT-notification-and-car.md, §3). Точная локация в реплее не
 * проверялась: её порог взят из оценки по интенсивным сессиям.
 */
data class PromptParams(
    val departRadiusMeters: Double = 200.0,
    val arriveRadiusPassiveMeters: Double = 800.0,
    val arriveRadiusPreciseMeters: Double = 150.0,
    val fixMaxAgeMillis: Long = 15 * 60_000L,
    val departureFixWaitMillis: Long = 60_000L,
    val zoneEnterMeters: Double = 2_000.0,
    val zoneExitMeters: Double = 2_500.0,
    val zoneMaxMillis: Long = 20 * 60_000L,
    val preciseAccuracyMeters: Float = 50f,
    val preciseFreshMillis: Long = 10_000L,
    val ttlMillis: Long = 120_000L,
    val afterUseMillis: Long = 120_000L,
    val cooldownMillis: Long = 10 * 60_000L,
)
