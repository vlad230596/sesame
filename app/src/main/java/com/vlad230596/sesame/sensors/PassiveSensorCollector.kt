package com.vlad230596.sesame.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.content.getSystemService
import com.vlad230596.sesame.data.CollectorJournal
import com.vlad230596.sesame.data.LogEventType
import com.vlad230596.sesame.data.prefs.SesameSettings
import com.vlad230596.sesame.logging.DataFileStore
import com.vlad230596.sesame.logging.DataPaths
import com.vlad230596.sesame.logging.GzipCsvWriter
import com.vlad230596.sesame.logging.ManifestDraft
import com.vlad230596.sesame.logging.Manifests
import com.vlad230596.sesame.logging.SensorStreams
import com.vlad230596.sesame.logging.WriteScope
import com.vlad230596.sesame.ui.permissions.PermissionsChecker
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Пассивный слой (§4.4).
 *
 * Работает постоянно под foreground service, интенсивную запись не запускает.
 * По умолчанию: локация раз в 120 с (`PRIORITY_BALANCED_POWER_ACCURACY`, смещение 25 м),
 * акселерометр 5 Гц, барометр 1 Гц при наличии, аппаратный `TYPE_STEP_COUNTER`.
 * Все частоты и параметры берутся из настроек (§4.6) — крутить их надо на
 * телефоне, без пересборки.
 *
 * Гироскоп и магнитометр в пассивном слое не опрашиваются: на низкой частоте
 * бесполезны, а энергию расходуют наравне с полной (§4.4).
 *
 * **Аппаратная пакетная передача.** Подписка идёт с
 * `maxReportLatencyUs` = [BATCH_LATENCY_MILLIS], то есть датчику разрешено
 * копить отсчёты в своём FIFO и отдавать их пачкой. Это главный способ
 * уложиться в 5–7 % батареи: процессор просыпается раз в десять секунд, а не
 * пять раз в секунду, и — что важнее для §11 — FIFO продолжает наполняться,
 * пока система спит. Если FIFO у датчика нет, Android молча доставляет
 * отсчёты сразу, поведение от этого не меняется.
 *
 * Кольцевой буфер последних [DataFileStore.RING_BUFFER_SECONDS] секунд живёт в
 * [DataFileStore] — он наполняется в том же потоке записи, куда идут пассивные
 * отсчёты, поэтому его сброс в файл сессии (§4.3) не требует блокировок.
 */
@Singleton
class PassiveSensorCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: DataFileStore,
    private val permissions: PermissionsChecker,
    private val journal: CollectorJournal,
) : SensorEventListener2 {

    private val sensorManager: SensorManager? by lazy {
        context.getSystemService<SensorManager>()
    }

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    /**
     * Тип датчика → ключ потока. Типы здесь заведомо уникальны, см. [start].
     * Конкурентные коллекции не для скорости, а для видимости: заполняются они в
     * потоке вызывающего, а читаются в потоке доставки событий датчиков.
     */
    private val streamKeys = ConcurrentHashMap<Int, String>()

    private val registered = CopyOnWriteArrayList<Sensor>()
    private var locationWriter: LocationStreamWriter? = null

    @Volatile
    private var running = false

    /** Снимки для `day.json`; провайдер зовут из потока записи, в том числе в полночь. */
    @Volatile
    private var sensorInfos: JSONArray = JSONArray()

    @Volatile
    private var missingSensors: List<String> = emptyList()

    @Volatile
    private var locationInfo: JSONObject = JSONObject()

    @Volatile
    private var configSnapshot: SesameSettings = SesameSettings()

    /** Ожидание завершения аппаратного флаша; трогается только из [handler]. */
    private var pendingFlushes = 0
    private var flushDone: (() -> Unit)? = null

    val isRunning: Boolean get() = running

    /** Последняя известная локация — для догадок в метках (§4.5), следующий заход. */
    val lastLocation get() = locationWriter?.lastLocation

    fun start(config: SesameSettings) {
        if (running) return
        val manager = sensorManager
        if (manager == null) {
            journal.log(LogEventType.COLLECTION_ERROR, mapOf("reason" to "no_sensor_manager"))
            return
        }
        running = true
        configSnapshot = config

        val thread = HandlerThread(THREAD_NAME).apply { start() }
        handlerThread = thread
        val sensorHandler = Handler(thread.looper)
        handler = sensorHandler

        val infos = JSONArray()
        val missing = mutableListOf<String>()

        // §4.4: только акселерометр, барометр и шагомер. Каждый тип встречается
        // один раз, поэтому ключ потока однозначно определяется типом датчика.
        val requests = listOf(
            SensorRequest(Sensor.TYPE_ACCELEROMETER, SensorStreams.ACCELEROMETER, config.accelerometerHz),
            SensorRequest(Sensor.TYPE_PRESSURE, SensorStreams.BAROMETER, config.barometerHz),
            SensorRequest(Sensor.TYPE_STEP_COUNTER, SensorStreams.STEP_COUNTER, STEP_COUNTER_HZ),
        )

        requests.forEach { request ->
            val sensor = manager.getDefaultSensor(request.type)
            if (sensor == null) {
                // §3: отсутствие датчика — не ошибка, а запись в манифест.
                missing += request.streamKey
                return@forEach
            }
            val delayUs = (1_000_000 / request.hz.coerceAtLeast(1))
                .coerceAtLeast(sensor.minDelay.coerceAtLeast(0))
            val latencyUs = (BATCH_LATENCY_MILLIS * 1000).toInt()
            store.declareStream(request.streamKey) { count ->
                SensorStreams.columns(sensor.type, count)
            }
            // Ключ кладётся до подписки: первый отсчёт может прийти раньше, чем
            // вернётся registerListener, и терять его незачем.
            streamKeys[sensor.type] = request.streamKey
            val ok = runCatching {
                manager.registerListener(this, sensor, delayUs, latencyUs, sensorHandler)
            }.getOrDefault(false)
            if (ok) {
                registered += sensor
                infos.put(Manifests.sensorInfo(sensor, request.streamKey, delayUs, latencyUs))
            } else {
                streamKeys.remove(sensor.type)
                missing += request.streamKey
                journal.log(
                    LogEventType.COLLECTION_ERROR,
                    mapOf("reason" to "register_failed", "stream" to request.streamKey),
                )
            }
        }

        sensorInfos = infos
        missingSensors = missing

        startLocation(config, sensorHandler)

        store.setPassiveManifestProvider { buildDayManifest() }
        Log.i(TAG, "Пассивный слой запущен: ${registered.map { it.name }}, нет: $missing")
    }

    fun stop() {
        if (!running) return
        running = false

        locationWriter?.stop()
        locationWriter = null
        store.setPassiveManifestProvider(null)

        val manager = sensorManager
        val thread = handlerThread
        val sensorHandler = handler
        handler = null
        handlerThread = null

        if (manager == null || sensorHandler == null) {
            finishStop(manager, thread)
            return
        }
        // Сначала просим датчики отдать содержимое FIFO — иначе пакетная передача
        // уносила бы последние секунды при каждой остановке сервиса. Отписка идёт
        // с задержкой, чтобы пачка успела дойти; поток записи в это время ещё жив
        // (его останавливают после нас).
        sensorHandler.post {
            runCatching { manager.flush(this) }
            sensorHandler.postDelayed({ finishStop(manager, thread) }, FLUSH_DRAIN_MILLIS)
        }
    }

    /**
     * §4.3: перед сбросом кольцевого буфера в файл сессии просим датчики отдать
     * FIFO. Без этого при пакетной передаче предыстория теряла бы последние
     * секунды — ровно тот момент, ради которого буфер и заведён.
     *
     * [onDone] вызывается ровно один раз: либо когда все датчики отчитались,
     * либо по таймауту — ждать нельзя, старт записи не должен зависеть от
     * прошивки датчика.
     */
    fun flushHardwareBuffers(onDone: () -> Unit) {
        val manager = sensorManager
        val sensorHandler = handler
        if (!running || manager == null || sensorHandler == null || registered.isEmpty()) {
            onDone()
            return
        }
        sensorHandler.post {
            if (flushDone != null) {
                // Флаш уже идёт: не мешаем, просто дожидаемся своей очереди.
                sensorHandler.post { onDone() }
                return@post
            }
            flushDone = onDone
            pendingFlushes = registered.size
            val started = runCatching { manager.flush(this) }.getOrDefault(false)
            if (!started) {
                completeFlush()
                return@post
            }
            sensorHandler.postDelayed({ completeFlush() }, FLUSH_TIMEOUT_MILLIS)
        }
    }

    // --- SensorEventListener2 -------------------------------------------------------

    override fun onSensorChanged(event: SensorEvent) {
        val key = streamKeys[event.sensor.type] ?: return
        store.writeSensor(
            scope = WriteScope.PASSIVE,
            key = key,
            elapsedRealtimeNanos = event.timestamp,
            values = event.values,
            valueCount = SensorStreams.valueCount(event.sensor, event.values.size),
            accuracy = event.accuracy,
        )
    }

    /** Точность пишется столбцом в каждой строке, отдельного события не нужно. */
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onFlushCompleted(sensor: Sensor?) {
        pendingFlushes--
        if (pendingFlushes <= 0) completeFlush()
    }

    // --- внутреннее ----------------------------------------------------------------

    private fun completeFlush() {
        val done = flushDone ?: return
        flushDone = null
        pendingFlushes = 0
        runCatching { done() }.onFailure { Log.w(TAG, "Обработчик флаша упал", it) }
    }

    private fun finishStop(manager: SensorManager?, thread: HandlerThread?) {
        runCatching { manager?.unregisterListener(this) }
            .onFailure { Log.w(TAG, "Не удалось снять слушателей датчиков", it) }
        registered.clear()
        streamKeys.clear()
        flushDone = null
        pendingFlushes = 0
        runCatching { thread?.quitSafely() }
        Log.i(TAG, "Пассивный слой остановлен")
    }

    private fun startLocation(config: SesameSettings, sensorHandler: Handler) {
        val writer = LocationStreamWriter(context, WriteScope.PASSIVE, store, permissions)
        val intervalMillis = config.locationIntervalSeconds * 1000L
        val displacement = config.locationMinDisplacementMeters.toFloat()
        val granted = writer.hasPermission
        val started = granted && writer.start(
            priority = config.locationPriority,
            intervalMillis = intervalMillis,
            minDisplacementMeters = displacement,
            looper = sensorHandler.looper,
        )
        locationWriter = if (started) writer else null
        locationInfo = Manifests.locationInfo(
            available = started,
            priority = config.locationPriority.name,
            intervalMillis = intervalMillis,
            minDisplacementMeters = displacement,
            permissionGranted = granted,
        )
        if (!granted) {
            // §8: без разрешения не падать, а фиксировать в журнале.
            journal.log(LogEventType.LOCATION_PERMISSION_MISSING, mapOf("layer" to "passive"))
        } else if (!started) {
            journal.log(
                LogEventType.COLLECTION_ERROR,
                mapOf("reason" to "location_request_failed", "layer" to "passive"),
            )
        }
    }

    /**
     * `day.json` (§6). Строится на каждое открытие суток, поэтому переживает
     * полночь и перезапуск процесса: параметры сбора и состав датчиков могли
     * поменяться между сутками.
     */
    private fun buildDayManifest(): ManifestDraft {
        val now = System.currentTimeMillis()
        val config = configSnapshot
        val topLevel = JSONObject().apply {
            put("kind", "passive")
            put("date", DataPaths.DATE_FORMAT.format(DataPaths.localDate(now)))
            put("app", Manifests.appInfo())
            put("device", Manifests.deviceInfo())
        }
        val run = JSONObject().apply {
            put("sensors", sensorInfos)
            put("missingSensors", Manifests.strings(missingSensors))
            put("location", locationInfo)
            put("ringBufferSeconds", DataFileStore.RING_BUFFER_SECONDS)
            put("batchLatencyMillis", BATCH_LATENCY_MILLIS)
            put(
                "collectionParams",
                JSONObject().apply {
                    put("accelerometerHz", config.accelerometerHz)
                    put("barometerHz", config.barometerHz)
                    put("locationIntervalSeconds", config.locationIntervalSeconds)
                    put("locationPriority", config.locationPriority.name)
                    put("locationMinDisplacementMeters", config.locationMinDisplacementMeters)
                },
            )
        }
        return ManifestDraft(topLevel, run)
    }

    private class SensorRequest(val type: Int, val streamKey: String, val hz: Int)

    companion object {
        private const val TAG = "PassiveSensorCollector"
        private const val THREAD_NAME = "sesame-passive"

        /** §4.3: 60 секунд предыстории. Буфер живёт в [DataFileStore]. */
        const val RING_BUFFER_SECONDS: Int = DataFileStore.RING_BUFFER_SECONDS

        /**
         * Аппаратная пакетная передача: столько датчику разрешено копить в FIFO.
         * Совпадает с интервалом флаша (§5) — при падении сервиса потеря данных
         * и так ограничена десятью секундами, дальше растить смысла нет.
         */
        const val BATCH_LATENCY_MILLIS: Long = GzipCsvWriter.FLUSH_INTERVAL_MILLIS

        /** `TYPE_STEP_COUNTER` — on-change; частота лишь ограничивает поток сверху. */
        private const val STEP_COUNTER_HZ = 1

        private const val FLUSH_TIMEOUT_MILLIS = 500L
        private const val FLUSH_DRAIN_MILLIS = 300L
    }
}
