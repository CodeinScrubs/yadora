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

    /**
     * Timestamps of every committed study action (reviews + first-study check-ins), for the growth
     * visual. Lives in event_logs deliberately: earned growth must survive topic deletion.
     */
    @Query("SELECT at FROM event_logs WHERE type = 'STUDY_ACTION' ORDER BY at ASC")
    fun observeStudyActionTimes(): kotlinx.coroutines.flow.Flow<List<Long>>

    @Query("DELETE FROM event_logs")
    suspend fun deleteAll()
}
