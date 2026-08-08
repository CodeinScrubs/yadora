package com.example.domain.srs

import com.example.domain.model.MemoryRating
import com.example.domain.model.UnderstandingRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The FSRS-6 scheduling path and the TWO-CLOCK model.
 *
 * [Fsrs6SpecComplianceTest] proves the equations; this proves the product layer built on them —
 * that understanding no longer scales a memory prediction, that the first-study ceiling still holds
 * without the hand-chosen YADORA-3 damping, and that selecting FSRS-6 cannot disturb FSRS-5.
 */
class Fsrs6SchedulingTest {

    private val m6 = MedScheduler.MemoryModel.FSRS_6
    private val ratings = listOf(MemoryRating.Hard, MemoryRating.Good, MemoryRating.Easy)
    private val understandings = UnderstandingRating.entries

    private fun review(
        s: Double, d: Double, t: Double, m: MemoryRating, u: UnderstandingRating,
        hy: Boolean = false, rn: Int = 3,
    ) = MedScheduler.review(s, d, t, m, u, hy, rn, model = m6)

    // --- Elapsed time as the queue means it ------------------------------------------------------

    /**
     * THE day-granularity trap. A topic reviewed at 20:00 with a one-day interval is due at 20:00
     * tomorrow, but the queue offers everything due by end of tomorrow — so it appears at 00:00.
     * Measured in elapsed milliseconds, a learner reviewing at 09:00 waited only 13 hours, which
     * floors to ZERO completed days and routes a genuine next-day review into the same-day branch.
     */
    @Test
    fun `a next-day review counts as one day whatever hour it happens at`() {
        val tz = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Berlin"))
            fun at(d: Int, h: Int): Long = java.util.Calendar.getInstance().apply {
                clear(); set(2026, java.util.Calendar.MARCH, d, h, 0, 0)
            }.timeInMillis

            val lastReviewed = at(10, 20) // reviewed at 20:00
            for (hour in 0..23) {
                val elapsed = MedScheduler.modelElapsedDays(lastReviewed, at(11, hour), m6)
                assertEquals("reviewing on the next calendar day at ${hour}:00 is one day", 1.0, elapsed, 0.0)
            }
            // And the day after is two, again at any hour.
            for (hour in listOf(0, 9, 23)) {
                assertEquals("two calendar days later", 2.0, MedScheduler.modelElapsedDays(lastReviewed, at(12, hour), m6), 0.0)
            }
            // Same calendar day is still zero, so the same-day branch is reachable when it should be.
            assertEquals("same day stays same-day", 0.0, MedScheduler.modelElapsedDays(lastReviewed, at(10, 23), m6), 0.0)
            assertEquals("never negative", 0.0, MedScheduler.modelElapsedDays(at(11, 9), at(10, 9), m6), 0.0)
        } finally {
            java.util.TimeZone.setDefault(tz)
        }
    }

    /**
     * The consequence that matters: a one-day relearn step, reviewed the next morning, must be
     * graded as a real retrieval and not as a same-day restudy worth a few percent.
     */
    @Test
    fun `a next-morning relearn review earns real stability, not a same-day nudge`() {
        val sameDay = review(s = 1.0, d = 6.0, t = 0.0, m = MemoryRating.Good, u = UnderstandingRating.Clear)
        val nextDay = review(s = 1.0, d = 6.0, t = 1.0, m = MemoryRating.Good, u = UnderstandingRating.Clear)
        assertTrue(
            "a completed day of waiting must be worth more than a same-day restudy " +
                "(${nextDay.state.stability} vs ${sameDay.state.stability})",
            nextDay.state.stability > sameDay.state.stability * 1.5,
        )
    }

    /** FSRS-5 must keep counting fractional milliseconds — it is frozen. */
    @Test
    fun `the legacy model still measures elapsed time in fractional days`() {
        val day = 86400000L
        val elapsed = MedScheduler.modelElapsedDays(0L, (2.5 * day).toLong(), MedScheduler.MemoryModel.FSRS_5)
        assertEquals("FSRS-5 is unchanged", 2.5, elapsed, 1e-9)
    }

    // --- Completed model days -------------------------------------------------------------------

    /**
     * The model is fed COMPLETED whole days, as the reference measures them and as the weights were
     * fitted. On a day-granularity app that also removes noise the product cannot justify: reviewing
     * a topic at 22:00 rather than at 09:00 is the same evidence on the same calendar day and must
     * not buy a different interval.
     */
    @Test
    fun `the hour of day cannot change the interval`() {
        for (m in ratings) {
            for (u in understandings) {
                val morning = review(s = 12.0, d = 5.0, t = 7.0, m = m, u = u)
                for (frac in listOf(0.01, 0.25, 0.5, 0.75, 0.99)) {
                    val later = review(s = 12.0, d = 5.0, t = 7.0 + frac, m = m, u = u)
                    assertEquals(
                        "$m/$u at +$frac of a day must match the same calendar day",
                        morning.intervalDays, later.intervalDays, 0.0,
                    )
                    assertEquals("and so must the state", morning.state.stability, later.state.stability, 0.0)
                }
            }
        }
    }

    /** Crossing into the next day IS new evidence, so it must still move the result. */
    @Test
    fun `crossing a day boundary does change the result`() {
        val day7 = review(s = 12.0, d = 5.0, t = 7.9, m = MemoryRating.Good, u = UnderstandingRating.Clear)
        val day8 = review(s = 12.0, d = 5.0, t = 8.0, m = MemoryRating.Good, u = UnderstandingRating.Clear)
        assertNotEquals("a completed extra day is real evidence", day7.intervalDays, day8.intervalDays)
    }

    /** Flooring must never reroute a genuine long-term review into the same-day branch. */
    @Test
    fun `the same-day branch boundary is unchanged by flooring`() {
        assertEquals("0.99 days is still same-day", 0.0, MedScheduler.completedModelDays(0.99), 0.0)
        assertEquals("1.0 days is a completed day", 1.0, MedScheduler.completedModelDays(1.0), 0.0)
        assertEquals("negative elapsed cannot go below zero", 0.0, MedScheduler.completedModelDays(-3.0), 0.0)
    }

    // --- The two clocks -----------------------------------------------------------------------

    @Test
    fun `understanding no longer scales the memory interval`() {
        for (s in listOf(3.0, 20.0, 120.0, 400.0)) for (d in listOf(2.0, 5.0, 9.0)) for (m in ratings) {
            val clear = review(s, d, 30.0, m, UnderstandingRating.Clear)
            val partial = review(s, d, 30.0, m, UnderstandingRating.Partial)
            val confused = review(s, d, 30.0, m, UnderstandingRating.Confused)
            val ctx = "S=$s D=$d $m"

            // The memory prediction is the SAME regardless of understanding — that is the whole point.
            assertEquals("memory interval must not depend on understanding ($ctx)",
                clear.intervalDays, partial.intervalDays, 1e-12)
            assertEquals("memory interval must not depend on understanding ($ctx)",
                clear.intervalDays, confused.intervalDays, 1e-12)
            // And the memory state certainly must not.
            assertEquals("stability untouched ($ctx)", clear.state.stability, confused.state.stability, 1e-12)
            assertEquals("difficulty untouched ($ctx)", clear.state.difficulty, confused.state.difficulty, 1e-12)
        }
    }

    @Test
    fun `weak understanding produces a short repair deadline instead of a shrunken interval`() {
        // The scenario that motivated the change: a long memory prediction on a topic the user says
        // they do not understand. Old behaviour scaled 100 days to 80 — still effectively gone.
        val long = review(250.0, 3.0, 250.0, MemoryRating.Easy, UnderstandingRating.Partial)
        assertTrue("the memory prediction is preserved, not scaled", long.intervalDays > 100.0)
        assertEquals("and a short repair deadline is issued alongside it", 4.0, long.remediationDays!!, 1e-12)

        val confused = review(250.0, 3.0, 250.0, MemoryRating.Easy, UnderstandingRating.Confused)
        assertEquals("confused is repaired tomorrow", 1.0, confused.remediationDays!!, 1e-12)
        assertEquals("with the same untouched memory prediction",
            long.intervalDays, confused.intervalDays, 1e-12)
    }

    @Test
    fun `clear understanding adds no second clock at all`() {
        for (m in ratings) {
            assertNull("Clear needs no repair ($m)", review(20.0, 5.0, 20.0, m, UnderstandingRating.Clear).remediationDays)
        }
    }

    @Test
    fun `the remediation table is exactly as documented`() {
        assertEquals(1.0, MedScheduler.remediationDays(MemoryRating.Hard, UnderstandingRating.Confused)!!, 1e-12)
        assertEquals(1.0, MedScheduler.remediationDays(MemoryRating.Easy, UnderstandingRating.Confused)!!, 1e-12)
        assertEquals(2.0, MedScheduler.remediationDays(MemoryRating.Hard, UnderstandingRating.Partial)!!, 1e-12)
        assertEquals(3.0, MedScheduler.remediationDays(MemoryRating.Good, UnderstandingRating.Partial)!!, 1e-12)
        assertEquals(4.0, MedScheduler.remediationDays(MemoryRating.Easy, UnderstandingRating.Partial)!!, 1e-12)
        assertNull(MedScheduler.remediationDays(MemoryRating.Good, UnderstandingRating.Clear))
        // A lapse already relearns tomorrow; its remediation must agree rather than compete.
        for (u in understandings) {
            assertEquals("a lapse repairs tomorrow whatever the understanding ($u)",
                MedScheduler.RELEARN_STEP_DAYS, MedScheduler.remediationDays(MemoryRating.Forgot, u)!!, 1e-12)
        }
    }

    // --- First study --------------------------------------------------------------------------

    @Test
    fun `the first-study ceiling still holds without any hand-chosen damping`() {
        val seed = MedScheduler.firstStudy(UnderstandingRating.Partial, false).state
        for (m in ratings) for (u in understandings) {
            val o = MedScheduler.review(seed.stability, seed.difficulty, 0.0, m, u, false, 0, model = m6)
            assertTrue("$m/$u first rating stays inside the calm window (${o.baseIntervalDays})",
                o.baseIntervalDays <= MedScheduler.FIRST_STUDY_MAX_DAYS + 1e-9)
            assertTrue("and at least a day out", o.intervalDays >= MedScheduler.MIN_INTERVAL_DAYS - 1e-9)
        }
    }

    @Test
    fun `FSRS-6 seeds a first Easy rating from its own fitted value, not a damped FSRS-5 one`() {
        val seed = MedScheduler.firstStudy(UnderstandingRating.Partial, false).state
        val o = MedScheduler.review(
            seed.stability, seed.difficulty, 0.0,
            MemoryRating.Easy, UnderstandingRating.Clear, false, 0, model = m6,
        )
        // 8.2956 is FSRS-6's refit S0(Easy) — a fitted number replacing YADORA-3's hand-chosen 7.06.
        assertEquals("seeded from FSRS-6's own S0(Easy)", 8.2956, o.state.stability, 1e-9)
        assertNotEquals("not FSRS-5's raw value", 15.69105, o.state.stability, 1e-6)
    }

    @Test
    fun `a first rating is identical however late it happens`() {
        val seed = MedScheduler.firstStudy(UnderstandingRating.Partial, false).state
        for (m in ratings) {
            val onTime = MedScheduler.review(seed.stability, seed.difficulty, 0.0, m, UnderstandingRating.Clear, false, 0, model = m6)
            for (late in listOf(1.0, 30.0, 500.0)) {
                val delayed = MedScheduler.review(seed.stability, seed.difficulty, late, m, UnderstandingRating.Clear, false, 0, model = m6)
                assertEquals("$m first rating $late days late", onTime.intervalDays, delayed.intervalDays, 1e-12)
                assertEquals("and the same seed", onTime.state.stability, delayed.state.stability, 1e-12)
            }
        }
    }

    // --- Shared product invariants still hold under the new model ------------------------------

    @Test
    fun `every FSRS-6 review is bounded, finite and sane`() {
        val max = Fsrs6Parameters().maximumIntervalDays
        for (s in listOf(0.01, 1.0, 20.0, 400.0, 3000.0)) for (d in listOf(1.0, 5.0, 10.0))
            for (t in listOf(0.0, 1.0, 45.0, 900.0)) for (m in MemoryRating.entries)
                for (u in understandings) for (hy in listOf(false, true)) for (rn in listOf(0, 1, 9)) {
                    val o = MedScheduler.review(s, d, t, m, u, hy, rn, model = m6)
                    val ctx = "S=$s D=$d t=$t $m/$u hy=$hy rn=$rn"
                    assertTrue("finite ($ctx)", o.intervalDays.isFinite())
                    assertTrue("at least a day ($ctx)", o.intervalDays >= MedScheduler.MIN_INTERVAL_DAYS - 1e-9)
                    assertTrue("within the ceiling ($ctx)", o.intervalDays <= max + 1e-9)
                    assertTrue("stability positive ($ctx)", o.state.stability > 0.0 && o.state.stability.isFinite())
                    assertTrue("difficulty in range ($ctx)", o.state.difficulty in 1.0..10.0)
                    o.remediationDays?.let { assertTrue("repair deadline is short ($ctx)", it in 1.0..4.0) }
                }
    }

    @Test
    fun `forgetting still relearns tomorrow, and importance still never delays`() {
        for (u in understandings) {
            assertEquals("a lapse relearns tomorrow", MedScheduler.RELEARN_STEP_DAYS,
                review(60.0, 5.0, 90.0, MemoryRating.Forgot, u).intervalDays, 1e-12)
        }
        for (m in ratings) for (u in understandings) {
            val normal = MedScheduler.review(40.0, 5.0, 40.0, m, u, false, 3, model = m6)
            val important = MedScheduler.review(40.0, 5.0, 40.0, m, u, true, 3, model = m6)
            assertTrue("importance must never push a topic further away ($m/$u)",
                important.intervalDays <= normal.intervalDays + 1e-9)
        }
    }

    /**
     * The load-bearing separation, on the LIVE model. FSRS models MEMORY; what the learner says about
     * their understanding must never move stability or difficulty, or the two-clock design collapses
     * back into the multiplier it replaced. The equivalent assertion in SchedulerInvariantsTest is
     * scoped to FSRS-5 because it also pins the retired x0.9/x0.8 ratios.
     */
    @Test
    fun `understanding never touches the FSRS-6 memory state`() {
        for (s in listOf(1.0, 5.0, 20.0, 110.0)) {
            for (d in listOf(1.0, 5.0, 9.5)) {
                for (t in listOf(0.0, 1.0, 12.0, 200.0)) {
                    for (m in ratings + listOf(MemoryRating.Forgot)) {
                        val clear = review(s, d, t, m, UnderstandingRating.Clear)
                        for (u in understandings) {
                            val other = review(s, d, t, m, u)
                            val ctx = "S=$s D=$d t=$t $m/$u"
                            assertEquals("stability must not move ($ctx)", clear.state.stability, other.state.stability, 0.0)
                            assertEquals("difficulty must not move ($ctx)", clear.state.difficulty, other.state.difficulty, 0.0)
                            assertEquals("nor the memory interval ($ctx)", clear.intervalDays, other.intervalDays, 0.0)
                        }
                    }
                }
            }
        }
    }

    // --- Model isolation ------------------------------------------------------------------------

    @Test
    fun `choosing FSRS-6 changes the schedule but cannot disturb the FSRS-5 path`() {
        val five = MedScheduler.review(20.0, 5.0, 45.0, MemoryRating.Good, UnderstandingRating.Clear, false, 3, model = MedScheduler.MemoryModel.FSRS_5)
        val six = MedScheduler.review(20.0, 5.0, 45.0, MemoryRating.Good, UnderstandingRating.Clear, false, 3, model = m6)

        assertEquals("FSRS-6 matches its own golden transition", 86.24141137779552, six.state.stability, 1e-9)
        assertNotEquals("the two models genuinely disagree", five.state.stability, six.state.stability, 1e-6)

        // FSRS-5 keeps folding understanding into the interval (legacy replay depends on it)...
        val fiveConfused = MedScheduler.review(20.0, 5.0, 45.0, MemoryRating.Good, UnderstandingRating.Confused, false, 3, model = MedScheduler.MemoryModel.FSRS_5)
        assertEquals("FSRS-5 still multiplies", five.intervalDays * MedScheduler.UNDERSTANDING_CONFUSED_FACTOR,
            fiveConfused.intervalDays, 1e-9)
        assertNull("and reports no second clock", fiveConfused.remediationDays)
        // ...while FSRS-6 does not.
        val sixConfused = MedScheduler.review(20.0, 5.0, 45.0, MemoryRating.Good, UnderstandingRating.Confused, false, 3, model = m6)
        assertEquals("FSRS-6 does not multiply", six.intervalDays, sixConfused.intervalDays, 1e-12)
        assertEquals("it schedules a repair instead", 1.0, sixConfused.remediationDays!!, 1e-12)
    }

    @Test
    fun `model ids round-trip through the identity used by review logs`() {
        assertEquals("FSRS-5", MedScheduler.MemoryModel.FSRS_5.id)
        assertEquals("FSRS-6", MedScheduler.MemoryModel.FSRS_6.id)
        assertEquals(MedScheduler.MemoryModel.FSRS_6, MedScheduler.MemoryModel.of("FSRS-6"))
        assertEquals(MedScheduler.MemoryModel.FSRS_5, MedScheduler.MemoryModel.of("FSRS-5"))
        // Pre-versioning rows carried a blank schedulerVersion; those were all FSRS-5.
        assertEquals("legacy blank id", MedScheduler.MemoryModel.FSRS_5, MedScheduler.MemoryModel.of(""))
        assertEquals("unknown id fails safe", MedScheduler.MemoryModel.FSRS_5, MedScheduler.MemoryModel.of("FSRS-9"))
    }
}
