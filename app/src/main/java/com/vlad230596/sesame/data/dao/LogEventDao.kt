package com.vlad230596.sesame.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.vlad230596.sesame.data.entity.LogEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface LogEventDao {

    @Insert
    suspend fun insert(event: LogEvent): Long

    @Insert
    suspend fun insertAll(events: List<LogEvent>)

    @Query("SELECT * FROM log_event ORDER BY eventTime DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<LogEvent>>

    /**
     * Весь журнал хронологически — для публикации в `Documents/Sesame/journal/`
     * при старте сервиса (§7). Объём — тысячи строк, раскладка по суткам делается
     * в Kotlin той же зоной, что и у пассивного слоя, а не `localtime` SQLite.
     */
    @Query("SELECT * FROM log_event ORDER BY eventTime, id")
    suspend fun allChronological(): List<LogEvent>

    /** События с `eventTime` в `[fromMillis, untilMillis)` — файл одних суток (§7). */
    @Query(
        "SELECT * FROM log_event WHERE eventTime >= :fromMillis AND eventTime < :untilMillis " +
            "ORDER BY eventTime, id",
    )
    suspend fun between(fromMillis: Long, untilMillis: Long): List<LogEvent>

    /**
     * Самое раннее `eventTime` среди событий, полученных начиная с [sinceMillis]:
     * запоздавшее событие относится к суткам, когда произошло, и переписать надо
     * именно их файл, а не только текущий.
     */
    @Query("SELECT MIN(eventTime) FROM log_event WHERE receivedTime >= :sinceMillis")
    suspend fun earliestEventTimeReceivedSince(sinceMillis: Long): Long?

    /** Строка «последнее фоновое событие N минут назад» на главном экране (§4.7). */
    @Query("SELECT MAX(receivedTime) FROM log_event")
    fun observeLastReceivedTime(): Flow<Long?>
}
