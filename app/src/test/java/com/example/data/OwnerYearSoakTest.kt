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
import com.example.ui.today.DailyPlan
import com.example.ui.today.DayBounds
import com.example.ui.today.OverdueRedistributor
import com.example.ui.today.TodayBuckets
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
 * The owner's own year, run through the REAL app code: one exam a year away, about 2,000 topics, and study that is
 * anything but regular (the owner, 2026-10-03: "one day I add 10 new topics, another none or 2; one day I do every
 * review and add new ones, another I review nothing, or some reviews wait for the next days; and sometimes I review a
 * topic myself, even when the app has not asked for it").
 *
 * [TwoYearSoakTest] runs a tidy learner: four new topics six days a week and every evening's plan done. This one runs
 * the irregular one, which is where a scheduler's edges are:
 * - 10% of days nothing at all, 15% light days (a couple of new topics, 40% of the plan), the rest normal days with
 *   0 to 10 new topics, rated at once ("Save and rate now");
 * - the evening plan at the default daily limit, a "Not today" now and then, "Review more anyway" when the limit held
 *   reviews back, "Spread out" when the backlog is bigger than a day (through [OverdueRedistributor.deferrals], the
 *   Today screen's own function);
 * - reviews the learner chooses: a topic not due, and now and then one already reviewed today (the same-day branch);
 * - Review ahead on spare evenings, and as a final push in the last month;
 * - an occasional rating correction, a phone change in the middle of the year (backup, restore), and the personal
 *   model's refit every few weeks, exactly as the daily worker calls it.
 *
 * The learner's true memory is FSRS-6 at the published defaults and the ratings are honest; a review on a day the
 * topic was already reviewed changes nothing in that true memory (a second look the same day is not a new, spaced
 * retrieval). Every day the plan must respect the limit; at the end the export must be free of self-check issues and
 * replay exactly in tools/pilot/analyze.py (app/build/owner-soak/export.json), a backup must restore to identical data,
 * a pure replay of every topic must reproduce its row, and the learner must beat a twin who spent the same review time
 * on random topics. It also prints what a year of this costs the phone: the plan, the export, the backup, the refit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OwnerYearSoakTest {

    private val day = 86_400_000L
    private val days = 365
    private val dailyLimit = 50
    /** New topics stop a month before the exam: the last weeks are review. */
    private val lastNewTopicDay = 330
    private val maxTopics = 2_000
    private val pushFrom = days - 30
    private val pushExtra = 40
    private val zoneName = "Asia/Tehran"
    private lateinit var zone: ZoneId

    /** New topics on a normal day: 0 to 10, about 7.6 on average, so the year ends near 2,000. */
    private val newTopicsOnNormalDay = intArrayOf(0, 2, 5, 7, 8, 9, 10, 10, 10, 10, 10, 10)

    private enum class DayKind { OFF, LIGHT, NORMAL }

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

        /** A second look on the same day is not a spaced retrieval: the true memory keeps its state. */
        fun update(onDay: Int, grade: Grade) {
            if (onDay == lastDay) return
            val next = Fsrs6.nextState(MemoryState(s, d), (onDay - lastDay).toDouble(), grade, p)
            s = next.stability; d = next.difficulty; lastDay = onDay
        }
    }

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

    private fun ms(startNanos: Long) = (System.nanoTime() - startNanos) / 1_000_000

    @Test
    fun `a year of irregular study toward one exam keeps every invariant and beats random review`() {
        val saved = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zoneName))
        try {
            zone = ZoneId.of(zoneName)
            runYear()
        } finally {
            java.util.TimeZone.setDefault(saved)
            // The scheduler's session state is process-wide; leave it as other tests expect to find it.
            MedScheduler.calibrationScale = 1.0
            MedScheduler.userRetention = MedScheduler.BASE_RETENTION
            MedScheduler.activeParameterSet = MedScheduler.DEFAULT_PARAMETER_SET
            MedScheduler.knownParameterSets = emptyMap()
        }
    }

    private fun runYear() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MedReviewApplication>()
        val repo = app.repository
        val unitDao = app.database.studyUnitDao()
        val logDao = app.database.reviewLogDao()
        val rnd = kotlin.random.Random(20261003)
        val randomTwinRnd = kotlin.random.Random(11)
        MedScheduler.calibrationScale = 1.0
        MedScheduler.userRetention = 0.90

        val memory = HashMap<Long, TrueMemory>()
        val randomTwin = HashMap<Long, TrueMemory>()
        val oldestTwin = HashMap<Long, TrueMemory>()
        var randomDebt = 0.0
        var oldestDebt = 0.0
        val order = ArrayList<Long>()

        val counts = sortedMapOf<String, Int>()
        fun count(key: String, n: Int = 1) { counts[key] = (counts[key] ?: 0) + n }
        var maxDayReviews = 0
        var maxBacklog = 0
        var backlogDays = 0
        val refitMillis = ArrayList<Long>()

        val subjectNames = listOf(
            "Cardiology", "Pulmonology", "Gastroenterology", "Nephrology", "Endocrinology", "Hematology", "Infectious",
            "Rheumatology", "Neurology", "Psychiatry", "Pediatrics", "Obstetrics", "Gynecology", "Surgery", "Orthopedics",
            "Urology", "ENT", "Ophthalmology", "Dermatology", "Pharmacology",
        )
        val subjects = subjectNames.map { repo.insertSubject(it) }

        for (d in 0 until days) {
            val kind = rnd.nextDouble().let { when { it < 0.10 -> DayKind.OFF; it < 0.25 -> DayKind.LIGHT; else -> DayKind.NORMAL } }
            count("days.$kind")

            // The personal model's daily worker: the repository decides whether there is enough new evidence.
            if (d > 0 && d % 20 == 0) {
                val t0 = System.nanoTime()
                val report = repo.refitPersonalModel(now = atLocal(d, 3))
                if (report != null) {
                    refitMillis += ms(t0)
                    count("refit.${report.verdict}")
                }
            }

            // The phone is replaced in the middle of the year: everything moves through a backup.
            if (d == days / 2) {
                val before = BackupManager.buildBackupJson(app)
                assertEquals(order.size, BackupManager.restoreFromJson(app, before))
                fun comparable(s: String) = JSONObject(s).apply { remove("exportedAt") }.toString()
                assertEquals("mid-year backup -> restore -> backup is the identity", comparable(before), comparable(BackupManager.buildBackupJson(app)))
                count("phoneChanges")
            }

            if (kind == DayKind.OFF) {
                // Nobody studies, nobody reviews; the twins get no time either.
                continue
            }

            var dayCost = 0.0
            var dayReviews = 0
            suspend fun review(unit: StudyUnitEntity, at: Long, sessionKind: SessionKind) {
                val mem = memory.getValue(unit.id)
                val sameDay = mem.lastDay == d
                val recalled = rnd.nextDouble() < mem.r(d)
                val grade = if (recalled) successGrade(rnd) else Grade.Again
                val understanding = if (recalled && rnd.nextDouble() < 0.15) UnderstandingRating.Partial else UnderstandingRating.Clear
                repo.rateUnit(
                    unitId = unit.id, now = at, memoryRating = ratingOf(grade),
                    understandingRating = if (recalled) understanding else UnderstandingRating.Partial,
                    understandingAsked = recalled, sessionKind = sessionKind, reviewDurationMs = 120_000,
                )!!
                mem.update(d, grade)
                if (sameDay) count("reviews.sameDay")
                count("reviews.$sessionKind")
                dayCost += if (recalled) 1.0 else 1.5
                dayReviews++
            }

            // New material in the afternoon, each topic logged and rated at once ("Save and rate now").
            val newToday = when {
                d >= lastNewTopicDay || order.size >= maxTopics -> 0
                kind == DayKind.LIGHT -> rnd.nextInt(0, 3)
                else -> newTopicsOnNormalDay[rnd.nextInt(newTopicsOnNormalDay.size)]
            }.coerceAtMost(maxTopics - order.size)
            repeat(newToday) { k ->
                val studiedAt = atLocal(d, 13, 0) + k * 12 * 60_000L
                val id = repo.insertUnit(
                    StudyUnitEntity(
                        title = "Topic $d-$k", studyType = "Topic", subjectId = subjects[rnd.nextInt(subjects.size)],
                        highYield = rnd.nextDouble() < 0.15, stability = 1.0, difficulty = 5.0, retrievability = 1.0,
                        state = "New", studiedAt = studiedAt, nextReviewAt = studiedAt, modelDueAt = studiedAt,
                        currentIntervalDays = 0.0, reviewCount = 0, lapseCount = 0, createdAt = studiedAt,
                    )
                )
                val grade = firstGrade(rnd)
                memory[id] = TrueMemory().apply { seed(grade, d) }
                randomTwin[id] = TrueMemory().apply { seed(grade, d) }
                oldestTwin[id] = TrueMemory().apply { seed(grade, d) }
                order += id
                repo.rateUnit(
                    unitId = id, now = studiedAt + 60_000, memoryRating = ratingOf(grade),
                    understandingRating = UnderstandingRating.Clear, sessionKind = SessionKind.TOPIC,
                    reviewDurationMs = 60_000,
                )!!
                count("topics.new")
            }

            // "Spread out", when the backlog is bigger than one day's limit: the Today screen's own plan.
            if (kind == DayKind.NORMAL) {
                val now = atLocal(d, 19)
                val start = DayBounds.startOf(now)
                val end = DayBounds.endOf(now)
                val active = unitDao.getAllActiveOnce()
                val overdue = active.filter { TodayBuckets.isOverdue(it.nextReviewAt, start) }
                val backlog = OverdueRedistributor.spreadable(overdue).size
                if (OverdueRedistributor.offersRecovery(backlog, dailyLimit) && rnd.nextDouble() < 0.5) {
                    val updated = OverdueRedistributor.deferrals(
                        overdue = overdue,
                        dueTodayReviews = active.count { TodayBuckets.isDueToday(it.nextReviewAt, start, end) && !DailyPlan.isFirstRating(it) },
                        doneToday = logDao.countReviewsBetween(start, end),
                        dailyCapacity = dailyLimit,
                        now = now,
                    )
                    if (updated.isNotEmpty()) {
                        repo.updateUnitsAtomic(updated)
                        repo.logEvent("REDISTRIBUTE", detail = updated.size.toString())
                        count("spreadOut")
                        count("spreadOut.topics", updated.size)
                    }
                }
            }

            // The evening session, opened exactly as the review screen opens it.
            val evening = atLocal(d, if (kind == DayKind.LIGHT) 22 else 20)
            repo.refreshMemoryModel()
            MedScheduler.calibrationScale = repo.recallCalibrationScale()
            val plan = repo.todayPlan(dailyLimit, evening)
            assertTrue("day $d: every first rating is offered and was rated at once", plan.firstRatings.isEmpty())
            assertTrue("day $d: the plan respects the daily limit (${plan.reviews.size} + ${plan.doneToday})",
                plan.reviews.size + plan.doneToday <= dailyLimit)
            val toDo = if (kind == DayKind.LIGHT) plan.reviews.take((plan.reviews.size * 0.4).toInt()) else plan.reviews
            for ((i, unit) in toDo.withIndex()) {
                if (kind == DayKind.NORMAL && rnd.nextDouble() < 0.02) {
                    repo.procrastinateUnit(unit.id, atLocal(d + 1, 8))
                    count("notToday")
                    continue
                }
                review(unit, evening + i * 60_000L, SessionKind.PLAN)
            }

            // "Review more anyway" when the limit held reviews back and there is still energy.
            if (kind == DayKind.NORMAL && plan.heldBack > 0 && rnd.nextDouble() < 0.35) {
                val at = atLocal(d, 21, 0)
                val more = repo.todayPlan(dailyLimit, at, ignoreLimit = true)
                for ((i, unit) in more.reviews.take(25).withIndex()) review(unit, at + i * 60_000L, SessionKind.EXTRA)
            }

            // A topic the learner decides to review themselves, due or not; now and then one already reviewed today.
            if (kind == DayKind.NORMAL && rnd.nextDouble() < 0.25) {
                val at = atLocal(d, 21, 30)
                val endOfToday = DayBounds.endOf(at)
                val active = unitDao.getAllActiveOnce().filter { it.reviewCount > 0 }
                val reviewedToday = active.filter { (it.lastReviewedAt ?: 0L) >= DayBounds.startOf(at) }
                val notDue = active.filter { it.nextReviewAt > endOfToday }
                repeat(rnd.nextInt(1, 4)) { k ->
                    val pick = if (reviewedToday.isNotEmpty() && rnd.nextDouble() < 0.25) reviewedToday.random(rnd)
                        else notDue.randomOrNull(rnd) ?: return@repeat
                    review(unitDao.getUnitById(pick.id)!!, at + k * 60_000L, SessionKind.TOPIC)
                    count("onDemand")
                }
            }

            // Review ahead: on a spare evening, and as a final push in the last month.
            val pushing = d >= pushFrom
            if (pushing || (kind == DayKind.NORMAL && rnd.nextDouble() < 0.3)) {
                val target = if (pushing) pushExtra else rnd.nextInt(5, 21)
                val at = atLocal(d, 22, 30)
                var extra = 0
                while (extra < target) {
                    val ahead = repo.reviewAheadQueue(at, limit = 20)
                    if (ahead.isEmpty()) break
                    for (unit in ahead.take(target - extra)) {
                        review(unit, at + extra * 30_000L, SessionKind.AHEAD)
                        extra++
                    }
                }
            }

            // Now and then a past rating is corrected (the whole history replays).
            if (rnd.nextDouble() < 0.015 && order.size > 10) {
                val id = order[rnd.nextInt(order.size)]
                val logs = logDao.getLogsForUnitOnce(id).sortedWith(REVIEW_HISTORY_ORDER)
                if (logs.size >= 3) {
                    val log = logs[rnd.nextInt(1, logs.size)]
                    val corrected = when (log.memoryRating) { "Good" -> MemoryRating.Hard; "Hard" -> MemoryRating.Good; "Easy" -> MemoryRating.Good; else -> MemoryRating.Hard }
                    repo.editReviewRating(id, log.id, corrected, null)
                    count("corrections")
                }
            }

            // What is still waiting at the end of the evening.
            val after = repo.todayPlan(dailyLimit, atLocal(d, 23, 59), ignoreLimit = true)
            val waiting = after.reviews.size
            if (waiting > 0) backlogDays++
            maxBacklog = maxOf(maxBacklog, waiting)
            maxDayReviews = maxOf(maxDayReviews, dayReviews)

            // The twins: the same review time today, one on random topics, one always on the topic untouched longest.
            fun spend(twin: HashMap<Long, TrueMemory>, pool: List<Long>, budget0: Double, rng: kotlin.random.Random?): Double {
                var budget = budget0
                for (id in pool) {
                    if (budget <= 0) break
                    val mem = twin.getValue(id)
                    val recalled = (rng ?: rnd).nextDouble() < mem.r(d)
                    mem.update(d, if (recalled) successGrade(rng ?: rnd) else Grade.Again)
                    budget -= if (recalled) 1.0 else 1.5
                }
                return maxOf(0.0, -budget)
            }
            randomDebt = spend(randomTwin, order.filter { randomTwin.getValue(it).lastDay < d }.shuffled(randomTwinRnd), dayCost - randomDebt, randomTwinRnd)
            oldestDebt = spend(oldestTwin, order.filter { oldestTwin.getValue(it).lastDay < d }.sortedBy { oldestTwin.getValue(it).lastDay }, dayCost - oldestDebt, randomTwinRnd)
        }

        // Exam day.
        val last = days - 1
        fun knowledge(m: Map<Long, TrueMemory>) = order.map { m.getValue(it).r(last) }
        val yadora = knowledge(memory)
        val yadoraMean = yadora.average()
        val weakestTenth = yadora.sorted().take(yadora.size / 10).average()
        val at90 = yadora.count { it >= 0.9 }.toDouble() / yadora.size
        val randomMean = knowledge(randomTwin).average()
        val oldestMean = knowledge(oldestTwin).average()
        val totalReviews = counts.filterKeys { it.startsWith("reviews.") && it != "reviews.sameDay" }.values.sum()

        val endOfExamDay = DayBounds.endOf(atLocal(last, 20))
        val units = order.map { repo.getUnitById(it)!! }
        val overdueAtExam = units.filter { it.nextReviewAt < DayBounds.startOf(atLocal(last, 20)) }
        val worstOverdueDays = overdueAtExam.maxOfOrNull { (endOfExamDay - it.nextReviewAt) / day } ?: 0L

        // What a year of this costs the phone's code paths (desktop JVM; a phone is several times slower).
        val tPlan = System.nanoTime(); repo.todayPlan(dailyLimit, atLocal(last, 20)); val planMs = ms(tPlan)
        val tExport = System.nanoTime(); val json = JSONObject(AnalyticsExporter.buildJson(app)); val exportMs = ms(tExport)
        val tBackup = System.nanoTime(); val backup = BackupManager.buildBackupJson(app); val backupMs = ms(tBackup)
        val tRestore = System.nanoTime(); assertEquals(order.size, BackupManager.restoreFromJson(app, backup)); val restoreMs = ms(tRestore)
        val tAhead = System.nanoTime(); repo.reviewAheadQueue(atLocal(last, 22)); val aheadMs = ms(tAhead)

        println("OWNER YEAR: ${order.size} topics, $totalReviews reviews (busiest day $maxDayReviews), events $counts")
        println("OWNER YEAR: exam day recall Yadora ${"%.4f".format(yadoraMean)}, random twin ${"%.4f".format(randomMean)}, oldest-first twin ${"%.4f".format(oldestMean)}")
        println("OWNER YEAR: topics at 90%+ ${"%.4f".format(at90)}, weakest tenth ${"%.4f".format(weakestTenth)}")
        println("OWNER YEAR: backlog left at night on $backlogDays days (largest $maxBacklog); on exam day ${overdueAtExam.size} topics overdue, the oldest by $worstOverdueDays days")
        println("OWNER YEAR: plan ${planMs} ms, review-ahead queue ${aheadMs} ms, export ${exportMs} ms (${json.toString().length / 1024} KB), backup ${backupMs} ms (${backup.length / 1024} KB), restore ${restoreMs} ms, refits ${refitMillis} ms")

        // The year's schedule beats the same time spent at random, on the real code.
        assertTrue("Yadora on exam day ($yadoraMean) must beat random review at equal time ($randomMean) by 3+ points",
            yadoraMean > randomMean + 0.03)
        assertTrue("and the disciplined oldest-first twin ($oldestMean)", yadoraMean > oldestMean)

        // The export: zero self-check issues, written for tools/pilot/analyze.py to replay.
        assertEquals("export self-check: " + json.getJSONObject("consistency"), 0, json.getJSONObject("consistency").getInt("issueCount"))
        java.io.File(System.getProperty("user.dir"), "build/owner-soak/export.json").apply {
            parentFile!!.mkdirs()
            writeText(json.toString())
        }

        // A backup restores to exactly the same data.
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
