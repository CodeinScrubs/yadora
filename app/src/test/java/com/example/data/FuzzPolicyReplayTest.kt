package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.database.AppDatabase
import com.example.data.local.entity.MemoryParameterSetEntity
import com.example.data.local.entity.StudyUnitEntity
import com.example.data.repository.MedReviewRepository
import com.example.domain.model.MemoryRating
import com.example.domain.model.SessionKind
import com.example.domain.model.UnderstandingRating
import com.example.domain.srs.Fsrs6Optimizer
import com.example.domain.srs.Fsrs6Parameters
import com.example.domain.srs.MedScheduler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FuzzPolicyReplayTest {
    @Test fun `mixed old and current policies survive real repository replay and only the edited row changes policy`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val oldRetention = MedScheduler.userRetention
        try {
            MedScheduler.userRetention = 0.9
            MedScheduler.calibrationScale = 1.0
            val repo = MedReviewRepository(db.studyUnitDao(), db.categoryDao(), db.reviewLogDao(), db)
            val origin = 1_790_000_000_000L
            val day = 86_400_000L
            val weights = Fsrs6Parameters.DEFAULT_WEIGHTS.copyOf().also { it[2] = 3.0996 }
            val set = db.memoryParameterSetDao().insert(MemoryParameterSetEntity(createdAt = origin, status = "ACTIVE",
                weights = Fsrs6Optimizer.encode(weights), comparedWithSetId = 0, availableReviews = 640,
                trainReviews = 512, testReviews = 128, currentLogLoss = 0.36, candidateLogLoss = 0.33,
                currentRmseBins = 0.09, candidateRmseBins = 0.05, currentAuc = 0.70, candidateAuc = 0.73,
                zScore = 3.0, activatedAt = origin))
            repo.refreshMemoryModel()
            val id = repo.insertUnit(StudyUnitEntity(id = 6, title = "Asthma", studyType = "Topic", studiedAt = origin,
                nextReviewAt = origin, modelDueAt = origin, memoryModel = "FSRS-6", parameterSetId = set))
            repo.rateUnit(id, origin, MemoryRating.Good, UnderstandingRating.Clear, sessionKind = SessionKind.TOPIC, reviewDurationMs = 1000)
            val first = db.reviewLogDao().getLogsForUnitOnce(id).single()
            assertEquals("the real current commit applies the boundary", 3.0, first.nextIntervalDays, 0.0)
            assertEquals("YADORA-9", first.schedulerPolicyVersion)
            // A valid pre-upgrade decision, computed independently from the frozen legacy RNG.
            val legacy = 3.0996 * (1.0 + kotlin.random.Random(6L * 31L).nextDouble(-0.05, 0.05))
            assertTrue(legacy < 3.0)
            db.reviewLogDao().insertLog(first.copy(nextIntervalDays = legacy, schedulerPolicyVersion = "YADORA-7"))
            val u = repo.getUnitById(id)!!
            repo.updateUnit(u.copy(currentIntervalDays = legacy, modelDueAt = origin + (legacy * day).toLong(),
                nextReviewAt = origin + (legacy * day).toLong()))
            repo.rateUnit(id, origin + 7 * day, MemoryRating.Good, UnderstandingRating.Clear, sessionKind = SessionKind.TOPIC, reviewDurationMs = 1000)
            val before = repo.getUnitById(id)!!
            val logs = db.reviewLogDao().getLogsForUnitOnce(id).sortedBy { it.id }
            repo.editReviewRating(id, -1L, MemoryRating.Good, UnderstandingRating.Clear)
            val replayed = db.reviewLogDao().getLogsForUnitOnce(id).sortedBy { it.id }
            assertEquals(legacy, replayed.first().nextIntervalDays, 0.0)
            assertEquals(logs.map { it.schedulerPolicyVersion }, replayed.map { it.schedulerPolicyVersion })
            assertEquals(before.currentIntervalDays, repo.getUnitById(id)!!.currentIntervalDays, 1e-12)
            assertEquals(before.nextReviewAt, repo.getUnitById(id)!!.nextReviewAt)
            repo.editReviewRating(id, first.id, MemoryRating.Good, UnderstandingRating.Partial)
            val corrected = db.reviewLogDao().getLogsForUnitOnce(id).sortedBy { it.id }
            assertEquals("the edited row is a new decision", "YADORA-9", corrected.first().schedulerPolicyVersion)
            assertEquals(3.0, corrected.first().nextIntervalDays, 0.0)
            assertEquals("YADORA-9", corrected.last().schedulerPolicyVersion)
        } finally {
            db.close()
            MedScheduler.userRetention = oldRetention
            MedScheduler.activeParameterSet = MedScheduler.DEFAULT_PARAMETER_SET
            MedScheduler.knownParameterSets = emptyMap()
            MedScheduler.calibrationScale = 1.0
        }
    }
}
