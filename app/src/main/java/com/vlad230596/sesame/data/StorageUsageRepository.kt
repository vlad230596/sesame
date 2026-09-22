package com.vlad230596.sesame.data

import com.vlad230596.sesame.logging.DataPaths
import com.vlad230596.sesame.logging.MediaStorePublisher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Занятый объём для плашки «занято X из 10 ГБ» (§4.7, §7).
 *
 * Считается по файлам, а не по сумме `sizeBytes` в Room: в Room попадают только
 * интенсивные сессии, а основную часть датасета за неделю даёт пассивный слой,
 * которого там нет вовсе. Кроме того, запись в Room происходит один раз при
 * закрытии сессии и после ручной чистки файлов с компьютера разойдётся с
 * реальностью — а плашка существует ровно для того, чтобы показывать реальность.
 *
 * Источников два: опубликованное в `Documents/Sesame/` через `MediaStore` и то,
 * что ещё лежит в staging (текущие сутки и идущая сессия).
 */
@Singleton
class StorageUsageRepository @Inject constructor(
    private val publisher: MediaStorePublisher,
    private val paths: DataPaths,
) {

    /** Опрос: на файлы нет ни `Flow`, ни наблюдателя, а частота тут не нужна. */
    fun observeUsedBytes(): Flow<Long> = flow {
        while (true) {
            emit(usedBytes())
            delay(POLL_INTERVAL_MILLIS)
        }
    }.flowOn(Dispatchers.IO)

    fun usedBytes(): Long = publisher.usedBytes() + directorySize(paths.stagingRoot)

    private fun directorySize(dir: File): Long = runCatching {
        if (!dir.isDirectory) return@runCatching 0L
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    private companion object {
        const val POLL_INTERVAL_MILLIS = 30_000L
    }
}
