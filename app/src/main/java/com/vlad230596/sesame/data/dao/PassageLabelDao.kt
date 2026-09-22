package com.vlad230596.sesame.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.vlad230596.sesame.data.entity.PassageLabel
import kotlinx.coroutines.flow.Flow

@Dao
interface PassageLabelDao {

    @Query("SELECT * FROM passage_label ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<PassageLabel>>

    /** Для плашки «N меток без подтверждения» на главном экране (§4.5). */
    @Query("SELECT COUNT(*) FROM passage_label WHERE confirmed = 0 AND discarded = 0")
    fun observeUnconfirmedCount(): Flow<Int>

    /** Карточки для плашки «N меток без подтверждения» на главном экране (§4.5). */
    @Query("SELECT * FROM passage_label WHERE confirmed = 0 AND discarded = 0 ORDER BY timestamp DESC")
    fun observeUnconfirmed(): Flow<List<PassageLabel>>

    @Query("SELECT * FROM passage_label WHERE id = :id")
    suspend fun byId(id: Long): PassageLabel?

    @Insert
    suspend fun insert(label: PassageLabel): Long

    @Update
    suspend fun update(label: PassageLabel)
}
