package com.vlad230596.sesame.session

import android.os.SystemClock
import com.vlad230596.sesame.data.LogEventType
import com.vlad230596.sesame.data.SessionLabel
import com.vlad230596.sesame.data.dao.LogEventDao
import com.vlad230596.sesame.data.dao.RecordingSessionDao
import com.vlad230596.sesame.data.entity.LogEvent
import com.vlad230596.sesame.data.entity.RecordingSession
import com.vlad230596.sesame.data.prefs.SettingsRepository
import com.vlad230596.sesame.sensors.IntensiveRecorder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
 * Тонкая обёртка над Room и [IntensiveRecorder]. Реальная запись датчиков в v0
 * этой задачей не делается — [IntensiveRecorder] пока заглушка, — но метка
 * сессии, её время и запись в журнале появляются по-настоящему: разметка нужна
 * ровно так же, как и сами данные.
 */
@Singleton
class RecordingSessionController @Inject constructor(
    private val sessionDao: RecordingSessionDao,
    private val logEventDao: LogEventDao,
    private val settings: SettingsRepository,
    private val recorder: IntensiveRecorder,
) {

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
        sessionDao.byId(id)?.let { sessionDao.update(it.copy(fileDir = "$SESSIONS_DIR/$id")) }
        settings.setActiveSession(id, now + config.recordingDurationMinutes * 60_000L)
        log(LogEventType.RECORDING_SESSION_STARTED, id, label)
        recorder.start(label)
        return id
    }

    /** «Продлить» на экране сессии — сдвигает предохранитель от текущего момента. */
    suspend fun extend(minutes: Int) {
        val config = settings.current()
        val id = config.activeSessionId ?: return
        val base = maxOf(config.activeSessionPlannedEndAt ?: 0L, System.currentTimeMillis())
        settings.setActiveSession(id, base + minutes * 60_000L)
        recorder.extend(minutes)
    }

    /**
     * Остановка — и по кнопке «Стоп», и по истечении предохранителя.
     * Идемпотентна: повторный вызов на уже закрытой сессии ничего не делает.
     */
    suspend fun stop() {
        val id = settings.current().activeSessionId
        val session = id?.let { sessionDao.byId(it) } ?: sessionDao.observeActive().first()
        settings.setActiveSession(null, null)
        recorder.stop()
        if (session == null || session.endedAt != null) return
        sessionDao.update(session.copy(endedAt = System.currentTimeMillis()))
        log(LogEventType.RECORDING_SESSION_STOPPED, session.id, session.label)
    }

    private suspend fun log(type: LogEventType, sessionId: Long, label: SessionLabel) {
        val now = System.currentTimeMillis()
        logEventDao.insert(
            LogEvent(
                type = type,
                eventTime = now,
                receivedTime = now,
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                payloadJson = """{"sessionId":$sessionId,"label":"${label.name}"}""",
            ),
        )
    }

    private companion object {
        /** §5: `Documents/Sesame/sessions/<session-id>/`. */
        const val SESSIONS_DIR = "Documents/Sesame/sessions"
    }
}
