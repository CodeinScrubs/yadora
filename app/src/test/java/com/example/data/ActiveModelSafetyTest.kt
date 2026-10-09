package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.database.AppDatabase
import com.example.data.local.entity.MemoryParameterSetEntity
import com.example.data.local.entity.ReviewLogEntity
import com.example.data.local.entity.StudyUnitEntity
import com.example.data.repository.MedReviewRepository
import com.example.domain.srs.Fsrs6
import com.example.domain.srs.Fsrs6Optimizer
import com.example.domain.srs.Fsrs6Parameters
import com.example.domain.srs.Grade
import com.example.domain.srs.MedScheduler
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Room refits: rejecting a replacement must not retain an active set that fails its safety guard. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ActiveModelSafetyTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: MedReviewRepository
    private val origin = 1_790_000_000_000L
    private val day = 86_400_000L
    private val longWeights = Fsrs6Parameters.DEFAULT_WEIGHTS.copyOf().also { for (i in 0..3) it[i] *= 3.0 }
    private var savedRetention = 0.9

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = MedReviewRepository(db.studyUnitDao(), db.categoryDao(), db.reviewLogDao(), db)
        savedRetention = MedScheduler.userRetention
        MedScheduler.userRetention = 0.9
        MedScheduler.activeParameterSet = MedScheduler.DEFAULT_PARAMETER_SET
        MedScheduler.knownParameterSets = emptyMap()
        MedScheduler.calibrationScale = 1.0
    }

    @After fun teardown() {
        db.close()
        MedScheduler.userRetention = savedRetention
        MedScheduler.activeParameterSet = MedScheduler.DEFAULT_PARAMETER_SET
        MedScheduler.knownParameterSets = emptyMap()
        MedScheduler.calibrationScale = 1.0
    }

    private suspend fun active(weights: DoubleArray): Long = db.memoryParameterSetDao().insert(
        MemoryParameterSetEntity(createdAt = origin, status = MemoryParameterSetEntity.ACTIVE,
            weights = Fsrs6Optimizer.encode(weights), comparedWithSetId = 0, availableReviews = 640,
            trainReviews = 512, testReviews = 128, currentLogLoss = 0.36, candidateLogLoss = 0.33,
            currentRmseBins = 0.09, candidateRmseBins = 0.05, currentAuc = 0.70, candidateAuc = 0.73,
            zScore = 3.0, activatedAt = origin),
    )

    private suspend fun evidence() {
        // A simulated learner for whom the active model predicts well but schedules above the app's
        // published-defaults ceiling. The outcome is not an argument that this simulation is real learning.
        val truth = Fsrs6Parameters(weights = longWeights)
        val rng = kotlin.random.Random(84)
        repeat(700) { n ->
            val id = repo.insertUnit(StudyUnitEntity(title = "Topic $n", studyType = "Topic", studiedAt = origin,
                nextReviewAt = origin, modelDueAt = origin, memoryModel = "FSRS-6"))
            val gap = rng.nextInt(4, 61)
            val recalled = rng.nextDouble() < Fsrs6.retrievability(gap.toDouble(), Fsrs6.initialState(Grade.Good, truth).stability, truth)
            for ((elapsed, rating, type) in listOf(Triple(0, "Good", "FIRST_STUDY"),
                Triple(gap, if (recalled) "Good" else "Forgot", "RECALL"))) {
                db.reviewLogDao().insertLog(ReviewLogEntity(studyUnitId = id, reviewedAt = origin + elapsed * day,
                    memoryRating = rating, understandingRating = "Clear", previousIntervalDays = 0.0,
                    nextIntervalDays = 1.0, previousState = "Learning", nextState = "Learning", logType = type,
                    schedulerVersion = "FSRS-6", elapsedDays = elapsed.toDouble()))
            }
        }
    }

    @Test fun `a rejected candidate no longer leaves an unsafe active set scheduling`() = runBlocking {
        evidence()
        val id = active(longWeights)
        repo.refreshMemoryModel()
        val weightsText = db.memoryParameterSetDao().getActive()!!.weights
        val ratio = Fsrs6Optimizer.lengthening(repo.trainingHistories(), longWeights, 0.9)
        assertTrue("fixture must cross the existing adoption ceiling, ratio=$ratio", ratio > 1.3)
        val report = repo.refitPersonalModel(now = origin + 100 * day, force = true)!!
        assertEquals("the well-predicting baseline must not be replaced by a safe better candidate", Fsrs6Optimizer.Verdict.REJECTED, report.verdict)
        assertNull("a failed replacement is not permission to keep an unsafe active model", db.memoryParameterSetDao().getActive())
        val retired = db.memoryParameterSetDao().getAll().single { it.id == id }
        assertEquals(MemoryParameterSetEntity.RETIRED, retired.status)
        assertEquals(weightsText, retired.weights)
        assertEquals(origin + 100 * day, retired.retiredAt)
        val event = db.eventLogDao().getAll().single { it.type == "PERSONAL_MODEL_RETIRED" }
        assertTrue(event.detail.orEmpty().contains("set=$id") && event.detail.orEmpty().contains("lengthening"))
        repo.refreshMemoryModel()
        assertEquals(0L, MedScheduler.activeParameterSet.id)
        assertArrayEquals(longWeights, MedScheduler.knownParameterSets[id], 0.0)
        // A repeated forced attempt cannot retire or report the same set again.
        repo.refitPersonalModel(now = origin + 101 * day, force = true)
        assertEquals(1, db.eventLogDao().getAll().count { it.type == "PERSONAL_MODEL_RETIRED" })
    }

    @Test fun `a passing active guard keeps the model when the candidate is rejected`() = runBlocking {
        evidence()
        val id = active(Fsrs6Parameters.DEFAULT_WEIGHTS)
        val report = repo.refitPersonalModel(now = origin + 100 * day, force = true)!!
        assertEquals(Fsrs6Optimizer.Verdict.REJECTED, report.verdict)
        assertEquals(id, db.memoryParameterSetDao().getActive()!!.id)
        assertTrue(db.eventLogDao().getAll().none { it.type == "PERSONAL_MODEL_RETIRED" })
    }

    @Test fun `switching off during a fit discards retirement as well as adoption`() = runBlocking {
        evidence()
        val id = active(longWeights)
        repo.refitPersonalModel(now = origin + 100 * day, force = true, isEnabled = { false })
        assertEquals(id, db.memoryParameterSetDao().getActive()!!.id)
        assertEquals(1, db.memoryParameterSetDao().getAll().size)
        assertTrue(db.eventLogDao().getAll().none { it.type == "PERSONAL_MODEL_RETIRED" })
        assertEquals("PERSONAL_MODEL_DISCARDED", db.eventLogDao().getAll().last().type)
    }

    @Test fun `a changed retention during the fit cannot retire using the old target`() = runBlocking {
        evidence()
        val id = active(longWeights)
        // Called at the commit boundary after fitting; its side effect simulates the settings change.
        repo.refitPersonalModel(now = origin + 100 * day, force = true,
            isEnabled = { MedScheduler.userRetention = 0.95; true })
        assertEquals(id, db.memoryParameterSetDao().getActive()!!.id)
        assertEquals(1, db.memoryParameterSetDao().getAll().size)
        assertTrue(db.eventLogDao().getAll().none { it.type == "PERSONAL_MODEL_RETIRED" })
        assertEquals("PERSONAL_MODEL_DISCARDED", db.eventLogDao().getAll().last().type)
    }

    @Test fun `an insufficient capped fit can retire invalid active weights without fabricating an attempt`() = runBlocking {
        val id = repo.insertUnit(StudyUnitEntity(title = "Repeated topic", studyType = "Topic", studiedAt = origin,
            nextReviewAt = origin, modelDueAt = origin, memoryModel = "FSRS-6"))
        repeat(701) { n ->
            db.reviewLogDao().insertLog(ReviewLogEntity(studyUnitId = id, reviewedAt = origin + n * day,
                memoryRating = "Good", understandingRating = "Clear", previousIntervalDays = 0.0,
                nextIntervalDays = 1.0, previousState = "Learning", nextState = "Learning",
                logType = if (n == 0) "FIRST_STUDY" else "RECALL", schedulerVersion = "FSRS-6", elapsedDays = if (n == 0) 0.0 else 1.0))
        }
        val setId = active(longWeights)
        val set = db.memoryParameterSetDao().getActive()!!
        db.memoryParameterSetDao().insert(set.copy(weights = "invalid"))
        val result = repo.refitPersonalModel(now = origin + 100 * day, force = true)!!
        assertEquals(Fsrs6Optimizer.Verdict.NOT_ENOUGH_DATA, result.verdict)
        assertNull(db.memoryParameterSetDao().getActive())
        assertEquals("no fake rejected or accepted attempt", 1, db.memoryParameterSetDao().getAll().size)
        assertEquals(setId, db.memoryParameterSetDao().getAll().single().id)
        assertEquals("PERSONAL_MODEL_RETIRED", db.eventLogDao().getAll().last().type)
    }

    @Test fun `a history correction during the fit cannot retire a model using stale evidence`() = runBlocking {
        evidence()
        val id = active(longWeights)
        repo.refitPersonalModel(now = origin + 100 * day, force = true, isEnabled = {
            db.openHelper.writableDatabase.execSQL("UPDATE review_logs SET elapsedDays = 999 WHERE id = 2")
            true
        })
        assertEquals(id, db.memoryParameterSetDao().getActive()!!.id)
        assertEquals(1, db.memoryParameterSetDao().getAll().size)
        assertTrue(db.eventLogDao().getAll().none { it.type == "PERSONAL_MODEL_RETIRED" })
        assertEquals("PERSONAL_MODEL_DISCARDED", db.eventLogDao().getAll().last().type)
    }

}
