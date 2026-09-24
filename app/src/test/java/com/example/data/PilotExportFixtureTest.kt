package com.example.data

import androidx.test.core.app.ApplicationProvider
import com.example.MedReviewApplication
import com.example.data.local.entity.ReviewLogEntity
import com.example.data.local.entity.StudyUnitEntity
import com.example.domain.model.MemoryRating
import com.example.domain.model.QuestionScore
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
 * worth something if it is run against what the app actually writes, so this test drives the real
 * repository through the same commit path the review screen uses (mirroring ReviewViewModel
 * .rateCurrentUnit field for field, like ReplayEqualsLiveTest) and writes the real exporter's output to
 * app/build/pilot-fixture/sample_export.json. tools/pilot/fixtures/sample_export.json is a copy of it;
 * tools/pilot/test_analyze.py checks the toolkit against that copy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PilotExportFixtureTest {

    private val day = 86_400_000L

    private fun commit(
        repo: com.example.data.repository.MedReviewRepository,
        unitId: Long,
        now: Long,
        memory: MemoryRating,
        understanding: UnderstandingRating,
        methods: Set<ReviewMethod>,
        score: Pair<Int?, Int?>,
        kind: SessionKind,
    ) = runBlocking {
        val unit = repo.projectOntoCurrentModel(repo.getUnitById(unitId)!!)
        val elapsedDays = MedScheduler.modelElapsedDays(unit.lastReviewedAt ?: unit.studiedAt, now, MedScheduler.CURRENT_MODEL)
        val reviewNumber = MedScheduler.effectiveReviewNumber(unit.reviewCount)
        val understandingAsked = memory != MemoryRating.Forgot || reviewNumber == 0
        // A review rated Forgot commits at once with Partial as a placeholder, exactly as the screen does.
        val und = if (understandingAsked) understanding else UnderstandingRating.Partial
        val outcome = MedScheduler.review(
            stability = unit.stability, difficulty = unit.difficulty, elapsedDays = elapsedDays,
            memoryRating = memory, understanding = und, highYield = unit.highYield,
            reviewNumber = reviewNumber, model = MedScheduler.CURRENT_MODEL,
            unrepairedStreak = repo.unrepairedStreak(unit.id), parameterSetId = unit.parameterSetId,
        )
        val nextInterval = MedScheduler.fuzzedInterval(
            outcome.intervalDays, outcome.baseIntervalDays, unit.id, unit.reviewCount, isFirstStudy = reviewNumber == 0,
        )
        val memoryDueAt = now + (nextInterval * 86400000).toLong()
        val understandingDueAt = outcome.remediationDays?.let { now + (it * 86400000).toLong() }
        val nextState = MedScheduler.masteryState(outcome.state.stability, memory == MemoryRating.Forgot)
        val (qc, qt) = if (reviewNumber == 0) -1 to -1 else QuestionScore.normalized(score.first, score.second)
        repo.commitReview(
            unit.copy(
                lastReviewedAt = now, nextReviewAt = listOfNotNull(memoryDueAt, understandingDueAt).min(),
                modelDueAt = memoryDueAt, understandingDueAt = understandingDueAt,
                memoryModel = MedScheduler.CURRENT_MODEL.id, deferredUntil = null,
                currentIntervalDays = nextInterval, reviewCount = unit.reviewCount + 1,
                lapseCount = if (memory == MemoryRating.Forgot) unit.lapseCount + 1 else unit.lapseCount,
                state = nextState.name, difficulty = outcome.state.difficulty, stability = outcome.state.stability,
                retrievability = outcome.retrievabilityAtReview, updatedAt = now,
            ),
            ReviewLogEntity(
                studyUnitId = unit.id, reviewedAt = now,
                memoryRating = memory.name,
                understandingRating = if (understandingAsked) und.name else "NotAsked",
                previousIntervalDays = unit.currentIntervalDays, nextIntervalDays = nextInterval,
                previousState = unit.state, nextState = nextState.name,
                retrievabilityAtReview = outcome.retrievabilityAtReview, elapsedDays = elapsedDays,
                logType = if (reviewNumber == 0) "FIRST_STUDY" else "RECALL",
                initialDifficulty = if (reviewNumber == 0) MedScheduler.difficultyLabelFor(memory) else null,
                reviewDurationMs = 90_000,
                wasImportantAtReview = if (unit.highYield) 1 else 0,
                desiredRetentionAtReview = MedScheduler.effectiveRetention(unit.highYield),
                schedulerVersion = MedScheduler.SCHEDULER_VERSION,
                schedulerPolicyVersion = MedScheduler.POLICY_VERSION,
                understandingFactorAtReview = if (understandingAsked) MedScheduler.understandingFactor(und) else -1.0,
                calibrationScaleAtReview = MedScheduler.calibrationScale,
                keyPointsTotal = -1, keyPointsRecalled = -1,
                parameterSetId = unit.parameterSetId,
                reviewMethods = if (reviewNumber == 0) null else ReviewMethod.encode(methods),
                questionsCorrect = qc, questionsTotal = qt,
                sessionKind = kind.name,
            ),
        )
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
                commit(repo, p.id, evening, memory, understanding, methods, score, SessionKind.PLAN)
            }
        }
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
