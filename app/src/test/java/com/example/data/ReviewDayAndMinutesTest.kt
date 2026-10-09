package com.example.data

import androidx.test.core.app.ApplicationProvider
import com.example.MedReviewApplication
import com.example.data.local.entity.EventLogEntity
import com.example.data.local.entity.REVIEW_HISTORY_ORDER
import com.example.data.local.entity.StudyUnitEntity
import com.example.domain.model.MemoryRating
import com.example.domain.model.ReviewDay
import com.example.domain.model.SessionKind
import com.example.domain.model.StudyMinutes
import com.example.domain.model.UnderstandingRating
import com.example.domain.srs.MedScheduler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

/**
 * What a rating records since the owner's redesign (2026-10-09): the day the review happened when it was not today, the
 * time it was saved, the learner's optional minutes, the understanding answer after Forgot, and the day of a logged
 * review corrected from the topic's history. Through the real commit and replay paths, and through backup and restore.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReviewDayAndMinutesTest {
    private val app get() = ApplicationProvider.getApplicationContext<MedReviewApplication>()
    private val repo get() = app.repository
    private val db get() = app.database
    private val day = 86_400_000L
    private val zone: ZoneId = ZoneId.systemDefault()
    private val now = 1_791_000_000_000L

    @Before fun reset() {
        MedScheduler.activeParameterSet = MedScheduler.DEFAULT_PARAMETER_SET
        MedScheduler.knownParameterSets = emptyMap()
        MedScheduler.calibrationScale = 1.0
    }

    private suspend fun topic(studiedAt: Long = now - 30 * day) = repo.insertUnit(
        StudyUnitEntity(
            title = "Asthma", studyType = "Topic", studiedAt = studiedAt, createdAt = studiedAt, updatedAt = studiedAt,
            nextReviewAt = studiedAt, modelDueAt = studiedAt, memoryModel = "FSRS-6",
        )
    )

    private suspend fun rate(
        id: Long, at: Long, grade: MemoryRating = MemoryRating.Good, understanding: UnderstandingRating = UnderstandingRating.Clear,
        minutes: Int = StudyMinutes.NOT_GIVEN, loggedAt: Long = at,
    ) = repo.rateUnit(id, at, grade, understanding, sessionKind = SessionKind.TOPIC, reviewDurationMs = 1000,
        studyMinutes = minutes, loggedAt = loggedAt)!!

    private suspend fun logs(id: Long) = db.reviewLogDao().getLogsForUnitOnce(id).sortedWith(REVIEW_HISTORY_ORDER)

    /** What a pure replay must reproduce: the schedule and the memory state. */
    private fun scheduleOf(u: StudyUnitEntity) = listOf(u.stability, u.difficulty, u.lastReviewedAt, u.modelDueAt, u.nextReviewAt,
        u.understandingDueAt, u.reviewCount, u.lapseCount, u.currentIntervalDays, u.state)

    @Test fun `a rating saved for yesterday counts from yesterday and records when it was saved`() = runBlocking {
        val id = topic()
        rate(id, now - 9 * day)
        val previous = repo.getUnitById(id)!!.lastReviewedAt!!
        val yesterday = ReviewDay.timeForRating(ReviewDay.day(now, zone).minusDays(1), now, previous, zone)

        val rated = rate(id, yesterday, minutes = 30, loggedAt = now)
        val log = logs(id).last()
        assertEquals("it happened yesterday", yesterday, log.reviewedAt)
        assertEquals("it was saved now", now, log.loggedAt)
        assertEquals(30, log.studyMinutes)
        assertEquals("the gap runs to the day it happened", MedScheduler.modelElapsedDays(previous, yesterday, MedScheduler.CURRENT_MODEL), log.elapsedDays, 0.0)
        val unit = repo.getUnitById(id)!!
        assertEquals(yesterday, unit.lastReviewedAt)
        assertEquals("the schedule counts from it", yesterday + (rated.memoryIntervalDays * day).toLong(), unit.modelDueAt)
        assertEquals("the row was changed now", now, unit.updatedAt)

        val forecast = db.eventLogDao().getAll().single { it.type == ReviewForecast.EVENT && it.detail!!.startsWith("log=${log.id} ") }
        assertEquals("the prediction is dated when the review happened", yesterday, forecast.at)
        assertTrue("and names the rating definition", " ratingDef=2" in forecast.detail!!)

        // Today's share counts what was done today; yesterday's review, saved today, is not today's work.
        val today = ReviewDay.day(now, zone).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(0, db.reviewLogDao().countReviewsBetween(today, today + day - 1))

        // Replay reproduces it.
        val live = scheduleOf(unit)
        repo.editReviewRating(id, -1L, MemoryRating.Good, null)
        assertEquals(live, scheduleOf(repo.getUnitById(id)!!))
    }

    @Test fun `minutes are a choice or not given, never a made-up zero`() = runBlocking {
        val id = topic()
        rate(id, now - 9 * day, minutes = StudyMinutes.NOT_GIVEN)
        rate(id, now - 5 * day, minutes = 45)
        rate(id, now - 2 * day, minutes = 0)
        rate(id, now, minutes = 5000)
        assertEquals(listOf(-1, 45, -1, -1), logs(id).map { it.studyMinutes })
    }

    @Test fun `Forgot records the understanding answer, and still comes back tomorrow`() = runBlocking {
        val id = topic()
        rate(id, now - 9 * day)
        val rated = rate(id, now, MemoryRating.Forgot, UnderstandingRating.Confused)
        val log = logs(id).last()
        assertEquals("Confused", log.understandingRating)
        assertEquals(MedScheduler.understandingFactor(UnderstandingRating.Confused), log.understandingFactorAtReview, 0.0)
        assertEquals("tomorrow, whatever the answer", 1.0, (rated.effectiveDueAt - now) / day.toDouble(), 1e-9)
    }

    @Test fun `a corrected day moves the review, counts its gaps again and leaves its trace`() = runBlocking {
        val id = topic()
        val t0 = now - 20 * day
        rate(id, t0)
        val t1 = t0 + 4 * day
        rate(id, t1)
        val t2 = t1 + 6 * day
        rate(id, t2, MemoryRating.Hard)
        val (first, middle, last) = logs(id)

        val moved = ReviewDay.timeForCorrection(ReviewDay.day(t1, zone).minusDays(1), t1, t0, t2, now, zone)
        repo.editReviewRating(id, middle.id, MemoryRating.Good, UnderstandingRating.Clear, newReviewedAt = moved, now = now)

        val after = logs(id)
        assertEquals("the same three reviews, in the same order", listOf(first.id, middle.id, last.id), after.map { it.id })
        assertEquals(moved, after[1].reviewedAt)
        assertEquals("its own gap, counted again", MedScheduler.modelElapsedDays(t0, moved, MedScheduler.CURRENT_MODEL), after[1].elapsedDays, 0.0)
        assertEquals("and the next review's", MedScheduler.modelElapsedDays(moved, t2, MedScheduler.CURRENT_MODEL), after[2].elapsedDays, 0.0)
        assertEquals("ratings untouched", listOf("Good", "Good", "Hard"), after.map { it.memoryRating })

        val events = db.eventLogDao().getAll()
        val trace = events.single { it.type == RecomputedPredictions.DATE_EVENT }
        assertEquals("log=${middle.id} upto=${last.id} from=$t1 to=$moved", trace.detail)
        assertFalse("no rating was corrected", events.any { it.type == RecomputedPredictions.EVENT })
        assertEquals("the moved review's own prediction and every later one were recomputed",
            setOf(middle.id, last.id), RecomputedPredictions.ids(after, db.eventLogDao().getCorrectionEvents()))

        val live = scheduleOf(repo.getUnitById(id)!!)
        repo.editReviewRating(id, -1L, MemoryRating.Good, null)
        assertEquals("a pure replay reproduces the corrected schedule", live, scheduleOf(repo.getUnitById(id)!!))
    }

    @Test fun `a first rating moved before the study date takes the study date with it`() = runBlocking {
        val studied = now - 20 * day
        val id = topic(studiedAt = studied)
        rate(id, studied + 2 * day)
        rate(id, studied + 8 * day)
        val (first, second) = logs(id)

        val earlier = ReviewDay.timeForCorrection(ReviewDay.day(studied, zone).minusDays(3), first.reviewedAt, null, second.reviewedAt, now, zone)
        repo.editReviewRating(id, first.id, MemoryRating.Good, UnderstandingRating.Clear, newReviewedAt = earlier, now = now)
        assertEquals(earlier, logs(id).first().reviewedAt)
        assertEquals("the study date follows the first rating back", earlier, repo.getUnitById(id)!!.studiedAt)
        assertEquals("the next review counts its days from the moved first rating",
            MedScheduler.modelElapsedDays(earlier, second.reviewedAt, MedScheduler.CURRENT_MODEL), logs(id)[1].elapsedDays, 0.0)

        val later = ReviewDay.timeForCorrection(ReviewDay.day(earlier, zone).plusDays(1), earlier, null, second.reviewedAt, now, zone)
        repo.editReviewRating(id, first.id, MemoryRating.Good, UnderstandingRating.Clear, newReviewedAt = later, now = now)
        assertEquals(later, logs(id).first().reviewedAt)
        assertEquals("moved later, the study date stays where it was", earlier, repo.getUnitById(id)!!.studiedAt)

        val live = scheduleOf(repo.getUnitById(id)!!)
        repo.editReviewRating(id, -1L, MemoryRating.Good, null)
        assertEquals("a pure replay reproduces it", live, scheduleOf(repo.getUnitById(id)!!))
    }

    @Test fun `a day outside the neighbours, or in the future, is refused and changes nothing`() = runBlocking {
        val id = topic()
        val t0 = now - 20 * day
        rate(id, t0)
        rate(id, t0 + 4 * day)
        rate(id, t0 + 10 * day)
        val middle = logs(id)[1]
        val before = Triple(logs(id), repo.getUnitById(id), db.eventLogDao().getAll())
        for (bad in listOf(t0 - day, t0 + 11 * day)) {
            val result = runCatching { repo.editReviewRating(id, middle.id, MemoryRating.Good, null, newReviewedAt = bad, now = now) }
            assertTrue("refused: $bad", result.exceptionOrNull() is IllegalArgumentException)
            assertEquals(before, Triple(logs(id), repo.getUnitById(id), db.eventLogDao().getAll()))
        }
        val last = logs(id).last()
        val future = runCatching { repo.editReviewRating(id, last.id, MemoryRating.Good, null, newReviewedAt = now + day, now = now) }
        assertTrue("the future is refused too", future.exceptionOrNull() is IllegalArgumentException)
        assertEquals(before, Triple(logs(id), repo.getUnitById(id), db.eventLogDao().getAll()))
    }

    @Test fun `a corrected day and a corrected rating together leave both traces`() = runBlocking {
        val id = topic()
        val t0 = now - 20 * day
        rate(id, t0)
        rate(id, t0 + 5 * day)
        val last = logs(id).last()
        val moved = t0 + 3 * day
        repo.editReviewRating(id, last.id, MemoryRating.Hard, UnderstandingRating.Partial, newReviewedAt = moved, now = now)
        val types = db.eventLogDao().getAll().map { it.type }
        assertTrue(RecomputedPredictions.EVENT in types && RecomputedPredictions.DATE_EVENT in types)
        assertEquals("Hard", logs(id).last().memoryRating)
        assertEquals(moved, logs(id).last().reviewedAt)
    }

    @Test fun `backup carries the minutes and the save time, and an older file restores them as not given`() = runBlocking {
        val id = topic()
        rate(id, now - 9 * day, minutes = 20, loggedAt = now - 8 * day)
        rate(id, now - 2 * day, minutes = 60, loggedAt = now)
        val stored = logs(id)
        val json = BackupManager.buildBackupJson(app)
        BackupManager.restoreFromJson(app, json)
        assertEquals("every field back, the new two included", stored, logs(id))

        // A v9 file has neither field: restored as not given / not recorded.
        val old = org.json.JSONObject(json).apply {
            put("backupVersion", 9)
            val reviews = getJSONArray("reviewLogs")
            for (i in 0 until reviews.length()) reviews.getJSONObject(i).apply { remove("studyMinutes"); remove("loggedAt") }
        }
        BackupManager.restoreFromJson(app, old.toString())
        assertEquals(listOf(-1, -1), logs(id).map { it.studyMinutes })
        assertEquals(listOf(-1L, -1L), logs(id).map { it.loggedAt })

        // A minute count no build writes is a damaged file, refused before anything is replaced.
        val bad = org.json.JSONObject(json).apply { getJSONArray("reviewLogs").getJSONObject(0).put("studyMinutes", -5) }
        assertTrue(runCatching { BackupManager.restoreFromJson(app, bad.toString()) }.isFailure)
        assertEquals(listOf(-1, -1), logs(id).map { it.studyMinutes })
    }

    @Test fun `restore reserves the review ids a corrected day still names`() {
        val events = listOf(EventLogEntity(at = 1, type = RecomputedPredictions.DATE_EVENT, unitId = 3, detail = "log=40 upto=97 from=1 to=2"))
        assertEquals(97L, BackupIdentity.floors(listOf(3L), listOf(40L), events).review)
    }
}
