package com.vlad230596.sesame.logging

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Куда пишется отсчёт: в пассивный слой суток (§4.4) или в файлы сессии (§4.3). */
enum class WriteScope { PASSIVE, SESSION }

/** Заготовка манифеста: постоянная часть и часть, относящаяся к текущему запуску. */
class ManifestDraft(val topLevel: JSONObject, val run: JSONObject)

/** Что получилось из сессии — идёт в `RecordingSession` (§5). */
data class SessionFiles(
    val sessionId: Long,
    val publishedDir: String,
    val sizeBytes: Long,
    val manifestJson: String,
    val streamKeys: List<String>,
    val publishedFiles: Int,
)

/**
 * Файловый слой сбора (§5, §6, §9).
 *
 * Единственный поток записи с очередью: потоки датчиков и UI не блокируются, а
 * порядок операций гарантирован самой очередью — именно поэтому сброс кольцевого
 * буфера (§4.3) заведомо попадает в файл сессии раньше живых отсчётов, без
 * блокировок и без дополнительной синхронизации.
 *
 * Всё состояние — открытые файлы, кольцевой буфер, манифесты — трогается только
 * из потока записи. Публичные методы лишь кладут задачи в очередь.
 */
@Singleton
class DataFileStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val paths: DataPaths,
    private val publisher: MediaStorePublisher,
    private val heartbeat: CollectorHeartbeat,
) {

    // --- очередь -------------------------------------------------------------------

    private sealed interface Task

    private class SensorTask(
        val scope: WriteScope,
        val key: String,
        val nanos: Long,
        val values: FloatArray,
        val count: Int,
        val accuracy: Int,
    ) : Task

    private class RowTask(
        val scope: WriteScope,
        val key: String,
        val nanos: Long,
        val cells: Array<String>,
    ) : Task

    private class ActionTask(val block: () -> Unit) : Task

    private val queue = LinkedBlockingQueue<Task>(QUEUE_CAPACITY)
    private val droppedSamples = AtomicLong()

    @Volatile
    private var running = false
    private var writerThread: Thread? = null

    /**
     * Колонки CSV по ключу потока; заполняется перед подпиской на датчик.
     * Конкурентная карта, а не `synchronized`: [shutdown] держит монитор класса,
     * пока ждёт поток записи, и блокировка на этой карте была бы взаимной.
     */
    private val columnProviders = ConcurrentHashMap<String, (Int) -> List<String>>()

    @Volatile
    private var passiveManifestProvider: (() -> ManifestDraft)? = null

    /** Стык суток пассивного слоя — отметка в журнале событий (§4.4). */
    @Volatile
    var onPassiveDayRotated: ((publishedDir: String) -> Unit)? = null

    // --- состояние потока записи ---------------------------------------------------

    private class Target(
        val stagingDir: File,
        val publishedDir: String,
        val manifestName: String,
        val manifest: JSONObject,
        val run: JSONObject,
    ) {
        val writers = LinkedHashMap<String, GzipCsvWriter>()
    }

    private class RingEntry(
        val key: String,
        val nanos: Long,
        val values: FloatArray?,
        val count: Int,
        val accuracy: Int,
        val cells: Array<String>?,
    )

    private var passive: Target? = null
    private var passiveDate: LocalDate? = null
    private var passiveDayEndMillis: Long = Long.MAX_VALUE
    private var session: Target? = null
    private var sessionId: Long = 0L

    /**
     * Кольцевой буфер пассивного слоя (§4.3): последние [RING_BUFFER_SECONDS]
     * секунд отсчётов одной хронологической очередью — так сброс в файл сессии
     * сохраняет порядок событий между датчиками, а обрезка окна стоит один
     * взгляд на голову очереди.
     */
    private val ring = ArrayDeque<RingEntry>()

    private var lastFlushAt = 0L
    private var lastPassivePublishAt = 0L
    private var sawDataSinceTick = false

    // --- жизненный цикл ------------------------------------------------------------

    @Synchronized
    fun start() {
        if (running) return
        // Предыдущий поток мог не уложиться в таймаут shutdown и всё ещё
        // дописывать хвост: два потока записи на одни файлы недопустимы.
        writerThread?.takeIf { it.isAlive }?.let { runCatching { it.join(START_JOIN_MILLIS) } }
        running = true
        lastFlushAt = System.currentTimeMillis()
        lastPassivePublishAt = System.currentTimeMillis()
        writerThread = Thread({ writerLoop() }, THREAD_NAME).apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
    }

    /**
     * Останавливает поток записи, дописывает и закрывает все файлы, публикует их
     * в `Documents/Sesame/` (§7). Вызывается из `onDestroy` сервиса, поэтому
     * ожидание ограничено: лучше потерять последние отсчёты, чем получить ANR.
     */
    @Synchronized
    fun shutdown(awaitMillis: Long = DEFAULT_SHUTDOWN_AWAIT_MILLIS) {
        val thread = writerThread ?: return
        running = false
        // Поток не прерываем: он может копировать сессию в Documents/Sesame/, и
        // InterruptedIOException посреди копирования оставил бы половину файла.
        // Цикл сам заметит флаг в пределах POLL_MILLIS.
        runCatching { thread.join(awaitMillis) }
        // Если не уложился — пусть дописывает: поток не демон, процесс его
        // дождётся, а обрывать запись на полуслове хуже, чем задержать выход.
        if (!thread.isAlive) {
            writerThread = null
            queue.clear()
        }
    }

    val isRunning: Boolean get() = running

    fun droppedSampleCount(): Long = droppedSamples.get()

    // --- декларации ----------------------------------------------------------------

    /** Колонки потока известны до первой записи, а число значений — только по факту. */
    fun declareStream(key: String, columnsFor: (Int) -> List<String>) {
        columnProviders[key] = columnsFor
    }

    private fun columnsFor(key: String): (Int) -> List<String> =
        columnProviders[key] ?: DEFAULT_COLUMNS

    /** Манифест суток пассивного слоя строится заново на каждой ротации. */
    fun setPassiveManifestProvider(provider: (() -> ManifestDraft)?) {
        passiveManifestProvider = provider
    }

    // --- запись --------------------------------------------------------------------

    fun writeSensor(
        scope: WriteScope,
        key: String,
        elapsedRealtimeNanos: Long,
        values: FloatArray,
        valueCount: Int,
        accuracy: Int,
    ) {
        val count = valueCount.coerceIn(0, values.size)
        // SensorEvent.values переиспользуется системой — копия обязательна.
        submit(SensorTask(scope, key, elapsedRealtimeNanos, values.copyOf(count), count, accuracy))
    }

    fun writeRow(
        scope: WriteScope,
        key: String,
        elapsedRealtimeNanos: Long,
        cells: Array<String>,
    ) {
        submit(RowTask(scope, key, elapsedRealtimeNanos, cells))
    }

    private fun submit(task: Task) {
        if (!running) {
            droppedSamples.incrementAndGet()
            return
        }
        if (!queue.offer(task)) droppedSamples.incrementAndGet()
    }

    /** Действие в потоке записи: сохраняет порядок относительно потока отсчётов. */
    private fun post(block: () -> Unit): Boolean {
        if (!running) return false
        val accepted = queue.offer(ActionTask(block))
        if (!accepted) Log.w(TAG, "Очередь записи переполнена, действие потеряно")
        return accepted
    }

    // --- сессия --------------------------------------------------------------------

    fun openSession(id: Long, draft: ManifestDraft) {
        post {
            if (session != null) closeSessionInternal(null, null)
            sessionId = id
            session = openTarget(
                stagingDir = paths.sessionStagingDir(id),
                publishedDir = paths.sessionPublishedDir(id),
                manifestName = Manifests.SESSION_FILE,
                draft = draft,
            )
        }
    }

    /**
     * §4.3: при старте интенсивной сессии кольцевой буфер пассивного слоя
     * сбрасывается в файл сессии первым. «Нажал запись, уже сидя в машине» не
     * должно терять момент посадки.
     */
    fun dumpRingBufferIntoSession() {
        post {
            val target = session ?: return@post
            if (ring.isEmpty()) {
                target.run.put("prehistory", JSONObject().put("rows", 0))
                return@post
            }
            val perStream = JSONObject()
            var rows = 0L
            var fromNanos = Long.MAX_VALUE
            var untilNanos = Long.MIN_VALUE
            ring.forEach { entry ->
                val writer = writerFor(target, entry.key)
                if (entry.values != null) {
                    writer.appendSensorValues(entry.nanos, entry.values, entry.count, entry.accuracy)
                } else if (entry.cells != null) {
                    writer.appendCells(entry.nanos, entry.cells)
                }
                rows++
                fromNanos = minOf(fromNanos, entry.nanos)
                untilNanos = maxOf(untilNanos, entry.nanos)
                perStream.put(entry.key, perStream.optLong(entry.key, 0L) + 1L)
            }
            // Границу предыстории пишем в манифест: оффлайн она нужна, чтобы отделить
            // 5 Гц из буфера от 500 Гц живой записи в том же файле.
            target.run.put(
                "prehistory",
                JSONObject().apply {
                    put("windowSeconds", RING_BUFFER_SECONDS)
                    put("rows", rows)
                    put("fromElapsedRealtimeNanos", fromNanos)
                    put("untilElapsedRealtimeNanos", untilNanos)
                    put("rowsByStream", perStream)
                },
            )
        }
    }

    /**
     * Закрывает файлы сессии, дописывает `session.json` и публикует каталог (§6, §7).
     *
     * [onClosed] вызывается всегда — в том числе когда поток записи уже не жив:
     * иначе строка сессии в Room осталась бы навсегда незакрытой, и главный
     * экран показывал бы идущую запись при мёртвом сборе.
     */
    fun closeSession(extras: JSONObject?, onClosed: (SessionFiles?) -> Unit) {
        if (!post { closeSessionInternal(extras, onClosed) }) onClosed(null)
    }

    // --- поток записи --------------------------------------------------------------

    private fun writerLoop() {
        Log.i(TAG, "Поток записи запущен")
        try {
            while (true) {
                val task = try {
                    queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    null
                }
                if (task != null) {
                    handle(task)
                    var drained = 0
                    while (drained < MAX_BATCH) {
                        val next = queue.poll() ?: break
                        handle(next)
                        drained++
                    }
                }
                tick()
                if (!running && queue.isEmpty()) break
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Поток записи упал", error)
        } finally {
            // Файлы закрываются при любом завершении, включая падение.
            runCatching { closeSessionInternal(null, null) }
            runCatching { closePassiveInternal(publish = true) }
            runCatching { heartbeat.persistNow() }
            Log.i(TAG, "Поток записи остановлен, потеряно отсчётов: ${droppedSamples.get()}")
        }
    }

    private fun handle(task: Task) {
        when (task) {
            is ActionTask -> runCatching { task.block() }
                .onFailure { Log.w(TAG, "Действие потока записи упало", it) }

            is SensorTask -> {
                sawDataSinceTick = true
                runCatching {
                    when (task.scope) {
                        WriteScope.PASSIVE -> {
                            ensurePassive()?.let {
                                writerFor(it, task.key)
                                    .appendSensorValues(task.nanos, task.values, task.count, task.accuracy)
                            }
                            remember(
                                RingEntry(task.key, task.nanos, task.values, task.count, task.accuracy, null),
                            )
                        }

                        WriteScope.SESSION -> session?.let {
                            writerFor(it, task.key)
                                .appendSensorValues(task.nanos, task.values, task.count, task.accuracy)
                        }
                    }
                }.onFailure { Log.w(TAG, "Не удалось записать отсчёт ${task.key}", it) }
            }

            is RowTask -> {
                sawDataSinceTick = true
                runCatching {
                    when (task.scope) {
                        WriteScope.PASSIVE -> {
                            ensurePassive()?.let {
                                writerFor(it, task.key).appendCells(task.nanos, task.cells)
                            }
                            remember(RingEntry(task.key, task.nanos, null, 0, 0, task.cells))
                        }

                        WriteScope.SESSION -> session?.let {
                            writerFor(it, task.key).appendCells(task.nanos, task.cells)
                        }
                    }
                }.onFailure { Log.w(TAG, "Не удалось записать строку ${task.key}", it) }
            }
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        if (sawDataSinceTick) {
            sawDataSinceTick = false
            heartbeat.touch(now)
        }
        if (now >= passiveDayEndMillis) rotatePassive(now)
        if (now - lastFlushAt >= GzipCsvWriter.FLUSH_INTERVAL_MILLIS) {
            lastFlushAt = now
            flushAll()
            heartbeat.persistIfDue(now)
        }
        if (now - lastPassivePublishAt >= PASSIVE_PUBLISH_INTERVAL_MILLIS) {
            lastPassivePublishAt = now
            publishPassiveSnapshot()
        }
    }

    private fun flushAll() {
        passive?.writers?.values?.forEach { runCatching { it.flush() } }
        session?.writers?.values?.forEach { runCatching { it.flush() } }
    }

    private fun remember(entry: RingEntry) {
        ring.addLast(entry)
        val newest = entry.nanos
        while (ring.isNotEmpty() &&
            (newest - ring.first().nanos > RING_BUFFER_NANOS || ring.size > RING_BUFFER_MAX_ENTRIES)
        ) {
            ring.removeFirst()
        }
    }

    // --- цели записи ---------------------------------------------------------------

    private fun writerFor(target: Target, key: String): GzipCsvWriter =
        target.writers.getOrPut(key) {
            GzipCsvWriter(File(target.stagingDir, "$key.csv.gz"), columnsFor(key))
        }

    /**
     * Манифест переживает перезапуск процесса: старый файл дочитывается, а новый
     * запуск добавляется в `runs` со своим снимком часов. Без этого сырые
     * `elapsedRealtimeNanos`, записанные до перезагрузки и после, было бы нечем
     * различить (§6).
     */
    private fun openTarget(
        stagingDir: File,
        publishedDir: String,
        manifestName: String,
        draft: ManifestDraft,
    ): Target {
        stagingDir.mkdirs()
        val manifestFile = File(stagingDir, manifestName)
        val manifest = runCatching {
            manifestFile.takeIf { it.isFile }?.readText()?.let { JSONObject(it) }
        }.getOrNull() ?: JSONObject()

        draft.topLevel.keys().forEach { key -> manifest.put(key, draft.topLevel.get(key)) }
        manifest.put("schemaVersion", Manifests.SCHEMA_VERSION)
        manifest.put("publishedDir", publishedDir)

        val run = draft.run
        run.put("openedAtMillis", System.currentTimeMillis())
        run.put("clock", Manifests.clockSnapshot(context))

        val runs = manifest.optJSONArray("runs") ?: JSONArray()
        runs.put(run)
        manifest.put("runs", runs)

        val target = Target(stagingDir, publishedDir, manifestName, manifest, run)
        writeManifest(target)
        return target
    }

    private fun writeManifest(target: Target) {
        runCatching {
            val streams = JSONArray()
            target.writers.forEach { (key, writer) ->
                streams.put(
                    JSONObject().apply {
                        put("stream", key)
                        put("file", writer.file.name)
                        put("rowsSinceOpen", writer.rowsWritten)
                        put("sizeBytes", writer.sizeBytes())
                        put("columns", Manifests.strings(writer.columns.orEmpty()))
                    },
                )
            }
            target.manifest.put("streams", streams)
            target.manifest.put("updatedAtMillis", System.currentTimeMillis())
            target.manifest.put("droppedSamples", droppedSamples.get())
            File(target.stagingDir, target.manifestName).writeText(target.manifest.toString(2))
        }.onFailure { Log.w(TAG, "Не удалось записать ${target.manifestName}", it) }
    }

    private fun ensurePassive(): Target? {
        passive?.let { return it }
        val provider = passiveManifestProvider ?: return null
        val now = System.currentTimeMillis()
        val date = DataPaths.localDate(now)
        val target = runCatching {
            openTarget(
                stagingDir = paths.passiveStagingDir(date),
                publishedDir = paths.passivePublishedDir(date),
                manifestName = Manifests.DAY_FILE,
                draft = provider(),
            )
        }.onFailure { Log.e(TAG, "Не удалось открыть сутки пассивного слоя", it) }.getOrNull()
        passive = target
        passiveDate = date
        passiveDayEndMillis = DataPaths.nextMidnightMillis(now)
        return target
    }

    /** §5: ротация пассивных файлов — посуточная. */
    private fun rotatePassive(now: Long) {
        val closing = passive?.publishedDir
        closePassiveInternal(publish = true)
        passiveDayEndMillis = DataPaths.nextMidnightMillis(now)
        if (closing != null) runCatching { onPassiveDayRotated?.invoke(closing) }
        // Новые сутки откроются на первом же отсчёте: пустой каталог заводить незачем.
    }

    private fun closePassiveInternal(publish: Boolean) {
        val target = passive ?: return
        passive = null
        passiveDate = null
        target.run.put("closedAtMillis", System.currentTimeMillis())
        target.writers.values.forEach { runCatching { it.close() } }
        writeManifest(target)
        if (publish) {
            val published = publisher.publishDirectory(target.stagingDir, target.publishedDir)
            Log.i(TAG, "Пассивные сутки опубликованы: $published файлов в ${target.publishedDir}")
            discardStagingIfPublished(target.stagingDir, published)
        }
    }

    /**
     * Промежуточная публикация текущих суток: §7 требует, чтобы переустановка не
     * уносила датасет, а сутки закрываются только в полночь. Файл маленький
     * (порядка 2 МБ в сутки), поэтому копия целиком раз в
     * [PASSIVE_PUBLISH_INTERVAL_MILLIS] дешевле, чем риск потерять день.
     */
    private fun publishPassiveSnapshot() {
        val target = passive ?: return
        flushAll()
        writeManifest(target)
        publisher.publishDirectory(target.stagingDir, target.publishedDir)
    }

    private fun closeSessionInternal(extras: JSONObject?, onClosed: ((SessionFiles?) -> Unit)?) {
        val target = session
        if (target == null) {
            onClosed?.invoke(null)
            return
        }
        session = null
        val id = sessionId
        sessionId = 0L

        extras?.keys()?.forEach { key -> target.run.put(key, extras.get(key)) }
        target.run.put("closedAtMillis", System.currentTimeMillis())
        target.writers.values.forEach { runCatching { it.close() } }
        writeManifest(target)

        val published = publisher.publishDirectory(target.stagingDir, target.publishedDir)
        val sizeBytes = target.stagingDir.listFiles()
            ?.filter { it.isFile }
            ?.sumOf { it.length() }
            ?: 0L
        discardStagingIfPublished(target.stagingDir, published)

        onClosed?.invoke(
            SessionFiles(
                sessionId = id,
                publishedDir = target.publishedDir,
                sizeBytes = sizeBytes,
                manifestJson = target.manifest.toString(),
                streamKeys = target.writers.keys.toList(),
                publishedFiles = published,
            ),
        )
    }

    /**
     * Staging — временная площадка, а не второй экземпляр датасета: как только
     * все файлы каталога легли в `Documents/Sesame/`, копия удаляется. Иначе
     * внутреннее хранилище за неделю сбора выросло бы вдвое против §7, а плашка
     * «занято X из 10 ГБ» считала бы одни и те же байты дважды.
     *
     * Если опубликовалось не всё — staging остаётся: это единственный уцелевший
     * экземпляр, и терять его из-за сбоя MediaStore нельзя.
     */
    private fun discardStagingIfPublished(stagingDir: File, published: Int) {
        val files = stagingDir.listFiles()?.filter { it.isFile }.orEmpty()
        if (files.isEmpty() || published < files.size) {
            if (files.isNotEmpty()) {
                Log.w(TAG, "Опубликовано $published из ${files.size}, staging оставлен: $stagingDir")
            }
            return
        }
        runCatching { stagingDir.deleteRecursively() }
            .onFailure { Log.w(TAG, "Не удалось убрать staging $stagingDir", it) }
    }

    companion object {
        private const val TAG = "DataFileStore"
        private const val THREAD_NAME = "sesame-writer"

        /** §4.3: 60 секунд предыстории. */
        const val RING_BUFFER_SECONDS: Int = 60
        private const val RING_BUFFER_NANOS: Long = RING_BUFFER_SECONDS * 1_000_000_000L

        /**
         * Страховка от роста памяти, если пассивные частоты выкрутят в настройках:
         * 60 с при разумных частотах — это сотни записей, а не десятки тысяч.
         */
        private const val RING_BUFFER_MAX_ENTRIES = 40_000

        /** §7: текущие сутки публикуются, не дожидаясь полуночи. */
        const val PASSIVE_PUBLISH_INTERVAL_MILLIS = 15 * 60_000L

        private const val QUEUE_CAPACITY = 60_000
        private const val MAX_BATCH = 4_096
        private const val POLL_MILLIS = 200L
        private const val DEFAULT_SHUTDOWN_AWAIT_MILLIS = 2_500L
        private const val START_JOIN_MILLIS = 2_000L

        private val DEFAULT_COLUMNS: (Int) -> List<String> = { count ->
            listOf(SensorStreams.COLUMN_TIME, SensorStreams.COLUMN_ACCURACY) +
                (0 until count).map { "v$it" }
        }
    }
}
