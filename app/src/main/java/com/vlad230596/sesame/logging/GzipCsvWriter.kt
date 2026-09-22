package com.vlad230596.sesame.logging

import java.io.Closeable

/**
 * Писатель одного потока датчика в gzip-CSV (§5, §6).
 *
 * - по одному файлу на датчик;
 * - в CSV пишется сырой `elapsedRealtimeNanos` без преобразований — привязка к
 *   стенным часам живёт в `session.json` / `day.json`;
 * - поток флашится раз в [FLUSH_INTERVAL_SECONDS] секунд, чтобы аварийное завершение
 *   сервиса уносило не более десяти секунд данных;
 * - запись идёт в отдельном потоке с очередью, UI и потоки датчиков не блокируются (§9).
 *
 * TODO(v0): реализовать.
 */
class GzipCsvWriter : Closeable {

    fun writeHeader(columns: List<String>) {
        // TODO(v0)
    }

    fun append(elapsedRealtimeNanos: Long, values: FloatArray) {
        // TODO(v0)
    }

    fun flush() {
        // TODO(v0)
    }

    override fun close() {
        // TODO(v0)
    }

    companion object {
        /** §5: флаш раз в 10 секунд. */
        const val FLUSH_INTERVAL_SECONDS: Int = 10

        /** §7: данные лежат в `Documents/Sesame/` через MediaStore, а не в приватной папке. */
        const val ROOT_RELATIVE_PATH: String = "Documents/Sesame"
    }
}
