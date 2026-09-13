package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.ReviewLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReviewLogDao {
    // Tie-break by id everywhere two logs can share a millisecond (restored/synthetic data), so
    // "latest" and display order are deterministic — matching the repository's replay ordering.
    @Query("SELECT * FROM review_logs WHERE studyUnitId = :unitId ORDER BY reviewedAt DESC, id DESC")
    fun getLogsForUnit(unitId: Long): Flow<List<ReviewLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: ReviewLogEntity): Long
    
    @Query("SELECT * FROM review_logs WHERE reviewedAt >= :sinceTime ORDER BY reviewedAt ASC, id ASC")
    fun getLogsSince(sinceTime: Long): Flow<List<ReviewLogEntity>>

    @Query("DELETE FROM review_logs WHERE id = :logId")
    suspend fun deleteLogById(logId: Long)

    /** One-shot full read for backup/analytics (wrapped in a transaction by the caller). */
    @Query("SELECT * FROM review_logs ORDER BY reviewedAt ASC, id ASC")
    suspend fun getAllLogsOnce(): List<ReviewLogEntity>

    /**
     * One-shot chronological history for a single topic. A Flow query cannot be collected inside a
     * Room transaction (it runs on the query executor and would not see the transaction's own reads),
     * and replay/projection needs the history in ascending order, which is the opposite of the
     * newest-first order the UI Flow uses.
     */
    @Query("SELECT * FROM review_logs WHERE studyUnitId = :unitId ORDER BY reviewedAt ASC, id ASC")
    suspend fun getLogsForUnitOnce(unitId: Long): List<ReviewLogEntity>

    /** Purge helper: drop the history of topics being hard-deleted after the 30-day grace. */
    @Query("DELETE FROM review_logs WHERE studyUnitId IN (:unitIds)")
    suspend fun deleteLogsForUnits(unitIds: List<Long>)

    /** Merge support: move history onto the surviving topic so no review is ever thrown away. */
    @Query("UPDATE review_logs SET studyUnitId = :toUnitId WHERE studyUnitId IN (:fromUnitIds)")
    suspend fun reassignLogs(fromUnitIds: List<Long>, toUnitId: Long)

    @Query("DELETE FROM review_logs")
    suspend fun deleteAllLogs()
}
