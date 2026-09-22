package com.vlad230596.sesame.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.vlad230596.sesame.data.entity.RecordingSession
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingSessionDao {

    @Query("SELECT * FROM recording_session ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<RecordingSession>>

    /** Для кнопки «Поделиться непошаренным» в истории (§7). */
    @Query("SELECT * FROM recording_session WHERE shared = 0 AND endedAt IS NOT NULL")
    suspend fun unshared(): List<RecordingSession>

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM recording_session")
    fun observeTotalSizeBytes(): Flow<Long>

    @Insert
    suspend fun insert(session: RecordingSession): Long

    @Update
    suspend fun update(session: RecordingSession)
}
