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

    /** Незавершённая сессия: на главном экране показывается её состояние (§4.3). */
    @Query("SELECT * FROM recording_session WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    fun observeActive(): Flow<RecordingSession?>

    /** Полный снимок для `Documents/Sesame/journal/sessions.csv.gz` (§7). */
    @Query("SELECT * FROM recording_session ORDER BY id")
    suspend fun all(): List<RecordingSession>

    @Query("SELECT * FROM recording_session WHERE id = :id")
    suspend fun byId(id: Long): RecordingSession?

    /** Отметить выгруженным после share sheet (§7). */
    @Query("UPDATE recording_session SET shared = 1 WHERE id IN (:ids)")
    suspend fun markShared(ids: List<Long>)

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
