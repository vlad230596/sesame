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

    /** Строка «последнее фоновое событие N минут назад» на главном экране (§4.7). */
    @Query("SELECT MAX(receivedTime) FROM log_event")
    fun observeLastReceivedTime(): Flow<Long?>
}
