package com.vlad230596.sesame.logging

import android.util.Log
import com.vlad230596.sesame.data.dao.LogEventDao
import com.vlad230596.sesame.data.dao.PassageLabelDao
import com.vlad230596.sesame.data.dao.RecordingSessionDao
import com.vlad230596.sesame.data.entity.LogEvent
import com.vlad230596.sesame.data.entity.PassageLabel
import com.vlad230596.sesame.data.entity.RecordingSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Выгрузка журнала событий, меток проездов и сессий из Room в
 * `Documents/Sesame/journal/` (§5, §7).
 *
 * **Зачем.** Потоки датчиков публикуются файлами, а журнал, разметка и строки
 * сессий жили только в приватной базе. У release-сборки нет ни `run-as`, ни
 * adb backup, так что забрать их с телефона было нельзя, а переустановка их
 * стирала — то есть без этого класса датасет терял ровно ту часть, ради
 * которой собирается: события с задержками доставки (§4.4) и метки (§4.5).
 *
 * Раскладка:
 *
 * - `journal/<YYYY-MM-DD>/events.csv.gz` — события, чей `eventTime` приходится
 *   на эти местные сутки (та же зона, что у пассивного слоя, [DataPaths.localDate]);
 * - `journal/labels.csv.gz`, `journal/sessions.csv.gz` — полные снимки таблиц.
 *   Снимок, а не дописывание: метки подтверждаются и правятся задним числом, и
 *   файл обязан отражать последнее состояние, а не историю правок.
 *
 * **Почему файлы пересобираются целиком.** Источник истины — Room, объём —
 * тысячи строк, а [MediaStorePublisher] перезаписывает файл на месте. Пересборка
 * из базы дешевле и надёжнее, чем учёт «что уже выгружено»: она сама чинит
 * любой пропущенный запуск.
 *
 * Когда публикуется — решает [com.vlad230596.sesame.service.CollectorService]:
 * при старте сервиса вся история ([publishAll]), по тику промежуточной
 * публикации пассивного слоя ([publishRecent]) и на суточной ротации
 * ([publishDay]). Всё идёт на IO и под одним [Mutex]: staging-файлы общие, и
 * две параллельные пересборки одного файла дали бы мусор.
 */
@Singleton
class JournalPublisher @Inject constructor(
    private val logEventDao: LogEventDao,
    private val passageLabelDao: PassageLabelDao,
    private val sessionDao: RecordingSessionDao,
    private val paths: DataPaths,
    private val publisher: MediaStorePublisher,
) {

    private val mutex = Mutex()

    /** Начало последней публикации; от него ищутся запоздавшие события. */
    private var lastPublishStartedAt: Long? = null

    /**
     * Все сутки, в которых есть события, плюс оба снимка. Догоняет историю,
     * накопленную до появления выгрузки, и восстанавливает `Documents/Sesame/`
     * после переустановки — в той мере, в какой база ещё жива.
     */
    suspend fun publishAll() = locked("всей истории") { startedAt ->
        logEventDao.allChronological()
            .groupBy { DataPaths.localDate(it.eventTime) }
            .forEach { (date, events) -> publishEvents(date, events) }
        publishSnapshots()
        lastPublishStartedAt = startedAt
    }

    /**
     * Текущие сутки и оба снимка — раз в [DataFileStore.PASSIVE_PUBLISH_INTERVAL_MILLIS].
     *
     * Плюс сутки запоздавших событий: геофенс или переход активности, доставленный
     * после полуночи, относится ко вчерашнему файлу, а вчерашние сутки уже закрыты.
     */
    suspend fun publishRecent() = locked("текущих суток") { startedAt ->
        val today = DataPaths.localDate(startedAt)
        val earliest = lastPublishStartedAt
            ?.let { logEventDao.earliestEventTimeReceivedSince(it - RECEIVE_SLACK_MILLIS) }
            ?.let { DataPaths.localDate(it) }
            // Сбитые часы не должны превращать тик в пересборку всего журнала.
            ?.coerceIn(today.minusDays(MAX_LATE_DAYS), today)
            ?: today
        var date = earliest
        while (!date.isAfter(today)) {
            publishDayEvents(date)
            date = date.plusDays(1)
        }
        publishSnapshots()
        lastPublishStartedAt = startedAt
    }

    /** §5: на суточной ротации — закрывшиеся сутки целиком. */
    suspend fun publishDay(date: LocalDate) = locked("суток $date") {
        publishDayEvents(date)
    }

    // --- внутреннее ----------------------------------------------------------------

    private suspend fun locked(what: String, block: suspend (startedAt: Long) -> Unit) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching { block(System.currentTimeMillis()) }
                    .onFailure { Log.w(TAG, "Не удалось опубликовать журнал: $what", it) }
            }
        }
    }

    private suspend fun publishDayEvents(date: LocalDate) {
        val from = DataPaths.startOfDayMillis(date)
        val until = DataPaths.startOfDayMillis(date.plusDays(1))
        publishEvents(date, logEventDao.between(from, until))
    }

    /** Пустые сутки не публикуются: файл без строк ничего не говорит. */
    private fun publishEvents(date: LocalDate, events: List<LogEvent>) {
        if (events.isEmpty()) return
        val file = File(paths.journalDayStagingDir(date), EVENTS_FILE)
        writeAndPublish(file, paths.journalDayPublishedDir(date), EVENT_COLUMNS, events) { event ->
            listOf(
                event.id.toString(),
                event.type.name,
                event.eventTime.toString(),
                event.receivedTime.toString(),
                (event.receivedTime - event.eventTime).toString(),
                event.elapsedRealtimeNanos.toString(),
                event.payloadJson.orEmpty(),
            )
        }
    }

    private suspend fun publishSnapshots() {
        val labels = passageLabelDao.all()
        writeAndPublish(
            File(paths.journalStagingDir, LABELS_FILE),
            paths.journalPublishedDir,
            LABEL_COLUMNS,
            labels,
        ) { label -> labelCells(label) }

        val sessions = sessionDao.all()
        writeAndPublish(
            File(paths.journalStagingDir, SESSIONS_FILE),
            paths.journalPublishedDir,
            SESSION_COLUMNS,
            sessions,
        ) { session -> sessionCells(session) }
    }

    private fun labelCells(label: PassageLabel): List<String> = listOf(
        label.id.toString(),
        label.timestamp.toString(),
        label.barrierId?.toString().orEmpty(),
        label.direction.name,
        label.mode.name,
        label.source.name,
        label.outcome?.name.orEmpty(),
        label.discarded.toString(),
        label.suspicious.toString(),
        label.note.orEmpty(),
        label.confirmed.toString(),
    )

    private fun sessionCells(session: RecordingSession): List<String> = listOf(
        session.id.toString(),
        session.startedAt.toString(),
        session.endedAt?.toString().orEmpty(),
        session.label.name,
        session.sensorManifest.orEmpty(),
        session.fileDir.orEmpty(),
        session.sizeBytes.toString(),
        session.shared.toString(),
    )

    /**
     * Пишет файл в staging одним gzip-членом и публикует его. [GzipCsvWriter]
     * здесь не подходит: он заточен под потоки датчиков — первой колонкой всегда
     * пишет `elapsedRealtimeNanos` и дописывает файл, а не пересоздаёт.
     *
     * Staging-копия после успешной публикации удаляется: это не второй экземпляр
     * данных, а черновик, который в любой момент пересобирается из Room.
     */
    private fun <T> writeAndPublish(
        file: File,
        publishedDir: String,
        columns: List<String>,
        rows: List<T>,
        cellsOf: (T) -> List<String>,
    ) {
        file.parentFile?.mkdirs()
        val gzip = GZIPOutputStream(file.outputStream().buffered(BUFFER_BYTES))
        gzip.bufferedWriter(StandardCharsets.UTF_8).use { out ->
            out.write(columns.joinToString(","))
            out.write("\n")
            val line = StringBuilder(256)
            rows.forEach { row ->
                line.setLength(0)
                cellsOf(row).forEachIndexed { index, cell ->
                    if (index > 0) line.append(',')
                    appendEscaped(line, cell)
                }
                line.append('\n')
                out.write(line.toString())
            }
        }
        if (publisher.publish(file, publishedDir)) {
            runCatching { file.delete() }
            file.parentFile?.takeIf { it != paths.journalStagingDir }?.let { dir ->
                if (dir.list().isNullOrEmpty()) runCatching { dir.delete() }
            }
        } else {
            Log.w(TAG, "Не удалось опубликовать ${file.name} в $publishedDir, staging оставлен")
        }
    }

    /**
     * RFC 4180: поле в кавычках, если в нём запятая, кавычка или перевод строки;
     * кавычки удваиваются. `payload_json` и `sensor_manifest` — JSON, там всё это есть.
     */
    private fun appendEscaped(line: StringBuilder, cell: String) {
        if (cell.isEmpty()) return
        val needsQuotes = cell.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuotes) {
            line.append(cell)
            return
        }
        line.append('"')
        for (ch in cell) {
            if (ch == '"') line.append('"')
            line.append(ch)
        }
        line.append('"')
    }

    private companion object {
        const val TAG = "JournalPublisher"

        const val EVENTS_FILE = "events.csv.gz"
        const val LABELS_FILE = "labels.csv.gz"
        const val SESSIONS_FILE = "sessions.csv.gz"

        /**
         * `receivedTime` ставится до асинхронной вставки в Room, так что событие
         * может лечь в базу чуть позже начала предыдущей публикации, имея более
         * раннее `receivedTime`. Минута запаса это покрывает.
         */
        const val RECEIVE_SLACK_MILLIS = 60_000L

        /** Дальше этого запоздавшие события догоняет только [publishAll] при старте. */
        const val MAX_LATE_DAYS = 7L

        const val BUFFER_BYTES = 16 * 1024

        val EVENT_COLUMNS = listOf(
            "id",
            "type",
            "event_time_millis",
            "received_time_millis",
            "delay_millis",
            "elapsed_realtime_nanos",
            "payload_json",
        )

        val LABEL_COLUMNS = listOf(
            "id",
            "timestamp",
            "barrier_id",
            "direction",
            "mode",
            "source",
            "outcome",
            "discarded",
            "suspicious",
            "note",
            "confirmed",
        )

        val SESSION_COLUMNS = listOf(
            "id",
            "started_at",
            "ended_at",
            "label",
            "sensor_manifest",
            "file_dir",
            "size_bytes",
            "shared",
        )
    }
}
