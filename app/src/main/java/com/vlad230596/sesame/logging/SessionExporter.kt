package com.vlad230596.sesame.logging

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.vlad230596.sesame.data.entity.RecordingSession
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Готовый архив: что в нём и сколько он весит (для сообщения в UI). */
data class ExportBundle(
    val uri: Uri,
    val fileName: String,
    val sizeBytes: Long,
    val sessionIds: List<Long>,
    val fileCount: Int,
)

/**
 * «Поделиться непошаренным» (§7) — настоящая отправка файлов.
 *
 * Основной способ забрать датасет остаётся прежним и указан в §7: файлы лежат в
 * `Documents/Sesame/` и видны с компьютера по USB. Share sheet — второй путь, и
 * он должен отдавать данные, а не их опись.
 *
 * **Почему один zip, а не список файлов.** Одна сессия — это 5–8 потоков плюс
 * манифест; десять несохранённых сессий превращаются в сотню `content://`-ссылок
 * в `ACTION_SEND_MULTIPLE`, что большинство приёмников либо обрезают, либо не
 * переваривают вовсе. Архив к тому же сохраняет раскладку каталогов из §5, то
 * есть распаковывается на компьютере в точности в то, что лежало на телефоне.
 *
 * **Почему через FileProvider, а не ссылками на MediaStore.** Архив собирается в
 * кэше приложения и отдаётся временным грантом на один файл. Раздавать наружу
 * URI самих данных значило бы выдать доступ ко всему, что лежит в
 * `Documents/Sesame/`, — а §7 прямо говорит, что телеметрии такого рода в чужих
 * руках быть не должно.
 *
 * **Сжатие выключено.** Внутри уже gzip-CSV (§5), повторный deflate даст
 * проценты и потратит минуты процессорного времени на сотнях мегабайт.
 */
@Singleton
class SessionExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val paths: DataPaths,
) {

    private val collection: Uri
        get() = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /**
     * Собирает архив из файлов переданных сессий.
     *
     * @return `null`, если ни одного файла найти не удалось — тогда делиться
     *         нечем, и отмечать сессии выгруженными нельзя.
     */
    fun buildBundle(sessions: List<RecordingSession>): ExportBundle? {
        if (sessions.isEmpty()) return null

        val dir = File(context.cacheDir, EXPORT_DIR)
        // Прошлый архив уже не нужен и может весить сотни мегабайт.
        runCatching { dir.deleteRecursively() }
        if (!dir.mkdirs() && !dir.isDirectory) return null

        val stamp = FILE_STAMP.format(Instant.now().atZone(ZoneId.systemDefault()))
        val archive = File(dir, "sesame-sessions-$stamp.zip")

        var files = 0
        var journalFiles = 0
        val exported = mutableListOf<Long>()

        val built = runCatching {
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                zip.setLevel(Deflater.NO_COMPRESSION)
                sessions.forEach { session ->
                    val added = addSession(zip, session)
                    if (added > 0) {
                        files += added
                        exported += session.id
                    }
                }
                // Журнал, метки и строки сессий — разметка к этим файлам, без неё
                // сессии на компьютере не с чем сопоставить. Кладётся только если
                // есть сами сессии: иначе делиться по-прежнему нечем.
                if (files > 0) journalFiles = addJournal(zip)
            }
        }.onFailure { Log.w(TAG, "Не удалось собрать архив", it) }.isSuccess

        if (!built || files == 0) {
            runCatching { archive.delete() }
            return null
        }

        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.files", archive)
        }.onFailure { Log.w(TAG, "FileProvider не отдал ссылку на архив", it) }.getOrNull()
            ?: return null

        return ExportBundle(
            uri = uri,
            fileName = archive.name,
            sizeBytes = archive.length(),
            sessionIds = exported,
            fileCount = files + journalFiles,
        )
    }

    /** Интент для share sheet. Грант временный и только на этот архив. */
    fun shareIntent(bundle: ExportBundle): Intent = Intent(Intent.ACTION_SEND).apply {
        type = MIME_ZIP
        putExtra(Intent.EXTRA_STREAM, bundle.uri)
        putExtra(Intent.EXTRA_SUBJECT, bundle.fileName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /**
     * Файлы сессии: сначала из `Documents/Sesame/` через MediaStore (штатный
     * случай), и если там пусто — из staging. Staging остаётся только когда
     * публикация не удалась целиком, и тогда это единственный экземпляр данных.
     */
    private fun addSession(zip: ZipOutputStream, session: RecordingSession): Int {
        val relativeDir = session.fileDir ?: paths.sessionPublishedDir(session.id)
        val entryDir = "sessions/${session.id}"
        val published = addFromMediaStore(zip, relativeDir, entryDir)
        if (published > 0) return published
        return addFromStaging(zip, paths.sessionStagingDir(session.id), entryDir)
    }

    private fun addFromMediaStore(zip: ZipOutputStream, relativeDir: String, entryDir: String): Int {
        val path = relativeDir.trim('/') + "/"
        return addFromMediaStore(zip, "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", path) { entryDir }
    }

    /**
     * `Documents/Sesame/journal/` со всеми подкаталогами суток — в архиве под
     * `journal/...`, в той же раскладке, что на телефоне (§5). Берётся уже
     * опубликованное: оно отстаёт от Room максимум на один тик публикации.
     */
    private fun addJournal(zip: ZipOutputStream): Int {
        val root = GzipCsvWriter.ROOT_RELATIVE_PATH + "/"
        val path = paths.journalPublishedDir.trim('/') + "/"
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        return addFromMediaStore(zip, selection, "$path%") { relativePath ->
            relativePath.removePrefix(root).trim('/')
        }
    }

    private fun addFromMediaStore(
        zip: ZipOutputStream,
        selection: String,
        selectionArg: String,
        entryDirOf: (relativePath: String) -> String,
    ): Int {
        var count = 0
        runCatching {
            context.contentResolver.query(
                collection,
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                ),
                selection,
                arrayOf(selectionArg),
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                    val name = cursor.getString(nameColumn) ?: continue
                    val entryDir = entryDirOf(cursor.getString(pathColumn).orEmpty())
                    val copied = runCatching {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            zip.putNextEntry(ZipEntry("$entryDir/$name"))
                            input.copyTo(zip, COPY_BUFFER_BYTES)
                            zip.closeEntry()
                            true
                        } ?: false
                    }.onFailure { Log.w(TAG, "Не удалось вложить $name", it) }.getOrDefault(false)
                    if (copied) count++
                }
            }
        }.onFailure { Log.w(TAG, "Не удалось перечислить $selectionArg", it) }
        return count
    }

    private fun addFromStaging(zip: ZipOutputStream, dir: File, entryDir: String): Int {
        val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
        var count = 0
        files.forEach { file ->
            val copied = runCatching {
                zip.putNextEntry(ZipEntry("$entryDir/${file.name}"))
                file.inputStream().use { it.copyTo(zip, COPY_BUFFER_BYTES) }
                zip.closeEntry()
                true
            }.onFailure { Log.w(TAG, "Не удалось вложить ${file.name}", it) }.getOrDefault(false)
            if (copied) count++
        }
        return count
    }

    private companion object {
        const val TAG = "SessionExporter"
        const val EXPORT_DIR = "export"
        const val MIME_ZIP = "application/zip"
        const val COPY_BUFFER_BYTES = 64 * 1024
        val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
    }
}
