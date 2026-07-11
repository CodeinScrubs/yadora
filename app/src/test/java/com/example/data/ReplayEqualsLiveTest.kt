package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.database.AppDatabase
import com.example.data.local.entity.ReviewLogEntity
import com.example.data.local.entity.StudyUnitEntity
import com.example.data.repository.MedReviewRepository
import com.example.domain.model.MemoryRating
import com.example.domain.model.UnderstandingRating
import com.example.domain.srs.MedScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * THE core scheduling invariant: editing a past rating replays the unit's history through the exact
 * same math as the live review path — so re-submitting the SAME ratings must reproduce the unit's
 * state bit-for-bit. Round 1 of external review found a real bug here (the replay seeded 1.0/5.0
 * while live seeded firstStudy(Partial)); this test makes that class of drift impossible to miss.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReplayEqualsLiveTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: MedReviewRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = MedReviewRepository(db.studyUnitDao(), db.categoryDao(), db.reviewLogDao(), db)
    }

    @After
    fun teardown() {
        db.close()
    }

    /** Mirrors the live commit in ReviewSessionViewModel.rateCurrentUnit (same math, same writes). */
    private fun liveReview(unitId: Long, now: Long, memory: MemoryRating, understanding: UnderstandingRating): Long = runBlocking {
        val unit = repo.getUnitById(unitId)!!
        val elapsedDays = (now - (unit.lastReviewedAt ?: unit.studiedAt)) / 86400000.0
        val reviewNumber = MedScheduler.effectiveReviewNumber(unit.studiedAt, now, unit.reviewCount)
        val outcome = MedScheduler.review(
            stability = unit.stability, difficulty = unit.difficulty, elapsedDays = elapsedDays,
            memoryRating = memory, understanding = understanding, highYield = unit.highYield,
            reviewNumber = reviewNumber,
        )
        val nextInterval = MedScheduler.fuzzedInterval(outcome.intervalDays, outcome.baseIntervalDays, unit.id, unit.reviewCount)
        val nextState = MedScheduler.masteryState(outcome.state.stability, memory == MemoryRating.Forgot)
        val updated = unit.copy(
            lastReviewedAt = now,
            nextReviewAt = now + (nextInterval * 86400000).toLong(),
            currentIntervalDays = nextInterval,
            reviewCount = unit.reviewCount + 1,
            lapseCount = if (memory == MemoryRating.Forgot) unit.lapseCount + 1 else unit.lapseCount,
            state = nextState.name,
            difficulty = outcome.state.difficulty,
            stability = outcome.state.stability,
            retrievability = outcome.retrievabilityAtReview,
        )
        val log = ReviewLogEntity(
            studyUnitId = unit.id, reviewedAt = now,
            memoryRating = memory.name, understandingRating = understanding.name,
            previousIntervalDays = unit.currentIntervalDays, nextIntervalDays = nextInterval,
            previousState = unit.state, nextState = nextState.name,
            retrievabilityAtReview = outcome.retrievabilityAtReview, elapsedDays = elapsedDays,
            logType = if (reviewNumber == 0) "FIRST_STUDY" else "RECALL",
        )
        repo.commitReview(updated, log)
    }

    @Test
    fun `replaying identical ratings reproduces the live state exactly`() = runBlocking {
        val day = 86400000L
        val now = System.currentTimeMillis()
        val studiedAt = now - 20 * day // back-dated topic: the hardest case (recall-after-gap seeding)

        // Insert exactly as AddUnitScreen does: neutral firstStudy(Partial) seed, due on the study date.
        val seed = MedScheduler.firstStudy(UnderstandingRating.Partial, highYield = false)
        val unitId = repo.insertUnit(
            StudyUnitEntity(
                title = "AKI", studyType = "Pathology",
                stability = seed.state.stability, difficulty = seed.state.difficulty,
                retrievability = 1.0, state = "New",
                studiedAt = studiedAt, nextReviewAt = studiedAt,
                currentIntervalDays = 0.0, reviewCount = 0, lapseCount = 0,
            )
        )

        // Two live reviews: first rating 15 days after study (back-dated recall), second 5 days later.
        val t1 = now - 5 * day
        val logId1 = liveReview(unitId, t1, MemoryRating.Good, UnderstandingRating.Partial)
        val t2 = now - 1 * day
        val logId2 = liveReview(unitId, t2, MemoryRating.Hard, UnderstandingRating.Clear)

        val live = repo.getUnitById(unitId)!!

        // Replay with the SAME ratings (a no-op edit) — every derived value must match exactly.
        repo.editReviewRating(unitId, logId2, MemoryRating.Hard, UnderstandingRating.Clear)
        val replayed = repo.getUnitById(unitId)!!

        assertEquals("stability", live.stability, replayed.stability, 1e-9)
        assertEquals("difficulty", live.difficulty, replayed.difficulty, 1e-9)
        assertEquals("interval", live.currentIntervalDays, replayed.currentIntervalDays, 1e-9)
        assertEquals("nextReviewAt", live.nextReviewAt, replayed.nextReviewAt)
        assertEquals("reviewCount", live.reviewCount, replayed.reviewCount)
        assertEquals("lapseCount", live.lapseCount, replayed.lapseCount)
        assertEquals("state", live.state, replayed.state)

        // And editing the FIRST rating (the seeded one) must also replay from the shared seed without
        // drifting the second review's math off the live values' order of magnitude.
        repo.editReviewRating(unitId, logId1, MemoryRating.Good, UnderstandingRating.Partial)
        val replayed2 = repo.getUnitById(unitId)!!
        assertEquals("stability after no-op first-rating edit", live.stability, replayed2.stability, 1e-9)
        assertEquals("nextReviewAt after no-op first-rating edit", live.nextReviewAt, replayed2.nextReviewAt)
    }

    @Test
    fun `pure replay from unchanged study date is a no-op and keeps first previousInterval at zero`() = runBlocking {
        val day = 86400000L
        val now = System.currentTimeMillis()
        val studiedAt = now - 20 * day
        val seed = MedScheduler.firstStudy(UnderstandingRating.Partial, highYield = false)
        val unitId = repo.insertUnit(
            StudyUnitEntity(
                title = "CHF", studyType = "Pathology",
                stability = seed.state.stability, difficulty = seed.state.difficulty,
                retrievability = 1.0, state = "New",
                studiedAt = studiedAt, nextReviewAt = studiedAt,
                currentIntervalDays = 0.0, reviewCount = 0, lapseCount = 0,
            )
        )
        liveReview(unitId, now - 5 * day, MemoryRating.Good, UnderstandingRating.Partial)
        liveReview(unitId, now - 1 * day, MemoryRating.Hard, UnderstandingRating.Clear)
        val live = repo.getUnitById(unitId)!!
        val firstLogBefore = db.reviewLogDao().getLogsForUnit(unitId).first().minByOrNull { it.reviewedAt }!!

        // Pure replay via the studiedAt-edit path, but with studiedAt UNCHANGED: must reproduce state
        // exactly (logId = -1 substitutes no rating) and must NOT invent a previousInterval for the
        // first review — the origin is still day 0.
        repo.updateUnitReplayingHistory(live.copy(updatedAt = System.currentTimeMillis()))
        val replayed = repo.getUnitById(unitId)!!
        val firstLogAfter = db.reviewLogDao().getLogsForUnit(unitId).first().minByOrNull { it.reviewedAt }!!

        assertEquals("stability", live.stability, replayed.stability, 1e-9)
        assertEquals("difficulty", live.difficulty, replayed.difficulty, 1e-9)
        assertEquals("nextReviewAt", live.nextReviewAt, replayed.nextReviewAt)
        assertEquals("reviewCount", live.reviewCount, replayed.reviewCount)
        assertEquals("first log previousIntervalDays stays 0", 0.0, firstLogBefore.previousIntervalDays, 1e-9)
        assertEquals("first log previousIntervalDays unchanged by replay", 0.0, firstLogAfter.previousIntervalDays, 1e-9)
    }

    @Test
    fun `moving the study date earlier reschedules the topic later`() = runBlocking {
        val day = 86400000L
        val now = System.currentTimeMillis()
        val studiedAt = now - 10 * day
        val seed = MedScheduler.firstStudy(UnderstandingRating.Partial, highYield = false)
        val unitId = repo.insertUnit(
            StudyUnitEntity(
                title = "COPD", studyType = "Pathology",
                stability = seed.state.stability, difficulty = seed.state.difficulty,
                retrievability = 1.0, state = "New",
                studiedAt = studiedAt, nextReviewAt = studiedAt,
                currentIntervalDays = 0.0, reviewCount = 0, lapseCount = 0,
            )
        )
        liveReview(unitId, now - 4 * day, MemoryRating.Good, UnderstandingRating.Clear)
        val before = repo.getUnitById(unitId)!!

        // Move the study origin 10 days earlier: the first review is now a longer-gap recall, so the
        // replayed schedule must differ from the original — proving the caption's promise is real.
        repo.updateUnitReplayingHistory(before.copy(studiedAt = studiedAt - 10 * day, updatedAt = now))
        val after = repo.getUnitById(unitId)!!

        org.junit.Assert.assertNotEquals(
            "moving studiedAt must change the schedule", before.nextReviewAt, after.nextReviewAt)
    }

    @Test
    fun `changing a past rating actually changes downstream state`() = runBlocking {
        val day = 86400000L
        val now = System.currentTimeMillis()
        val studiedAt = now - 10 * day
        val seed = MedScheduler.firstStudy(UnderstandingRating.Partial, highYield = false)
        val unitId = repo.insertUnit(
            StudyUnitEntity(
                title = "Asthma", studyType = "Pathology",
                stability = seed.state.stability, difficulty = seed.state.difficulty,
                retrievability = 1.0, state = "New",
                studiedAt = studiedAt, nextReviewAt = studiedAt,
                currentIntervalDays = 0.0, reviewCount = 0, lapseCount = 0,
            )
        )
        val logId = liveReview(unitId, now - 2 * day, MemoryRating.Easy, UnderstandingRating.Clear)
        val before = repo.getUnitById(unitId)!!

        repo.editReviewRating(unitId, logId, MemoryRating.Forgot, UnderstandingRating.Confused)
        val after = repo.getUnitById(unitId)!!

        org.junit.Assert.assertTrue("Forgot must shrink stability (${before.stability} -> ${after.stability})",
            after.stability < before.stability)
        assertEquals("lapse recorded", 1, after.lapseCount)
        assertEquals("relearn state", "NeedsRelearn", after.state)
    }
}
