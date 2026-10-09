package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.MedReviewApplication
import com.example.data.local.database.AppDatabase
import com.example.data.local.entity.EventLogEntity
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
class BackupIdentityTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repo: MedReviewRepository
    private val start = 1_790_000_000_000L
    private val day = 86_400_000L

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
    private suspend fun topic() = repo.insertUnit(StudyUnitEntity(
        title = "Topic", studyType = "Topic", studiedAt = start, nextReviewAt = start, modelDueAt = start,
    ))
    private suspend fun rate(id: Long, at: Long) = repo.rateUnit(id, at, MemoryRating.Good,
        UnderstandingRating.Clear, sessionKind = SessionKind.TOPIC, reviewDurationMs = -1)!!
    private suspend fun freshRestore(backup: String) {
        BackupManager.deleteAllData(context)
        // An empty installation has none of the old phone's AUTOINCREMENT high-water marks.
        db.openHelper.writableDatabase.execSQL("DELETE FROM sqlite_sequence")
        BackupManager.restoreFromJson(context, backup)
    }

    @Test fun `a fresh restore cannot reuse a purged review id and erase past growth on undo`() = runBlocking {
        val survivor = topic(); rate(survivor, start)
        val removed = topic(); val gone = rate(removed, start + day)
        repo.softDeleteUnit(removed); repo.purgeExpiredDeleted(graceMillis = -1)
        assertNull(repo.getUnitById(removed))
        freshRestore(BackupManager.buildBackupJson(context))
        val newReview = rate(survivor, start + 4 * day)
        assertTrue("the old growth event must not alias a newly saved review", newReview.logId > gone.logId)
        // This is Undo's growth-event deletion on both the main and guarded-Undo implementations.
        db.eventLogDao().deleteStudyActionForLog(newReview.logId.toString())
        assertEquals("earned growth of the purged topic survives", 2,
            db.eventLogDao().getAll().count { it.type == "STUDY_ACTION" })
    }

    @Test fun `an empty restored library still reserves ids used by retained growth events`() = runBlocking {
        val removed = topic(); val gone = rate(removed, start)
        repo.softDeleteUnit(removed); repo.purgeExpiredDeleted(graceMillis = -1)
        assertEquals(0, db.studyUnitDao().countAllOnce())
        assertTrue(db.reviewLogDao().getAllLogsOnce().isEmpty())
        freshRestore(BackupManager.buildBackupJson(context))
        val next = topic(); val newReview = rate(next, start + day)
        assertTrue(next > removed)
        assertTrue(newReview.logId > gone.logId)
        db.eventLogDao().deleteStudyActionForLog(newReview.logId.toString())
        assertEquals(1, db.eventLogDao().getAll().count { it.type == "STUDY_ACTION" })
    }

    @Test fun `a new topic cannot become the absorbed id of an old merge after restore`() = runBlocking {
        val a = topic(); val b = topic(); rate(a, start); rate(b, start + day)
        repo.mergeUnits(a, listOf(b))!!
        repo.purgeExpiredDeleted(graceMillis = -1)
        freshRestore(BackupManager.buildBackupJson(context))
        val next = topic()
        assertTrue("new topic must not be classified as an old absorbed copy", next > b)
        assertEquals(1, db.eventLogDao().getMergeEvents().size)
    }

    @Test fun `older backups reserve correction bounds and prospective keys when their rows are gone`() = runBlocking {
        val id = topic(); rate(id, start)
        db.eventLogDao().insert(EventLogEntity(type = "RATING_CORRECTED", unitId = 45,
            detail = "log=70 memory=Good>Hard understanding=Clear>Partial upto=80"))
        db.eventLogDao().insert(EventLogEntity(type = "REVIEW_FORECAST", unitId = 60, detail = "log=90 v=1 at=1000"))
        freshRestore(BackupManager.buildBackupJson(context))
        assertTrue(topic() > 60)
        assertTrue(rate(id, start + 4 * day).logId > 90)
    }

    @Test fun `malformed unrelated details cannot become sql or invent an identity`() {
        val events = listOf(
            EventLogEntity(type = "STUDY_ACTION", unitId = 5, detail = "1; DROP TABLE review_logs"),
            EventLogEntity(type = "MERGE", detail = "2, -1, nope, 8"),
            EventLogEntity(type = "UNKNOWN", detail = "log=50000 upto=80000"),
            EventLogEntity(type = "REVIEW_FORECAST", detail = "log=12 v=1 at=999999 upto=80000"),
        )
        assertEquals(BackupIdentity.Floors(topic = 8, review = 12), BackupIdentity.floors(listOf(3), listOf(4), events))
    }

    @Test fun `an exhausted historical allocator is refused before any user data changes`() = runBlocking {
        val id = topic(); rate(id, start)
        val before = BackupManager.buildBackupJson(context)
        val invalid = org.json.JSONObject(before)
        invalid.getJSONArray("eventLogs").put(org.json.JSONObject().apply {
            put("id", 999); put("type", "STUDY_ACTION"); put("unitId", id); put("detail", Long.MAX_VALUE.toString())
        })
        assertTrue(runCatching { BackupManager.restoreFromJson(context, invalid.toString()) }.isFailure)
        assertEquals(1, db.reviewLogDao().getAllLogsOnce().size)
        assertEquals(1, db.eventLogDao().getAll().count { it.type == "STUDY_ACTION" })
    }

    @Test fun `restore never lowers an existing allocator with a higher watermark`() = runBlocking {
        val id = topic(); rate(id, start)
        val backup = BackupManager.buildBackupJson(context)
        db.openHelper.writableDatabase.execSQL("UPDATE sqlite_sequence SET seq=1000 WHERE name='review_logs'")
        db.openHelper.writableDatabase.execSQL("UPDATE sqlite_sequence SET seq=1000 WHERE name='study_units'")
        BackupManager.restoreFromJson(context, backup)
        assertTrue(topic() > 1000)
        assertTrue(rate(id, start + 4 * day).logId > 1000)
    }
}
