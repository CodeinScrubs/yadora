package com.example.data

import androidx.test.core.app.ApplicationProvider
import com.example.MedReviewApplication
import com.example.data.local.entity.REVIEW_HISTORY_ORDER
import com.example.data.local.entity.StudyUnitEntity
import com.example.domain.model.MemoryRating
import com.example.domain.model.SessionKind
import com.example.domain.model.UnderstandingRating
import com.example.domain.srs.Fsrs6
import com.example.domain.srs.Fsrs6Parameters
import com.example.domain.srs.Grade
import com.example.domain.srs.MedScheduler
import com.example.domain.srs.MemoryState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * POLICY YADORA-9 (the owner's decision, 2026-10-09): FSRS-6 follows py-fsrs 6.3.2, where a same-day Hard review cannot
 * shrink stability; 6.3.1 cut a mature topic's stability by more than half. A review stamped YADORA-8 or earlier keeps
 * replaying the equation that computed it, so old history means what it meant; a correction is a new decision under
 * today's rule. Through the real commit, replay and projection paths.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SameDayHardFloorTest {
    private val app get() = ApplicationProvider.getApplicationContext<MedReviewApplication>()
    private val repo get() = app.repository
    private val db get() = app.database
    private val day = 86_400_000L
    private val t0 = 1_790_000_000_000L

    @Before fun reset() {
        MedScheduler.activeParameterSet = MedScheduler.DEFAULT_PARAMETER_SET
        MedScheduler.knownParameterSets = emptyMap()
        MedScheduler.calibrationScale = 1.0
    }

    private suspend fun rate(id: Long, at: Long, grade: MemoryRating, und: UnderstandingRating = UnderstandingRating.Clear) =
        repo.rateUnit(id, at, grade, und, sessionKind = SessionKind.TOPIC, reviewDurationMs = 1000)!!

    private suspend fun logs(id: Long) = db.reviewLogDao().getLogsForUnitOnce(id).sortedWith(REVIEW_HISTORY_ORDER)

    /** A first study, an Easy a month later, and a Hard an hour after that, on the same day. */
    private suspend fun topicWithSameDayHard(): Long {
        val id = repo.insertUnit(
            StudyUnitEntity(title = "Asthma", studyType = "Topic", studiedAt = t0, createdAt = t0, updatedAt = t0,
                nextReviewAt = t0, modelDueAt = t0, memoryModel = "FSRS-6")
        )
        rate(id, t0, MemoryRating.Good)
        rate(id, t0 + 30 * day, MemoryRating.Easy)
        rate(id, t0 + 30 * day + 3_600_000L, MemoryRating.Hard)
        return id
    }

    @Test
    fun `the rule is YADORA-9 onward, and an unstamped row follows the current policy`() {
        assertEquals("YADORA-9", MedScheduler.POLICY_VERSION)
        for (n in 1..8) assertFalse("YADORA-$n", MedScheduler.floorsSameDayHard("YADORA-$n"))
        assertTrue(MedScheduler.floorsSameDayHard("YADORA-9"))
        assertTrue(MedScheduler.floorsSameDayHard(""))
    }

    @Test
    fun `a same-day Hard keeps the stability it had`() = runBlocking {
        val id = topicWithSameDayHard()
        val (_, easy, hard) = logs(id)
        assertEquals("a same-day pair", 0.0, hard.elapsedDays, 0.0)
        assertEquals("YADORA-9", hard.schedulerPolicyVersion)
        val afterEasy = Fsrs6.nextState(
            Fsrs6.initialState(Grade.Good, Fsrs6Parameters()), easy.elapsedDays, Grade.Easy, Fsrs6Parameters(),
        )
        val unit = repo.getUnitById(id)!!
        assertEquals("kept, not cut", afterEasy.stability, unit.stability, 1e-9)
        val old = Fsrs6.nextState(afterEasy, 0.0, Grade.Hard, Fsrs6Parameters(), floorSameDayHard = false).stability
        assertTrue("py-fsrs 6.3.1 would have cut it to about half: $old against ${unit.stability}", old < 0.6 * unit.stability)
    }

    @Test
    fun `an old same-day Hard replays as it was given, and a correction is a new decision`() = runBlocking {
        val id = topicWithSameDayHard()
        val hard = logs(id).last()
        val kept = repo.getUnitById(id)!!.stability
        // The same review as an older build stamped it: its replay keeps py-fsrs 6.3.1's same-day equation.
        db.reviewLogDao().insertLog(hard.copy(schedulerPolicyVersion = "YADORA-8"))
        repo.editReviewRating(id, -1L, MemoryRating.Good, null)
        val replayed = repo.getUnitById(id)!!
        val afterEasy = stateAfterEasy(id)
        assertEquals("the floor had kept the Easy review's stability", afterEasy.stability, kept, 1e-9)
        val expected = Fsrs6.nextState(afterEasy, 0.0, Grade.Hard, Fsrs6Parameters(), floorSameDayHard = false)
        assertEquals("the 6.3.1 cut, reproduced", expected.stability, replayed.stability, 1e-9)
        assertEquals("the stamp stays", "YADORA-8", logs(id).last().schedulerPolicyVersion)

        // Projection rebuilds the same history the same way (one history, one reconstruction).
        val projected = repo.projectOntoCurrentModel(replayed.copy(memoryModel = "FSRS-5"))
        assertEquals(replayed.stability, projected.stability, 1e-9)

        // Correcting that review re-decides it under today's policy: the floor applies.
        repo.editReviewRating(id, hard.id, MemoryRating.Hard, UnderstandingRating.Partial)
        assertEquals("YADORA-9", logs(id).last().schedulerPolicyVersion)
        assertEquals(kept, repo.getUnitById(id)!!.stability, 1e-9)
    }

    /** The state right after the Easy review, rebuilt from the first two logs. */
    private suspend fun stateAfterEasy(id: Long): MemoryState {
        val p = Fsrs6Parameters()
        val easy = logs(id)[1]
        return Fsrs6.nextState(Fsrs6.initialState(Grade.Good, p), easy.elapsedDays, Grade.Easy, p)
    }
}
