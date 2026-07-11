package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.data.local.entity.EventLogEntity

@Dao
interface EventLogDao {
    @Insert
    suspend fun insert(event: EventLogEntity): Long

    @Query("SELECT * FROM event_logs ORDER BY at ASC")
    suspend fun getAll(): List<EventLogEntity>

    @Query("DELETE FROM event_logs")
    suspend fun deleteAll()
}
