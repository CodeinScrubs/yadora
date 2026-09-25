package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.entity.StudyUnitEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StudyUnitDao {
    @Query("SELECT * FROM study_units WHERE archived = 0 ORDER BY nextReviewAt ASC")
    fun getAllActiveUnits(): Flow<List<StudyUnitEntity>>
    
    @Query("SELECT * FROM study_units WHERE archived = 0 AND nextReviewAt <= :cutoffTime ORDER BY nextReviewAt ASC")
    fun getDueUnits(cutoffTime: Long): Flow<List<StudyUnitEntity>>

    /** Due units, one-shot (not a Flow): today's plan (DailyPlan) is built from these. */
    @Query("SELECT * FROM study_units WHERE archived = 0 AND nextReviewAt <= :cutoffTime ORDER BY highYield DESC, nextReviewAt ASC")
    suspend fun getDueUnitsList(cutoffTime: Long): List<StudyUnitEntity>

    /**
     * Push every currently-due unit to [tomorrow] ("Not today" / procrastinate all). Records it as a
     * DEFERRAL: the model's opinion (modelDueAt) is untouched, so the data stays honest about what
     * was science and what was the user's choice.
     */
    @Query("UPDATE study_units SET nextReviewAt = :tomorrow, deferredUntil = :tomorrow, updatedAt = :stamp WHERE archived = 0 AND nextReviewAt <= :now")
    suspend fun procrastinateAllDue(now: Long, tomorrow: Long, stamp: Long)

    @Query("SELECT * FROM study_units WHERE id = :id")
    suspend fun getUnitById(id: Long): StudyUnitEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUnit(unit: StudyUnitEntity): Long

    @Update
    suspend fun updateUnit(unit: StudyUnitEntity)

    @Query("SELECT * FROM study_units WHERE archived = 1 AND deletedAt IS NULL ORDER BY nextReviewAt ASC")
    fun getArchivedUnits(): Flow<List<StudyUnitEntity>>

    // One-shot variants for backup/analytics: reading via Flows takes each table at a different
    // moment; export wraps these in ONE transaction so the snapshot is internally consistent.
    @Query("SELECT * FROM study_units WHERE archived = 0 ORDER BY nextReviewAt ASC")
    suspend fun getAllActiveOnce(): List<StudyUnitEntity>

    @Query("SELECT * FROM study_units WHERE archived = 1 AND deletedAt IS NULL ORDER BY nextReviewAt ASC")
    suspend fun getArchivedOnce(): List<StudyUnitEntity>

    @Query("SELECT * FROM study_units WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    suspend fun getRecentlyDeletedOnce(): List<StudyUnitEntity>

    // --- 30-day recoverable soft delete (DB v5). archived=1 keeps every active-list query clean. ---

    @Query("UPDATE study_units SET deletedAt = :now, archived = 1, updatedAt = :now WHERE id = :id")
    suspend fun softDeleteUnit(id: Long, now: Long)

    /** Restore straight to the ACTIVE library — the user asked for it back; don't hide it in the archive. */
    @Query("UPDATE study_units SET deletedAt = NULL, archived = 0, updatedAt = :stamp WHERE id = :id")
    suspend fun restoreDeletedUnit(id: Long, stamp: Long)

    @Query("SELECT * FROM study_units WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun getRecentlyDeleted(): Flow<List<StudyUnitEntity>>

    @Query("SELECT id FROM study_units WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun getPurgeCandidateIds(cutoff: Long): List<Long>

    @Query("DELETE FROM study_units WHERE id IN (:ids)")
    suspend fun hardDeleteUnits(ids: List<Long>)

    @Query("UPDATE study_units SET archived = 1, updatedAt = :stamp WHERE id = :id")
    suspend fun archiveUnit(id: Long, stamp: Long)

    /**
     * Un-archive, but ONLY a row that is not in the recycle bin. Active queries filter on
     * `archived = 0` and rely on the invariant "soft-deleted implies archived"; un-archiving a
     * deleted row would break that invariant and produce a zombie — visible and reviewable, yet still
     * carrying a deletedAt that the 30-day purge would eventually act on. Restoring from the recycle
     * bin is restoreDeletedUnit's job, which clears deletedAt as well.
     */
    @Query("UPDATE study_units SET archived = 0, updatedAt = :stamp WHERE id = :id AND deletedAt IS NULL")
    suspend fun unarchiveUnit(id: Long, stamp: Long)
    
    @Query("SELECT COUNT(*) FROM study_units WHERE archived = 0")
    fun getTotalActiveUnitsCount(): Flow<Int>

    /** Every row, archived and recently deleted too: whether a backup would have anything in it. */
    @Query("SELECT COUNT(*) FROM study_units")
    suspend fun countAllOnce(): Int
    
    @Query("SELECT COUNT(*) FROM study_units WHERE archived = 0 AND state = :state")
    fun getCountByState(state: String): Flow<Int>

    @Query("DELETE FROM study_units")
    suspend fun deleteAllUnits()
}
