package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.database.AppDatabase
import com.example.data.local.dao.ReviewLogDao
import com.example.data.local.entity.ReviewLogEntity
import com.example.data.local.entity.StudyUnitEntity
import com.example.data.repository.MedReviewRepository
import com.example.domain.model.MemoryRating
import com.example.domain.model.SessionKind
import com.example.domain.model.UnderstandingRating
import com.example.domain.srs.MedScheduler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReviewPolicySnapshotTest {
    @Test fun `a settings refresh during the review read cannot change its captured scheduling context`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val oldRetention = MedScheduler.userRetention
        try {
            MedScheduler.userRetention = 0.9
            MedScheduler.calibrationScale = 1.0
            MedScheduler.activeParameterSet = MedScheduler.DEFAULT_PARAMETER_SET
            MedScheduler.knownParameterSets = emptyMap()
            var changed = false
            val real = db.reviewLogDao()
            val duringRead = object : ReviewLogDao by real {
                override suspend fun getLogsForUnitOnce(unitId: Long): List<ReviewLogEntity> {
                    val logs = real.getLogsForUnitOnce(unitId)
                    // The queued settings/calibration refresh occurs during the suspendable streak read.
                    MedScheduler.userRetention = 0.95
                    MedScheduler.calibrationScale = 0.5
                    changed = true
                    return logs
                }
            }
            val repo = MedReviewRepository(db.studyUnitDao(), db.categoryDao(), duringRead, db)
            val origin = 1_790_000_000_000L
            val id = repo.insertUnit(StudyUnitEntity(title = "Asthma", studyType = "Topic", highYield = true,
                studiedAt = origin, nextReviewAt = origin, modelDueAt = origin, memoryModel = "FSRS-6"))
            val rated = repo.rateUnit(id, origin, MemoryRating.Good, UnderstandingRating.Clear,
                sessionKind = SessionKind.TOPIC, reviewDurationMs = 1000)!!
            assertTrue("the context actually changed during the read", changed)
            val log = real.getLogsForUnitOnce(id).single()
            assertEquals("captured Important retention is also the value stored for replay", 0.93, log.desiredRetentionAtReview, 1e-12)
            assertEquals(1.0, log.calibrationScaleAtReview, 0.0)
            val expected = MedScheduler.review(1.0, 5.0, 0.0, MemoryRating.Good, UnderstandingRating.Clear,
                highYield = true, reviewNumber = 0, model = MedScheduler.MemoryModel.FSRS_6,
                desiredRetentionOverride = 0.93, calibrationScaleOverride = 1.0, parameterSetId = 0)
            val interval = MedScheduler.fuzzedInterval(expected.intervalDays, expected.baseIntervalDays, id, 0, true)
            assertEquals(interval, rated.memoryIntervalDays, 1e-12)
            val forecast = db.eventLogDao().getAll().single { it.type == "REVIEW_FORECAST" }
            val fields = forecast.detail!!.split(" ").associate { it.substringBefore("=") to it.substringAfter("=") }
            assertEquals(log.desiredRetentionAtReview, fields["retention"]!!.toDouble(), 0.0)
            assertEquals(log.calibrationScaleAtReview, fields["scale"]!!.toDouble(), 0.0)
        } finally {
            db.close()
            MedScheduler.userRetention = oldRetention
            MedScheduler.activeParameterSet = MedScheduler.DEFAULT_PARAMETER_SET
            MedScheduler.knownParameterSets = emptyMap()
            MedScheduler.calibrationScale = 1.0
        }
    }
}
