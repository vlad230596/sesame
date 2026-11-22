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

/**
 * Куда пишется отсчёт. Остался один пассивный слой суток (§4.4): интенсивная
 * запись убрана, но параметр в API записи сохранён, чтобы не трогать всех
 * пишущих разом.
 */
enum class WriteScope { PASSIVE }

/** Заготовка манифеста: постоянная часть и часть, относящаяся к текущему запуску. */
class ManifestDraft(val topLevel: JSONObject, val run: JSONObject)

/**
 * Файловый слой сбора (§5, §6, §9).
 *
 * Единственный поток записи с очередью: потоки датчиков и UI не блокируются, а
 * порядок операций гарантирован самой очередью, без блокировок и без
 * дополнительной синхронизации.
 *
 * Всё состояние — открытые файлы и манифесты — трогается только
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
        val key: String,
        val nanos: Long,
        val values: FloatArray,
        val count: Int,
        val accuracy: Int,
    ) : Task

    private class RowTask(
        val key: String,
        val nanos: Long,
        val cells: Array<String>,
    ) : Task

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

    /**
     * Стык суток пассивного слоя — отметка в журнале событий (§4.4) и выгрузка
     * журнала за закрывшиеся сутки (§7). Зовётся на каждой полуночи, даже если
     * пассивных файлов за сутки не было: тогда [publishedDir] — `null`, а события
     * журнала за эти сутки всё равно могли накопиться.
     *
     * Вызывается из потока записи — обработчик не должен в нём задерживаться.
     */
    @Volatile
    var onPassiveDayRotated: ((finishedDate: LocalDate, publishedDir: String?) -> Unit)? = null

    /**
     * Тик промежуточной публикации текущих суток, раз в
     * [PASSIVE_PUBLISH_INTERVAL_MILLIS]. Журнал событий живёт в Room, а не в этом
     * классе, но публикуется в том же ритме: отдельный таймер ради него дал бы
     * второй источник «когда что лежит в `Documents/Sesame/`».
     *
     * Вызывается из потока записи — обработчик не должен в нём задерживаться.
     */
    @Volatile
    var onPublishTick: (() -> Unit)? = null

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

    private var passive: Target? = null
    private var passiveDate: LocalDate? = null
    private var passiveDayEndMillis: Long = Long.MAX_VALUE

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
        // Поток не прерываем: он может копировать сутки в Documents/Sesame/, и
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

    // [WriteScope] теперь один, параметр оставлен ради совместимости вызовов.
    @Suppress("UNUSED_PARAMETER")
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
        submit(SensorTask(key, elapsedRealtimeNanos, values.copyOf(count), count, accuracy))
    }

    @Suppress("UNUSED_PARAMETER")
    fun writeRow(
        scope: WriteScope,
        key: String,
        elapsedRealtimeNanos: Long,
        cells: Array<String>,
    ) {
        submit(RowTask(key, elapsedRealtimeNanos, cells))
    }

    private fun submit(task: Task) {
        if (!running) {
            droppedSamples.incrementAndGet()
            return
        }
        if (!queue.offer(task)) droppedSamples.incrementAndGet()
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
            runCatching { closePassiveInternal(publish = true) }
            runCatching { heartbeat.persistNow() }
            Log.i(TAG, "Поток записи остановлен, потеряно отсчётов: ${droppedSamples.get()}")
        }
    }

    private fun handle(task: Task) {
        when (task) {
            is SensorTask -> {
                sawDataSinceTick = true
                runCatching {
                    ensurePassive()?.let {
                        writerFor(it, task.key)
                            .appendSensorValues(task.nanos, task.values, task.count, task.accuracy)
                    }
                }.onFailure { Log.w(TAG, "Не удалось записать отсчёт ${task.key}", it) }
            }

            is RowTask -> {
                sawDataSinceTick = true
                runCatching {
                    ensurePassive()?.let {
                        writerFor(it, task.key).appendCells(task.nanos, task.cells)
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
            runCatching { onPublishTick?.invoke() }
                .onFailure { Log.w(TAG, "Обработчик тика публикации упал", it) }
        }
    }

    private fun flushAll() {
        passive?.writers?.values?.forEach { runCatching { it.flush() } }
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
        val finishedDate = DataPaths.localDate(passiveDayEndMillis - 1)
        val closing = passive?.publishedDir
        closePassiveInternal(publish = true)
        passiveDayEndMillis = DataPaths.nextMidnightMillis(now)
        runCatching { onPassiveDayRotated?.invoke(finishedDate, closing) }
            .onFailure { Log.w(TAG, "Обработчик ротации суток упал", it) }
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

    /**
     * Staging — временная площадка, а не второй экземпляр датасета: как только
     * все файлы каталога легли в `Documents/Sesame/`, копия удаляется. Иначе
     * внутреннее хранилище за неделю сбора выросло бы вдвое против §7, а плашка
     * «занято X из 1 ГБ» считала бы одни и те же байты дважды.
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
