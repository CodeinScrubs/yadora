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

    @Query("SELECT COUNT(*) FROM study_units WHERE archived = 0 AND nextReviewAt <= :cutoffTime")
    suspend fun getDueCount(cutoffTime: Long): Int

    /** Due units for the rich reminder — high-yield first, then soonest-due. One-shot (not a Flow). */
    @Query("SELECT * FROM study_units WHERE archived = 0 AND nextReviewAt <= :cutoffTime ORDER BY highYield DESC, nextReviewAt ASC")
    suspend fun getDueUnitsList(cutoffTime: Long): List<StudyUnitEntity>

    /** Push every currently-due unit to [tomorrow] ("Not today" / procrastinate all). */
    @Query("UPDATE study_units SET nextReviewAt = :tomorrow, updatedAt = :stamp WHERE archived = 0 AND nextReviewAt <= :now")
    suspend fun procrastinateAllDue(now: Long, tomorrow: Long, stamp: Long)

    @Query("SELECT * FROM study_units WHERE id = :id")
    suspend fun getUnitById(id: Long): StudyUnitEntity?

    /** Active units sharing a title (case/space-insensitive) — used for duplicate detection. */
    @Query("SELECT * FROM study_units WHERE archived = 0 AND lower(trim(title)) = lower(trim(:title))")
    suspend fun findActiveByTitle(title: String): List<StudyUnitEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUnit(unit: StudyUnitEntity): Long

    @Update
    suspend fun updateUnit(unit: StudyUnitEntity)

    @Query("SELECT * FROM study_units WHERE archived = 1 ORDER BY nextReviewAt ASC")
    fun getArchivedUnits(): Flow<List<StudyUnitEntity>>

    @Query("UPDATE study_units SET archived = 1, updatedAt = :stamp WHERE id = :id")
    suspend fun archiveUnit(id: Long, stamp: Long)

    @Query("UPDATE study_units SET archived = 0, updatedAt = :stamp WHERE id = :id")
    suspend fun unarchiveUnit(id: Long, stamp: Long)
    
    @Query("SELECT COUNT(*) FROM study_units WHERE archived = 0")
    fun getTotalActiveUnitsCount(): Flow<Int>
    
    @Query("SELECT COUNT(*) FROM study_units WHERE archived = 0 AND state = :state")
    fun getCountByState(state: String): Flow<Int>

    @Query("DELETE FROM study_units")
    suspend fun deleteAllUnits()
}
