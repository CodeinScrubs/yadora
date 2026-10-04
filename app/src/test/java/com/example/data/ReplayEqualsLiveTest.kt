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
import org.junit.Assert.assertTrue
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

    /**
     * The live commit: the SAME repository function the review screen calls (MedReviewRepository.rateUnit),
     * not a copy of it. A copy used to live here and had to be kept in step by hand; when FSRS-6 went live
     * it still defaulted to FSRS-5 and this test kept passing while production disagreed with itself.
     */
    private fun liveReview(unitId: Long, now: Long, memory: MemoryRating, understanding: UnderstandingRating): Long = runBlocking {
        repo.rateUnit(
            unitId = unitId, now = now, memoryRating = memory, understandingRating = understanding,
            sessionKind = com.example.domain.model.SessionKind.PLAN, reviewDurationMs = 60_000,
        )!!.logId
    }

    /**
     * POLICY YADORA-6: the repair clock doubles per consecutive unrepaired answer. The streak is read
     * from the logs by both paths, so a no-op correction must reproduce every deadline exactly, and
     * the deadlines themselves must be the doubled ones, not the flat table.
     */
    @Test
    fun `consecutive unrepaired answers back off the repair clock identically live and replayed`() = runBlocking {
        val day = 86400000L
        val now = System.currentTimeMillis()
        val studiedAt = now - 40 * day
        val unitId = repo.insertUnit(newUnit("Pancreatitis", studiedAt))
        liveReview(unitId, studiedAt, MemoryRating.Good, UnderstandingRating.Partial)                  // first check-in: streak 0
        liveReview(unitId, studiedAt + 3 * day, MemoryRating.Good, UnderstandingRating.Partial)        // streak 1 -> 6 days
        val logId = liveReview(unitId, studiedAt + 9 * day, MemoryRating.Good, UnderstandingRating.Partial) // streak 2 -> 12 days
        liveReview(unitId, studiedAt + 21 * day, MemoryRating.Good, UnderstandingRating.Partial)       // streak 3 -> 24 days
        val live = repo.getUnitById(unitId)!!

        assertEquals("streak of four unrepaired answers on the logs", 4, repo.unrepairedStreak(unitId))
        assertTrue("the memory date is far enough out for the deadline to matter",
            live.modelDueAt - live.lastReviewedAt!! > 24 * day)
        assertEquals("the fourth Partial repairs in 24 days, not 3",
            24 * day, live.understandingDueAt!! - live.lastReviewedAt!!)
        assertEquals("and that is the date the topic returns on", live.understandingDueAt, live.nextReviewAt)

        // A pure replay (an unchanged correction now changes nothing at all, so it cannot stand in for one).
        repo.editReviewRating(unitId, -1L, MemoryRating.Good, UnderstandingRating.Clear)
        val replayed = repo.getUnitById(unitId)!!
        assertEquals("stability", live.stability, replayed.stability, 1e-9)
        assertEquals("interval", live.currentIntervalDays, replayed.currentIntervalDays, 1e-9)
        assertEquals("modelDueAt", live.modelDueAt, replayed.modelDueAt)
        assertEquals("understandingDueAt", live.understandingDueAt, replayed.understandingDueAt)
        assertEquals("nextReviewAt", live.nextReviewAt, replayed.nextReviewAt)

        // Repairing the understanding on the corrected review resets the streak for everything after it:
        // the fourth answer becomes the FIRST unrepaired one again and gets the flat 3 days.
        repo.editReviewRating(unitId, logId, MemoryRating.Good, UnderstandingRating.Clear)
        val reset = repo.getUnitById(unitId)!!
        assertEquals("the last Partial is now the first of a new streak: 3 days", 3 * day, reset.understandingDueAt!! - reset.lastReviewedAt!!)
        assertEquals("the streak on the logs agrees", 1, repo.unrepairedStreak(unitId))
    }

    /**
     * DB v7: every log records the calibration scale it was scheduled with. When the scale later
     * moves, a replay must reproduce each untouched review's interval from ITS scale, and only the
     * row being corrected — a new decision — takes the scale the logs support now (never a stale
     * value left in memory).
     */
    @Test
    fun `a moved calibration scale never rewrites the intervals of untouched reviews`() = runBlocking {
        val day = 86400000L
        val now = System.currentTimeMillis()
        val studiedAt = now - 60 * day
        val before = MedScheduler.calibrationScale
        try {
            MedScheduler.calibrationScale = 0.8
            val unitId = repo.insertUnit(newUnit("Cirrhosis", studiedAt))
            val firstLogId = liveReview(unitId, studiedAt, MemoryRating.Good, UnderstandingRating.Clear)
            liveReview(unitId, studiedAt + 2 * day, MemoryRating.Good, UnderstandingRating.Clear)
            liveReview(unitId, studiedAt + 14 * day, MemoryRating.Good, UnderstandingRating.Clear)
            val live = repo.getUnitById(unitId)!!
            val liveLogs = db.reviewLogDao().getLogsForUnitOnce(unitId)
            assertTrue("every live log recorded the scale", liveLogs.all { it.calibrationScaleAtReview == 0.8 })

            // The evidence moves on: 200 on-schedule reviews elsewhere, all recalled at a predicted 90%.
            val otherId = repo.insertUnit(newUnit("Evidence", now - 400 * day))
            for (i in 0 until 200) {
                db.reviewLogDao().insertLog(
                    ReviewLogEntity(
                        studyUnitId = otherId, reviewedAt = now - 300 * day + i * 60_000L,
                        memoryRating = "Good", understandingRating = "Clear",
                        previousIntervalDays = 10.0, nextIntervalDays = 10.0, previousState = "Building", nextState = "Building",
                        retrievabilityAtReview = 0.9, elapsedDays = 10.0, logType = "RECALL",
                        schedulerVersion = MedScheduler.CURRENT_MODEL.id,
                    )
                )
            }
            val learned = repo.recallCalibrationScale()
            assertTrue("the scale really moved ($learned)", Math.abs(learned - 0.8) > 0.1 && Math.abs(learned - 1.25) > 0.1)

            MedScheduler.calibrationScale = 1.25 // a stale value in memory, which the correction must not use
            // Only the understanding changes: a new decision (so the new scale), with every memory state left alone.
            repo.editReviewRating(unitId, firstLogId, MemoryRating.Good, UnderstandingRating.Partial)
            val replayed = repo.getUnitById(unitId)!!
            val replayedLogs = db.reviewLogDao().getLogsForUnitOnce(unitId)

            assertEquals("the corrected row took the scale the logs support now", learned, replayedLogs[0].calibrationScaleAtReview, 1e-12)
            assertTrue("and its interval moved with it",
                Math.abs(replayedLogs[0].nextIntervalDays - liveLogs[0].nextIntervalDays) > 1e-6)
            for (i in 1 until liveLogs.size) {
                assertEquals("untouched row $i keeps its scale", 0.8, replayedLogs[i].calibrationScaleAtReview, 0.0)
                assertEquals("untouched row $i keeps its interval", liveLogs[i].nextIntervalDays, replayedLogs[i].nextIntervalDays, 1e-9)
            }
            assertEquals("the schedule the last review set is untouched", live.nextReviewAt, replayed.nextReviewAt)
            assertEquals("stability never depended on the scale", live.stability, replayed.stability, 1e-9)
        } finally {
            MedScheduler.calibrationScale = before
        }
    }

    /**
     * Crossing the FSRS-5 -> FSRS-6 boundary must not break preview == commit.
     *
     * The rating buttons preview an interval from the DISPLAYED unit's state. When FSRS-6 went live,
     * the review screen showed the raw un-projected row while the commit path projected first, so an
     * un-migrated topic previewed one number and then scheduled another. Projection is idempotent
     * precisely so it can run at display time AND at commit time without drifting.
     */
    @Test
    fun `projecting onto the current model is idempotent and moves state but never dates`() = runBlocking {
        val day = 86400000L
        val now = System.currentTimeMillis()
        val studiedAt = now - 40 * day

        // A topic as it exists BEFORE the migration: FSRS-5 state, FSRS-5 logs.
        val unitId = repo.insertUnit(
            StudyUnitEntity(
                title = "Appendicitis", studyType = "Topic",
                stability = 68.93, difficulty = 2.13, retrievability = 0.95, state = "Strong",
                studiedAt = studiedAt, nextReviewAt = now + 30 * day, modelDueAt = now + 30 * day,
                currentIntervalDays = 110.3, reviewCount = 2, lapseCount = 0,
                memoryModel = MedScheduler.MemoryModel.FSRS_5.id,
            )
        )
        repo.insertReviewLog(
            ReviewLogEntity(
                studyUnitId = unitId, reviewedAt = studiedAt + 1 * day,
                memoryRating = "Easy", understandingRating = "Clear",
                previousIntervalDays = 0.0, nextIntervalDays = 5.0,
                previousState = "New", nextState = "Building",
                retrievabilityAtReview = 1.0, elapsedDays = 1.0, logType = "FIRST_STUDY",
                schedulerVersion = MedScheduler.MemoryModel.FSRS_5.id,
            )
        )
        repo.insertReviewLog(
            ReviewLogEntity(
                studyUnitId = unitId, reviewedAt = studiedAt + 7 * day,
                memoryRating = "Easy", understandingRating = "Clear",
                previousIntervalDays = 5.0, nextIntervalDays = 110.3,
                previousState = "Building", nextState = "Strong",
                retrievabilityAtReview = 0.958, elapsedDays = 6.0, logType = "RECALL",
                schedulerVersion = MedScheduler.MemoryModel.FSRS_5.id,
            )
        )

        val before = repo.getUnitById(unitId)!!
        val projected = repo.projectOntoCurrentModel(before)

        assertEquals("now owned by the current model", MedScheduler.CURRENT_MODEL.id, projected.memoryModel)
        // Rebuilt from the real history rather than carried over, so it must actually differ.
        org.junit.Assert.assertNotEquals("state is rebuilt, not relabelled", before.stability, projected.stability, 1e-6)
        org.junit.Assert.assertTrue("and is a usable memory", projected.stability > 0.0 && projected.stability.isFinite())
        assertEquals("graded retrievals recounted from evidence", 2, projected.reviewCount)

        // The user was already promised these dates by the old model; the migration keeps that promise.
        assertEquals("effective due date untouched", before.nextReviewAt, projected.nextReviewAt)
        assertEquals("model due date untouched", before.modelDueAt, projected.modelDueAt)
        assertEquals("study date untouched", before.studiedAt, projected.studiedAt)

        // Idempotent: display-time and commit-time projection must agree exactly.
        val again = repo.projectOntoCurrentModel(projected)
        assertEquals("second projection changes nothing", projected.stability, again.stability, 0.0)
        assertEquals("second projection changes nothing", projected.difficulty, again.difficulty, 0.0)
        assertEquals("second projection changes nothing", projected.reviewCount, again.reviewCount)
        assertEquals("second projection changes nothing", projected.memoryModel, again.memoryModel)
    }

    /**
     * A skipped understanding question must STAY skipped through a replay.
     *
     * The fast Forgot path passes Partial as a placeholder so the math has a value, and the log
     * records the honest string "NotAsked". The factor column has to agree: writing Partial's 0.9
     * there puts a judgement in the research export that the user never made. The commit path was
     * fixed for this, and the replay quietly undid it on the next rating correction -- a half-fix is
     * worse than either end, because the data looks right until someone edits an old rating.
     */
    @Test
    fun `a skipped understanding question stays unrecorded through a replay`() = runBlocking {
        val day = 86400000L
        val now = System.currentTimeMillis()
        val unitId = repo.insertUnit(
            StudyUnitEntity(
                title = "Skipped understanding", studyType = "Topic",
                stability = 3.0, difficulty = 5.0, retrievability = 1.0, state = "Learning",
                studiedAt = now - 20 * day, nextReviewAt = now, modelDueAt = now,
                currentIntervalDays = 3.0, reviewCount = 2, lapseCount = 1,
                memoryModel = MedScheduler.CURRENT_MODEL.id,
            )
        )
        repo.insertReviewLog(
            ReviewLogEntity(
                studyUnitId = unitId, reviewedAt = now - 10 * day,
                memoryRating = "Good", understandingRating = "Clear",
                previousIntervalDays = 0.0, nextIntervalDays = 3.0,
                previousState = "New", nextState = "Learning",
                retrievabilityAtReview = 1.0, elapsedDays = 10.0, logType = "FIRST_STUDY",
                schedulerVersion = MedScheduler.CURRENT_MODEL.id,
                schedulerPolicyVersion = MedScheduler.POLICY_VERSION,
                understandingFactorAtReview = 1.0,
            )
        )
        // The fast-commit shape: Forgot with the question never asked.
        repo.insertReviewLog(
            ReviewLogEntity(
                studyUnitId = unitId, reviewedAt = now - 3 * day,
                memoryRating = "Forgot", understandingRating = "NotAsked",
                previousIntervalDays = 3.0, nextIntervalDays = 1.0,
                previousState = "Learning", nextState = "NeedsRelearn",
                retrievabilityAtReview = 0.7, elapsedDays = 7.0, logType = "RECALL",
                schedulerVersion = MedScheduler.CURRENT_MODEL.id,
                schedulerPolicyVersion = MedScheduler.POLICY_VERSION,
                understandingFactorAtReview = -1.0,
            )
        )

        val logs = db.reviewLogDao().getLogsForUnit(unitId).first().sortedBy { it.reviewedAt }
        // Correct the FIRST rating, leaving the NotAsked row untouched.
        repo.editReviewRating(unitId, logs.first().id, MemoryRating.Easy, UnderstandingRating.Clear)

        val after = db.reviewLogDao().getLogsForUnit(unitId).first().sortedBy { it.reviewedAt }
        val skipped = after.first { it.understandingRating == "NotAsked" }
        assertEquals("the honest string survives", "NotAsked", skipped.understandingRating)
        assertTrue(
            "and so must the sentinel -- a factor of ${skipped.understandingFactorAtReview} claims " +
                "the user answered when they never did",
            skipped.understandingFactorAtReview < 0.0,
        )
    }

    /**
     * A topic that goes overdue and is IGNORED must not be touched by the app at all.
     *
     * This is the most common real path in a study app: the reminder is dismissed, the user is busy
     * for weeks, and the topic sits there. Everything the app does in the meantime is a READ — the
     * queue lists it, the Today screen scores it, the review screen projects it onto the current
     * memory model to preview intervals. None of that is evidence about the user's memory, so none
     * of it may be written back. If any of it were, the topic would drift on its own and the review
     * history would stop explaining the state.
     *
     * The projection is the sharp edge: it returns a MODIFIED copy (FSRS-6 state rebuilt from the
     * FSRS-5 history) and the review screen shows that copy — but the row must stay on FSRS-5 until
     * a real review commits it.
     */
    @Test
    fun `an overdue topic is never written to just by being looked at`() = runBlocking {
        val day = 86400000L
        val now = System.currentTimeMillis()

        val unitId = repo.insertUnit(
            StudyUnitEntity(
                title = "Ignored for a month", studyType = "Topic",
                stability = 9.4, difficulty = 5.5, retrievability = 0.9, state = "Building",
                studiedAt = now - 60 * day,
                nextReviewAt = now - 30 * day, modelDueAt = now - 30 * day, // a month overdue
                currentIntervalDays = 9.0, reviewCount = 2, lapseCount = 0,
                memoryModel = MedScheduler.MemoryModel.FSRS_5.id, // not yet migrated
            )
        )
        repo.insertReviewLog(
            ReviewLogEntity(
                studyUnitId = unitId, reviewedAt = now - 50 * day,
                memoryRating = "Good", understandingRating = "Clear",
                previousIntervalDays = 0.0, nextIntervalDays = 3.0,
                previousState = "New", nextState = "Learning",
                retrievabilityAtReview = 1.0, elapsedDays = 10.0, logType = "FIRST_STUDY",
                schedulerVersion = MedScheduler.MemoryModel.FSRS_5.id,
            )
        )
        repo.insertReviewLog(
            ReviewLogEntity(
                studyUnitId = unitId, reviewedAt = now - 39 * day,
                memoryRating = "Good", understandingRating = "Clear",
                previousIntervalDays = 3.0, nextIntervalDays = 9.0,
                previousState = "Learning", nextState = "Building",
                retrievabilityAtReview = 0.91, elapsedDays = 11.0, logType = "RECALL",
                schedulerVersion = MedScheduler.MemoryModel.FSRS_5.id,
            )
        )

        val before = repo.getUnitById(unitId)!!

        // Everything the app does while the topic sits there ignored, several times over.
        repeat(3) {
            val due = repo.getDueUnits(now).first()
            assertTrue("an overdue topic must stay in the queue", due.any { it.id == unitId })
            due.forEach { u ->
                MedScheduler.priorityScore(u.highYield, u.nextReviewAt, now)
                MedScheduler.retrievability(30.0, u.stability, MedScheduler.MemoryModel.of(u.memoryModel))
            }
            // The review screen projects before displaying — a pure read that must not persist.
            val projected = repo.projectOntoCurrentModel(repo.getUnitById(unitId)!!)
            assertEquals("the preview really is on the new model", MedScheduler.CURRENT_MODEL.id, projected.memoryModel)
        }

        val after = repo.getUnitById(unitId)!!
        assertEquals("the stored row must be byte-identical after all that reading", before, after)
        assertEquals("still owned by the legacy model on disk", MedScheduler.MemoryModel.FSRS_5.id, after.memoryModel)
        assertEquals("no fabricated review", 2, after.reviewCount)
        assertEquals("no fabricated lapse", 0, after.lapseCount)
        assertEquals("the promised date is untouched", now - 30 * day, after.nextReviewAt)
        assertEquals("history is untouched", 2, db.reviewLogDao().getLogsForUnit(unitId).first().size)
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
        assertEquals("modelDueAt equals effective date after replay", replayed.nextReviewAt, replayed.modelDueAt)
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

    /**
     * Live and replay must count time the same way at ANY hour, not only at whole-day offsets.
     *
     * The helper above used to measure elapsed time in milliseconds while the app counts whole local
     * calendar days. Every other test here reviews at exact multiples of 24 hours, where the two
     * conventions agree, so they passed while mirroring the wrong rule. Evening and morning reviews are
     * where they differ: 20:00 to 09:00 three days later is 2 days in milliseconds but 3 calendar days.
     */
    @Test
    fun `replay equals live when reviews happen at different hours of the day`() = runBlocking {
        val zone = java.time.ZoneId.systemDefault()
        val studyDay = java.time.LocalDate.now(zone).minusDays(30)
        fun at(daysAfter: Long, hour: Int): Long =
            studyDay.plusDays(daysAfter).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

        val unitId = repo.insertUnit(newUnit("Nephrotic syndrome", at(0, 19)))
        liveReview(unitId, at(0, 20), MemoryRating.Good, UnderstandingRating.Clear)                 // evening first check-in
        liveReview(unitId, at(3, 9), MemoryRating.Good, UnderstandingRating.Clear)                  // 61 hours later
        liveReview(unitId, at(4, 22), MemoryRating.Hard, UnderstandingRating.Partial)              // 37 hours later
        liveReview(unitId, at(5, 7), MemoryRating.Easy, UnderstandingRating.Clear)                  // 9 hours later
        val live = repo.getUnitById(unitId)!!

        repo.editReviewRating(unitId, -1L, MemoryRating.Good, UnderstandingRating.Clear) // a pure replay
        val replayed = repo.getUnitById(unitId)!!

        assertEquals("stability", live.stability, replayed.stability, 1e-9)
        assertEquals("difficulty", live.difficulty, replayed.difficulty, 1e-9)
        assertEquals("interval", live.currentIntervalDays, replayed.currentIntervalDays, 1e-9)
        assertEquals("nextReviewAt", live.nextReviewAt, replayed.nextReviewAt)
        assertEquals("modelDueAt", live.modelDueAt, replayed.modelDueAt)
        assertEquals("understandingDueAt", live.understandingDueAt, replayed.understandingDueAt)
        assertEquals("reviewCount", live.reviewCount, replayed.reviewCount)
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

    /**
     * A phone clock set back between two reviews (changed by hand, or a phone that boots with the wrong date) gives the
     * later review the EARLIER time. The live path counts that gap as zero. Sorted by time, a replay put the two in
     * the wrong order and rebuilt a state the topic never had (an outside audit, 2026-10-02); it walks the saved order
     * now (REVIEW_HISTORY_ORDER), and the repair-clock streak counts back in that order too.
     */
    @Test
    fun `replay equals live when the clock was set back between two reviews`() = runBlocking {
        val zone = java.time.ZoneId.systemDefault()
        val studyDay = java.time.LocalDate.now(zone).minusDays(40)
        fun at(daysAfter: Long, hour: Int): Long =
            studyDay.plusDays(daysAfter).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

        val unitId = repo.insertUnit(newUnit("Hyperkalaemia", at(0, 9)))
        liveReview(unitId, at(0, 10), MemoryRating.Good, UnderstandingRating.Clear)
        liveReview(unitId, at(9, 10), MemoryRating.Hard, UnderstandingRating.Partial)
        liveReview(unitId, at(4, 10), MemoryRating.Good, UnderstandingRating.Partial) // the clock five days behind
        liveReview(unitId, at(12, 10), MemoryRating.Good, UnderstandingRating.Clear)
        val live = repo.getUnitById(unitId)!!

        repo.editReviewRating(unitId, -1L, MemoryRating.Good, UnderstandingRating.Clear) // a pure replay
        val replayed = repo.getUnitById(unitId)!!

        assertEquals("stability", live.stability, replayed.stability, 1e-9)
        assertEquals("difficulty", live.difficulty, replayed.difficulty, 1e-9)
        assertEquals("interval", live.currentIntervalDays, replayed.currentIntervalDays, 1e-9)
        assertEquals("nextReviewAt", live.nextReviewAt, replayed.nextReviewAt)
        assertEquals("understandingDueAt", live.understandingDueAt, replayed.understandingDueAt)
        assertEquals("lastReviewedAt", live.lastReviewedAt, replayed.lastReviewedAt)
    }

    /**
     * A replay after the phone's time zone changed (an outside audit, 2026-10-04). Two reviews at 23:30 and 00:30 in
     * Tehran are a calendar day apart; counted again in UTC they fall on one day, and the replay used to rebuild a
     * stability of 2.31 days where the live one was 7.32. Every replay now reads back each review's own day count
     * (MedScheduler.storedModelDays): a rating correction, the move onto a new weight set and the personal model's
     * training data all rebuild the history the topic actually had.
     */
    @Test
    fun `replay equals live after the phone's time zone changed`() = runBlocking {
        val savedZone = java.util.TimeZone.getDefault()
        val savedActive = MedScheduler.activeParameterSet
        val savedKnown = MedScheduler.knownParameterSets
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Tehran"))
            val zone = java.time.ZoneId.of("Asia/Tehran")
            val day = java.time.LocalDate.now(zone).minusDays(30)
            fun at(daysAfter: Long, hour: Int, minute: Int): Long =
                day.plusDays(daysAfter).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

            val unitId = repo.insertUnit(newUnit("Nephritic syndrome", at(0, 22, 0)))
            liveReview(unitId, at(0, 23, 30), MemoryRating.Good, UnderstandingRating.Clear) // first rating, 23:30
            liveReview(unitId, at(1, 0, 30), MemoryRating.Good, UnderstandingRating.Clear) // an hour later: the next day there
            liveReview(unitId, at(9, 20, 0), MemoryRating.Good, UnderstandingRating.Clear)
            val live = repo.getUnitById(unitId)!!
            val second = db.reviewLogDao().getLogsForUnitOnce(unitId).sortedBy { it.id }[1]
            assertEquals("in Tehran the second review came a calendar day later", 1.0, second.elapsedDays, 0.0)

            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC")) // the phone now says UTC
            assertEquals("counted again in UTC, the two fall on one day", 0.0,
                MedScheduler.modelElapsedDays(at(0, 23, 30), at(1, 0, 30), MedScheduler.MemoryModel.FSRS_6), 0.0)

            repo.editReviewRating(unitId, -1L, MemoryRating.Good, UnderstandingRating.Clear) // a pure replay
            val replayed = repo.getUnitById(unitId)!!
            assertEquals("stability", live.stability, replayed.stability, 1e-9)
            assertEquals("difficulty", live.difficulty, replayed.difficulty, 1e-9)
            assertEquals("interval", live.currentIntervalDays, replayed.currentIntervalDays, 1e-9)
            assertEquals("nextReviewAt", live.nextReviewAt, replayed.nextReviewAt)
            assertEquals("and the second review keeps its day", 1.0,
                db.reviewLogDao().getLogsForUnitOnce(unitId).first { it.id == second.id }.elapsedDays, 0.0)

            // The personal model's training data counts the same days.
            assertEquals(1.0, repo.trainingHistories().single().elapsedDays[1], 0.0)

            // A new weight set (the defaults' numbers under another id): the topic crosses to it by replaying its history.
            MedScheduler.knownParameterSets = mapOf(5L to com.example.domain.srs.Fsrs6Parameters.DEFAULT_WEIGHTS)
            MedScheduler.activeParameterSet = MedScheduler.ParameterSet(5L, com.example.domain.srs.Fsrs6Parameters.DEFAULT_WEIGHTS)
            val projected = repo.projectOntoCurrentModel(repo.getUnitById(unitId)!!)
            assertEquals("crossed onto the new set", 5L, projected.parameterSetId)
            assertEquals("with the state it had", live.stability, projected.stability, 1e-9)
            assertEquals(live.difficulty, projected.difficulty, 1e-9)
        } finally {
            java.util.TimeZone.setDefault(savedZone)
            MedScheduler.activeParameterSet = savedActive
            MedScheduler.knownParameterSets = savedKnown
        }
    }

    /** A correction rewrites the log in place; the answer it replaced is kept as an event (2026-10-03). */
    @Test
    fun `a correction records the answer it replaced, and nothing else does`() = runBlocking {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val unitId = repo.insertUnit(newUnit("Hyponatraemia", now - 20 * day))
        liveReview(unitId, now - 20 * day + 60_000, MemoryRating.Good, UnderstandingRating.Clear)
        val second = liveReview(unitId, now - 15 * day, MemoryRating.Good, UnderstandingRating.Clear)
        val last = liveReview(unitId, now - 5 * day, MemoryRating.Easy, UnderstandingRating.Clear)
        fun corrections() = runBlocking { db.eventLogDao().getAll().filter { it.type == "RATING_CORRECTED" } }

        repo.editReviewRating(unitId, -1L, MemoryRating.Good, UnderstandingRating.Clear) // a pure replay
        repo.editReviewRating(unitId, second, MemoryRating.Good, UnderstandingRating.Clear) // changes nothing
        assertEquals("a replay or an unchanged correction records nothing", 0, corrections().size)

        repo.editReviewRating(unitId, second, MemoryRating.Forgot, UnderstandingRating.Partial)
        val recorded = corrections().single()
        assertEquals(unitId, recorded.unitId)
        // upto: the topic's last log the replay rewrote, so which predictions it recomputed is known without the clock.
        assertEquals("log=$second upto=$last memory=Good>Forgot understanding=Clear>Partial", recorded.detail)
        assertEquals("and the log holds the new answer", "Forgot", db.reviewLogDao().getLogsForUnitOnce(unitId).first { it.id == second }.memoryRating)
    }

    private fun newUnit(title: String, studiedAt: Long): StudyUnitEntity {
        val seed = MedScheduler.firstStudy(UnderstandingRating.Partial, highYield = false)
        return StudyUnitEntity(
            title = title, studyType = "Pathology",
            stability = seed.state.stability, difficulty = seed.state.difficulty,
            retrievability = 1.0, state = "New",
            studiedAt = studiedAt, nextReviewAt = studiedAt, modelDueAt = studiedAt,
            currentIntervalDays = 0.0, reviewCount = 0, lapseCount = 0,
        )
    }

    /**
     * An UNRATED topic is due on its study date, so editing that date must move the due date with it.
     * Regression: a topic back-dated after creation used to keep its original due date, leaving it due
     * BEFORE the day the user says they studied it (observed in a real analytics export).
     */
    @Test
    fun `moving the study date of an unrated topic moves its due date`() = runBlocking {
        val day = 86400000L
        val now = System.currentTimeMillis()
        val studiedAt = now - 10 * day
        val unitId = repo.insertUnit(newUnit("COPD", studiedAt))

        val moved = studiedAt - 10 * day
        repo.updateUnitReplayingHistory(repo.getUnitById(unitId)!!.copy(studiedAt = moved, updatedAt = now))
        val after = repo.getUnitById(unitId)!!

        assertEquals("unrated topic is due on its (new) study date", moved, after.nextReviewAt)
        assertEquals("model due date follows too", moved, after.modelDueAt)
    }

    /**
     * The mirror invariant, and the point of POLICY YADORA-2: once a topic has been RATED, its schedule
     * comes from the ratings, not from when the user says they studied it. Moving the study date must
     * therefore leave a rated topic's schedule and memory state completely alone. Before YADORA-2 this
     * date silently re-classified the first rating as a long-gap recall against a placeholder memory
     * state, which is what made overdue/back-dated topics schedule months out.
     */
    @Test
    fun `moving the study date of a rated topic leaves its schedule untouched`() = runBlocking {
        val day = 86400000L
        val now = System.currentTimeMillis()
        val studiedAt = now - 10 * day
        val unitId = repo.insertUnit(newUnit("COPD", studiedAt))
        liveReview(unitId, now - 4 * day, MemoryRating.Good, UnderstandingRating.Clear)
        val before = repo.getUnitById(unitId)!!

        repo.updateUnitReplayingHistory(before.copy(studiedAt = studiedAt - 10 * day, updatedAt = now))
        val after = repo.getUnitById(unitId)!!

        assertEquals("nextReviewAt", before.nextReviewAt, after.nextReviewAt)
        assertEquals("modelDueAt", before.modelDueAt, after.modelDueAt)
        assertEquals("stability", before.stability, after.stability, 1e-9)
        assertEquals("difficulty", before.difficulty, after.difficulty, 1e-9)
    }

    @Test
    fun `procrastinating records a deferral and preserves the model due date`() = runBlocking {
        val day = 86400000L
        val now = System.currentTimeMillis()
        val seed = MedScheduler.firstStudy(UnderstandingRating.Partial, highYield = false)
        val unitId = repo.insertUnit(
            StudyUnitEntity(
                title = "Nephron", studyType = "Pathology",
                stability = seed.state.stability, difficulty = seed.state.difficulty,
                retrievability = 1.0, state = "New",
                studiedAt = now - 3 * day, nextReviewAt = now - day, modelDueAt = now - day,
                currentIntervalDays = 0.0, reviewCount = 1, lapseCount = 0,
            )
        )
        val tomorrow = now + day
        db.studyUnitDao().procrastinateAllDue(now, tomorrow, now)
        val after = repo.getUnitById(unitId)!!

        assertEquals("effective date moved", tomorrow, after.nextReviewAt)
        assertEquals("deferral recorded", tomorrow, after.deferredUntil)
        assertEquals("model's opinion untouched", now - day, after.modelDueAt)

        // A real review then clears the deferral and re-unifies the two dates.
        liveReview(unitId, now, MemoryRating.Good, UnderstandingRating.Clear)
        val reviewed = repo.getUnitById(unitId)!!
        org.junit.Assert.assertNull("review spends the deferral", reviewed.deferredUntil)
        assertEquals("model date is the effective date again", reviewed.nextReviewAt, reviewed.modelDueAt)
    }

    /**
     * The notification's "Not today" defers today's REVIEWS. A topic never rated stays due: the plan never holds a
     * first rating back and its schedule counts from the rating (an outside audit, 2026-09-30, found it moved).
     */
    @Test
    fun `the notification's not-today leaves a first rating due`() = runBlocking {
        val day = 86400000L
        val now = System.currentTimeMillis()
        val unrated = repo.insertUnit(newUnit("Hyponatraemia", now - day))
        val rated = repo.insertUnit(newUnit("Sepsis bundle", now - 6 * day))
        liveReview(rated, now - 5 * day, MemoryRating.Hard, UnderstandingRating.Clear)
        val ratedBefore = repo.getUnitById(rated)!!
        org.junit.Assert.assertTrue("the review is due", ratedBefore.nextReviewAt <= now)

        val tomorrow = now + day
        db.studyUnitDao().procrastinateAllDue(now, tomorrow, now)

        val first = repo.getUnitById(unrated)!!
        assertEquals("the first rating is still due on its study day", now - day, first.nextReviewAt)
        org.junit.Assert.assertNull("and nothing was recorded as deferred", first.deferredUntil)
        assertEquals("the review moved to tomorrow", tomorrow, repo.getUnitById(rated)!!.nextReviewAt)
    }

    @Test
    fun `per-topic not-today is a transactional deferral with an audit event`() = runBlocking {
        val day = 86400000L
        val now = System.currentTimeMillis()
        val seed = MedScheduler.firstStudy(UnderstandingRating.Partial, highYield = false)
        val unitId = repo.insertUnit(
            StudyUnitEntity(
                title = "Gout", studyType = "Pathology",
                stability = seed.state.stability, difficulty = seed.state.difficulty,
                retrievability = 1.0, state = "New",
                studiedAt = now - 2 * day, nextReviewAt = now - day, modelDueAt = now - day,
                currentIntervalDays = 0.0, reviewCount = 1, lapseCount = 0,
            )
        )
        val tomorrow = now + day
        repo.procrastinateUnit(unitId, tomorrow)
        val after = repo.getUnitById(unitId)!!

        assertEquals("effective date moved", tomorrow, after.nextReviewAt)
        assertEquals("recorded as a USER deferral", tomorrow, after.deferredUntil)
        assertEquals("model's own date untouched", now - day, after.modelDueAt)
        org.junit.Assert.assertTrue(
            "audit event written atomically with the deferral",
            db.eventLogDao().getAll().any { it.type == "PROCRASTINATE" && it.unitId == unitId })
    }

    /**
     * The Library's review-now opens topics that are not due. "Not today" there used to write tomorrow
     * morning unconditionally, pulling a topic due in a month forward and recording it as a deferral.
     */
    @Test
    fun `not today never pulls a topic forward`() = runBlocking {
        val day = 86400000L
        val now = System.currentTimeMillis()
        val dueLater = now + 30 * day
        val unitId = repo.insertUnit(
            StudyUnitEntity(
                title = "Sarcoidosis", studyType = "Pathology",
                stability = 30.0, difficulty = 5.0, retrievability = 1.0, state = "Strong",
                studiedAt = now - 60 * day, lastReviewedAt = now - day, nextReviewAt = dueLater, modelDueAt = dueLater,
                currentIntervalDays = 31.0, reviewCount = 4, lapseCount = 0, memoryModel = MedScheduler.CURRENT_MODEL.id,
            )
        )
        repo.procrastinateUnit(unitId, now + day)
        val after = repo.getUnitById(unitId)!!

        assertEquals("due date unchanged", dueLater, after.nextReviewAt)
        assertEquals("no deferral recorded", null, after.deferredUntil)
        assertEquals("model date unchanged", dueLater, after.modelDueAt)
        assertTrue("no deferral event either",
            db.eventLogDao().getAll().none { it.type == "PROCRASTINATE" && it.unitId == unitId })
    }

    /**
     * A rating correction is a new scheduling decision. It must use the learned calibration even when
     * no review session has run in this process, where MedScheduler.calibrationScale is still 1.0.
     */
    @Test
    fun `a rating correction uses the learned calibration straight after launch`() = runBlocking {
        val day = 86400000L
        val now = System.currentTimeMillis()
        val before = MedScheduler.calibrationScale
        try {
            // Evidence that this learner forgets faster than the defaults: 200 on-schedule reviews
            // predicted at 90%, 85% recalled, on another topic.
            val otherId = repo.insertUnit(newUnit("Evidence", now - 400 * day))
            for (i in 0 until 200) {
                db.reviewLogDao().insertLog(
                    ReviewLogEntity(
                        studyUnitId = otherId, reviewedAt = now - 300 * day + i * 60_000L,
                        memoryRating = if (i < 170) "Good" else "Forgot",
                        understandingRating = if (i < 170) "Clear" else "NotAsked",
                        previousIntervalDays = 10.0, nextIntervalDays = 10.0, previousState = "Building", nextState = "Building",
                        retrievabilityAtReview = 0.9, elapsedDays = 10.0, logType = "RECALL",
                        schedulerVersion = MedScheduler.CURRENT_MODEL.id,
                    )
                )
            }
            val learned = repo.recallCalibrationScale()
            assertTrue("the evidence implies a correction ($learned)", learned < 0.9)

            MedScheduler.calibrationScale = 1.0 // a fresh process: nothing has refreshed it yet
            val studiedAt = now - 40 * day
            val unitId = repo.insertUnit(newUnit("Cholangitis", studiedAt))
            liveReview(unitId, studiedAt, MemoryRating.Good, UnderstandingRating.Clear)
            val logId = liveReview(unitId, studiedAt + 2 * day, MemoryRating.Good, UnderstandingRating.Clear)
            MedScheduler.calibrationScale = 1.0

            repo.editReviewRating(unitId, logId, MemoryRating.Hard, UnderstandingRating.Clear)
            val edited = db.reviewLogDao().getLogsForUnitOnce(unitId).first { it.id == logId }
            assertEquals("the corrected row was scheduled with the learned scale", learned, edited.calibrationScaleAtReview, 1e-12)
        } finally {
            MedScheduler.calibrationScale = before
        }
    }

    @Test
    fun `undo removes the growth event so the visual can't count an undone review`() = runBlocking {
        val now = System.currentTimeMillis()
        val seed = MedScheduler.firstStudy(UnderstandingRating.Partial, highYield = false)
        val unitId = repo.insertUnit(
            StudyUnitEntity(
                title = "Anemia", studyType = "Pathology",
                stability = seed.state.stability, difficulty = seed.state.difficulty,
                retrievability = 1.0, state = "New",
                studiedAt = now, nextReviewAt = now, modelDueAt = now,
                currentIntervalDays = 0.0, reviewCount = 0, lapseCount = 0,
            )
        )
        val before = repo.getUnitById(unitId)!!
        val logId = liveReview(unitId, now, MemoryRating.Good, UnderstandingRating.Clear)
        assertEquals("commit creates exactly one growth event", 1,
            db.eventLogDao().getAll().count { it.type == "STUDY_ACTION" })

        repo.undoReview(before, logId)
        assertEquals("undo removes the growth event with the review", 0,
            db.eventLogDao().getAll().count { it.type == "STUDY_ACTION" })
    }

    @Test
    fun `soft delete hides the topic but restore brings it back with history`() = runBlocking {
        val now = System.currentTimeMillis()
        val seed = MedScheduler.firstStudy(UnderstandingRating.Partial, highYield = false)
        val unitId = repo.insertUnit(
            StudyUnitEntity(
                title = "Krebs cycle", studyType = "Pathology",
                stability = seed.state.stability, difficulty = seed.state.difficulty,
                retrievability = 1.0, state = "New",
                studiedAt = now, nextReviewAt = now, modelDueAt = now,
                currentIntervalDays = 0.0, reviewCount = 0, lapseCount = 0,
            )
        )
        liveReview(unitId, now, MemoryRating.Good, UnderstandingRating.Clear)

        repo.softDeleteUnit(unitId)
        val deleted = repo.getUnitById(unitId)!!
        org.junit.Assert.assertNotNull("deletedAt stamped", deleted.deletedAt)
        org.junit.Assert.assertTrue("hidden from active lists", deleted.archived)
        assertEquals("still recoverable", 1, repo.recentlyDeleted.first().size)

        // Within the grace window the purge must NOT touch it.
        repo.purgeExpiredDeleted()
        org.junit.Assert.assertNotNull("survives purge inside grace", repo.getUnitById(unitId))

        repo.restoreDeletedUnit(unitId)
        val restored = repo.getUnitById(unitId)!!
        org.junit.Assert.assertNull("no longer deleted", restored.deletedAt)
        org.junit.Assert.assertFalse("back in the active library", restored.archived)
        assertEquals("history intact", 1, db.reviewLogDao().getLogsForUnit(unitId).first().size)

        // Past the grace window the purge removes the topic AND its history.
        repo.softDeleteUnit(unitId)
        repo.purgeExpiredDeleted(graceMillis = -1L) // cutoff in the future → everything deleted qualifies
        org.junit.Assert.assertNull("purged after grace", repo.getUnitById(unitId))
        assertEquals("history purged with it", 0, db.reviewLogDao().getLogsForUnit(unitId).first().size)
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
