package com.vlad230596.sesame.logging

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Публикация готовых файлов в `Documents/Sesame/` через `MediaStore` (§7).
 *
 * Обоснование двухступенчатой записи — в [DataPaths]. Здесь только копирование
 * уже закрытого файла из staging в общий каталог и учёт занятого объёма.
 *
 * Повторная публикация того же имени перезаписывает запись на месте, а не плодит
 * `accel (1).csv.gz`: пассивные сутки публикуются несколько раз за день, чтобы
 * переустановка уносила максимум последние минуты, а не весь день.
 */
@Singleton
class MediaStorePublisher @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val collection: Uri
        get() = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /**
     * @param relativeDir каталог вида `Documents/Sesame/sessions/12` (без слеша на конце).
     * @return `true`, если файл лежит в `Documents/Sesame/` в актуальном виде.
     */
    fun publish(source: File, relativeDir: String): Boolean {
        if (!source.isFile) return false
        val displayName = source.name
        val path = normalizeDir(relativeDir)
        return runCatching {
            val resolver = context.contentResolver
            val existing = findExisting(path, displayName)
            val uri = existing ?: resolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeOf(displayName))
                    put(MediaStore.MediaColumns.RELATIVE_PATH, path)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                },
            ) ?: return false

            // "wt" — усечь и переписать: иначе более короткая новая версия
            // оставила бы хвост предыдущей.
            resolver.openOutputStream(uri, "wt")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output, COPY_BUFFER_BYTES) }
            } ?: return false

            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            true
        }.onFailure { error ->
            Log.w(TAG, "Не удалось опубликовать ${source.name} в $relativeDir", error)
        }.getOrDefault(false)
    }

    /** Публикует все файлы каталога. Возвращает число успешно опубликованных. */
    fun publishDirectory(source: File, relativeDir: String): Int {
        val files = source.listFiles()?.filter { it.isFile }.orEmpty()
        return files.count { publish(it, relativeDir) }
    }

    /** Занятый объём в `Documents/Sesame/` — для плашки «занято X из 10 ГБ» (§4.7, §7). */
    fun usedBytes(): Long = runCatching {
        var total = 0L
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns.SIZE),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("${GzipCsvWriter.ROOT_RELATIVE_PATH}/%"),
            null,
        )?.use { cursor ->
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            while (cursor.moveToNext()) {
                if (!cursor.isNull(sizeColumn)) total += cursor.getLong(sizeColumn)
            }
        }
        total
    }.onFailure { Log.w(TAG, "Не удалось посчитать занятый объём", it) }.getOrDefault(0L)

    private fun findExisting(path: String, displayName: String): Uri? = runCatching {
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(path, displayName),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0))
            else null
        }
    }.getOrNull()

    /** `MediaStore` хранит `RELATIVE_PATH` со слешем на конце — сравнение точное. */
    private fun normalizeDir(relativeDir: String): String =
        relativeDir.trim('/').let { "$it/" }

    private fun mimeTypeOf(displayName: String): String = when {
        displayName.endsWith(".gz") -> "application/gzip"
        displayName.endsWith(".json") -> "application/json"
        displayName.endsWith(".csv") -> "text/csv"
        else -> "application/octet-stream"
    }

    private companion object {
        const val TAG = "MediaStorePublisher"
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
