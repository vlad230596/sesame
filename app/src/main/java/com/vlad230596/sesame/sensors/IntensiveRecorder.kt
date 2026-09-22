package com.vlad230596.sesame.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log
import androidx.core.content.getSystemService
import com.vlad230596.sesame.data.CollectorJournal
import com.vlad230596.sesame.data.LogEventType
import com.vlad230596.sesame.data.SessionLabel
import com.vlad230596.sesame.data.prefs.LocationPriority
import com.vlad230596.sesame.logging.DataFileStore
import com.vlad230596.sesame.logging.ManifestDraft
import com.vlad230596.sesame.logging.Manifests
import com.vlad230596.sesame.logging.SensorStreams
import com.vlad230596.sesame.logging.SessionFiles
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
 * Интенсивная запись (§4.3).
 *
 * Запускается только вручную — из приложения или из виджета. Пишет все датчики,
 * вернувшиеся из `SensorManager.getSensorList(TYPE_ALL)`, на `SENSOR_DELAY_FASTEST`,
 * локацию — 1 Гц с `PRIORITY_HIGH_ACCURACY`. Отсутствие любого датчика не ошибка
 * и фиксируется в манифесте сессии (§3).
 *
 * Порядок старта важен и опирается на то, что у [DataFileStore] одна очередь:
 * сначала открывается сессия, затем датчики отдают содержимое своих FIFO, затем
 * в файлы сессии уходит кольцевой буфер пассивного слоя — и только потом
 * начинают поступать живые отсчёты. Так 60 секунд предыстории оказываются в
 * начале файла, а не вперемешку с ним.
 *
 * **Wake lock.** На время сессии берётся `PARTIAL_WAKE_LOCK`. Foreground service
 * сам по себе не мешает системе усыпить процессор, а не-wakeup датчики при
 * засыпании перестают доставлять отсчёты — то есть без wake lock критерий §12.3
 * («15 минут при погашенном экране пишутся полностью») не выполнялся бы. Для
 * пассивного слоя wake lock не берётся: там ту же задачу решает аппаратная
 * пакетная передача.
 */
@Singleton
class IntensiveRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: DataFileStore,
    private val passive: PassiveSensorCollector,
    private val permissions: PermissionsChecker,
    private val journal: CollectorJournal,
) : SensorEventListener {

    private val sensorManager: SensorManager? by lazy {
        context.getSystemService<SensorManager>()
    }

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    /** Заполняется в потоке старта, читается в потоке доставки событий датчиков. */
    private val streamKeys = ConcurrentHashMap<Int, String>()
    private val registered = CopyOnWriteArrayList<Sensor>()

    @Volatile
    private var locationWriter: LocationStreamWriter? = null

    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    private val failedStreams = CopyOnWriteArrayList<String>()
    private var extendCount = 0

    @Volatile
    var recordingSessionId: Long? = null
        private set

    val isRecording: Boolean get() = recordingSessionId != null

    /**
     * @param plannedEndAt жёсткий предохранитель (§4.3). Сам предохранитель живёт
     *        в сервисе, здесь значение нужно только для манифеста и таймаута
     *        wake lock: даже при полной потере управления система отпустит
     *        процессор сама.
     */
    fun start(sessionId: Long, label: SessionLabel, plannedEndAt: Long): Boolean {
        if (isRecording) return false
        val manager = sensorManager
        if (manager == null) {
            journal.log(LogEventType.COLLECTION_ERROR, mapOf("reason" to "no_sensor_manager"))
            return false
        }
        recordingSessionId = sessionId
        extendCount = 0
        failedStreams.clear()

        val thread = HandlerThread(THREAD_NAME).apply { start() }
        handlerThread = thread
        val sensorHandler = Handler(thread.looper)
        handler = sensorHandler

        acquireWakeLock(plannedEndAt)

        val sensors = candidateSensors(manager)
        val infos = JSONArray()
        sensors.forEach { (sensor, key) ->
            store.declareStream(key) { count -> SensorStreams.columns(sensor.type, count) }
            infos.put(
                Manifests.sensorInfo(
                    sensor = sensor,
                    streamKey = key,
                    requestedDelayUs = SensorManager.SENSOR_DELAY_FASTEST,
                    requestedMaxReportLatencyUs = 0,
                ),
            )
        }

        val locationGranted =
            permissions.isGranted(android.Manifest.permission.ACCESS_FINE_LOCATION) ||
                permissions.isGranted(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        store.openSession(
            sessionId,
            buildManifest(
                sessionId = sessionId,
                label = label,
                plannedEndAt = plannedEndAt,
                sensors = infos,
                streamKeys = sensors.map { it.second },
                locationGranted = locationGranted,
            ),
        )

        // FIFO пассивных датчиков — в буфер, буфер — в файл сессии, и только
        // потом подписка на живые отсчёты.
        passive.flushHardwareBuffers {
            // «Стоп» могли нажать, пока датчики отдавали FIFO: подписываться
            // после этого — прямая утечка слушателей на мёртвом Looper'е.
            if (recordingSessionId != sessionId) return@flushHardwareBuffers
            store.dumpRingBufferIntoSession()
            registerAll(manager, sensors, sensorHandler)
            startLocation(sensorHandler)
        }
        Log.i(TAG, "Интенсивная запись начата: сессия $sessionId, датчиков ${sensors.size}")
        return true
    }

    /** «Продлить» на экране сессии (§4.3): предохранитель двигает сервис, тут — учёт. */
    fun extend(plannedEndAt: Long) {
        if (!isRecording) return
        extendCount++
        acquireWakeLock(plannedEndAt)
    }

    /**
     * Останавливает запись и закрывает файлы. [onClosed] вызывается всегда, в том
     * числе если писать было нечего: строка сессии в Room не должна остаться
     * незакрытой.
     */
    fun stop(reason: String, onClosed: (SessionFiles?) -> Unit) {
        val sessionId = recordingSessionId
        recordingSessionId = null

        val manager = sensorManager
        runCatching { manager?.unregisterListener(this) }
            .onFailure { Log.w(TAG, "Не удалось снять слушателей датчиков", it) }
        registered.clear()
        streamKeys.clear()

        locationWriter?.stop()
        locationWriter = null

        val extras = JSONObject().apply {
            put("stopReason", reason)
            put("extendCount", extendCount)
            put("failedStreams", Manifests.strings(failedStreams.toList()))
        }
        store.closeSession(extras) { files -> onClosed(files) }

        runCatching { handlerThread?.quitSafely() }
        handlerThread = null
        handler = null
        releaseWakeLock()
        Log.i(TAG, "Интенсивная запись остановлена: сессия $sessionId, причина $reason")
    }

    /**
     * Состав датчиков определяется в рантайме (§3) и пишется в `session.json` (§6).
     * Метод остаётся для строки `RecordingSession.sensorManifest` в момент
     * создания сессии, когда файлов ещё нет.
     */
    fun buildSensorManifest(): String {
        val manager = sensorManager ?: return "{}"
        return runCatching {
            val sensors = candidateSensors(manager)
            JSONObject().apply {
                put("schemaVersion", Manifests.SCHEMA_VERSION)
                put("device", Manifests.deviceInfo())
                put("app", Manifests.appInfo())
                put(
                    "sensors",
                    JSONArray().apply {
                        sensors.forEach { (sensor, key) ->
                            put(
                                Manifests.sensorInfo(
                                    sensor,
                                    key,
                                    SensorManager.SENSOR_DELAY_FASTEST,
                                    0,
                                ),
                            )
                        }
                    },
                )
                put("missingSensors", Manifests.strings(missingSensors(sensors.map { it.second })))
            }.toString()
        }.getOrDefault("{}")
    }

    // --- внутреннее ----------------------------------------------------------------

    /**
     * Все датчики, которые вообще можно слушать непрерывно.
     *
     * Одноразовые (`REPORTING_MODE_ONE_SHOT`, например `TYPE_SIGNIFICANT_MOTION`)
     * слушаются другим API и на `registerListener` молча отвечают отказом.
     * Дубликаты по типу — обычно wake-up вариант того же железа — сворачиваются
     * в один поток, причём предпочтение у «стандартного» датчика: иначе два
     * потока претендовали бы на одно имя файла, а ключ потока по типу датчика
     * перестал бы быть однозначным.
     */
    private fun candidateSensors(manager: SensorManager): List<Pair<Sensor, String>> {
        val all = runCatching { manager.getSensorList(Sensor.TYPE_ALL) }.getOrNull().orEmpty()
        val byType = LinkedHashMap<Int, Sensor>()
        all.filter { it.reportingMode != Sensor.REPORTING_MODE_ONE_SHOT }
            .forEach { sensor ->
                val preferred = runCatching { manager.getDefaultSensor(sensor.type) }.getOrNull()
                val current = byType[sensor.type]
                if (current == null || (preferred != null && preferred.name == sensor.name)) {
                    byType[sensor.type] = sensor
                }
            }
        val used = HashSet<String>()
        return byType.values.map { sensor ->
            var key = SensorStreams.streamKey(sensor)
            var suffix = 2
            while (!used.add(key)) {
                key = "${SensorStreams.streamKey(sensor)}_$suffix"
                suffix++
            }
            sensor to key
        }
    }

    private fun registerAll(
        manager: SensorManager,
        sensors: List<Pair<Sensor, String>>,
        sensorHandler: Handler,
    ) {
        sensors.forEach { (sensor, key) ->
            // Ключ кладётся до подписки: первый отсчёт на SENSOR_DELAY_FASTEST
            // приходит практически мгновенно.
            streamKeys[sensor.type] = key
            val ok = runCatching {
                manager.registerListener(
                    this,
                    sensor,
                    SensorManager.SENSOR_DELAY_FASTEST,
                    0,
                    sensorHandler,
                )
            }.getOrDefault(false)
            if (ok) {
                registered += sensor
            } else {
                streamKeys.remove(sensor.type)
                failedStreams += key
            }
        }
        if (failedStreams.isNotEmpty()) {
            journal.log(
                LogEventType.COLLECTION_ERROR,
                mapOf("reason" to "register_failed", "streams" to failedStreams.joinToString(",")),
            )
        }
    }

    private fun startLocation(sensorHandler: Handler) {
        val writer = LocationStreamWriter(context, WriteScope.SESSION, store, permissions)
        if (!writer.hasPermission) {
            journal.log(LogEventType.LOCATION_PERMISSION_MISSING, mapOf("layer" to "intensive"))
            return
        }
        val started = writer.start(
            priority = LocationPriority.HIGH_ACCURACY,
            intervalMillis = LOCATION_INTERVAL_MILLIS,
            minDisplacementMeters = 0f,
            looper = sensorHandler.looper,
        )
        locationWriter = if (started) writer else null
        if (!started) {
            journal.log(
                LogEventType.COLLECTION_ERROR,
                mapOf("reason" to "location_request_failed", "layer" to "intensive"),
            )
        }
    }

    private fun buildManifest(
        sessionId: Long,
        label: SessionLabel,
        plannedEndAt: Long,
        sensors: JSONArray,
        streamKeys: List<String>,
        locationGranted: Boolean,
    ): ManifestDraft {
        val topLevel = JSONObject().apply {
            put("kind", "session")
            put("sessionId", sessionId)
            put("label", label.name)
            put("app", Manifests.appInfo())
            put("device", Manifests.deviceInfo())
        }
        val run = JSONObject().apply {
            put("plannedEndAtMillis", plannedEndAt)
            put("sensors", sensors)
            put("missingSensors", Manifests.strings(missingSensors(streamKeys)))
            put(
                "location",
                Manifests.locationInfo(
                    available = locationGranted,
                    priority = LocationPriority.HIGH_ACCURACY.name,
                    intervalMillis = LOCATION_INTERVAL_MILLIS,
                    minDisplacementMeters = 0f,
                    permissionGranted = locationGranted,
                ),
            )
            put("requestedDelay", "SENSOR_DELAY_FASTEST")
        }
        return ManifestDraft(topLevel, run)
    }

    /** §3, §11: отсутствие барометра — исследуемый факт, а не дефект; пишем его. */
    private fun missingSensors(streamKeys: List<String>): List<String> =
        EXPECTED_STREAMS.filterNot { it in streamKeys }

    private fun acquireWakeLock(plannedEndAt: Long) {
        releaseWakeLock()
        val power = context.getSystemService<PowerManager>() ?: return
        val timeout = (plannedEndAt - System.currentTimeMillis())
            .coerceIn(MIN_WAKE_LOCK_MILLIS, MAX_WAKE_LOCK_MILLIS) + WAKE_LOCK_MARGIN_MILLIS
        wakeLock = runCatching {
            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                // Таймаут обязателен: даже если стоп потеряется, батарея не уйдёт в ноль.
                acquire(timeout)
            }
        }.onFailure { Log.w(TAG, "Не удалось взять wake lock", it) }.getOrNull()
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        wakeLock = null
        runCatching { if (lock.isHeld) lock.release() }
            .onFailure { Log.w(TAG, "Не удалось отпустить wake lock", it) }
    }

    // --- SensorEventListener --------------------------------------------------------

    override fun onSensorChanged(event: SensorEvent) {
        val key = streamKeys[event.sensor.type] ?: return
        store.writeSensor(
            scope = WriteScope.SESSION,
            key = key,
            elapsedRealtimeNanos = event.timestamp,
            values = event.values,
            valueCount = SensorStreams.valueCount(event.sensor, event.values.size),
            accuracy = event.accuracy,
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val TAG = "IntensiveRecorder"
        private const val THREAD_NAME = "sesame-intensive"
        private const val WAKE_LOCK_TAG = "sesame:intensive-recording"

        /** §4.3: по умолчанию 15 минут, значение в настройках. */
        const val DEFAULT_DURATION_MINUTES: Int = 15

        /** §4.3: локация в сессии — 1 Гц. */
        private const val LOCATION_INTERVAL_MILLIS = 1_000L

        private const val MIN_WAKE_LOCK_MILLIS = 60_000L
        private const val MAX_WAKE_LOCK_MILLIS = 3 * 60 * 60_000L
        private const val WAKE_LOCK_MARGIN_MILLIS = 30_000L

        /** Датчики, отсутствие которых стоит назвать по имени в манифесте. */
        private val EXPECTED_STREAMS = listOf(
            SensorStreams.ACCELEROMETER,
            SensorStreams.GYROSCOPE,
            SensorStreams.MAGNETOMETER,
            SensorStreams.BAROMETER,
            SensorStreams.STEP_COUNTER,
        )
    }
}
