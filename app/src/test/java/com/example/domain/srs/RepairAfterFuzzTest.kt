package com.example.domain.srs

import com.example.domain.model.MemoryRating
import com.example.domain.model.UnderstandingRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * YADORA-7 (an outside audit, 2026-10-04): an understanding repair deadline is kept when it beats the memory interval as
 * finally scheduled, the ±5% fuzz included. YADORA-6 compared it with the interval before the fuzz, so a deadline inside
 * the fuzz band was dropped and the topic came back after its repair date. A row stamped YADORA-6 or older replays the
 * comparison it was given (MedScheduler.repairDays, used by the preview, the commit and the replay alike).
 */
class RepairAfterFuzzTest {

    private fun outcome(stability: Double, rating: MemoryRating, understanding: UnderstandingRating, reviewNumber: Int = 8) =
        MedScheduler.review(
            stability = stability, difficulty = 5.0, elapsedDays = 1.0, memoryRating = rating, understanding = understanding,
            highYield = false, reviewNumber = reviewNumber, desiredRetentionOverride = 0.90,
            model = MedScheduler.MemoryModel.FSRS_6, calibrationScaleOverride = 1.0, parameterSetId = 0L,
        )

    @Test
    fun `a repair deadline inside the fuzz band is kept, and an old row replays as it was given`() {
        // The audit's case: topic 4 after 8 reviews, rated Easy with Partial understanding a day after the last review.
        val o = outcome(0.17643240250219983, MemoryRating.Easy, UnderstandingRating.Partial)
        val final = MedScheduler.fuzzedInterval(o.intervalDays, o.baseIntervalDays, 4L, 8, isFirstStudy = false, policyVersion = "YADORA-7")
        assertEquals("the memory interval before the fuzz", 3.995, o.intervalDays, 1e-9)
        assertEquals("and after it", 4.052766843189816, final, 1e-9)
        assertEquals("the repair deadline asked for", 4.0, o.candidateRemediationDays!!, 0.0)
        assertNull("YADORA-6 compared it with 3.995 days and dropped it", MedScheduler.repairDays(o, MemoryRating.Easy, final, "YADORA-6"))
        assertEquals("YADORA-7 keeps it: the topic returns on its repair date, not 76 minutes later",
            4.0, MedScheduler.repairDays(o, MemoryRating.Easy, final, "YADORA-7")!!, 0.0)
        assertEquals("current policy keeps the final-interval repair rule", 4.0,
            MedScheduler.repairDays(o, MemoryRating.Easy, final, MedScheduler.POLICY_VERSION)!!, 0.0)
        assertEquals("the policy new reviews are stamped with", "YADORA-9", MedScheduler.POLICY_VERSION)
        assertEquals("an unstamped row replays under the current policy", 4.0, MedScheduler.repairDays(o, MemoryRating.Easy, final, "")!!, 0.0)
    }

    @Test
    fun `across a sweep the rule compares with the final interval, and older policies keep their decision`() {
        var bandCases = 0
        for (s in listOf(0.05, 0.17643240250219983, 0.5, 1.0, 2.0, 3.3, 5.0, 8.0, 13.0, 21.0)) {
            for (rating in listOf(MemoryRating.Forgot, MemoryRating.Hard, MemoryRating.Good, MemoryRating.Easy)) {
                for (und in listOf(UnderstandingRating.Confused, UnderstandingRating.Partial, UnderstandingRating.Clear)) {
                    val o = outcome(s, rating, und)
                    for (unit in 1L..40L) {
                        val final = MedScheduler.fuzzedInterval(o.intervalDays, o.baseIntervalDays, unit, 8, isFirstStudy = false, policyVersion = "YADORA-7")
                        val now = MedScheduler.repairDays(o, rating, final, "YADORA-7")
                        val old = MedScheduler.repairDays(o, rating, final, "YADORA-6")
                        assertEquals("YADORA-6 keeps its pre-fuzz decision", o.remediationDays, old)
                        val expected = o.candidateRemediationDays?.takeIf { rating == MemoryRating.Forgot || it < final }
                        assertEquals("YADORA-7: kept iff before the final interval (s=$s $rating $und unit $unit)", expected, now)
                        if (now != null && rating != MemoryRating.Forgot) assertTrue("a kept deadline comes first", now < final)
                        if (now != old) {
                            bandCases++
                            assertTrue("the two differ only inside the fuzz band", kotlin.math.abs(final - o.intervalDays) <= 0.05 * o.intervalDays + 1e-9)
                        }
                    }
                }
            }
        }
        assertTrue("the sweep reaches the fuzz band", bandCases > 0)
    }
}
