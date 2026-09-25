package com.example.data

import androidx.test.core.app.ApplicationProvider
import com.example.MedReviewApplication
import com.example.data.local.entity.StudyUnitEntity
import com.example.domain.model.MemoryRating
import com.example.domain.model.SessionKind
import com.example.domain.model.UnderstandingRating
import com.example.domain.srs.Fsrs6
import com.example.domain.srs.Fsrs6Parameters
import com.example.domain.srs.Grade
import com.example.domain.srs.MedScheduler
import com.example.domain.srs.MemoryState
import com.example.ui.today.DayBounds
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * Two years of a residency candidate's study, run through the REAL app code, day by day.
 *
 * The twin simulation (tools/pilot/simulate.py, residency.py) runs on a Python transcription of the
 * scheduler. That transcription is checked review by review against real exports, but the product around
 * the scheduler (today's plan, the daily limit, the queue order, projection, the calibration refresh,
 * deferrals, Review ahead) is mirrored there, not run. This test runs all of it for 730 simulated days:
 *
 * - a learner studies 4 new topics a day, six days a week (about 2,500 topics), rates each at once ("Save
 *   and rate now"), and every evening opens today's session exactly as the review screen does: refresh the
 *   memory model and the calibration, take [MedReviewRepository.todayPlan] at the default limit of 50, and
 *   rate each topic through [MedReviewRepository.rateUnit], the review screen's own commit path;
 * - the ratings are honest outcomes of a simulated true memory (FSRS-6 at the published defaults, the
 *   same TrueMemory as simulate.py), understanding is sometimes Partial (so the repair clock runs), there is
 *   a three-week holiday, a "Not today" now and then, and the last four weeks add Review ahead sessions;
 * - every day the plan must respect the daily limit and offer every first rating; at the end no topic may
 *   be long overdue or have waited more than 400 days between reviews, the export must report zero
 *   inconsistencies, a pure replay of every topic's history must reproduce the live row, and a backup must
 *   restore to the same data;
 * - and the twin claim is checked on this real schedule: on exam day the learner must know far more than
 *   a twin who spent exactly the same review time on topics picked at random.
 *
 * The export is written to app/build/soak/export.json; CI replays every review in it with
 * tools/pilot/analyze.py, which fails on a single mismatch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TwoYearSoakTest {

    private val day = 86_400_000L
    private val days = 730
    private val newPerStudyDay = 4
    private val dailyLimit = 50
    private val holiday = 400 until 421
    private val pushDays = 28
    private val pushExtra = 40
    /**
     * Where Yadora's learners are: Iran, UTC+3:30. A half-hour offset puts every local midnight on a half hour of
     * UTC, the kind of boundary a day-granularity scheduler gets wrong without noticing. The export records the
     * zone, so tools/pilot/analyze.py replays the same local days.
     */
    private val zoneName = "Asia/Tehran"
    private lateinit var zone: ZoneId

    /** The learner's actual memory: FSRS-6 at the published defaults (simulate.py's TrueMemory with k = 1). */
    private class TrueMemory {
        private val p = Fsrs6Parameters()
        var s = 0.0
        var d = 0.0
        var lastDay = 0

        fun seed(grade: Grade, onDay: Int) {
            val st = Fsrs6.initialState(grade, p)
            s = st.stability; d = st.difficulty; lastDay = onDay
        }

        fun r(onDay: Int): Double = Fsrs6.retrievability((onDay - lastDay).toDouble(), s, p)

        fun update(onDay: Int, grade: Grade) {
            val next = Fsrs6.nextState(MemoryState(s, d), (onDay - lastDay).toDouble(), grade, p)
            s = next.stability; d = next.difficulty; lastDay = onDay
        }
    }

    // simulate.py's grade tables: first ratings, and the grade of a successful recall.
    private fun firstGrade(rnd: kotlin.random.Random): Grade = rnd.nextDouble().let {
        when { it < 0.25 -> Grade.Hard; it < 0.80 -> Grade.Good; else -> Grade.Easy }
    }

    private fun successGrade(rnd: kotlin.random.Random): Grade = rnd.nextDouble().let {
        when { it < 0.22 -> Grade.Hard; it < 0.86 -> Grade.Good; else -> Grade.Easy }
    }

    private fun ratingOf(g: Grade) = when (g) {
        Grade.Again -> MemoryRating.Forgot
        Grade.Hard -> MemoryRating.Hard
        Grade.Good -> MemoryRating.Good
        Grade.Easy -> MemoryRating.Easy
    }

    private fun atLocal(d: Int, hour: Int, minute: Int = 0): Long =
        LocalDate.now(zone).minusDays((days - d).toLong()).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `two years of residency study run through the real app code keep every invariant and beat random review`() {
        val saved = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zoneName))
        try {
            zone = ZoneId.of(zoneName)
            runSoak()
        } finally {
            java.util.TimeZone.setDefault(saved)
            // The scheduler's session state is process-wide; leave it as other tests expect to find it.
            MedScheduler.calibrationScale = 1.0
            MedScheduler.userRetention = MedScheduler.BASE_RETENTION
            MedScheduler.activeParameterSet = MedScheduler.DEFAULT_PARAMETER_SET
        }
    }

    private fun runSoak() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MedReviewApplication>()
        val repo = app.repository
        val rnd = kotlin.random.Random(20260924)
        val twinRnd = kotlin.random.Random(7)
        MedScheduler.calibrationScale = 1.0
        MedScheduler.userRetention = 0.90

        val memory = HashMap<Long, TrueMemory>()
        val twinMemory = HashMap<Long, TrueMemory>()
        val order = ArrayList<Long>()
        var reviews = 0
        var deferrals = 0
        var maxDayReviews = 0
        val subjects = listOf("Cardiology", "Nephrology", "Pharmacology", "Pediatrics", "Surgery").map { repo.insertSubject(it) }

        for (d in 0 until days) {
            val onHoliday = d in holiday
            // Study in the late afternoon, and log each topic at once ("Save and rate now").
            if (!onHoliday && d % 7 != 6) {
                repeat(newPerStudyDay) { k ->
                    val studiedAt = atLocal(d, 16, 10 * k)
                    val id = repo.insertUnit(
                        StudyUnitEntity(
                            title = "Topic $d-$k", studyType = "Topic", subjectId = subjects[(d + k) % subjects.size],
                            highYield = (d + k) % 9 == 0, stability = 1.0, difficulty = 5.0, retrievability = 1.0,
                            state = "New", studiedAt = studiedAt, nextReviewAt = studiedAt, modelDueAt = studiedAt,
                            currentIntervalDays = 0.0, reviewCount = 0, lapseCount = 0, createdAt = studiedAt,
                        )
                    )
                    val grade = firstGrade(rnd)
                    memory[id] = TrueMemory().apply { seed(grade, d) }
                    twinMemory[id] = TrueMemory().apply { seed(grade, d) }
                    order += id
                    repo.rateUnit(
                        unitId = id, now = studiedAt + 60_000, memoryRating = ratingOf(grade),
                        understandingRating = UnderstandingRating.Clear, sessionKind = SessionKind.TOPIC,
                        reviewDurationMs = 60_000,
                    )!!
                }
            }
            if (onHoliday) continue

            // The evening session, opened exactly as the review screen opens it.
            val evening = atLocal(d, 20)
            repo.refreshMemoryModel()
            MedScheduler.calibrationScale = repo.recallCalibrationScale()
            val plan = repo.todayPlan(dailyLimit, evening)
            assertTrue("day $d: every first rating is offered", plan.firstRatings.isEmpty())
            assertTrue("day $d: the plan respects the daily limit (${plan.reviews.size} + ${plan.doneToday})",
                plan.reviews.size + plan.doneToday <= dailyLimit)

            var dayCost = 0.0
            var dayReviews = 0
            suspend fun review(unit: StudyUnitEntity, at: Long, kind: SessionKind) {
                val mem = memory.getValue(unit.id)
                val recalled = rnd.nextDouble() < mem.r(d)
                val grade = if (recalled) successGrade(rnd) else Grade.Again
                val understanding = if (recalled && rnd.nextDouble() < 0.15) UnderstandingRating.Partial else UnderstandingRating.Clear
                repo.rateUnit(
                    unitId = unit.id, now = at, memoryRating = ratingOf(grade),
                    understandingRating = if (recalled) understanding else UnderstandingRating.Partial,
                    understandingAsked = recalled, sessionKind = kind, reviewDurationMs = 90_000,
                )!!
                mem.update(d, grade)
                dayCost += if (recalled) 1.0 else 1.5
                dayReviews++
            }

            for ((i, unit) in plan.reviews.withIndex()) {
                // Now and then the learner taps "Not today" on the last topic of the evening instead.
                if (d % 37 == 5 && i == plan.reviews.lastIndex) {
                    repo.procrastinateUnit(unit.id, atLocal(d + 1, 8))
                    deferrals++
                    continue
                }
                review(unit, evening + i * 60_000L, SessionKind.PLAN)
            }
            // The final push: Review ahead, weakest predicted recall first.
            if (d >= days - pushDays) {
                var extra = 0
                while (extra < pushExtra) {
                    val ahead = repo.reviewAheadQueue(atLocal(d, 21), limit = 20)
                    if (ahead.isEmpty()) break
                    for (unit in ahead.take(pushExtra - extra)) {
                        review(unit, atLocal(d, 21) + extra * 60_000L, SessionKind.AHEAD)
                        extra++
                    }
                }
            }
            // Later that evening: the plan counts exactly what was done, and offers nothing more unless
            // the limit held something back (a Forgot or a repair comes back tomorrow, not tonight).
            val after = repo.todayPlan(dailyLimit, atLocal(d, 23))
            assertEquals("day $d: the plan counts what was done", plan.doneToday + dayReviews, after.doneToday)
            if (plan.heldBack == 0) assertTrue("day $d: nothing left for tonight: ${after.reviews.size}", after.reviews.isEmpty())
            reviews += dayReviews
            maxDayReviews = maxOf(maxDayReviews, dayReviews)

            // The twin: the same review time today, spent on topics picked at random.
            val pool = order.filter { twinMemory.getValue(it).lastDay < d }.shuffled(twinRnd)
            var budget = dayCost
            for (id in pool) {
                if (budget <= 0) break
                val mem = twinMemory.getValue(id)
                val recalled = twinRnd.nextDouble() < mem.r(d)
                mem.update(d, if (recalled) successGrade(twinRnd) else Grade.Again)
                budget -= if (recalled) 1.0 else 1.5
            }
        }

        val last = days - 1
        fun knowledge(m: Map<Long, TrueMemory>) = order.map { m.getValue(it).r(last) }
        val yadora = knowledge(memory)
        val twin = knowledge(twinMemory)
        val yadoraMean = yadora.average()
        val twinMean = twin.average()
        val weakestTenth = yadora.sorted().take(yadora.size / 10).average()
        val at90 = yadora.count { it >= 0.9 }.toDouble() / yadora.size
        println("SOAK: ${order.size} topics, $reviews reviews (busiest day $maxDayReviews), $deferrals deferrals")
        println("SOAK: exam day recall Yadora ${"%.4f".format(yadoraMean)}, random twin at equal time ${"%.4f".format(twinMean)}")
        println("SOAK: Yadora topics at 90%+ ${"%.4f".format(at90)}, weakest tenth ${"%.4f".format(weakestTenth)}")

        // The twin claim, on the real schedule.
        assertTrue("Yadora on exam day ($yadoraMean) must beat random review at equal time ($twinMean) by 4+ points",
            yadoraMean > twinMean + 0.04)
        // Measured 0.966 on 2026-09-24; the bar leaves room for a deliberate scheduling change to move it a little.
        assertTrue("with the final push, nearly everything is known on exam day: $yadoraMean", yadoraMean > 0.95)
        assertTrue("and no topic is left behind: 95% of topics at 90%+ ($at90), weakest tenth $weakestTenth",
            at90 > 0.95 && weakestTenth > 0.85)

        // No topic starves: nothing long overdue, and no topic waits more than 400 days between reviews.
        val endOfExamDay = DayBounds.endOf(atLocal(last, 20))
        val units = order.map { repo.getUnitById(it)!! }
        assertEquals("topics overdue by more than two weeks on exam day", 0,
            units.count { it.nextReviewAt < endOfExamDay - 14 * day })
        for (u in units) {
            val times = listOf(u.studiedAt) + app.database.reviewLogDao().getLogsForUnitOnce(u.id).map { it.reviewedAt }.sorted()
            val gap = times.zipWithNext { a, b -> b - a }.maxOrNull() ?: 0L
            assertTrue("topic ${u.id} waited ${gap / day} days between reviews", gap <= 400 * day)
        }

        // The export: zero self-check issues, written for tools/pilot/analyze.py to replay in CI.
        val json = JSONObject(AnalyticsExporter.buildJson(app))
        assertEquals("export self-check: " + json.getJSONObject("consistency"), 0,
            json.getJSONObject("consistency").getInt("issueCount"))
        java.io.File(System.getProperty("user.dir"), "build/soak/export.json").apply {
            parentFile!!.mkdirs()
            writeText(json.toString())
        }

        // A backup restores to exactly the same data.
        val backup = BackupManager.buildBackupJson(app)
        assertEquals(order.size, BackupManager.restoreFromJson(app, backup))
        fun comparable(s: String) = JSONObject(s).apply { remove("exportedAt") }.toString()
        assertEquals("backup -> restore -> backup is the identity", comparable(backup), comparable(BackupManager.buildBackupJson(app)))

        // Replay == live: rebuilding every topic's schedule from its own history reproduces the row.
        for (id in order) {
            val live = repo.getUnitById(id)!!
            repo.editReviewRating(id, -1L, MemoryRating.Good, UnderstandingRating.Clear) // pure replay
            val replayed = repo.getUnitById(id)!!
            val what = "topic $id"
            assertEquals("$what stability", live.stability, replayed.stability, 1e-9)
            assertEquals("$what difficulty", live.difficulty, replayed.difficulty, 1e-9)
            assertEquals("$what reviews", live.reviewCount, replayed.reviewCount)
            assertEquals("$what lapses", live.lapseCount, replayed.lapseCount)
            assertEquals("$what memory date", live.modelDueAt, replayed.modelDueAt)
            assertEquals("$what repair date", live.understandingDueAt, replayed.understandingDueAt)
            assertEquals("$what interval", live.currentIntervalDays, replayed.currentIntervalDays, 1e-9)
            if (live.deferredUntil == null) assertEquals("$what due date", live.nextReviewAt, replayed.nextReviewAt)
        }
    }
}
