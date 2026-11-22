package com.vlad230596.sesame.logging

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.vlad230596.sesame.data.CollectorJournal
import com.vlad230596.sesame.data.LogEventType
import com.vlad230596.sesame.data.prefs.SesameSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Файл датасета в `Documents/Sesame/`, как его видит `MediaStore`. */
data class StoredFile(val dir: String, val name: String, val sizeBytes: Long, val modifiedMillis: Long)

/** Каталог датасета: сутки пассивного слоя, сессия, журнал. */
data class StoredDir(val path: String, val files: List<StoredFile>) {
    val sizeBytes: Long get() = files.sumOf { it.sizeBytes }
    val thinned: Boolean get() = files.any { it.name == RetentionPolicy.THINNED_MARKER }
}

/**
 * Что прореживать и что удалять (§7) — чистая логика, без `MediaStore`.
 *
 * Порядок: сначала прореживание, потом удаление. Прореживание старых суток —
 * датчики до той частоты, которая и была заказана, — освобождает в разы больше,
 * чем стоит, и ничего не теряет для задач приложения: поездки, парковки и
 * движение видны и на 5 Гц. Удаление — только если и после него не влезли в
 * лимит, и всегда с самых старых.
 *
 * Никогда не трогаются: журнал (он маленький и это единственная разметка) и
 * последние [KEEP_FULL_DAYS] суток — их ещё дописывает сбор и смотрят глазами.
 */
object RetentionPolicy {
    const val THINNED_MARKER = "thinned.json"

    /** Сегодня и вчера остаются как записаны. */
    const val KEEP_FULL_DAYS = 2L

    /** Удаляем до 90 % лимита, а не до самого лимита: иначе чистка шла бы каждые сутки. */
    const val DELETE_TARGET_FRACTION = 0.9

    private const val ROOT = GzipCsvWriter.ROOT_RELATIVE_PATH
    private val PASSIVE_DAY = Regex("""^$ROOT/passive/(\d{4}-\d{2}-\d{2})/?$""")
    private val SESSION = Regex("""^$ROOT/sessions/[^/]+/?$""")

    /** Потоки, которые не прореживаются: редкие события, где каждая строка — факт. */
    private val KEEP_ALL_ROWS = setOf("location", "steps", "step_detector", "labels", "events")

    fun passiveDate(path: String): LocalDate? =
        PASSIVE_DAY.find(path)?.groupValues?.get(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    fun isSession(path: String): Boolean = SESSION.matches(path)

    /** Каталоги, которые можно трогать: старые сутки и сессии, не журнал. */
    fun isAged(path: String, today: LocalDate): Boolean {
        passiveDate(path)?.let { return it <= today.minusDays(KEEP_FULL_DAYS) }
        return isSession(path)
    }

    /**
     * Минимальный интервал между строками потока при прореживании, наносекунды;
     * `null` — поток не трогать.
     */
    fun thinIntervalNanos(dirPath: String, fileName: String): Long? {
        if (!fileName.endsWith(".csv.gz")) return null
        val stream = fileName.removeSuffix(".csv.gz")
        if (stream in KEEP_ALL_ROWS) return null
        val hz = when {
            passiveDate(dirPath) != null -> when (stream) {
                SensorStreams.ACCELEROMETER -> SesameSettings.MIN_ACCELEROMETER_HZ
                SensorStreams.BAROMETER -> SesameSettings.MIN_BAROMETER_HZ
                else -> return null
            }
            // Сессии — бывшая интенсивная запись: все датчики на 50–200 Гц.
            isSession(dirPath) -> SesameSettings.MIN_ACCELEROMETER_HZ
            else -> return null
        }
        return SampleDecimator.intervalNanosForHz(hz)
    }

    fun dirsToThin(dirs: List<StoredDir>, today: LocalDate): List<StoredDir> =
        dirs.filter { isAged(it.path, today) && !it.thinned }
            .filter { dir -> dir.files.any { thinIntervalNanos(dir.path, it.name) != null } }
            .sortedBy { ageKey(it) }

    /**
     * Что удалить, чтобы общий объём стал не больше [DELETE_TARGET_FRACTION] от
     * [limitBytes]. Пусто, если лимит не превышен.
     */
    fun dirsToDelete(dirs: List<StoredDir>, today: LocalDate, limitBytes: Long, extraBytes: Long = 0): List<StoredDir> {
        var total = dirs.sumOf { it.sizeBytes } + extraBytes
        if (total <= limitBytes) return emptyList()
        val target = (limitBytes * DELETE_TARGET_FRACTION).toLong()
        val result = mutableListOf<StoredDir>()
        for (dir in dirs.filter { isAged(it.path, today) }.sortedBy { ageKey(it) }) {
            if (total <= target) break
            result += dir
            total -= dir.sizeBytes
        }
        return result
    }

    /** Старшинство каталога: сутки — по дате, сессия — по самому старому файлу. */
    private fun ageKey(dir: StoredDir): Long =
        passiveDate(dir.path)?.let { it.toEpochDay() * 86_400_000L }
            ?: dir.files.minOfOrNull { it.modifiedMillis }
            ?: Long.MAX_VALUE
}

/**
 * Держит датасет в `Documents/Sesame/` в пределах [SesameSettings.STORAGE_LIMIT_BYTES]
 * (§7) по [RetentionPolicy].
 *
 * Видны и правятся только собственные файлы приложения: всё, что осталось от
 * прошлой установки, `MediaStore` нам не покажет — это не ошибка.
 *
 * **Переживает обрыв.** Перезапись файла в `MediaStore` — не атомарна: процесс,
 * убитый посреди копирования, оставил бы обрезанный файл суток. Поэтому
 * прореженная копия сначала целиком ложится в `filesDir/thin/…/<имя>.ready`, и
 * только потом переписывается поверх оригинала. Пока `.ready` лежит, копия
 * считается недоставленной: следующий запуск переписывает её заново — это
 * идемпотентно, ведь прореживание прореженного ничего не меняет.
 */
@Singleton
class StorageRetention @Inject constructor(
    @ApplicationContext private val context: Context,
    private val publisher: MediaStorePublisher,
    private val journal: CollectorJournal,
) {
    private val mutex = Mutex()
    private val collection: Uri get() = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val readyRoot: File get() = File(context.filesDir, "thin")

    /** Один проход: доставить недоставленное, проредить старое, удалить лишнее. */
    suspend fun run(today: LocalDate = DataPaths.localDate(System.currentTimeMillis())) = withContext(Dispatchers.IO) {
        if (!mutex.tryLock()) return@withContext
        try {
            deliverReadyCopies()
            var dirs = listDirs()
            var freed = 0L
            var thinnedDirs = 0
            for (dir in RetentionPolicy.dirsToThin(dirs, today)) {
                currentCoroutineContext().ensureActive()
                freed += thinDir(dir)
                thinnedDirs++
            }
            if (thinnedDirs > 0) dirs = listDirs()

            val toDelete = RetentionPolicy.dirsToDelete(dirs, today, SesameSettings.STORAGE_LIMIT_BYTES)
            var deleted = 0L
            toDelete.forEach { dir ->
                currentCoroutineContext().ensureActive()
                deleted += deleteDir(dir)
            }
            if (thinnedDirs > 0 || toDelete.isNotEmpty()) {
                Log.i(TAG, "Прорежено каталогов: $thinnedDirs (−$freed Б), удалено: ${toDelete.size} (−$deleted Б)")
                journal.log(
                    LogEventType.STORAGE_TRIMMED,
                    mapOf(
                        "thinnedDirs" to thinnedDirs,
                        "thinnedFreedBytes" to freed,
                        "deletedDirs" to toDelete.joinToString(";") { it.path },
                        "deletedBytes" to deleted,
                    ),
                )
            }
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            Log.w(TAG, "Чистка хранилища не удалась", error)
        } finally {
            mutex.unlock()
        }
    }

    private fun listDirs(): List<StoredDir> {
        val files = mutableListOf<StoredFile>()
        runCatching {
            context.contentResolver.query(
                collection,
                arrayOf(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                ),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("${GzipCsvWriter.ROOT_RELATIVE_PATH}/%"),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val dir = c.getString(0)?.trimEnd('/') ?: continue
                    val name = c.getString(1) ?: continue
                    files += StoredFile(dir, name, c.getLong(2), c.getLong(3) * 1000)
                }
            }
        }.onFailure { Log.w(TAG, "Не удалось перечислить файлы", it) }
        return files.groupBy { it.dir }.map { (dir, list) -> StoredDir(dir, list) }
    }

    /** @return сколько байт освободилось. */
    private suspend fun thinDir(dir: StoredDir): Long {
        val report = JSONObject()
        var freed = 0L
        for (file in dir.files) {
            currentCoroutineContext().ensureActive()
            val interval = RetentionPolicy.thinIntervalNanos(dir.path, file.name) ?: continue
            val uri = find(dir.path, file.name) ?: continue
            val ready = File(readyRoot, "${dir.path}/${file.name}.ready")
            val partial = File(ready.path.removeSuffix(".ready") + ".part")
            ready.parentFile?.mkdirs()
            val result = runCatching {
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    partial.outputStream().use { out -> CsvThinner.thinGzip(input, out, interval) }
                }
            }.onFailure { Log.w(TAG, "Не удалось проредить ${dir.path}/${file.name}", it) }.getOrNull()
            if (result == null || result.rowsOut >= result.rowsIn) {
                partial.delete()
                continue
            }
            if (!partial.renameTo(ready)) {
                partial.delete()
                continue
            }
            val newSize = ready.length()
            if (deliver(ready, uri)) freed += (file.sizeBytes - newSize).coerceAtLeast(0)
            report.put(
                file.name,
                JSONObject().apply {
                    put("minIntervalMillis", interval / 1_000_000)
                    put("rowsBefore", result.rowsIn)
                    put("rowsAfter", result.rowsOut)
                    put("bytesBefore", file.sizeBytes)
                    put("bytesAfter", newSize)
                },
            )
        }
        // Метка ставится и когда прореживать было нечего: иначе каталог
        // перечитывался бы целиком на каждом проходе.
        val marker = File(context.cacheDir, RetentionPolicy.THINNED_MARKER)
        runCatching {
            marker.writeText(
                JSONObject().apply {
                    put("thinnedAtMillis", System.currentTimeMillis())
                    put(
                        "note",
                        "Отсчёты прорежены до частоты, заказанной в настройках; " +
                            "строки исходной частоты удалены, чтобы уложиться в лимит хранилища",
                    )
                    put("streams", report)
                }.toString(2),
            )
            publisher.publish(marker, dir.path)
        }
        marker.delete()
        return freed
    }

    /** Переписывает оригинал прореженной копией; `.ready` удаляется только после успеха. */
    private fun deliver(ready: File, uri: Uri): Boolean = runCatching {
        context.contentResolver.openOutputStream(uri, "wt")!!.use { out ->
            ready.inputStream().use { it.copyTo(out, 64 * 1024) }
        }
        ready.delete()
        true
    }.onFailure { Log.w(TAG, "Не удалось записать прореженный ${ready.name}", it) }.getOrDefault(false)

    private fun deliverReadyCopies() {
        val root = readyRoot
        if (!root.isDirectory) return
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = file.relativeTo(root).invariantSeparatorsPath
            when {
                file.name.endsWith(".part") -> file.delete()
                file.name.endsWith(".ready") -> {
                    val dir = relative.substringBeforeLast('/')
                    val name = file.name.removeSuffix(".ready")
                    val uri = find(dir, name)
                    if (uri == null) file.delete() else deliver(file, uri)
                }
            }
        }
    }

    private fun deleteDir(dir: StoredDir): Long {
        var freed = 0L
        dir.files.forEach { file ->
            val uri = find(dir.path, file.name) ?: return@forEach
            val ok = runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
            if (ok) freed += file.sizeBytes
        }
        return freed
    }

    private fun find(dir: String, name: String): Uri? = runCatching {
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf("$dir/", name),
            null,
        )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null }
    }.getOrNull()

    private companion object {
        const val TAG = "StorageRetention"
    }
}
