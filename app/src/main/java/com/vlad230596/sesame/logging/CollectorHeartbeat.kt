package com.vlad230596.sesame.logging

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * «Последнее фоновое событие N минут назад» (§4.7).
 *
 * Это единственный индикатор того, что сбор данных не умер, поэтому считать его
 * только по журналу событий в Room нельзя: журнал наполняется редкими событиями
 * (Bluetooth, экран, геофенсы), и ночью в покое он молчит часами при совершенно
 * живом сборе. Честный ответ — момент, когда поток записи последний раз положил
 * на диск отсчёт датчика.
 *
 * Значение живёт в памяти процесса (сервис и UI — один процесс) и раз в минуту
 * оседает в файл, чтобы переживать перезапуск процесса: иначе после рестарта
 * главный экран показывал бы «событий ещё не было» при работающем сборе.
 */
@Singleton
class CollectorHeartbeat @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val file: File get() = File(context.filesDir, FILE_NAME)

    private val _lastSampleAt = MutableStateFlow(readPersisted())
    val lastSampleAt: StateFlow<Long?> = _lastSampleAt.asStateFlow()

    private var persistedAt: Long = 0L

    /** Вызывается из потока записи раз на пачку отсчётов, а не на каждый отсчёт. */
    fun touch(nowMillis: Long = System.currentTimeMillis()) {
        _lastSampleAt.value = nowMillis
    }

    /** Вызывается из потока записи на каждом флаше; пишет файл не чаще раза в минуту. */
    fun persistIfDue(nowMillis: Long = System.currentTimeMillis()) {
        val value = _lastSampleAt.value ?: return
        if (nowMillis - persistedAt < PERSIST_INTERVAL_MILLIS) return
        persistedAt = nowMillis
        persist(value)
    }

    fun persistNow() {
        _lastSampleAt.value?.let { persist(it) }
    }

    private fun persist(value: Long) {
        runCatching { file.writeText(value.toString()) }
    }

    private fun readPersisted(): Long? = runCatching {
        file.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
    }.getOrNull()

    private companion object {
        const val FILE_NAME = "collector_heartbeat"
        const val PERSIST_INTERVAL_MILLIS = 60_000L
    }
}
