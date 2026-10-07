package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PersonalFitSnapshotTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: MedReviewRepository
    private val origin = 1_790_000_000_000L
    private val day = 86_400_000L
    private var savedRetention = 0.9

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = MedReviewRepository(db.studyUnitDao(), db.categoryDao(), db.reviewLogDao(), db)
        MedScheduler.activeParameterSet = MedScheduler.DEFAULT_PARAMETER_SET
        MedScheduler.knownParameterSets = emptyMap()
        MedScheduler.calibrationScale = 1.0
        savedRetention = MedScheduler.userRetention
        MedScheduler.userRetention = 0.9
    }

    @After fun teardown() { db.close(); MedScheduler.userRetention = savedRetention }

    private suspend fun history(): Long {
        val id = repo.insertUnit(StudyUnitEntity(title = "Asthma", studyType = "Topic", studiedAt = origin,
            nextReviewAt = origin, modelDueAt = origin, memoryModel = "FSRS-6"))
        for (at in listOf(origin, origin + 4 * day)) repo.rateUnit(id, at, MemoryRating.Good, UnderstandingRating.Clear,
            sessionKind = SessionKind.TOPIC, reviewDurationMs = 1000)
        return id
    }

    @Test fun `restoring different stored elapsed days invalidates an in-flight fit`() = runBlocking {
        val id = history()
        val max = db.reviewLogDao().maxLogId()
        val before = repo.fitIdentity(max)
        val last = db.reviewLogDao().getLogsForUnitOnce(id).maxBy { it.id }
        db.reviewLogDao().insertLog(last.copy(elapsedDays = 12.0))
        assertEquals(12.0, repo.trainingHistories().single().elapsedDays.last(), 0.0)
        assertNotEquals("the exact training input changed although ids, ratings and timestamps did not", before, repo.fitIdentity(max))
    }

    @Test fun `merge exclusions invalidate an in-flight fit even without reassigning its logs`() = runBlocking {
        val id = history()
        val max = db.reviewLogDao().maxLogId()
        val before = repo.fitIdentity(max)
        // Merging an unrated duplicate into this survivor changes membership but none of its old logs.
        val duplicate = repo.insertUnit(StudyUnitEntity(title = "Asthma duplicate", studyType = "Topic",
            studiedAt = origin, nextReviewAt = origin, modelDueAt = origin))
        repo.mergeUnits(id, listOf(duplicate))
        assertTrue(repo.trainingHistories().isEmpty())
        assertNotEquals("the fit must not adopt evidence now excluded as a merged history", before, repo.fitIdentity(max))
    }

    @Test fun `a zone change invalidates a fit that reconstructs legacy elapsed days`() = runBlocking {
        val savedZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tehran"))
            val id = history()
            val rows = db.reviewLogDao().getLogsForUnitOnce(id).sortedBy { it.id }
            val start = java.time.Instant.parse("2026-09-01T20:00:00Z").toEpochMilli()
            db.reviewLogDao().insertLog(rows[0].copy(reviewedAt = start))
            db.reviewLogDao().insertLog(rows[1].copy(reviewedAt = start + 3_600_000, elapsedDays = -1.0))
            val max = db.reviewLogDao().maxLogId()
            val before = repo.fitIdentity(max)
            assertEquals(1.0, repo.trainingHistories().single().elapsedDays.last(), 0.0)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertEquals(0.0, repo.trainingHistories().single().elapsedDays.last(), 0.0)
            assertNotEquals("legacy training inputs changed while the fit ran", before, repo.fitIdentity(max))
        } finally { TimeZone.setDefault(savedZone) }
    }

    @Test fun `a changed retention target invalidates the interval adoption check`() = runBlocking {
        history()
        val max = db.reviewLogDao().maxLogId()
        val before = repo.fitIdentity(max)
        MedScheduler.userRetention = 0.95
        assertNotEquals(before, repo.fitIdentity(max))
    }

    @Test fun `training preserves physical timestamps but validation follows saved ids after a rollback`() = runBlocking {
        val id = history()
        repo.rateUnit(id, origin + day, MemoryRating.Hard, UnderstandingRating.Clear,
            sessionKind = SessionKind.TOPIC, reviewDurationMs = 1000)
        repo.rateUnit(id, origin + 2 * day, MemoryRating.Good, UnderstandingRating.Clear,
            sessionKind = SessionKind.TOPIC, reviewDurationMs = 1000)
        val logs = db.reviewLogDao().getLogsForUnitOnce(id).sortedBy { it.id }
        val h = repo.trainingHistories().single()
        assertArrayEquals(logs.map { it.reviewedAt }.toLongArray(), h.reviewedAt)
        assertArrayEquals(logs.map { it.id }.toLongArray(), h.validationOrder)
        assertArrayEquals(doubleArrayOf(0.0, 4.0, 0.0, 1.0), h.elapsedDays, 0.0)
    }
}
