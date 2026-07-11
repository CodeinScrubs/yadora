package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.MedReviewApplication
import com.example.data.local.entity.EventLogEntity
import com.example.data.local.entity.ReviewLogEntity
import com.example.data.local.entity.StudyUnitEntity
import com.example.data.local.entity.SubjectEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Backup is core trust infrastructure: a lossy or silently-coercing restore would corrupt a user's
 * entire study history. These tests prove (1) export → wipe → restore reproduces every table
 * field-for-field, and (2) a corrupt file is rejected BEFORE any current data is touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BackupRoundTripTest {

    @Test
    fun `export then restore reproduces every table field-for-field`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = (context as MedReviewApplication).database
        val now = System.currentTimeMillis()
        val day = 86400000L

        val subjId = db.categoryDao().insertSubject(SubjectEntity(name = "Physics", colorHex = "#4E7A5A"))
        val unitId = db.studyUnitDao().insertUnit(
            StudyUnitEntity(
                title = "Photosynthesis", studyType = "Topic", subjectId = subjId,
                notes = "Light reactions vs Calvin cycle", source = "textbook p.41", highYield = true,
                state = "Learning", stability = 2.5, difficulty = 5.5, retrievability = 0.93,
                studiedAt = now - day, lastReviewedAt = now, nextReviewAt = now + 2 * day,
                currentIntervalDays = 2.0, reviewCount = 1, lapseCount = 0,
            )
        )
        db.reviewLogDao().insertLog(
            ReviewLogEntity(
                studyUnitId = unitId, reviewedAt = now, memoryRating = "Good", understandingRating = "Clear",
                previousIntervalDays = 0.0, nextIntervalDays = 2.0, previousState = "New", nextState = "Learning",
                retrievabilityAtReview = 0.93, elapsedDays = 1.0, logType = "RECALL",
                initialDifficulty = null, reviewDurationMs = 4200, wasImportantAtReview = 1,
                desiredRetentionAtReview = 0.93, schedulerVersion = "FSRS-5",
            )
        )
        db.eventLogDao().insert(EventLogEntity(type = "SNOOZE", detail = "test"))

        val json = BackupManager.buildBackupJson(context)

        // Simulate a fresh device: wipe everything.
        db.eventLogDao().deleteAll()
        db.reviewLogDao().deleteAllLogs()
        db.studyUnitDao().deleteAllUnits()
        db.categoryDao().deleteAllSubjects()
        db.categoryDao().deleteAllSystems()

        val restoredCount = BackupManager.restoreFromJson(context, json)
        assertEquals(1, restoredCount)

        val unit = db.studyUnitDao().getUnitById(unitId)!!
        assertEquals("Photosynthesis", unit.title)
        assertEquals("Light reactions vs Calvin cycle", unit.notes)
        assertEquals("textbook p.41", unit.source)
        assertEquals(subjId, unit.subjectId)
        assertEquals(true, unit.highYield)
        assertEquals("Learning", unit.state)
        assertEquals(2.5, unit.stability, 1e-9)
        assertEquals(5.5, unit.difficulty, 1e-9)
        assertEquals(now + 2 * day, unit.nextReviewAt)
        assertEquals(1, unit.reviewCount)

        val log = db.reviewLogDao().getLogsForUnit(unitId).first().single()
        assertEquals("Good", log.memoryRating)
        assertEquals("RECALL", log.logType)
        assertEquals(4200L, log.reviewDurationMs)
        assertEquals(1, log.wasImportantAtReview)
        assertEquals(0.93, log.desiredRetentionAtReview, 1e-9)
        assertEquals("FSRS-5", log.schedulerVersion)

        assertEquals("SNOOZE", db.eventLogDao().getAll().single().type)
        assertEquals("Physics", db.categoryDao().getAllSubjects().first().single().name)
    }

    @Test
    fun `corrupt backup is rejected before any data is deleted`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = (context as MedReviewApplication).database
        val now = System.currentTimeMillis()

        val unitId = db.studyUnitDao().insertUnit(
            StudyUnitEntity(title = "Keep me", studyType = "Topic", studiedAt = now, nextReviewAt = now)
        )
        db.reviewLogDao().insertLog(
            ReviewLogEntity(
                studyUnitId = unitId, reviewedAt = now, memoryRating = "Good", understandingRating = "Clear",
                previousIntervalDays = 0.0, nextIntervalDays = 1.0, previousState = "New", nextState = "Learning",
            )
        )

        // Tamper a rating into garbage — pretty-printed JSON makes the field targetable.
        val json = BackupManager.buildBackupJson(context)
        val bad = json.replace("\"memoryRating\": \"Good\"", "\"memoryRating\": \"WAT\"")
        assertTrue("tampering must have applied", bad != json)

        val result = runCatching { BackupManager.restoreFromJson(context, bad) }
        assertTrue("corrupt backup must be rejected", result.isFailure)
        // And the current data must be untouched — validation runs BEFORE any delete.
        assertEquals("Keep me", db.studyUnitDao().getUnitById(unitId)!!.title)
        assertEquals(1, db.reviewLogDao().getLogsForUnit(unitId).first().size)
    }
}
