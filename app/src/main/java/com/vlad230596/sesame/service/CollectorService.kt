package com.vlad230596.sesame.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.vlad230596.sesame.R
import com.vlad230596.sesame.data.CollectorJournal
import com.vlad230596.sesame.data.LogEventType
import com.vlad230596.sesame.data.dao.RecordingSessionDao
import com.vlad230596.sesame.data.prefs.SesameSettings
import com.vlad230596.sesame.data.prefs.SettingsRepository
import com.vlad230596.sesame.events.ActivityRecognitionWatcher
import com.vlad230596.sesame.events.GeofenceWatcher
import com.vlad230596.sesame.events.SystemEventWatcher
import com.vlad230596.sesame.logging.DataFileStore
import com.vlad230596.sesame.sensors.IntensiveRecorder
import com.vlad230596.sesame.sensors.PassiveSensorCollector
import com.vlad230596.sesame.session.RecordingSessionController
import com.vlad230596.sesame.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Единственный foreground service приложения (§9).
 *
 * Живёт постоянно, поднимается при старте системы, ведёт пассивный слой (§4.4)
 * и по команде переходит в режим интенсивной записи (§4.3). Уведомление — канал
 * с `IMPORTANCE_MIN`, одна строка, без кнопок.
 *
 * **Жёсткий предохранитель сессии живёт здесь** (§4.3), а не в ViewModel:
 * ViewModel умирает вместе с экраном, а сессия обязана остановиться сама даже
 * если приложение свернули сразу после старта записи. Сторож сверяется со
 * стенными часами, а не считает тики, поэтому переживает и засыпание системы.
 */
@AndroidEntryPoint
class CollectorService : android.app.Service() {

    @Inject lateinit var store: DataFileStore

    @Inject lateinit var passive: PassiveSensorCollector

    @Inject lateinit var recorder: IntensiveRecorder

    @Inject lateinit var settings: SettingsRepository

    @Inject lateinit var sessions: RecordingSessionController

    @Inject lateinit var sessionDao: RecordingSessionDao

    @Inject lateinit var journal: CollectorJournal

    @Inject lateinit var systemEvents: SystemEventWatcher

    @Inject lateinit var geofences: GeofenceWatcher

    @Inject lateinit var activityRecognition: ActivityRecognitionWatcher

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var guardJob: Job? = null
    private var collecting = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!startForegroundSafely()) {
            // Без разрешения на локацию FGS типа location не поднимается. Это не
            // падение: экран состояния (§8) покажет, чего не хватает, а сессию,
            // если она была открыта, закроет страховка в контроллере.
            stopSelf()
            return START_NOT_STICKY
        }

        ensureCollecting()

        when (intent?.action) {
            ACTION_START_SESSION -> scope.launch { startSession() }
            ACTION_STOP_SESSION -> scope.launch { stopSession("request") }
            ACTION_EXTEND_SESSION -> scope.launch { extendSession() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Сервис останавливается")
        guardJob?.cancel()
        guardJob = null
        journal.log(LogEventType.SERVICE_STOPPED, mapOf("recording" to recorder.isRecording))

        // Порядок важен: сначала кладём в очередь записи закрытие сессии и флаш
        // FIFO датчиков, и только потом останавливаем поток записи — он успеет
        // дописать и опубликовать файлы до выхода.
        if (recorder.isRecording) {
            recorder.stop("service_destroyed") { files ->
                sessions.finishSessionAsync(files, "service_destroyed")
            }
        }
        // Динамическую регистрацию надо снять, иначе приёмник утечёт вместе с
        // контекстом сервиса. Геофенсы и Activity Recognition, наоборот,
        // остаются: их ценность именно в срабатывании, когда нас нет.
        systemEvents.stop()
        geofences.stopWatching()

        passive.stop()
        store.shutdown()
        store.onPassiveDayRotated = null
        collecting = false
        scope.cancel()
        super.onDestroy()
    }

    // --- пассивный слой ------------------------------------------------------------

    private fun ensureCollecting() {
        if (collecting) return
        collecting = true
        store.onPassiveDayRotated = { dir ->
            journal.log(LogEventType.PASSIVE_DAY_ROTATED, mapOf("publishedDir" to dir))
        }
        store.start()

        // §4.4. Журнал событий пишется всегда и стоит почти ноль, поэтому он
        // поднимается вместе со сбором и не зависит от разрешений: чего не
        // хватило, будет видно в самом журнале.
        systemEvents.start()
        // Геофенсы и переходы активности живут в системе, а не в процессе, и при
        // остановке сервиса не снимаются; перезагрузка их стирает, поэтому
        // регистрация повторяется на каждом старте сбора.
        geofences.start()
        activityRecognition.start()

        scope.launch {
            val config = settings.current()
            passive.start(config)
            journal.log(
                LogEventType.SERVICE_STARTED,
                mapOf(
                    "accelerometerHz" to config.accelerometerHz,
                    "barometerHz" to config.barometerHz,
                    "locationIntervalSeconds" to config.locationIntervalSeconds,
                    "locationPriority" to config.locationPriority.name,
                ),
            )
            recoverSession(config)
        }
        startGuard()
    }

    /**
     * Процесс могли убить посреди сессии (`START_STICKY` поднимет сервис заново
     * с пустым интентом). Тогда в настройках остаётся идущая сессия: её надо либо
     * продолжить — файлы дописываются, в манифест добавляется новый запуск со
     * своим снимком часов (§6), — либо, если предохранитель уже истёк, закрыть
     * по-настоящему, чтобы каталог попал в `Documents/Sesame/`.
     */
    private suspend fun recoverSession(config: SesameSettings) {
        val id = config.activeSessionId ?: return
        if (recorder.isRecording) return
        val session = sessionDao.byId(id)
        if (session == null || session.endedAt != null) {
            settings.setActiveSession(null, null)
            return
        }
        val plannedEnd = plannedEndOf(config, id) ?: return
        recorder.start(id, session.label, plannedEnd)
        updateNotification(recording = true)
        if (System.currentTimeMillis() >= plannedEnd) stopSession("expired_after_restart")
    }

    /** §4.3: жёсткий предохранитель на уровне сервиса. */
    private fun startGuard() {
        guardJob?.cancel()
        guardJob = scope.launch {
            while (isActive) {
                delay(GUARD_INTERVAL_MILLIS)
                // Дешёвая повторная попытка: разрешение «Разрешить всегда»
                // могли выдать уже после старта сбора, и без неё геофенсы
                // молчали бы до перезапуска сервиса.
                geofences.retry()
                activityRecognition.retry()
                val config = runCatching { settings.current() }.getOrNull() ?: continue
                val id = config.activeSessionId ?: continue
                val plannedEnd = plannedEndOf(config, id) ?: continue
                if (System.currentTimeMillis() >= plannedEnd) stopSession("timer")
            }
        }
    }

    // --- интенсивная запись --------------------------------------------------------

    private suspend fun startSession() {
        val config = settings.current()
        val id = config.activeSessionId ?: return
        if (recorder.isRecording) return
        val session = sessionDao.byId(id) ?: return
        val plannedEnd = plannedEndOf(config, id) ?: return
        recorder.start(id, session.label, plannedEnd)
        updateNotification(recording = true)
    }

    private suspend fun extendSession() {
        val config = settings.current()
        val id = config.activeSessionId ?: return
        val plannedEnd = plannedEndOf(config, id) ?: return
        recorder.extend(plannedEnd)
    }

    private fun stopSession(reason: String) {
        if (!recorder.isRecording) {
            // Строку в Room всё равно надо закрыть: сюда попадаем и когда запись
            // не поднялась вовсе.
            sessions.finishSessionAsync(null, reason)
            updateNotification(recording = false)
            return
        }
        recorder.stop(reason) { files -> sessions.finishSessionAsync(files, reason) }
        updateNotification(recording = false)
    }

    /**
     * Плановый конец сессии. Настройки — источник истины, но если процесс убили
     * между двумя записями, значение достраивается от начала сессии: жёсткий
     * предохранитель не имеет права потеряться (§4.3).
     */
    private suspend fun plannedEndOf(config: SesameSettings, id: Long): Long? {
        config.activeSessionPlannedEndAt
            ?.takeIf { config.activeSessionId == id }
            ?.let { return it }
        val startedAt = sessionDao.byId(id)?.startedAt ?: return null
        return startedAt + config.recordingDurationMinutes * 60_000L
    }

    // --- уведомление ---------------------------------------------------------------

    private fun startForegroundSafely(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(recording = recorder.isRecording),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        true
    } catch (e: Exception) {
        // Android 14+ не даёт поднять FGS типа location без выданного разрешения
        // на геолокацию. Это не падение: экран состояния (§8) покажет, чего не хватает.
        Log.w(TAG, "Не удалось поднять foreground service типа location", e)
        journal.log(LogEventType.COLLECTION_ERROR, mapOf("reason" to "foreground_denied"))
        false
    }

    private fun updateNotification(recording: Boolean) {
        runCatching {
            getSystemService<NotificationManager>()
                ?.notify(NOTIFICATION_ID, buildNotification(recording))
        }
    }

    /** §9: одна строка, без кнопок. Текст меняется только на старте и стопе сессии. */
    private fun buildNotification(recording: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = getString(
            if (recording) R.string.notification_collector_recording
            else R.string.notification_collector_text,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_sesame)
            .setContentTitle(getString(R.string.notification_collector_title))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
        }
        getSystemService<NotificationManager>()?.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "CollectorService"

        /** Сторож сверяется со стенными часами, поэтому интервал можно держать редким. */
        private const val GUARD_INTERVAL_MILLIS = 10_000L

        const val CHANNEL_ID = "sesame_collector"
        const val NOTIFICATION_ID = 1

        const val ACTION_START = "com.vlad230596.sesame.action.START_COLLECTOR"
        const val ACTION_STOP = "com.vlad230596.sesame.action.STOP_COLLECTOR"
        const val ACTION_START_SESSION = "com.vlad230596.sesame.action.START_SESSION"
        const val ACTION_STOP_SESSION = "com.vlad230596.sesame.action.STOP_SESSION"
        const val ACTION_EXTEND_SESSION = "com.vlad230596.sesame.action.EXTEND_SESSION"

        fun start(context: Context) = send(context, ACTION_START)

        fun stop(context: Context) {
            val intent = Intent(context, CollectorService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }

        /** Сессия уже создана в Room и настройках — сервису остаётся начать писать. */
        fun startSession(context: Context) = send(context, ACTION_START_SESSION)

        fun stopSession(context: Context) = send(context, ACTION_STOP_SESSION)

        fun extendSession(context: Context) = send(context, ACTION_EXTEND_SESSION)

        private fun send(context: Context, action: String) {
            val intent = Intent(context, CollectorService::class.java).setAction(action)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.w(TAG, "Не удалось отправить $action сервису", it) }
        }
    }
}
