package com.vlad230596.sesame.session

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.vlad230596.sesame.data.LogEventType
import com.vlad230596.sesame.data.SessionLabel
import com.vlad230596.sesame.data.dao.LogEventDao
import com.vlad230596.sesame.data.dao.RecordingSessionDao
import com.vlad230596.sesame.data.entity.LogEvent
import com.vlad230596.sesame.data.entity.RecordingSession
import com.vlad230596.sesame.data.prefs.SettingsRepository
import com.vlad230596.sesame.logging.DataPaths
import com.vlad230596.sesame.logging.SessionFiles
import com.vlad230596.sesame.sensors.IntensiveRecorder
import com.vlad230596.sesame.service.CollectorService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Состояние идущей интенсивной сессии для UI (§4.3).
 *
 * [plannedEndAt] — жёсткий предохранитель: сессия останавливается автоматически
 * по истечении времени. Хранится в настройках, поэтому переживает перезапуск
 * процесса.
 */
data class ActiveSession(
    val id: Long,
    val startedAt: Long,
    val plannedEndAt: Long,
    val label: SessionLabel,
)

/**
 * Жизненный цикл интенсивной сессии: старт, продление, стоп (§4.3).
 *
 * Разметка и время живут здесь, в Room и настройках; сама запись датчиков — в
 * [CollectorService], и команды туда уходят интентами. Разделение не
 * декоративное: сессия обязана пережить закрытие экрана и смерть ViewModel,
 * поэтому владельцем и записи, и жёсткого предохранителя должен быть сервис, а
 * не то, что живёт, пока на экран кто-то смотрит.
 *
 * Финализацию строки в Room делает [finishSession] — её зовёт сервис, когда
 * файлы уже закрыты и опубликованы, чтобы в `sizeBytes` попал реальный объём.
 */
@Singleton
class RecordingSessionController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionDao: RecordingSessionDao,
    private val logEventDao: LogEventDao,
    private val settings: SettingsRepository,
    private val paths: DataPaths,
    private val recorder: IntensiveRecorder,
) {

    /** Переживает смерть вызывающего: страховка ниже не должна отменяться вместе с UI. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Незавершённая сессия. Источник истины о самом факте сессии — Room, о её
     * плановом конце — настройки; если они разошлись (например, процесс убили
     * между двумя записями), плановый конец достраивается от старта.
     */
    val active: Flow<ActiveSession?> =
        combine(sessionDao.observeActive(), settings.settings) { session, config ->
            if (session == null) return@combine null
            val planned = config.activeSessionPlannedEndAt
                ?.takeIf { config.activeSessionId == session.id }
                ?: (session.startedAt + config.recordingDurationMinutes * 60_000L)
            ActiveSession(
                id = session.id,
                startedAt = session.startedAt,
                plannedEndAt = planned,
                label = session.label,
            )
        }

    suspend fun start(label: SessionLabel): Long {
        val config = settings.current()
        val now = System.currentTimeMillis()
        val id = sessionDao.insert(
            RecordingSession(
                startedAt = now,
                label = label,
                sensorManifest = recorder.buildSensorManifest(),
            ),
        )
        // Каталог известен только после получения id (§5).
        sessionDao.byId(id)?.let {
            sessionDao.update(it.copy(fileDir = paths.sessionPublishedDir(id)))
        }
        settings.setActiveSession(id, now + config.recordingDurationMinutes * 60_000L)
        log(LogEventType.RECORDING_SESSION_STARTED, id, label)
        CollectorService.startSession(context)
        return id
    }

    /** «Продлить» на экране сессии — сдвигает предохранитель от текущего момента. */
    suspend fun extend(minutes: Int) {
        val config = settings.current()
        val id = config.activeSessionId ?: return
        val base = maxOf(config.activeSessionPlannedEndAt ?: 0L, System.currentTimeMillis())
        settings.setActiveSession(id, base + minutes * 60_000L)
        CollectorService.extendSession(context)
    }

    /**
     * Остановка — и по кнопке «Стоп», и по истечении предохранителя.
     *
     * Само закрытие файлов делает сервис, поэтому здесь только команда и
     * страховка: если через [STOP_FALLBACK_MILLIS] сессия в Room всё ещё
     * открыта, значит сервис до неё не добрался (например, не смог подняться без
     * разрешения на локацию), и строку закрываем сами. Незакрытая сессия хуже
     * неточного `sizeBytes`: главный экран показывал бы идущую запись вечно.
     */
    suspend fun stop() {
        val open = sessionDao.observeActive().first()
        if (open == null) {
            settings.setActiveSession(null, null)
            return
        }
        CollectorService.stopSession(context)
        scope.launch {
            delay(STOP_FALLBACK_MILLIS)
            val still = sessionDao.byId(open.id) ?: return@launch
            if (still.endedAt != null) return@launch
            Log.w(TAG, "Сервис не закрыл сессию ${open.id}, закрываем сами")
            finishSession(null, reason = "fallback")
        }
    }

    /**
     * То же, но из места без своего скоупа: сервис финализирует сессию в
     * `onDestroy`, когда его собственный скоуп уже отменяется.
     */
    fun finishSessionAsync(files: SessionFiles?, reason: String) {
        scope.launch { finishSession(files, reason) }
    }

    /**
     * Финализация строки сессии: пути, реальный размер и состав датчиков из
     * манифеста (§5). Идемпотентна — повторный вызов на закрытой сессии ничего
     * не делает.
     */
    suspend fun finishSession(files: SessionFiles?, reason: String) {
        val config = settings.current()
        // Приоритет у идентификатора из закрытых файлов: страховка в [stop] могла
        // успеть закрыть строку раньше, чем сервис досчитал реальный объём, и
        // тогда дописать его надо именно в ту сессию, а не в «текущую».
        val session = files?.sessionId?.let { sessionDao.byId(it) }
            ?: config.activeSessionId?.let { sessionDao.byId(it) }
            ?: sessionDao.observeActive().first()
        settings.setActiveSession(null, null)
        if (session == null) return
        if (session.endedAt != null) {
            if (files != null) {
                sessionDao.update(
                    session.copy(
                        fileDir = files.publishedDir,
                        sizeBytes = files.sizeBytes,
                        sensorManifest = files.manifestJson,
                    ),
                )
            }
            return
        }

        sessionDao.update(
            session.copy(
                endedAt = System.currentTimeMillis(),
                fileDir = files?.publishedDir ?: session.fileDir,
                sizeBytes = files?.sizeBytes ?: session.sizeBytes,
                sensorManifest = files?.manifestJson ?: session.sensorManifest,
            ),
        )
        log(
            type = LogEventType.RECORDING_SESSION_STOPPED,
            sessionId = session.id,
            label = session.label,
            extra = buildMap {
                put("reason", reason)
                files?.let {
                    put("sizeBytes", it.sizeBytes)
                    put("streams", it.streamKeys.size)
                    put("publishedFiles", it.publishedFiles)
                }
            },
        )
    }

    private suspend fun log(
        type: LogEventType,
        sessionId: Long,
        label: SessionLabel,
        extra: Map<String, Any> = emptyMap(),
    ) {
        val now = System.currentTimeMillis()
        val payload = JSONObject().apply {
            put("sessionId", sessionId)
            put("label", label.name)
            extra.forEach { (key, value) -> put(key, value) }
        }
        logEventDao.insert(
            LogEvent(
                type = type,
                eventTime = now,
                receivedTime = now,
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                payloadJson = payload.toString(),
            ),
        )
    }

    private companion object {
        const val TAG = "RecordingSession"

        /**
         * Столько ждём сервис, прежде чем закрыть сессию самостоятельно.
         * С запасом: закрытие включает копирование десятков мегабайт в
         * `Documents/Sesame/` через MediaStore, и торопить его незачем.
         */
        const val STOP_FALLBACK_MILLIS = 20_000L
    }
}
