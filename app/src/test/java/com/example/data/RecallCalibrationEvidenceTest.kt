package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.database.AppDatabase
import com.example.data.local.entity.ReviewLogEntity
import com.example.data.local.entity.StudyUnitEntity
import com.example.data.repository.MedReviewRepository
import com.example.domain.srs.MedScheduler
import com.example.domain.srs.RecallCalibration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WHICH rows may teach the calibration anything. The estimator itself is pinned in
 * RecallCalibrationTest; this pins the evidence rule the repository applies on top of it: real
 * recall reviews only, on the live model only, with a stored prediction, at least
 * MIN_ELAPSED_DAYS apart, and only the most recent WINDOW of them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RecallCalibrationEvidenceTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: MedReviewRepository
    private var unitId = 0L

    @Before
    fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = MedReviewRepository(db.studyUnitDao(), db.categoryDao(), db.reviewLogDao(), db)
        unitId = repo.insertUnit(StudyUnitEntity(title = "Sepsis", studyType = "Topic", studiedAt = 1L, nextReviewAt = 1L))
    }

    @After
    fun teardown() = db.close()

    private suspend fun log(
        at: Long, recalled: Boolean, predicted: Double = 0.9, elapsed: Double = 10.0,
        type: String = "RECALL", model: String = MedScheduler.CURRENT_MODEL.id, previousInterval: Double = 10.0,
    ) {
        db.reviewLogDao().insertLog(
            ReviewLogEntity(
                studyUnitId = unitId, reviewedAt = at, memoryRating = if (recalled) "Good" else "Forgot",
                understandingRating = if (recalled) "Clear" else "NotAsked",
                previousIntervalDays = previousInterval, nextIntervalDays = 1.0, previousState = "Learning", nextState = "Learning",
                retrievabilityAtReview = predicted, elapsedDays = elapsed, logType = type, schedulerVersion = model,
            )
        )
    }

    @Test
    fun `with no history the scale is one`() = runBlocking {
        assertEquals(1.0, repo.recallCalibrationScale(), 0.0)
    }

    @Test
    fun `only real recall reviews on the live model, far enough apart, with a prediction, count`() = runBlocking {
        // 200 qualifying reviews, 85% recalled against a 90% prediction: the scale must drop (to
        // about 0.66 — a plausible learner, inside the search band rather than clipped at its edge).
        for (i in 0 until 200) log(at = 1_000L + i, recalled = i < 170)
        val withFailures = repo.recallCalibrationScale()
        assertTrue("qualifying failures lower the scale ($withFailures)", withFailures < 0.8)
        assertTrue("but not to the clamp ($withFailures)", withFailures > RecallCalibration.MIN_SCALE)

        // Thousands of non-qualifying successes: none of them may move it.
        for (i in 0 until 500) log(at = 10_000L + i, recalled = true, type = "FIRST_STUDY")           // self-assessments
        for (i in 0 until 500) log(at = 20_000L + i, recalled = true, model = "FSRS-5")               // the frozen model's curve
        for (i in 0 until 500) log(at = 30_000L + i, recalled = true, elapsed = RecallCalibration.MIN_ELAPSED_DAYS - 0.5) // too close together
        for (i in 0 until 500) log(at = 40_000L + i, recalled = true, predicted = -1.0)                // no stored prediction (pre-v2)
        for (i in 0 until 500) log(at = 45_000L + i, recalled = true, elapsed = 3.0, previousInterval = 40.0) // brought forward by a repair or a self-test
        assertEquals("non-evidence rows change nothing", withFailures, repo.recallCalibrationScale(), 0.0)

        // Qualifying successes DO count, and exactly at both thresholds counts.
        for (i in 0 until 200) log(
            at = 50_000L + i, recalled = true, elapsed = RecallCalibration.MIN_ELAPSED_DAYS,
            previousInterval = RecallCalibration.MIN_ELAPSED_DAYS / RecallCalibration.EARLY_REVIEW_FRACTION,
        )
        assertTrue("qualifying successes raise it back", repo.recallCalibrationScale() > withFailures)
    }

    @Test
    fun `only the most recent window is read`() = runBlocking {
        // Old failures beyond the window, then a full window of successes: the failures must age out.
        for (i in 0 until 300) log(at = 1_000L + i, recalled = false)
        for (i in 0 until RecallCalibration.WINDOW) log(at = 100_000L + i, recalled = true)
        val predicted = DoubleArray(RecallCalibration.WINDOW) { 0.9 }
        val recalled = BooleanArray(RecallCalibration.WINDOW) { true }
        assertEquals("the estimate is the window's alone", RecallCalibration.scale(predicted, recalled), repo.recallCalibrationScale(), 1e-12)
    }
}
