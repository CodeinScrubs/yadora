package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.data.local.entity.EventLogEntity

@Dao
interface EventLogDao {
    @Insert
    suspend fun insert(event: EventLogEntity): Long

    /** Every event at once, for a restore: the same insert as [insert], one call instead of thousands. */
    @Insert
    suspend fun insertAll(events: List<EventLogEntity>)

    @Query("SELECT * FROM event_logs ORDER BY at ASC")
    suspend fun getAll(): List<EventLogEntity>

    /**
     * Timestamps of every committed study action (reviews + first-study check-ins), for the growth
     * visual. Lives in event_logs deliberately: earned growth must survive topic deletion.
     */
    @Query("SELECT at FROM event_logs WHERE type = 'STUDY_ACTION' ORDER BY at ASC")
    fun observeStudyActionTimes(): kotlinx.coroutines.flow.Flow<List<Long>>

    /** Undo support: growth events are keyed to their review log (detail = logId) so undo is exact. */
    @Query("DELETE FROM event_logs WHERE type = 'STUDY_ACTION' AND detail = :logIdStr")
    suspend fun deleteStudyActionForLog(logIdStr: String)

    @Query("DELETE FROM event_logs")
    suspend fun deleteAll()

    /** Every merge: the survivor in unitId, the absorbed copies' ids comma-separated in detail. */
    @Query("SELECT * FROM event_logs WHERE type = 'MERGE'")
    suspend fun getMergeEvents(): List<EventLogEntity>

    /** Every rating correction (com.example.data.RecomputedPredictions reads which predictions each one recomputed). */
    @Query("SELECT * FROM event_logs WHERE type = 'RATING_CORRECTED'")
    suspend fun getCorrectionEvents(): List<EventLogEntity>

    /** [getCorrectionEvents] as a Flow, for the Progress screen's calibration card. */
    @Query("SELECT * FROM event_logs WHERE type = 'RATING_CORRECTED'")
    fun observeCorrectionEvents(): kotlinx.coroutines.flow.Flow<List<EventLogEntity>>
}
