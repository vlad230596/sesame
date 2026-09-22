package com.vlad230596.sesame.logging

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPOutputStream

/**
 * Писатель одного потока датчика в gzip-CSV (§5, §6).
 *
 * - по одному файлу на датчик;
 * - в CSV пишется сырой `elapsedRealtimeNanos` без преобразований — привязка к
 *   стенным часам живёт в `session.json` / `day.json`;
 * - поток флашится раз в [FLUSH_INTERVAL_SECONDS] секунд, чтобы аварийное завершение
 *   сервиса уносило не более десяти секунд данных;
 * - запись идёт в отдельном потоке с очередью, UI и потоки датчиков не блокируются (§9)
 *   — очередь и поток живут в [DataFileStore], сам писатель не потокобезопасен и
 *   трогается только из потока записи.
 *
 * **Почему gzip пишется многочленно (multi-member).** Оборванный на середине
 * gzip-поток без концевика Python читать отказывается (`EOFError`), то есть
 * падение сервиса уносило бы весь файл, а не последние секунды. Поэтому каждый
 * флаш закрывает текущий gzip-член целиком, а следующая запись открывает новый.
 * Конкатенация gzip-членов — валидный gzip: `gzip`, `zcat` и `pandas.read_csv`
 * читают такой файл как один поток, а заголовок CSV, написанный в первый член,
 * остаётся единственной первой строкой. Цена — 18 байт на член и сброс словаря
 * компрессии раз в десять секунд.
 *
 * Побочное следствие, которым пользуется ротация и восстановление после
 * перезапуска процесса: файл можно **дописывать**. Новый член просто добавляется
 * в хвост, заголовок при этом не повторяется.
 *
 * @param columnsFor заголовок CSV, который зависит от числа значений в первом
 *        отсчёте: `SensorEvent.values` у неизвестных датчиков имеет заранее
 *        неизвестную длину, а состав датчиков определяется в рантайме (§3).
 */
class GzipCsvWriter(
    val file: File,
    private val columnsFor: (valueCount: Int) -> List<String>,
) : Closeable {

    private val fileOutput: FileOutputStream
    private val buffered: BufferedOutputStream
    private val nonClosing: OutputStream

    private var gzip: GZIPOutputStream? = null
    private val row = StringBuilder(192)

    /** Заголовок уже есть в файле — либо мы его написали, либо файл дописывается. */
    private var headerWritten: Boolean

    private var closed = false

    /** Фактические имена столбцов; известны после первой строки. Идут в манифест (§6). */
    var columns: List<String>? = null
        private set

    var rowsWritten: Long = 0L
        private set

    init {
        file.parentFile?.mkdirs()
        val continuing = file.isFile && file.length() > 0L
        headerWritten = continuing
        fileOutput = FileOutputStream(file, /* append = */ true)
        buffered = BufferedOutputStream(fileOutput, BUFFER_BYTES)
        nonClosing = NonClosingOutputStream(buffered)
    }

    /** Быстрый путь для датчиков: форматирование float'ов идёт в потоке записи. */
    fun appendSensorValues(
        elapsedRealtimeNanos: Long,
        values: FloatArray,
        valueCount: Int,
        accuracy: Int,
    ) {
        val count = valueCount.coerceIn(0, values.size)
        ensureHeader(count)
        row.setLength(0)
        row.append(elapsedRealtimeNanos)
        row.append(',').append(accuracy)
        for (i in 0 until count) {
            row.append(',')
            appendFloat(values[i])
        }
        writeRow()
    }

    /** Медленный путь: локация и прочие редкие потоки с разнородными столбцами. */
    fun appendCells(elapsedRealtimeNanos: Long, cells: Array<String>) {
        ensureHeader(cells.size)
        row.setLength(0)
        row.append(elapsedRealtimeNanos)
        for (cell in cells) {
            row.append(',')
            appendEscaped(cell)
        }
        writeRow()
    }

    /**
     * Закрывает текущий gzip-член и сбрасывает всё на диск. Вызывается раз в
     * [FLUSH_INTERVAL_SECONDS] секунд из потока записи.
     */
    fun flush() {
        if (closed) return
        closeMember()
        buffered.flush()
        // Дешёвая страховка от внезапного отключения питания: раз в десять секунд
        // fsync ничего не стоит, а страницы кеша переживают только падение процесса.
        runCatching { fileOutput.fd.sync() }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { closeMember() }
        runCatching { buffered.flush() }
        runCatching { fileOutput.fd.sync() }
        runCatching { fileOutput.close() }
    }

    fun sizeBytes(): Long = runCatching { file.length() }.getOrDefault(0L)

    // --- внутреннее ---------------------------------------------------------------

    private fun ensureHeader(valueCount: Int) {
        if (columns == null) columns = columnsFor(valueCount)
        if (headerWritten) return
        headerWritten = true
        val header = columns!!.joinToString(",")
        row.setLength(0)
        row.append(header)
        writeRow(countRow = false)
    }

    private fun writeRow(countRow: Boolean = true) {
        row.append('\n')
        val stream = gzip ?: GZIPOutputStream(nonClosing, GZIP_BUFFER_BYTES).also { gzip = it }
        stream.write(row.toString().toByteArray(StandardCharsets.UTF_8))
        if (countRow) rowsWritten++
    }

    /**
     * `DeflaterOutputStream.close()` дописывает концевик, освобождает Deflater и
     * закрывает нижележащий поток — последнее и перехватывает [NonClosingOutputStream],
     * так что файл остаётся открытым, а член — законченным.
     */
    private fun closeMember() {
        val stream = gzip ?: return
        gzip = null
        stream.close()
    }

    private fun appendFloat(value: Float) {
        // Float.toString даёт точку независимо от локали — в отличие от String.format.
        if (value.isNaN() || value.isInfinite()) row.append("") else row.append(value)
    }

    private fun appendEscaped(cell: String) {
        if (cell.isEmpty()) return
        val needsQuotes = cell.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuotes) {
            row.append(cell)
            return
        }
        row.append('"')
        for (ch in cell) {
            if (ch == '"') row.append('"')
            row.append(ch)
        }
        row.append('"')
    }

    /**
     * `GZIPOutputStream.close()` обязан дописать концевик, но не обязан закрывать
     * файл: файл живёт дольше одного члена.
     */
    private class NonClosingOutputStream(private val delegate: OutputStream) :
        FilterOutputStream(delegate) {

        override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)

        override fun close() = delegate.flush()
    }

    companion object {
        /** §5: флаш раз в 10 секунд. */
        const val FLUSH_INTERVAL_SECONDS: Int = 10

        const val FLUSH_INTERVAL_MILLIS: Long = FLUSH_INTERVAL_SECONDS * 1000L

        /**
         * §7: данные лежат в `Documents/Sesame/` через MediaStore, а не в приватной
         * папке приложения. Высокочастотная запись идёт в staging-каталог, готовый
         * файл публикуется сюда — см. [MediaStorePublisher].
         */
        const val ROOT_RELATIVE_PATH: String = "Documents/Sesame"

        private const val BUFFER_BYTES = 64 * 1024
        private const val GZIP_BUFFER_BYTES = 16 * 1024
    }
}
