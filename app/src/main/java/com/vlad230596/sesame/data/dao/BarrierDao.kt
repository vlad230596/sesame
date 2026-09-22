package com.vlad230596.sesame.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.vlad230596.sesame.data.entity.Barrier
import kotlinx.coroutines.flow.Flow

@Dao
interface BarrierDao {

    @Query("SELECT * FROM barrier ORDER BY orderIndex ASC")
    fun observeAll(): Flow<List<Barrier>>

    @Query("SELECT COUNT(*) FROM barrier")
    suspend fun count(): Int

    @Query("SELECT * FROM barrier WHERE id = :id")
    suspend fun byId(id: Long): Barrier?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(barrier: Barrier): Long

    @Upsert
    suspend fun upsert(barrier: Barrier)
}
