package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.MedReviewApplication
import com.example.data.local.database.AppDatabase
import com.example.data.local.entity.StudyUnitEntity
import com.example.data.repository.MedReviewRepository
import com.example.domain.model.MemoryRating
import com.example.domain.model.SessionKind
import com.example.domain.model.UnderstandingRating
import com.example.domain.srs.MedScheduler
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReviewForecastTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repo: MedReviewRepository
    private val day = 86_400_000L
    private val start = 1_790_000_000_000L

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = (context as MedReviewApplication).database
        repo = MedReviewRepository(db.studyUnitDao(), db.categoryDao(), db.reviewLogDao(), db)
        MedScheduler.activeParameterSet = MedScheduler.DEFAULT_PARAMETER_SET
        MedScheduler.knownParameterSets = emptyMap()
        MedScheduler.calibrationScale = 1.0
    }

    @After fun teardown() {
        db.close()
        MedScheduler.activeParameterSet = MedScheduler.DEFAULT_PARAMETER_SET
        MedScheduler.knownParameterSets = emptyMap()
        MedScheduler.calibrationScale = 1.0
    }

    private suspend fun topic(): Long = repo.insertUnit(StudyUnitEntity(
        title = "آسم — private title", studyType = "Topic", studiedAt = start, nextReviewAt = start, modelDueAt = start,
    ))
    private suspend fun rate(id: Long, at: Long, grade: MemoryRating = MemoryRating.Good) =
        repo.rateUnit(id, at, grade, UnderstandingRating.Clear, sessionKind = SessionKind.TOPIC, reviewDurationMs = -1)!!
    private suspend fun forecasts() = db.eventLogDao().getAll().filter { it.type == ReviewForecast.EVENT }
    private fun fields(detail: String) = detail.split(' ').associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test fun `a forecast contains the actual pre-review state and never topic content`() = runBlocking {
        val id = topic(); rate(id, start)
        val before = repo.getUnitById(id)!!
        val rated = rate(id, start + 4 * day)
        val log = db.reviewLogDao().getLogsForUnitOnce(id).single { it.id == rated.logId }
        val e = forecasts().single { fields(it.detail!!)["log"] == rated.logId.toString() }
        val f = fields(e.detail!!)
        assertEquals(before.stability, f.getValue("stability").toDouble(), 0.0)
        assertEquals(before.difficulty, f.getValue("difficulty").toDouble(), 0.0)
        assertEquals(before.currentIntervalDays, f.getValue("previousInterval").toDouble(), 0.0)
        assertEquals(before.nextReviewAt, f.getValue("effectiveDue").toLong())
        assertEquals(log.retrievabilityAtReview, f.getValue("p").toDouble(), 0.0)
        assertEquals(log.elapsedDays, f.getValue("elapsed").toDouble(), 0.0)
        assertEquals("RECALL", f["type"])
        assertEquals("Good", f["memory"])
        assertEquals(log.reviewedAt, e.at)
        assertFalse(e.detail!!.contains("private"))
        assertEquals("FIRST_STUDY", fields(forecasts().first().detail!!)["type"])
    }

    @Test fun `rating corrections and pure replay preserve all original forecasts`() = runBlocking {
        val id = topic(); rate(id, start)
        val a = rate(id, start + 4 * day)
        rate(id, start + 16 * day)
        val original = forecasts()
        repo.editReviewRating(id, a.logId, MemoryRating.Hard, UnderstandingRating.Partial)
        assertEquals(original, forecasts())
        val unit = repo.getUnitById(id)!!
        repo.updateUnitReplayingHistory(unit.copy(studiedAt = start - day))
        assertEquals(original, forecasts())
        // The mutable replay did change, demonstrating that the immutable evidence is doing real work.
        assertEquals("Hard", db.reviewLogDao().getLogsForUnitOnce(id).single { it.id == a.logId }.memoryRating)
        assertEquals("Good", fields(original.single { fields(it.detail!!)["log"] == a.logId.toString() }.detail!!)["memory"])
    }

    @Test fun `merge retains original log keys and undo removes exactly its forecast`() = runBlocking {
        val a = topic(); val b = topic()
        rate(a, start); rate(b, start + day)
        val original = forecasts()
        repo.mergeUnits(a, listOf(b))!!
        assertEquals(original, forecasts())
        val logIds = db.reviewLogDao().getLogsForUnitOnce(a).map { it.id }.toSet()
        assertTrue(original.all { fields(it.detail!!).getValue("log").toLong() in logIds })
        val review = rate(a, start + 6 * day)
        assertEquals(3, forecasts().size)
        assertTrue(repo.undoReview(review))
        assertEquals(original, forecasts())
    }

    @Test fun `backup and research export preserve forecasts without backfilling legacy rows`() = runBlocking {
        val id = topic(); rate(id, start); rate(id, start + 3 * day)
        val original = forecasts()
        val research = org.json.JSONObject(AnalyticsExporter.buildJson(context)).getJSONArray("eventLogs")
        assertEquals(original.size, (0 until research.length()).count { research.getJSONObject(it).getString("type") == ReviewForecast.EVENT })
        val backup = BackupManager.buildBackupJson(context)
        BackupManager.restoreFromJson(context, backup)
        assertEquals(original, forecasts())
        val legacy = org.json.JSONObject(backup)
        val events = legacy.getJSONArray("eventLogs")
        legacy.put("eventLogs", org.json.JSONArray().apply {
            for (i in 0 until events.length()) if (events.getJSONObject(i).getString("type") != ReviewForecast.EVENT) put(events.getJSONObject(i))
        })
        BackupManager.restoreFromJson(context, legacy.toString())
        assertTrue(forecasts().isEmpty())
        assertEquals(2, db.reviewLogDao().getLogsForUnitOnce(id).size)
    }

    @Test fun `forecast insertion failure rolls back schedule log and study action`() = runBlocking {
        val id = topic()
        val before = repo.getUnitById(id)
        val logs = db.reviewLogDao().getAllLogsOnce()
        val events = db.eventLogDao().getAll()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_forecast BEFORE INSERT ON event_logs " +
            "WHEN NEW.type = 'REVIEW_FORECAST' BEGIN SELECT RAISE(ABORT, 'injected forecast failure'); END")
        assertTrue(runCatching { rate(id, start) }.isFailure)
        assertEquals(before, repo.getUnitById(id))
        assertEquals(logs, db.reviewLogDao().getAllLogsOnce())
        assertEquals(events, db.eventLogDao().getAll())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_forecast")
        rate(id, start)
        assertEquals(1, forecasts().size)
    }

    @Test fun `clock rollback still records forecasts in save order and explicit time zone`() = runBlocking {
        val id = topic(); rate(id, start)
        val a = rate(id, start + 5 * day)
        val b = rate(id, start + 2 * day)
        val f = forecasts().associate { fields(it.detail!!).getValue("log").toLong() to fields(it.detail!!) }
        assertTrue(b.logId > a.logId)
        assertEquals("0.0", f.getValue(b.logId)["elapsed"])
        assertEquals(java.time.ZoneId.systemDefault().id, f.getValue(b.logId)["zone"])
    }
}
