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

    /**
     * The evidence the per-user calibration reads: the most recent real recall reviews under one
     * memory model, newest first, with a stored prediction, at least [minElapsedDays] elapsed, and
     * not brought forward by anything but the memory clock (at least [earlyFraction] of the interval
     * that was scheduled had passed). First-study rows are self-assessments, not recalls;
     * short-interval rows carry the whole-day rounding bias RecallCalibration.MIN_ELAPSED_DAYS
     * explains; early rows are repairs and self-tests, which RecallCalibration.EARLY_REVIEW_FRACTION
     * explains. Only rows reviewed from [since] on, when the set began scheduling (MedScheduler.ParameterSet
     * .activatedAt): an older row stamped with the set was recomputed by a rating correction, not predicted.
     */
    @Query(
        "SELECT * FROM review_logs WHERE logType = 'RECALL' AND schedulerVersion = :model AND parameterSetId = :parameterSetId " +
            "AND reviewedAt >= :since AND retrievabilityAtReview >= 0.0 AND elapsedDays >= :minElapsedDays " +
            "AND elapsedDays >= :earlyFraction * previousIntervalDays ORDER BY reviewedAt DESC, id DESC LIMIT :limit"
    )
    suspend fun getRecentRecallLogsOnce(model: String, parameterSetId: Long, since: Long, minElapsedDays: Double, earlyFraction: Double, limit: Int): List<ReviewLogEntity>

    /**
     * Reviews whose time falls between [since] and [until] (one local day), first ratings excluded: what the
     * daily limit counts. A first rating logs a study that already happened, so it never uses up the day's
     * reviews. BOTH ends: a review stamped on a later day (the phone's clock was ahead and then corrected) used
     * to count against today as well, holding back a review that was due (an outside audit, 2026-09-27). Each
     * review now counts against exactly one day, the one its time says.
     */
    @Query("SELECT COUNT(*) FROM review_logs WHERE reviewedAt >= :since AND reviewedAt <= :until AND logType != 'FIRST_STUDY'")
    suspend fun countReviewsBetween(since: Long, until: Long): Int

    /** [countReviewsBetween] as a Flow, for the Today screen. */
    @Query("SELECT COUNT(*) FROM review_logs WHERE reviewedAt >= :since AND reviewedAt <= :until AND logType != 'FIRST_STUDY'")
    fun observeReviewsBetween(since: Long, until: Long): Flow<Int>

    @Query("SELECT COALESCE(MAX(id), 0) FROM review_logs")
    suspend fun maxLogId(): Long

    /** Purge helper: drop the history of topics being hard-deleted after the 30-day grace. */
    @Query("DELETE FROM review_logs WHERE studyUnitId IN (:unitIds)")
    suspend fun deleteLogsForUnits(unitIds: List<Long>)

    /** Merge support: move history onto the surviving topic so no review is ever thrown away. */
    @Query("UPDATE review_logs SET studyUnitId = :toUnitId WHERE studyUnitId IN (:fromUnitIds)")
    suspend fun reassignLogs(fromUnitIds: List<Long>, toUnitId: Long)

    @Query("DELETE FROM review_logs")
    suspend fun deleteAllLogs()
}
