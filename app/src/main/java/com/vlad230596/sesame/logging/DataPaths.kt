package com.vlad230596.sesame.logging

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Раскладка данных (§5, §7).
 *
 * **Где лежат данные и почему в два шага.** §7 требует `Documents/Sesame/`: этот
 * каталог виден с компьютера по USB и переживает переустановку приложения.
 * Но `Documents/` доступен только через `MediaStore` — это content-провайдер, а
 * не файловая система: путь к файлу неизвестен, дескриптор живёт через binder,
 * а запись сотен отсчётов в секунду на протяжении недели в такой дескриптор —
 * нехоженая дорога, где обрыв дескриптора уносит весь файл целиком.
 *
 * Поэтому запись двухступенчатая:
 *
 * 1. горячий путь пишет в обычный файл в [stagingRoot] — приватный **внутренний**
 *    каталог приложения (`/data/data/...`), не `Android/data/`: последний не виден
 *    по USB и всё равно стирается при удалении приложения, то есть не даёт ничего;
 * 2. готовый файл копируется в `Documents/Sesame/...` через `MediaStore`
 *    ([MediaStorePublisher]) — по завершении сессии, при суточной ротации
 *    пассивного слоя и раз в [DataFileStore.PASSIVE_PUBLISH_INTERVAL_MILLIS]
 *    для текущих суток.
 *
 * Итог: под угрозой переустановки остаётся только незавершённая сессия и
 * последние минуты текущих суток, а не датасет.
 */
@Singleton
class DataPaths @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Горячий путь записи. Внутреннее хранилище: не `Android/data/` (§7). */
    val stagingRoot: File get() = File(context.filesDir, "collect")

    fun sessionStagingDir(sessionId: Long): File = File(stagingRoot, "sessions/$sessionId")

    fun passiveStagingDir(date: LocalDate): File =
        File(stagingRoot, "passive/${DATE_FORMAT.format(date)}")

    /** Staging журнала: файлы собираются из Room заново на каждой публикации. */
    val journalStagingDir: File get() = File(stagingRoot, "journal")

    fun journalDayStagingDir(date: LocalDate): File =
        File(journalStagingDir, DATE_FORMAT.format(date))

    /** `Documents/Sesame/sessions/<session-id>/` (§5). */
    fun sessionPublishedDir(sessionId: Long): String =
        "${GzipCsvWriter.ROOT_RELATIVE_PATH}/sessions/$sessionId"

    /** `Documents/Sesame/passive/<YYYY-MM-DD>/` (§5). */
    fun passivePublishedDir(date: LocalDate): String =
        "${GzipCsvWriter.ROOT_RELATIVE_PATH}/passive/${DATE_FORMAT.format(date)}"

    /** `Documents/Sesame/journal/` — снимки меток и сессий (§5, §7). */
    val journalPublishedDir: String get() = "${GzipCsvWriter.ROOT_RELATIVE_PATH}/journal"

    /** `Documents/Sesame/journal/<YYYY-MM-DD>/` — события журнала за сутки (§5, §7). */
    fun journalDayPublishedDir(date: LocalDate): String =
        "$journalPublishedDir/${DATE_FORMAT.format(date)}"

    companion object {
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

        fun localDate(epochMillis: Long): LocalDate =
            Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()

        /** Начало суток [date] в местной зоне — та же зона, что у [localDate]. */
        fun startOfDayMillis(date: LocalDate): Long =
            date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        /** Полночь следующих суток в местной зоне: граница суточной ротации (§5). */
        fun nextMidnightMillis(epochMillis: Long): Long {
            val zone = ZoneId.systemDefault()
            return localDate(epochMillis)
                .plusDays(1)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()
        }
    }
}
