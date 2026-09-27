package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.database.AppDatabase
import com.example.data.local.entity.MemoryParameterSetEntity
import com.example.data.local.entity.ReviewLogEntity
import com.example.data.local.entity.StudyUnitEntity
import com.example.data.repository.MedReviewRepository
import com.example.domain.model.MemoryRating
import com.example.domain.model.UnderstandingRating
import com.example.domain.srs.Fsrs6
import com.example.domain.srs.Fsrs6Optimizer
import com.example.domain.srs.Fsrs6Parameters
import com.example.domain.srs.Grade
import com.example.domain.srs.MedScheduler
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * The personal memory model in the running app: adoption moves nothing until a topic's next review,
 * projection replays onto the new weights, replay reproduces a topic on the weights it is on, the
 * calibration never pools two sets, and a refit is recorded, adopted only when it passes, and throttled.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PersonalModelTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: MedReviewRepository
    private val day = 86_400_000L

    /** Local noon, so a whole number of elapsed days is also a whole number of calendar days. */
    private val t0 = LocalDate.of(2026, 3, 2).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val personalA = Fsrs6Parameters.DEFAULT_WEIGHTS.copyOf().also { for (i in 0..3) it[i] *= 0.5; it[20] = 0.3 }
    private val personalB = Fsrs6Parameters.DEFAULT_WEIGHTS.copyOf().also { it[8] = 1.3; it[20] = 0.45 }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = MedReviewRepository(db.studyUnitDao(), db.categoryDao(), db.reviewLogDao(), db)
        resetScheduler()
    }

    @After
    fun teardown() {
        db.close()
        resetScheduler()
    }

    private fun resetScheduler() {
        MedScheduler.activeParameterSet = MedScheduler.DEFAULT_PARAMETER_SET
        MedScheduler.knownParameterSets = emptyMap()
        MedScheduler.calibrationScale = 1.0
    }

    private suspend fun adopt(weights: DoubleArray): Long {
        db.memoryParameterSetDao().retireActive(t0)
        val id = db.memoryParameterSetDao().insert(
            MemoryParameterSetEntity(
                createdAt = t0, status = MemoryParameterSetEntity.ACTIVE, weights = Fsrs6Optimizer.encode(weights),
                comparedWithSetId = 0, availableReviews = 700, trainReviews = 560, testReviews = 140,
                currentLogLoss = 0.36, candidateLogLoss = 0.33, currentRmseBins = 0.09, candidateRmseBins = 0.05,
                currentAuc = 0.70, candidateAuc = 0.73, zScore = 6.0, activatedAt = t0,
            )
        )
        repo.refreshMemoryModel()
        return id
    }

    /** A topic studied at t0 (Good) and recalled Good after 3 days and Hard 7 days after that. */
    private suspend fun topicWithHistory(title: String, setId: Long = 0L): Long {
        val id = repo.insertUnit(
            StudyUnitEntity(
                title = title, studyType = "Topic", studiedAt = t0, lastReviewedAt = t0 + 10 * day,
                nextReviewAt = t0 + 20 * day, modelDueAt = t0 + 20 * day, currentIntervalDays = 10.0,
                reviewCount = 3, stability = 10.0, difficulty = 5.0, state = "Building",
                memoryModel = "FSRS-6", parameterSetId = setId,
            )
        )
        for ((offset, rating, type) in listOf(Triple(0, "Good", "FIRST_STUDY"), Triple(3, "Good", "RECALL"), Triple(10, "Hard", "RECALL"))) {
            db.reviewLogDao().insertLog(
                ReviewLogEntity(
                    studyUnitId = id, reviewedAt = t0 + offset * day, memoryRating = rating, understandingRating = "Clear",
                    previousIntervalDays = 0.0, nextIntervalDays = 3.0, previousState = "Learning", nextState = "Learning",
                    logType = type, schedulerVersion = "FSRS-6", schedulerPolicyVersion = MedScheduler.POLICY_VERSION,
                    parameterSetId = setId,
                )
            )
        }
        return id
    }

    /** The same history replayed by hand through the verified FSRS-6 on [weights]. */
    private fun expectedStability(weights: DoubleArray): Double {
        val p = Fsrs6Parameters(weights = weights)
        var s = Fsrs6.initialState(Grade.Good, p)
        s = Fsrs6.nextState(s, 3.0, Grade.Good, p)
        s = Fsrs6.nextState(s, 7.0, Grade.Hard, p)
        return s.stability
    }

    @Test
    fun `adopting a personal set moves nothing until a topic's next review, then projects it by replay`() = runBlocking {
        val id = topicWithHistory("Appendicitis")
        val before = repo.getUnitById(id)!!
        val setId = adopt(personalA)

        assertEquals("adoption writes no topic", before, repo.getUnitById(id))

        val projected = repo.projectOntoCurrentModel(repo.getUnitById(id)!!)
        assertEquals("now on the personal set", setId, projected.parameterSetId)
        assertEquals("its state is the history replayed on the personal weights", expectedStability(personalA), projected.stability, 1e-9)
        assertEquals("the date already promised is kept", before.nextReviewAt, projected.nextReviewAt)
        assertEquals("and the model's own date", before.modelDueAt, projected.modelDueAt)
        assertEquals("projecting again is a no-op", projected, repo.projectOntoCurrentModel(projected))
    }

    @Test
    fun `a topic still on an older set replays under that set, never the active one`() = runBlocking {
        val setA = adopt(personalA)
        val id = topicWithHistory("Cholecystitis", setId = setA)
        adopt(personalB) // A is retired, B is active

        repo.editReviewRating(id, -1L, MemoryRating.Good, UnderstandingRating.Clear) // pure replay
        val replayed = repo.getUnitById(id)!!
        assertEquals("still on A", setA, replayed.parameterSetId)
        assertEquals("replayed on A's weights", expectedStability(personalA), replayed.stability, 1e-9)
        assertNotEquals("which is not what B would say", expectedStability(personalB), replayed.stability, 1e-6)
    }

    @Test
    fun `a topic on a set the database does not hold is never replayed on the wrong weights`() = runBlocking {
        val id = topicWithHistory("Pancreatitis", setId = 99L)
        val before = repo.getUnitById(id)!!
        assertTrue(runCatching { repo.editReviewRating(id, -1L, MemoryRating.Good, UnderstandingRating.Clear) }.isFailure)
        assertEquals("left exactly as it was", before, repo.getUnitById(id))
    }

    @Test
    fun `the calibration never pools evidence from two weight sets`() = runBlocking {
        val id = repo.insertUnit(StudyUnitEntity(title = "Sepsis", studyType = "Topic", studiedAt = 1L, nextReviewAt = 1L))
        for (i in 0 until 200) {
            db.reviewLogDao().insertLog(
                ReviewLogEntity(
                    studyUnitId = id, reviewedAt = 1_000L + i, memoryRating = if (i < 170) "Good" else "Forgot",
                    understandingRating = "Clear", previousIntervalDays = 10.0, nextIntervalDays = 10.0,
                    previousState = "Building", nextState = "Building", retrievabilityAtReview = 0.9, elapsedDays = 10.0,
                    logType = "RECALL", schedulerVersion = "FSRS-6", parameterSetId = 0L,
                )
            )
        }
        assertTrue("the defaults' evidence lowers their scale", repo.recallCalibrationScale() < 0.8)
        adopt(personalA)
        assertEquals("nothing is known yet about the personal set", 1.0, repo.recallCalibrationScale(), 0.0)
    }

    @Test
    fun `a refit that passes is adopted and recorded, the next waits for evidence, and it can be switched off`() = runBlocking {
        // Seven hundred topics reviewed by a learner the defaults do not describe.
        val truth = Fsrs6Parameters(weights = Fsrs6Parameters.DEFAULT_WEIGHTS.copyOf().also { for (i in 0..3) it[i] *= 0.4; it[8] = 1.3; it[20] = 0.35 })
        val app = Fsrs6Parameters()
        val rng = kotlin.random.Random(21)
        repeat(700) { n ->
            val id = repo.insertUnit(StudyUnitEntity(title = "t$n", studyType = "Topic", studiedAt = t0, nextReviewAt = t0))
            var today = rng.nextInt(0, 120)
            val first = if (rng.nextDouble() < 0.3) Grade.Easy else Grade.Good
            var trueState = Fsrs6.initialState(first, truth)
            var appState = Fsrs6.initialState(first, app)
            fun log(at: Int, g: Grade, type: String) = ReviewLogEntity(
                studyUnitId = id, reviewedAt = t0 + at * day, memoryRating = MemoryRating.entries[g.value - 1].name,
                understandingRating = "Clear", previousIntervalDays = 0.0, nextIntervalDays = 1.0,
                previousState = "Learning", nextState = "Learning", logType = type, schedulerVersion = "FSRS-6",
            )
            val logs = mutableListOf(log(today, first, "FIRST_STUDY"))
            val end = today + 900
            while (logs.size < 14) {
                val gap = maxOf(1, Math.round(Fsrs6.intervalDays(appState.stability, 0.9, app).coerceIn(1.0, 365.0) * (0.7 + rng.nextDouble() * 0.9)).toInt())
                today += gap
                if (today > end) break
                val recalled = rng.nextDouble() < Fsrs6.retrievability(gap.toDouble(), trueState.stability, truth)
                val g = if (!recalled) Grade.Again else if (rng.nextDouble() < 0.2) Grade.Hard else Grade.Good
                trueState = Fsrs6.nextState(trueState, gap.toDouble(), g, truth)
                appState = Fsrs6.nextState(appState, gap.toDouble(), g, app)
                logs += log(today, g, "RECALL")
            }
            logs.forEach { db.reviewLogDao().insertLog(it) }
        }

        // Switched off while the fit ran (the switch is read again where the result would be adopted): the fit
        // passes, and nothing is adopted or recorded as an attempt.
        val offMidFit = repo.refitPersonalModel(now = t0 + 1200 * day, isEnabled = { false })
        assertEquals(Fsrs6Optimizer.Verdict.ACCEPTED, offMidFit!!.verdict)
        assertNull("nothing adopted after the switch went off", db.memoryParameterSetDao().getActive())
        assertTrue("and no attempt recorded", db.memoryParameterSetDao().getAll().isEmpty())
        assertEquals("the discard is logged", "PERSONAL_MODEL_DISCARDED", db.eventLogDao().getAll().last().type)

        val report = repo.refitPersonalModel(now = t0 + 1200 * day)
        assertNotNull(report)
        assertEquals("z = ${report!!.zScore}", Fsrs6Optimizer.Verdict.ACCEPTED, report.verdict)
        val active = db.memoryParameterSetDao().getActive()
        assertNotNull("recorded as the active set", active)
        assertNotNull("with weights that decode inside the bounds", Fsrs6Optimizer.decode(active!!.weights))
        assertTrue("with the held-out evidence it was judged on", active.candidateLogLoss < active.currentLogLoss)
        assertEquals("the scheduler has not switched yet", 0L, MedScheduler.activeParameterSet.id)

        assertNull("no new evidence, no new attempt", repo.refitPersonalModel(now = t0 + 1201 * day))

        repo.refreshMemoryModel()
        assertEquals("the next session start adopts it", active.id, MedScheduler.activeParameterSet.id)

        repo.useDefaultMemoryModel()
        assertEquals("switched off: the defaults schedule", 0L, MedScheduler.activeParameterSet.id)
        assertEquals(MemoryParameterSetEntity.RETIRED, db.memoryParameterSetDao().getAll().single { it.id == active.id }.status)
        assertTrue("still readable for replay", MedScheduler.knownParameterSets.containsKey(active.id))
    }
}
