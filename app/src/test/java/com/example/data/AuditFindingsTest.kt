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
import com.example.domain.model.SessionKind
import com.example.domain.model.UnderstandingRating
import com.example.domain.srs.Fsrs6Optimizer
import com.example.domain.srs.Fsrs6Parameters
import com.example.domain.srs.MedScheduler
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
 * Defects two outside audits reported on 2026-09-27, each checked against the code and reproduced before it was
 * fixed. Real Room database, real repository, histories written through the real commit path ([MedReviewRepository.rateUnit]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AuditFindingsTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: MedReviewRepository
    private val day = 86_400_000L

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = MedReviewRepository(db.studyUnitDao(), db.categoryDao(), db.reviewLogDao(), db)
        resetScheduler()
    }

    @After fun teardown() {
        db.close()
        resetScheduler()
    }

    private fun resetScheduler() {
        MedScheduler.activeParameterSet = MedScheduler.DEFAULT_PARAMETER_SET
        MedScheduler.knownParameterSets = emptyMap()
        MedScheduler.calibrationScale = 1.0
    }

    private suspend fun topic(title: String, studiedAt: Long): Long = repo.insertUnit(
        StudyUnitEntity(
            title = title, studyType = "Topic", stability = 1.0, difficulty = 5.0, retrievability = 1.0, state = "New",
            studiedAt = studiedAt, nextReviewAt = studiedAt, modelDueAt = studiedAt, currentIntervalDays = 0.0,
            reviewCount = 0, lapseCount = 0, createdAt = studiedAt,
        )
    )

    private suspend fun rate(id: Long, at: Long, m: MemoryRating, u: UnderstandingRating = UnderstandingRating.Clear) =
        repo.rateUnit(unitId = id, now = at, memoryRating = m, understandingRating = u, sessionKind = SessionKind.PLAN, reviewDurationMs = 60_000)!!

    /** Saving a correction with the rating it already had must change nothing, whatever the topic's history. */
    @Test
    fun `an unchanged rating correction moves nothing, even on a merged topic`() = runBlocking {
        val now = System.currentTimeMillis()
        val a = topic("Appendicitis", now - 60 * day)
        rate(a, now - 60 * day, MemoryRating.Good); rate(a, now - 57 * day, MemoryRating.Good); rate(a, now - 47 * day, MemoryRating.Easy)
        val b = topic("آپاندیسیت", now - 52 * day)
        rate(b, now - 52 * day, MemoryRating.Hard); rate(b, now - 47 * day, MemoryRating.Good)
        repo.mergeUnits(a, listOf(b))!!
        val rowBefore = repo.getUnitById(a)!!
        val logsBefore = db.reviewLogDao().getLogsForUnitOnce(a)

        val latest = logsBefore.last()
        repo.editReviewRating(a, latest.id, MemoryRating.valueOf(latest.memoryRating), UnderstandingRating.valueOf(latest.understandingRating))

        assertEquals("the merged state stands", rowBefore, repo.getUnitById(a))
        assertEquals("and so does every log", logsBefore, db.reviewLogDao().getLogsForUnitOnce(a))
    }

    /**
     * A correction recomputes every log's prediction on the model and weight set the topic is on, so every log
     * says so. It used to keep its original stamp: rows labelled "defaults" held a personal set's numbers.
     */
    @Test
    fun `a corrected history is stamped with the model and weight set that computed it`() = runBlocking {
        val now = System.currentTimeMillis()
        val id = topic("Pancreatitis", now - 50 * day)
        rate(id, now - 50 * day, MemoryRating.Good)
        val onDefaults = rate(id, now - 47 * day, MemoryRating.Good).logId
        assertEquals(0L, db.reviewLogDao().getLogsForUnitOnce(id).map { it.parameterSetId }.distinct().single())

        // A personal set is adopted; the next review carries the topic onto it by replay.
        val personal = Fsrs6Parameters.DEFAULT_WEIGHTS.copyOf().also { for (i in 0..3) it[i] *= 0.5; it[20] = 0.3 }
        val setId = db.memoryParameterSetDao().insert(
            MemoryParameterSetEntity(
                createdAt = now, status = MemoryParameterSetEntity.ACTIVE, weights = Fsrs6Optimizer.encode(personal),
                comparedWithSetId = 0, availableReviews = 700, trainReviews = 560, testReviews = 140,
                currentLogLoss = 0.36, candidateLogLoss = 0.33, currentRmseBins = 0.09, candidateRmseBins = 0.05,
                currentAuc = 0.70, candidateAuc = 0.73, zScore = 6.0, activatedAt = now,
            )
        )
        repo.refreshMemoryModel()
        rate(id, now - 30 * day, MemoryRating.Good)
        assertEquals("the topic crossed to the personal set", setId, repo.getUnitById(id)!!.parameterSetId)

        repo.editReviewRating(id, onDefaults, MemoryRating.Hard, UnderstandingRating.Clear)
        val logs = db.reviewLogDao().getLogsForUnitOnce(id)
        assertTrue("every row names the set its numbers came from: $logs", logs.all { it.parameterSetId == setId })
        assertTrue("and the model", logs.all { it.schedulerVersion == MedScheduler.CURRENT_MODEL.id })
    }

    /** A review stamped on a later day (a clock that was ahead, then corrected) counts on that day, not today. */
    @Test
    fun `a review stamped on a later day does not use up today's limit`() = runBlocking {
        val now = System.currentTimeMillis()
        val due = topic("Hyperkalaemia", now - 20 * day)
        rate(due, now - 20 * day, MemoryRating.Good)
        rate(due, now - 17 * day, MemoryRating.Hard) // due again well before today
        val other = topic("Sepsis", now - 10 * day)
        db.reviewLogDao().insertLog(
            ReviewLogEntity(
                studyUnitId = other, reviewedAt = now + 2 * day, memoryRating = "Good", understandingRating = "Clear",
                previousIntervalDays = 3.0, nextIntervalDays = 6.0, previousState = "Learning", nextState = "Building",
                logType = "RECALL",
            )
        )
        val plan = repo.todayPlan(dailyLimit = 1, now = now)
        assertEquals("nothing was reviewed today", 0, plan.doneToday)
        assertTrue("so the due review is offered", plan.queue.any { it.id == due })
    }

    /** Undo takes back the review; what the learner edited in the meantime stays. */
    @Test
    fun `undo takes back the review, not the edits made since`() = runBlocking {
        val now = System.currentTimeMillis()
        val id = topic("Cushing's syndrome", now - 10 * day)
        rate(id, now - 10 * day, MemoryRating.Good)
        val rated = rate(id, now, MemoryRating.Easy)
        repo.updateUnit(repo.getUnitById(id)!!.copy(title = "Cushing syndrome — causes", notes = "ACTH-dependent first", highYield = true))

        repo.undoReview(rated.before, rated.logId)
        val after = repo.getUnitById(id)!!
        assertEquals("the edit stays", "Cushing syndrome — causes", after.title)
        assertEquals("ACTH-dependent first", after.notes)
        assertTrue(after.highYield)
        assertEquals("the review is gone", rated.before.reviewCount, after.reviewCount)
        assertEquals(rated.before.stability, after.stability, 0.0)
        assertEquals(rated.before.nextReviewAt, after.nextReviewAt)
        assertEquals(1, db.reviewLogDao().getLogsForUnitOnce(id).size)
    }

    /**
     * Undo puts back the row as it was STORED. A topic still on an older model is shown and rated projected onto
     * the current one, and Undo used to put that projected copy back: the row then said FSRS-6 over a history
     * FSRS-5 wrote, which the research export's self-check reports as MODEL_OWNERSHIP. Found on the Samsung,
     * 2026-09-28.
     */
    @Test
    fun `undo puts back the row as stored, not the copy projected for the screen`() = runBlocking {
        val now = System.currentTimeMillis()
        val id = topic("Hypothyroidism", now - 20 * day)
        rate(id, now - 20 * day, MemoryRating.Good)
        rate(id, now - 10 * day, MemoryRating.Good)
        // A topic an older build wrote: its row and its logs on FSRS-5.
        db.reviewLogDao().getLogsForUnitOnce(id).forEach { db.reviewLogDao().insertLog(it.copy(schedulerVersion = "FSRS-5")) }
        repo.updateUnit(repo.getUnitById(id)!!.copy(memoryModel = "FSRS-5"))
        val stored = repo.getUnitById(id)!!

        val rated = rate(id, now, MemoryRating.Good)
        assertEquals("the review itself runs on the current model", MedScheduler.CURRENT_MODEL.id, rated.after.memoryModel)
        repo.undoReview(rated.before, rated.logId)

        assertEquals("undo leaves the row exactly as it was stored", stored, repo.getUnitById(id))
        assertEquals(2, db.reviewLogDao().getLogsForUnitOnce(id).size)
    }

    /** The personal fit leaves merged topics out, as the pilot analysis does. */
    @Test
    fun `merged topics are left out of the personal fit`() = runBlocking {
        val now = System.currentTimeMillis()
        val a = topic("Addison's disease", now - 40 * day)
        rate(a, now - 40 * day, MemoryRating.Good); rate(a, now - 37 * day, MemoryRating.Good); rate(a, now - 27 * day, MemoryRating.Good)
        val b = topic("بیماری آدیسون", now - 35 * day)
        rate(b, now - 35 * day, MemoryRating.Good); rate(b, now - 32 * day, MemoryRating.Hard); rate(b, now - 20 * day, MemoryRating.Good)
        val c = topic("Hypothyroidism", now - 40 * day)
        rate(c, now - 40 * day, MemoryRating.Good); rate(c, now - 37 * day, MemoryRating.Good)
        assertEquals(3, repo.trainingHistories().size)

        repo.mergeUnits(a, listOf(b))!!
        assertEquals("only the untouched topic trains", 1, repo.trainingHistories().size)
    }
}
