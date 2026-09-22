package com.vlad230596.sesame.data

import android.os.SystemClock
import android.util.Log
import com.vlad230596.sesame.data.dao.LogEventDao
import com.vlad230596.sesame.data.entity.LogEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Запись в журнал событий (§5, §6) из мест, у которых нет своего скоупа: сервиса,
 * приёмников, коллекторов датчиков.
 *
 * Три времени пишутся сразу (§6): [LogEvent.eventTime] — когда событие
 * произошло, [LogEvent.receivedTime] — когда приложение о нём узнало,
 * [LogEvent.elapsedRealtimeNanos] — сырое монотонное время для сопоставления с
 * потоками датчиков. Разница между первыми двумя и есть измеренная задержка
 * доставки фоновых событий, ради которой §4.4 и заводит журнал.
 *
 * Скоуп живёт всё время процесса намеренно: журнал должен дописаться даже если
 * сервис в этот момент останавливается.
 */
@Singleton
class CollectorJournal @Inject constructor(
    private val logEventDao: LogEventDao,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * @param eventTime когда событие произошло; по умолчанию — «сейчас», то есть
     *        задержка доставки нулевая, что верно для событий, которые приложение
     *        порождает само.
     */
    fun log(
        type: LogEventType,
        payload: Map<String, Any?> = emptyMap(),
        eventTime: Long = System.currentTimeMillis(),
        elapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos(),
    ) {
        val receivedTime = System.currentTimeMillis()
        val json = payload
            .takeIf { it.isNotEmpty() }
            ?.let { values ->
                runCatching {
                    JSONObject().apply {
                        values.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
                    }.toString()
                }.getOrNull()
            }
        scope.launch {
            runCatching {
                logEventDao.insert(
                    LogEvent(
                        type = type,
                        eventTime = eventTime,
                        receivedTime = receivedTime,
                        elapsedRealtimeNanos = elapsedRealtimeNanos,
                        payloadJson = json,
                    ),
                )
            }.onFailure { Log.w(TAG, "Не удалось записать событие $type", it) }
        }
    }

    private companion object {
        const val TAG = "CollectorJournal"
    }
}
