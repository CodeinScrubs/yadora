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
    
    @Query("SELECT COUNT(*) FROM review_logs WHERE reviewedAt >= :sinceTime")
    fun getReviewsCountSince(sinceTime: Long): Flow<Int>

    @Query("SELECT * FROM review_logs WHERE reviewedAt >= :sinceTime ORDER BY reviewedAt ASC, id ASC")
    fun getLogsSince(sinceTime: Long): Flow<List<ReviewLogEntity>>

    @Query("DELETE FROM review_logs WHERE id = (SELECT id FROM review_logs WHERE studyUnitId = :unitId ORDER BY reviewedAt DESC, id DESC LIMIT 1)")
    suspend fun deleteLastLogForUnit(unitId: Long)

    @Query("DELETE FROM review_logs WHERE id = :logId")
    suspend fun deleteLogById(logId: Long)

    @Query("DELETE FROM review_logs")
    suspend fun deleteAllLogs()
}
