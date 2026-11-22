package com.vlad230596.sesame.logging

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Пропускает не чаще одного отсчёта за [minIntervalNanos].
 *
 * Android отдаёт датчик не на заказанной частоте, а на той, что попросил самый
 * жадный подписчик телефона: заказывали 5 Гц, а в файлы акселерометра ложилось
 * 30–60 Гц, до 75 МБ в сутки. Решения от этого лучше не становятся, поэтому
 * лишние отсчёты отбрасываются при записи, а уже записанные — при прореживании
 * старых суток ([CsvThinner]), одним и тем же правилом.
 *
 * Время, пошедшее назад (новый запуск после перезагрузки, другой отсчёт
 * `elapsedRealtimeNanos`), начинает отсчёт заново: такой отсчёт сохраняется.
 */
class SampleDecimator(private val minIntervalNanos: Long) {
    private var lastKept = Long.MIN_VALUE

    fun accept(nanos: Long): Boolean {
        if (minIntervalNanos <= 0) return true
        val last = lastKept
        if (last != Long.MIN_VALUE && nanos >= last && nanos - last < minIntervalNanos) return false
        lastKept = nanos
        return true
    }

    companion object {
        /**
         * Интервал для частоты [hz] с допуском 10 %: отсчёты датчика идут с
         * дрожанием, и строгий интервал на 5 Гц дал бы 2,5 Гц через раз.
         */
        fun intervalNanosForHz(hz: Int): Long =
            if (hz <= 0) 0 else (1_000_000_000L / hz) * 9 / 10
    }
}

/**
 * Прореживание уже записанного потока `*.csv.gz` (§7: хранилище не резиновое).
 *
 * Первая строка — заголовок, первый столбец — `elapsed_realtime_nanos`. Строки,
 * время которых не читается, сохраняются как есть: лучше лишняя строка, чем
 * потерянная.
 */
object CsvThinner {

    data class Result(val rowsIn: Long, val rowsOut: Long)

    fun thinGzip(input: InputStream, output: OutputStream, minIntervalNanos: Long): Result {
        val decimator = SampleDecimator(minIntervalNanos)
        var rowsIn = 0L
        var rowsOut = 0L
        BufferedReader(InputStreamReader(GZIPInputStream(input, BUFFER), Charsets.UTF_8), BUFFER).use { reader ->
            val writer = OutputStreamWriter(GZIPOutputStream(output, BUFFER), Charsets.UTF_8).buffered(BUFFER)
            writer.use {
                val header = reader.readLineOrEnd() ?: return Result(0, 0)
                it.write(header)
                it.write("\n")
                while (true) {
                    val line = reader.readLineOrEnd() ?: break
                    if (line.isEmpty()) continue
                    rowsIn++
                    val nanos = line.substringBefore(',').toLongOrNull()
                    if (nanos == null || decimator.accept(nanos)) {
                        it.write(line)
                        it.write("\n")
                        rowsOut++
                    }
                }
            }
        }
        return Result(rowsIn, rowsOut)
    }

    /**
     * Обрезанный хвост (процесс убили посреди записи) — конец данных, а не повод
     * бросить весь файл непрореженным.
     */
    private fun BufferedReader.readLineOrEnd(): String? = try {
        readLine()
    } catch (_: java.io.EOFException) {
        null
    }

    private const val BUFFER = 64 * 1024
}
