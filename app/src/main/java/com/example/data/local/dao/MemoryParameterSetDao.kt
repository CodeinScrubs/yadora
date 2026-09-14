package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.MemoryParameterSetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryParameterSetDao {
    /** REPLACE only matters to restore, which writes rows back under their exported ids. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(set: MemoryParameterSetEntity): Long

    @Query("SELECT * FROM memory_parameter_sets ORDER BY id ASC")
    suspend fun getAll(): List<MemoryParameterSetEntity>

    @Query("SELECT * FROM memory_parameter_sets ORDER BY id ASC")
    fun observeAll(): Flow<List<MemoryParameterSetEntity>>

    @Query("SELECT * FROM memory_parameter_sets ORDER BY id DESC LIMIT 1")
    suspend fun getLatest(): MemoryParameterSetEntity?

    @Query("SELECT * FROM memory_parameter_sets WHERE status = 'ACTIVE' ORDER BY id DESC LIMIT 1")
    suspend fun getActive(): MemoryParameterSetEntity?

    @Query("UPDATE memory_parameter_sets SET status = 'RETIRED', retiredAt = :at WHERE status = 'ACTIVE'")
    suspend fun retireActive(at: Long)

    @Query("DELETE FROM memory_parameter_sets")
    suspend fun deleteAll()
}
