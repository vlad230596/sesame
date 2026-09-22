package com.vlad230596.sesame.ui.common

import android.content.Intent
import com.vlad230596.sesame.data.dao.RecordingSessionDao
import com.vlad230596.sesame.logging.SessionExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Готовый share sheet и список сессий, которые он выгружает (§7). */
data class ShareRequest(
    val intent: Intent,
    val sessionIds: List<Long>,
    /** Что именно уходит — для подписи под кнопкой и для снекбара. */
    val description: String = "",
)

/** Чем кончилась подготовка архива. */
sealed interface ArchiveResult {
    /** Выгружать нечего: всё уже отмечено выгруженным. */
    data object Empty : ArchiveResult

    /** Сессии есть, а файлов под ними нет — чистили руками с компьютера. */
    data object NoFiles : ArchiveResult

    data class Ready(val request: ShareRequest) : ArchiveResult
}

/**
 * «Поделиться непошаренным» (§7) — отправка самих файлов.
 *
 * Основной способ забрать данные остаётся прежним: USB, файлы лежат в
 * `Documents/Sesame/` и видны с компьютера без дополнительных действий. Share
 * sheet — второй путь, и он отдаёт архив с потоками датчиков и манифестами, а не
 * их опись.
 *
 * Общий для истории и настроек: на макете кнопка экспорта есть на обоих экранах,
 * а «выгрузить» обязано означать ровно одно и то же — включая то, какие сессии
 * попадут в архив и когда они станут помечены выгруженными.
 */
@Singleton
class ArchiveShareUseCase @Inject constructor(
    private val sessionDao: RecordingSessionDao,
    private val exporter: SessionExporter,
) {

    /** Сколько сессий ждут выгрузки — для подписи на кнопке. */
    suspend fun unsharedCount(): Int = sessionDao.unshared().size

    /**
     * Собирает архив. Сборка идёт в IO: сотни мегабайт копируются не мгновенно,
     * и держать на это UI-поток нельзя.
     */
    suspend fun prepare(): ArchiveResult {
        val unshared = sessionDao.unshared()
        if (unshared.isEmpty()) return ArchiveResult.Empty
        val bundle = withContext(Dispatchers.IO) { exporter.buildBundle(unshared) }
            ?: return ArchiveResult.NoFiles
        return ArchiveResult.Ready(
            ShareRequest(
                intent = exporter.shareIntent(bundle),
                sessionIds = bundle.sessionIds,
                description = "${bundle.fileName} · ${bundle.fileCount} " +
                    "${plural(bundle.fileCount.toLong(), "файл", "файла", "файлов")} · " +
                    formatBytes(bundle.sizeBytes),
            ),
        )
    }

    /**
     * Отметить выгруженным — **только** после того, как share sheet
     * действительно открылся.
     *
     * Иначе одна осечка интента навсегда пометила бы сессии выгруженными, и в
     * следующий раз кнопка их не предложила бы — при том, что данные никуда
     * не ушли.
     */
    suspend fun markShared(sessionIds: List<Long>) = sessionDao.markShared(sessionIds)
}
