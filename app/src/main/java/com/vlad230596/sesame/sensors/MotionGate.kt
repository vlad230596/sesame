package com.vlad230596.sesame.sensors

import android.os.SystemClock
import com.vlad230596.sesame.data.CollectorJournal
import com.vlad230596.sesame.data.LogEventType
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Решает, нужен ли пассивному слою собственный запрос локации.
 *
 * Раньше пассивная локация просилась раз в 120 с круглосуточно — значок
 * геолокации горел постоянно, а телефон на столе всю ночь будил приёмник ради
 * одной и той же точки. Теперь два режима:
 *
 * - [Mode.MOVING] — обычный запрос из настроек (по умолчанию BALANCED, 120 с).
 * - [Mode.STILL] — `PRIORITY_PASSIVE`: свои фиксы не просим, берём только чужие
 *   (навигатор, карты), если они случатся. Цена — ноль.
 *
 * Признаки движения, любой из них переводит в MOVING сразу:
 * - акселерометр: разброс модуля ускорения в окне выше [MOTION_STD_MPS2] — он
 *   уже пишется пассивным слоем, лишней энергии не стоит;
 * - Activity Recognition: вход в IN_VEHICLE / ON_BICYCLE / ON_FOOT / WALKING / RUNNING;
 * - подключён Bluetooth машины;
 * - пришедший (чужой) фикс дальше [STILL_RADIUS_METERS] от точки покоя.
 *
 * В STILL уходим только когда признаков нет [STILL_AFTER_MILLIS] подряд, машина
 * не подключена и последняя активность — не транспорт. Activity Recognition
 * сам по себе тут не решает: он опаздывает на десятки секунд (§4.4), поэтому
 * служит лишь «поддержкой» MOVING в машине на гладкой дороге, где акселерометр
 * почти спокоен.
 *
 * Потоки: акселерометр приходит из потока датчиков, AR — из приёмника в главном
 * потоке, Bluetooth — из диспетчера подсказки. Состояние маленькое, хватает
 * `synchronized`.
 */
@Singleton
class MotionGate internal constructor(
    private val clock: () -> Long,
    private val log: (Map<String, Any?>) -> Unit,
) {

    @Inject
    constructor(journal: CollectorJournal) : this(
        clock = { SystemClock.elapsedRealtime() },
        log = { payload -> journal.log(LogEventType.LOCATION_MODE, payload) },
    )

    enum class Mode { MOVING, STILL }

    /** Подписчик на смену режима; задаёт пассивный слой. Зовётся вне блокировки. */
    @Volatile
    var listener: ((Mode) -> Unit)? = null

    private val lock = Any()

    // Стартуем в MOVING: после запуска сервиса нужна хотя бы одна свежая точка.
    private var mode = Mode.MOVING
    private var lastMotionAt = clock()
    private var carConnected = false
    private var vehicleActivity = false
    private var stillAnchor: DoubleArray? = null

    // Окно акселерометра: сумма и сумма квадратов модуля.
    private var windowStartNanos = 0L
    private var count = 0
    private var sum = 0.0
    private var sumSq = 0.0

    val current: Mode get() = synchronized(lock) { mode }

    /** Отсчёт акселерометра из пассивного слоя. Окно — [WINDOW_NANOS]. */
    fun onAccelerometer(timestampNanos: Long, x: Float, y: Float, z: Float) {
        var changed: Mode? = null
        synchronized(lock) {
            if (count == 0) windowStartNanos = timestampNanos
            val magnitude = sqrt((x * x + y * y + z * z).toDouble())
            count++
            sum += magnitude
            sumSq += magnitude * magnitude
            if (timestampNanos - windowStartNanos < WINDOW_NANOS) return
            val mean = sum / count
            val std = sqrt((sumSq / count - mean * mean).coerceAtLeast(0.0))
            val samples = count
            count = 0
            sum = 0.0
            sumSq = 0.0
            changed = if (samples >= MIN_WINDOW_SAMPLES && std > MOTION_STD_MPS2) {
                motionLocked("accelerometer")
            } else {
                evaluateStillLocked()
            }
        }
        changed?.let { notify(it) }
    }

    /** Вход в активность по Activity Recognition (только ENTER). */
    fun onActivityEnter(activity: String) {
        val changed = synchronized(lock) {
            vehicleActivity = activity == "IN_VEHICLE" || activity == "ON_BICYCLE"
            if (activity in MOVING_ACTIVITIES) motionLocked("activity_$activity") else evaluateStillLocked()
        }
        changed?.let { notify(it) }
    }

    fun onCarConnection(connected: Boolean) {
        val changed = synchronized(lock) {
            carConnected = connected
            if (connected) motionLocked("car_connected") else null
        }
        changed?.let { notify(it) }
    }

    /** Любой фикс, свой или чужой: в покое — проверка, не уехали ли мы. */
    fun onLocation(lat: Double, lon: Double) {
        val changed = synchronized(lock) {
            val anchor = stillAnchor
            when {
                mode == Mode.MOVING -> {
                    stillAnchor = doubleArrayOf(lat, lon)
                    null
                }
                anchor == null -> {
                    stillAnchor = doubleArrayOf(lat, lon)
                    null
                }
                distanceMeters(anchor[0], anchor[1], lat, lon) > STILL_RADIUS_METERS ->
                    motionLocked("displacement")
                else -> null
            }
        }
        changed?.let { notify(it) }
    }

    private fun motionLocked(reason: String): Mode? {
        lastMotionAt = clock()
        if (mode == Mode.MOVING) return null
        mode = Mode.MOVING
        log(mapOf("mode" to mode.name, "reason" to reason))
        return mode
    }

    private fun evaluateStillLocked(): Mode? {
        if (mode == Mode.STILL) return null
        if (carConnected || vehicleActivity) return null
        val quietFor = clock() - lastMotionAt
        if (quietFor < STILL_AFTER_MILLIS) return null
        mode = Mode.STILL
        // Точку покоя задаст первый фикс уже в покое: последний фикс в движении
        // мог быть за километр до остановки.
        stillAnchor = null
        log(mapOf("mode" to mode.name, "reason" to "quiet", "quietMillis" to quietFor))
        return mode
    }

    private fun notify(mode: Mode) {
        runCatching { listener?.invoke(mode) }
    }

    companion object {
        /** Окно оценки разброса: совпадает с пакетной передачей датчика (10 с). */
        const val WINDOW_NANOS = 10_000_000_000L

        /** При 5 Гц в окне 50 отсчётов; меньше 10 — окно рваное, не судим. */
        const val MIN_WINDOW_SAMPLES = 10

        /**
         * Порог разброса модуля ускорения, м/с². Телефон на столе — 0.01–0.05,
         * в руке/кармане при ходьбе — 1–3, в машине по городу — 0.2–0.6.
         * Порог берём ниже машины: ложное MOVING стоит одного лишнего фикса,
         * ложное STILL — пропущенной подсказки у шлагбаума.
         */
        const val MOTION_STD_MPS2 = 0.15

        /** Сколько спокойствия нужно, чтобы перестать просить локацию. */
        const val STILL_AFTER_MILLIS = 5 * 60_000L

        /** Чужой фикс дальше этого от точки покоя — значит, мы едем. */
        const val STILL_RADIUS_METERS = 150.0

        val MOVING_ACTIVITIES = setOf("IN_VEHICLE", "ON_BICYCLE", "ON_FOOT", "WALKING", "RUNNING")

        fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dLat / 2).let { it * it } +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2).let { it * it }
            return 2 * r * Math.asin(sqrt(a))
        }
    }
}
