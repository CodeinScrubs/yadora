package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.MedReviewApplication
import com.example.data.local.entity.EventLogEntity
import com.example.data.local.entity.ReviewLogEntity
import com.example.data.local.entity.StudyUnitEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The backup and the research export are streamed, record by record ([JsonStreams]), because building them
 * as one tree and one String ran a multi-year history out of memory. These pin that the stream is still the
 * same format every older build reads and writes, and that what it refuses, it refuses before deleting
 * anything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BackupStreamingTest {

    private val day = 86_400_000L

    private suspend fun seed(app: MedReviewApplication, topics: Int, logsPerTopic: Int): Long {
        val db = app.database
        val now = System.currentTimeMillis()
        var firstId = -1L
        repeat(topics) { t ->
            val id = db.studyUnitDao().insertUnit(
                StudyUnitEntity(
                    title = "موضوع $t — topic $t", studyType = "Topic", notes = "line one\nline \"two\" \\ three",
                    stability = 2.0 + t, difficulty = 5.0, retrievability = 0.9, state = "Building",
                    studiedAt = now - 90 * day, nextReviewAt = now + day, modelDueAt = now + day,
                    currentIntervalDays = 4.0, reviewCount = logsPerTopic, memoryModel = "FSRS-6",
                )
            )
            if (firstId < 0) firstId = id
            repeat(logsPerTopic) { k ->
                db.reviewLogDao().insertLog(
                    ReviewLogEntity(
                        studyUnitId = id, reviewedAt = now - (80 - k * 10) * day,
                        memoryRating = if (k == 0) "Good" else listOf("Good", "Hard", "Forgot", "Easy")[k % 4],
                        understandingRating = "Clear", previousIntervalDays = 2.0, nextIntervalDays = 4.0,
                        previousState = "Learning", nextState = "Building", retrievabilityAtReview = 0.91,
                        elapsedDays = 3.0, logType = if (k == 0) "FIRST_STUDY" else "RECALL",
                        schedulerVersion = "FSRS-6", desiredRetentionAtReview = 0.9,
                        reviewMethods = if (k % 2 == 0) "Questions" else null, questionsCorrect = if (k % 2 == 0) 3 else -1,
                        questionsTotal = if (k % 2 == 0) 5 else -1, sessionKind = "PLAN",
                    )
                )
            }
        }
        db.eventLogDao().insert(EventLogEntity(type = "SNOOZE", detail = "x"))
        return firstId
    }

    private suspend fun wipe(app: MedReviewApplication) {
        val db = app.database
        db.eventLogDao().deleteAll(); db.reviewLogDao().deleteAllLogs(); db.studyUnitDao().deleteAllUnits()
        db.categoryDao().deleteAllSubjects(); db.categoryDao().deleteAllSystems()
    }

    @Test
    fun `a streamed backup round-trips a few thousand reviews, text and all`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MedReviewApplication>()
        val firstId = seed(app, topics = 300, logsPerTopic = 10)
        val before = app.database.reviewLogDao().getAllLogsOnce()

        val out = java.io.ByteArrayOutputStream()
        BackupManager.writeBackup(app, out)
        wipe(app)
        val restored = BackupManager.restoreFromStream(app, java.io.ByteArrayInputStream(out.toByteArray()))

        assertEquals(300, restored)
        val after = app.database.reviewLogDao().getAllLogsOnce()
        assertEquals("every review comes back, field for field", before.sortedBy { it.id }, after.sortedBy { it.id })
        val unit = app.database.studyUnitDao().getUnitById(firstId)!!
        assertEquals("Persian, quotes, backslashes and newlines survive the stream", "موضوع 0 — topic 0", unit.title)
        assertEquals("line one\nline \"two\" \\ three", unit.notes)
    }

    @Test
    fun `a backup written by an older build, pretty-printed, still restores`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MedReviewApplication>()
        seed(app, topics = 5, logsPerTopic = 3)
        val before = app.database.reviewLogDao().getAllLogsOnce().sortedBy { it.id }
        // Older builds wrote org.json's toString(2): indented, and whole doubles as integers ("4" for 4.0).
        val oldStyle = JSONObject(BackupManager.buildBackupJson(app)).toString(2)
        assertTrue("the fixture really is the old style", oldStyle.contains("\"nextIntervalDays\": 4,"))
        wipe(app)
        assertEquals(5, BackupManager.restoreFromJson(app, oldStyle))
        assertEquals(before, app.database.reviewLogDao().getAllLogsOnce().sortedBy { it.id })
    }

    @Test
    fun `a byte-order mark from a text editor does not break a restore`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MedReviewApplication>()
        seed(app, topics = 2, logsPerTopic = 2)
        val json = BackupManager.buildBackupJson(app)
        wipe(app)
        assertEquals(2, BackupManager.restoreFromJson(app, "﻿" + json))
    }

    @Test
    fun `a file that is not a backup is refused before anything is deleted`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MedReviewApplication>()
        val id = seed(app, topics = 1, logsPerTopic = 2)
        for (garbage in listOf("PK\u0003\u0004 not json at all", "", "[1, 2, 3]", "{\"hello\": \"world\"}", "{\"studyUnits\": [")) {
            val result = runCatching { BackupManager.restoreFromJson(app, garbage) }
            assertTrue("must refuse: $garbage", result.isFailure)
            assertEquals("and leave the data alone", 2, app.database.reviewLogDao().getLogsForUnit(id).first().size)
        }
    }

    @Test
    fun `the streamed export is valid JSON and counts deferrals per window exactly`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MedReviewApplication>()
        val db = app.database
        val now = System.currentTimeMillis()
        val id = db.studyUnitDao().insertUnit(
            StudyUnitEntity(
                title = "t", studyType = "Topic", stability = 3.0, difficulty = 5.0, retrievability = 0.9, state = "Building",
                studiedAt = now - 30 * day, nextReviewAt = now + day, modelDueAt = now + day, currentIntervalDays = 3.0,
                reviewCount = 3, memoryModel = "FSRS-6",
            )
        )
        val times = listOf(now - 30 * day, now - 20 * day, now - 10 * day)
        val logIds = times.mapIndexed { k, at ->
            db.reviewLogDao().insertLog(
                ReviewLogEntity(
                    studyUnitId = id, reviewedAt = at, memoryRating = "Good", understandingRating = "Clear",
                    previousIntervalDays = 3.0, nextIntervalDays = 3.0, previousState = "Learning", nextState = "Building",
                    logType = if (k == 0) "FIRST_STUDY" else "RECALL",
                )
            )
        }
        // Between the 1st and 2nd review: one own deferral and one bulk ("not today" on everything).
        db.eventLogDao().insert(EventLogEntity(type = "PROCRASTINATE", unitId = id, at = now - 25 * day, detail = "x"))
        db.eventLogDao().insert(EventLogEntity(type = "PROCRASTINATE_ALL", at = now - 24 * day))
        // Between the 2nd and 3rd: another topic's deferral (must not count) and one of this topic's.
        db.eventLogDao().insert(EventLogEntity(type = "PROCRASTINATE", unitId = id + 1000, at = now - 15 * day, detail = "x"))
        db.eventLogDao().insert(EventLogEntity(type = "REDISTRIBUTE", unitId = id, at = now - 12 * day))
        // Exactly at the 3rd review's instant: the window is inclusive at both ends, as it always was.
        db.eventLogDao().insert(EventLogEntity(type = "PROCRASTINATE_ALL", at = now - 10 * day))

        val out = java.io.ByteArrayOutputStream()
        AnalyticsExporter.writeJson(app, out)
        val json = JSONObject(out.toString("UTF-8"))
        val logs = json.getJSONArray("reviewLogs")
        val byId = (0 until logs.length()).map { logs.getJSONObject(it) }.associateBy { it.getLong("id") }
        assertEquals(0, byId.getValue(logIds[0]).getInt("deferralsBeforeThisReview"))
        assertEquals(2, byId.getValue(logIds[1]).getInt("deferralsBeforeThisReview"))
        assertEquals(2, byId.getValue(logIds[2]).getInt("deferralsBeforeThisReview"))
        assertEquals("the file still explains itself", true, json.has("fieldGuide"))
        assertEquals(0, json.getJSONObject("consistency").getInt("issueCount"))
    }

    @Test
    fun `delete all data also removes the last research export`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        seed(app as MedReviewApplication, topics = 1, logsPerTopic = 1)
        val file = AnalyticsExporter.writeShareableFile(app)
        assertTrue(file.exists())
        BackupManager.deleteAllData(app)
        assertTrue("nothing personal lingers in the cache", !file.exists())
    }
}
