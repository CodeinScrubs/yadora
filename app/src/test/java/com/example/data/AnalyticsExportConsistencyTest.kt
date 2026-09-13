package com.example.data

import androidx.test.core.app.ApplicationProvider
import com.example.MedReviewApplication
import com.example.data.local.entity.ReviewLogEntity
import com.example.data.local.entity.StudyUnitEntity
import com.example.domain.model.MemoryRating
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
 * The analytics export is the research artifact a field-test period produces — if it's internally
 * inconsistent (logs referencing missing topics, growth events that can't be joined to reviews) or
 * silently drops the v5 honest-scheduling fields, the whole data-gathering effort is wasted.
 * This runs against the REAL application database + exporter, not mocks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AnalyticsExportConsistencyTest {

    @Test
    fun `export is internally consistent and carries the v5 research fields`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MedReviewApplication>()
        val repo = app.repository
        val now = System.currentTimeMillis()
        val day = 86400000L

        fun newUnit(title: String) = StudyUnitEntity(
            title = title, studyType = "Topic",
            stability = 1.0, difficulty = 5.0, retrievability = 1.0, state = "New",
            studiedAt = now - day, nextReviewAt = now, modelDueAt = now,
            currentIntervalDays = 0.0, reviewCount = 0, lapseCount = 0,
        )

        // Topic 1: gets a committed review (creates a review log + STUDY_ACTION keyed to it).
        val id1 = repo.insertUnit(newUnit("Reviewed topic"))
        val u1 = repo.getUnitById(id1)!!
        val outcome = MedScheduler.review(
            stability = u1.stability, difficulty = u1.difficulty, elapsedDays = 1.0,
            memoryRating = MemoryRating.Good, understanding = UnderstandingRating.Clear,
            highYield = false, reviewNumber = 1,
            model = MedScheduler.CURRENT_MODEL,
        )
        val logId = repo.commitReview(
            u1.copy(lastReviewedAt = now, nextReviewAt = now + day, modelDueAt = now + day, deferredUntil = null, reviewCount = 1),
            ReviewLogEntity(
                studyUnitId = id1, reviewedAt = now,
                memoryRating = "Good", understandingRating = "Clear",
                previousIntervalDays = 0.0, nextIntervalDays = outcome.intervalDays,
                previousState = "New", nextState = "Learning",
                retrievabilityAtReview = outcome.retrievabilityAtReview, elapsedDays = 1.0,
                logType = "RECALL",
                schedulerPolicyVersion = MedScheduler.POLICY_VERSION,
                understandingFactorAtReview = 1.0,
            )
        )

        // Topic 2: reviewed then SOFT-DELETED — its log must still resolve in the export.
        val id2 = repo.insertUnit(newUnit("Deleted topic"))
        val u2 = repo.getUnitById(id2)!!
        repo.commitReview(
            u2.copy(lastReviewedAt = now, nextReviewAt = now + day, modelDueAt = now + day, reviewCount = 1),
            ReviewLogEntity(
                studyUnitId = id2, reviewedAt = now,
                memoryRating = "Hard", understandingRating = "Partial",
                previousIntervalDays = 0.0, nextIntervalDays = 1.0,
                previousState = "New", nextState = "Learning",
                retrievabilityAtReview = 0.9, elapsedDays = 1.0, logType = "RECALL",
                schedulerPolicyVersion = MedScheduler.POLICY_VERSION,
                understandingFactorAtReview = 0.9,
            )
        )
        repo.softDeleteUnit(id2)

        // Topic 3: deferred ("not today") — deferral must be visible in the export.
        val id3 = repo.insertUnit(newUnit("Deferred topic"))
        repo.procrastinateUnit(id3, now + day)

        val json = JSONObject(AnalyticsExporter.buildJson(app))

        assertEquals("export version", 7, json.getInt("exportVersion"))

        // THE TIME ZONE. FSRS-6 elapsed time is a difference of LOCAL calendar dates, so without the
        // zone that produced the file nothing in it can be recomputed from its own timestamps — an
        // export analysed in the wrong zone disagrees with the app by up to a day on every review.
        val env = json.getJSONObject("environment")
        assertTrue("time zone id", env.getString("timeZoneId").isNotBlank())
        assertTrue("utc offset", env.has("utcOffsetMinutesAtExport"))
        assertTrue("local date at export", env.getString("localDateAtExport").matches(Regex("""\d{4}-\d{2}-\d{2}""")))
        assertTrue("both time conventions are spelled out", env.getString("elapsedDaysConvention").isNotBlank())
        assertTrue("and the due-date one too", env.getString("dueDateConvention").isNotBlank())
        assertTrue("build is identified precisely", json.getInt("appVersionCode") > 0)

        // The export self-checks. On a healthy database this must be EMPTY: every entry is an
        // invariant the app is supposed to maintain, so a non-zero count is a bug report.
        val consistency = json.getJSONObject("consistency")
        assertEquals(
            "a freshly-built database must not violate its own invariants: " +
                consistency.getJSONArray("issues").toString(),
            0, consistency.getInt("issueCount"),
        )
        assertEquals("and it must actually have looked", unitsCountForCheck(json), consistency.getInt("checkedUnits"))

        // The live model must be named, and the legacy one named as retained -- calibration that
        // pooled FSRS-5 and FSRS-6 outcomes would be averaging two different forgetting curves.
        assertEquals("top-level scheduler reports the LIVE model",
            com.example.domain.srs.MedScheduler.CURRENT_MODEL.id, json.getString("scheduler"))

        // v4 policy block: an interval in the data is meaningless without the constants that produced
        // it, and a year-old export must be readable without the matching source revision.
        val policy = json.getJSONObject("policy")
        assertEquals("policy names the live model",
            com.example.domain.srs.MedScheduler.CURRENT_MODEL.id, policy.getString("memoryModel"))
        assertEquals("and the exact frozen weight vector",
            com.example.domain.srs.Fsrs6Parameters.DEFAULT_PARAMETER_SET_ID, policy.getString("parameterSetId"))
        assertEquals("FSRS-5 is recorded as retained for replay",
            "FSRS-5", policy.getString("legacyModelRetainedForReplay"))
        assertEquals("understanding is documented as a separate clock",
            true, policy.getBoolean("understandingIsSeparateClock"))
        assertEquals("policy version", com.example.domain.srs.MedScheduler.POLICY_VERSION, policy.getString("version"))
        assertEquals(
            "first-study cap is exported",
            com.example.domain.srs.MedScheduler.FIRST_STUDY_MAX_DAYS, policy.getDouble("firstStudyMaxDays"), 1e-9,
        )
        assertEquals(
            "the exam date must be recorded as NOT affecting scheduling",
            false, policy.getBoolean("examDateAffectsScheduling"),
        )

        // Every review log must reference an exported topic (including the soft-deleted one).
        val unitIds = HashSet<Long>()
        val unitsArr = json.getJSONArray("studyUnits")
        for (i in 0 until unitsArr.length()) unitIds.add(unitsArr.getJSONObject(i).getLong("id"))
        val logIds = HashSet<Long>()
        val logsArr = json.getJSONArray("reviewLogs")
        for (i in 0 until logsArr.length()) {
            val log = logsArr.getJSONObject(i)
            logIds.add(log.getLong("id"))
            assertTrue("log ${log.getLong("id")} references exported topic", log.getLong("studyUnitId") in unitIds)
            assertTrue("log carries policy version", log.has("schedulerPolicyVersion"))
            assertTrue("log carries applied factor", log.has("understandingFactorAtReview"))
            // v4 adherence: without these, analysis cannot tell a bad interval apart from a late user.
            assertTrue("log carries the date it was answering", log.has("scheduledForAt"))
            assertTrue("log carries lateness", log.has("daysLate"))
            // daysLate is DERIVED, and wrong whenever the user moved the date. Say so, and give the
            // analysis the count it needs to discard those rows instead of trusting them.
            assertEquals("lateness is labelled as reconstructed", "reconstructed", log.getString("scheduledForAtSource"))
            assertTrue("and carries the deferral count for its window", log.getInt("deferralsBeforeThisReview") >= 0)
            if (!log.isNull("scheduledForAt")) {
                val expected = (log.getLong("reviewedAt") - log.getLong("scheduledForAt")) / 86400000.0
                assertEquals("daysLate agrees with its own timestamps", expected, log.getDouble("daysLate"), 1e-9)
            }
        }
        assertTrue("committed review's log is exported", logId in logIds)

        // Every STUDY_ACTION growth event must join to an exported review log.
        val eventsArr = json.getJSONArray("eventLogs")
        var studyActions = 0
        var procrastinations = 0
        for (i in 0 until eventsArr.length()) {
            val e = eventsArr.getJSONObject(i)
            when (e.getString("type")) {
                "STUDY_ACTION" -> {
                    studyActions++
                    assertTrue("growth event joins to a log", e.getString("detail").toLong() in logIds)
                }
                "PROCRASTINATE" -> procrastinations++
            }
        }
        assertEquals("one growth event per committed review", 2, studyActions)
        assertEquals("deferral event recorded", 1, procrastinations)

        // The v5 honest-scheduling fields must be present per topic; the deferred topic must show it.
        var sawDeferred = false
        for (i in 0 until unitsArr.length()) {
            val u = unitsArr.getJSONObject(i)
            assertTrue("unit carries modelDueAt", u.has("modelDueAt"))
            assertTrue("unit says whether it has a recall prompt", u.has("hasRecallPrompt"))
            assertTrue("but never the prompt text itself", !u.has("recallPrompt"))
            if (u.getLong("id") == id3) {
                assertEquals("deferredUntil visible in export", now + day, u.getLong("deferredUntil"))
                assertEquals("model's date untouched by deferral", now, u.getLong("modelDueAt"))
                sawDeferred = true
            }
        }
        assertTrue("deferred topic present in export", sawDeferred)
    }

    /**
     * The self-check must actually CATCH things. A consistency block that only ever reports zero is
     * decoration — worse than nothing, because it manufactures confidence. This deliberately writes
     * two rows that violate invariants the app is supposed to maintain and asserts the export says
     * so, naming the offending topic.
     */
    @Test
    fun `the export reports corruption instead of hiding it`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MedReviewApplication>()
        val repo = app.repository
        val now = System.currentTimeMillis()
        val day = 86400000L

        // (1) A row claiming reviews its history cannot account for.
        val liar = repo.insertUnit(
            StudyUnitEntity(
                title = "Claims 99 reviews", studyType = "Topic",
                stability = 5.0, difficulty = 5.0, retrievability = 1.0, state = "Building",
                studiedAt = now - 10 * day, nextReviewAt = now, modelDueAt = now,
                currentIntervalDays = 5.0, reviewCount = 99, lapseCount = 7,
            )
        )
        repo.insertReviewLog(
            ReviewLogEntity(
                studyUnitId = liar, reviewedAt = now - 9 * day,
                memoryRating = "Good", understandingRating = "Clear",
                previousIntervalDays = 0.0, nextIntervalDays = 5.0,
                previousState = "New", nextState = "Building",
                retrievabilityAtReview = 1.0, elapsedDays = 1.0, logType = "FIRST_STUDY",
            )
        )

        // (2) A row whose effective date matches neither clock, with no deferral to explain it.
        val incoherent = repo.insertUnit(
            StudyUnitEntity(
                title = "Effective date from nowhere", studyType = "Topic",
                stability = 5.0, difficulty = 5.0, retrievability = 1.0, state = "Building",
                studiedAt = now - 10 * day,
                nextReviewAt = now + 40 * day, modelDueAt = now + 10 * day,
                understandingDueAt = now + 2 * day, deferredUntil = null,
                currentIntervalDays = 10.0, reviewCount = 0, lapseCount = 0,
            )
        )

        val json = JSONObject(AnalyticsExporter.buildJson(app))
        val consistency = json.getJSONObject("consistency")
        val issues = consistency.getJSONArray("issues")
        val found = (0 until issues.length()).map { issues.getJSONObject(it) }

        assertTrue("the self-check must not stay silent", consistency.getInt("issueCount") > 0)
        assertTrue(
            "a topic claiming 99 reviews with one log must be flagged: $issues",
            found.any { it.optLong("unitId") == liar && it.getString("kind") == "REVIEW_COUNT_MISMATCH" },
        )
        assertTrue(
            "a lapse count with no lapses in history must be flagged: $issues",
            found.any { it.optLong("unitId") == liar && it.getString("kind") == "LAPSE_COUNT_MISMATCH" },
        )
        assertTrue(
            "an effective date matching neither clock must be flagged: $issues",
            found.any { it.optLong("unitId") == incoherent && it.getString("kind") == "CLOCK_DISAGREEMENT" },
        )
        // And it must stay descriptive: the export reports, it never quietly repairs.
        assertEquals("the corrupt row is exported as-is", 99, repo.getUnitById(liar)!!.reviewCount)
    }

    private fun unitsCountForCheck(json: JSONObject): Int = json.getJSONArray("studyUnits").length()
}
