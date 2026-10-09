package com.example.data

import androidx.test.core.app.ApplicationProvider
import com.example.MedReviewApplication
import com.example.data.local.entity.StudyUnitEntity
import com.example.domain.model.MemoryRating
import com.example.domain.model.ReviewMethod
import com.example.domain.model.SessionKind
import com.example.domain.model.UnderstandingRating
import com.example.domain.srs.MedScheduler
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A REAL analytics export from a simulated six-week learner, for the pilot analysis toolkit.
 *
 * tools/pilot/analyze.py replays every exported history through an independent Python transcription of
 * the scheduler and reports whether the phone scheduled exactly what the rules say. That check is only
 * worth something if it is run against what the app actually writes, so this test rates through the
 * review screen's own commit path (MedReviewRepository.rateUnit) and writes the real exporter's output to
 * app/build/pilot-fixture/sample_export.json. tools/pilot/fixtures/sample_export.json is a copy of it;
 * tools/pilot/test_analyze.py checks the toolkit against that copy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PilotExportFixtureTest {

    private val day = 86_400_000L

    /** One rating through the review screen's own commit path (MedReviewRepository.rateUnit). */
    private fun commit(
        repo: com.example.data.repository.MedReviewRepository,
        unitId: Long,
        now: Long,
        memory: MemoryRating,
        understanding: UnderstandingRating,
        methods: Set<ReviewMethod>,
        score: Pair<Int?, Int?>,
        kind: SessionKind,
        minutes: Int = com.example.domain.model.StudyMinutes.NOT_GIVEN,
        loggedAt: Long = now,
    ) = runBlocking {
        // Every answer, Forgot included, is followed by the understanding question, as the screen does since 2026-10-09.
        repo.rateUnit(
            unitId = unitId, now = now, memoryRating = memory,
            understandingRating = understanding, understandingAsked = true, methods = methods,
            questionsCorrect = score.first, questionsTotal = score.second,
            sessionKind = kind, reviewDurationMs = 90_000,
            studyMinutes = minutes, loggedAt = loggedAt,
        )!!
    }

    @Test
    fun `a simulated six-week learner exports a consistent file for the analysis toolkit`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MedReviewApplication>()
        val repo = app.repository
        val rnd = kotlin.random.Random(20260924)
        MedScheduler.calibrationScale = 1.0

        // Local 20:00 on day 0, six weeks ago: study in the evening, review in the evening.
        val zone = java.time.ZoneId.systemDefault()
        val day0 = java.time.LocalDate.now(zone).minusDays(42).atTime(20, 0).atZone(zone).toInstant().toEpochMilli()
        val subjectIds = listOf("Cardiology", "Pharmacology", "Microbiology").map { repo.insertSubject(it) }

        data class Planned(val id: Long, val studiedDay: Int)
        val topics = mutableListOf<Planned>()
        for (d in 0 until 28) {
            if (d % 7 == 6) continue // a day off each week
            repeat(2) { k ->
                val studiedAt = day0 + d * day
                val id = repo.insertUnit(
                    StudyUnitEntity(
                        title = "Topic d$d-$k", studyType = "Topic", subjectId = subjectIds[(d + k) % 3],
                        highYield = (d + k) % 5 == 0, notes = if (k == 0) "notes ".repeat(d % 4) else null,
                        stability = 1.0, difficulty = 5.0, retrievability = 1.0, state = "New",
                        studiedAt = studiedAt, nextReviewAt = studiedAt, modelDueAt = studiedAt,
                        currentIntervalDays = 0.0, reviewCount = 0, lapseCount = 0, createdAt = studiedAt,
                    )
                )
                topics += Planned(id, d)
                // "Save and rate now": the first rating on the study day.
                val first = listOf(MemoryRating.Hard, MemoryRating.Good, MemoryRating.Good, MemoryRating.Easy)[rnd.nextInt(4)]
                commit(repo, id, studiedAt + 60_000L * (k + 1), first, UnderstandingRating.Clear, emptySet(), null to null, SessionKind.TOPIC)
            }
        }

        // Reviews: each evening, whatever is due, sometimes a day late; outcomes drawn from the model's own
        // prediction so the file looks like a learner the defaults describe.
        val now = System.currentTimeMillis()
        for (d in 1 until 42) {
            val evening = day0 + d * day + 30 * 60_000L
            if (evening > now) break
            if (d % 9 == 4) continue // a missed evening
            for (p in topics) {
                val u = repo.getUnitById(p.id)!!
                if (u.reviewCount == 0 || u.nextReviewAt > evening) continue
                val elapsed = MedScheduler.modelElapsedDays(u.lastReviewedAt ?: u.studiedAt, evening, MedScheduler.CURRENT_MODEL)
                val r = MedScheduler.retrievability(elapsed, u.stability, MedScheduler.CURRENT_MODEL, u.parameterSetId)
                val recalled = rnd.nextDouble() < r
                val memory = if (!recalled) MemoryRating.Forgot
                    else listOf(MemoryRating.Hard, MemoryRating.Good, MemoryRating.Good, MemoryRating.Good, MemoryRating.Easy)[rnd.nextInt(5)]
                val understanding = listOf(UnderstandingRating.Clear, UnderstandingRating.Clear, UnderstandingRating.Partial)[rnd.nextInt(3)]
                val methods = when (rnd.nextInt(4)) {
                    0 -> setOf(ReviewMethod.Questions)
                    1 -> setOf(ReviewMethod.Reading)
                    2 -> setOf(ReviewMethod.Questions, ReviewMethod.Reading)
                    else -> emptySet()
                }
                val score = if (ReviewMethod.Questions in methods) {
                    val total = 10 + rnd.nextInt(11)
                    (total * (if (recalled) 0.6 + 0.35 * rnd.nextDouble() else 0.2 + 0.3 * rnd.nextDouble())).toInt() to total
                } else null to null
                // The learner's rough minutes: given on about half the reviews (it is optional and often skipped).
                val minutes = if (rnd.nextBoolean()) com.example.domain.model.StudyMinutes.CHOICES[rnd.nextInt(5)]
                    else com.example.domain.model.StudyMinutes.NOT_GIVEN
                commit(repo, p.id, evening, memory, understanding, methods, score, SessionKind.PLAN, minutes)
            }
        }
        // A review logged the next morning for the evening before ("Reviewed: yesterday"): it happened at 20:30, it was
        // saved at 08:30, and the schedule counts from when it happened.
        val late = repo.insertUnit(
            StudyUnitEntity(
                title = "Logged the next morning", studyType = "Topic", subjectId = subjectIds[1],
                stability = 1.0, difficulty = 5.0, retrievability = 1.0, state = "New",
                studiedAt = day0 + 31 * day, nextReviewAt = day0 + 31 * day, modelDueAt = day0 + 31 * day,
                currentIntervalDays = 0.0, reviewCount = 0, lapseCount = 0, createdAt = day0 + 31 * day,
            )
        )
        commit(repo, late, day0 + 31 * day + 60_000L, MemoryRating.Good, UnderstandingRating.Clear, emptySet(), null to null, SessionKind.TOPIC, 45)
        commit(repo, late, day0 + 35 * day + 30 * 60_000L, MemoryRating.Hard, UnderstandingRating.Partial, setOf(ReviewMethod.Reading),
            null to null, SessionKind.TOPIC, 20, loggedAt = day0 + 35 * day + 12 * 3_600_000L + 30 * 60_000L)
        // A topic reviewed once with the phone's clock set back: its third review carries an EARLIER time than its
        // second. The app and the toolkit walk a history in saved order (REVIEW_HISTORY_ORDER), so the file must still
        // replay exactly (2026-10-03).
        val skewed = repo.insertUnit(
            StudyUnitEntity(
                title = "Clock set back", studyType = "Topic", subjectId = subjectIds[0],
                stability = 1.0, difficulty = 5.0, retrievability = 1.0, state = "New",
                studiedAt = day0 + 30 * day, nextReviewAt = day0 + 30 * day, modelDueAt = day0 + 30 * day,
                currentIntervalDays = 0.0, reviewCount = 0, lapseCount = 0, createdAt = day0 + 30 * day,
            )
        )
        commit(repo, skewed, day0 + 30 * day + 60_000L, MemoryRating.Good, UnderstandingRating.Clear, emptySet(), null to null, SessionKind.TOPIC)
        commit(repo, skewed, day0 + 36 * day, MemoryRating.Good, UnderstandingRating.Clear, emptySet(), null to null, SessionKind.PLAN)
        commit(repo, skewed, day0 + 33 * day, MemoryRating.Hard, UnderstandingRating.Partial, emptySet(), null to null, SessionKind.PLAN)
        commit(repo, skewed, day0 + 40 * day, MemoryRating.Good, UnderstandingRating.Clear, emptySet(), null to null, SessionKind.PLAN)

        // One rating corrected, so the export carries a RATING_CORRECTED event and the history it recomputed.
        val corrected = topics.first().id
        val second = app.database.reviewLogDao().getLogsForUnitOnce(corrected).sortedWith(com.example.data.local.entity.REVIEW_HISTORY_ORDER)[1]
        val fixedRating = if (second.memoryRating == MemoryRating.Forgot.name) MemoryRating.Good else MemoryRating.Forgot
        repo.editReviewRating(corrected, second.id, fixedRating, null)

        // One review's day corrected from the topic's history (REVIEW_DATE_CORRECTED): the middle review of a topic
        // moved one day earlier, inside its neighbours. The toolkit must replay the corrected history exactly too.
        val moved = topics.first { p ->
            p.id != corrected && app.database.reviewLogDao().getLogsForUnitOnce(p.id).size >= 3
        }.id
        val history = app.database.reviewLogDao().getLogsForUnitOnce(moved).sortedWith(com.example.data.local.entity.REVIEW_HISTORY_ORDER)
        val (before, middle, after) = Triple(history[0], history[1], history[2])
        val newTime = com.example.domain.model.ReviewDay.timeForCorrection(
            com.example.domain.model.ReviewDay.day(middle.reviewedAt, zone).minusDays(1),
            middle.reviewedAt, before.reviewedAt, after.reviewedAt, now, zone,
        )
        repo.editReviewRating(moved, middle.id, MemoryRating.valueOf(middle.memoryRating), null, newReviewedAt = newTime, now = now)

        // One deferral, so the export carries one.
        repo.procrastinateUnit(topics.last().id, now + 2 * day)

        val json = JSONObject(AnalyticsExporter.buildJson(app))
        assertEquals("the simulated history must not violate a single invariant: " + json.getJSONObject("consistency"),
            0, json.getJSONObject("consistency").getInt("issueCount"))
        assertTrue("a pilot-sized history", json.getJSONArray("reviewLogs").length() > 100)

        val out = java.io.File(System.getProperty("user.dir"), "build/pilot-fixture/sample_export.json")
        out.parentFile!!.mkdirs()
        out.writeText(json.toString(2))
    }
}
