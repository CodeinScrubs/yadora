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

    // --- Model isolation ------------------------------------------------------------------------

    @Test
    fun `choosing FSRS-6 changes the schedule but cannot disturb the FSRS-5 path`() {
        val five = MedScheduler.review(20.0, 5.0, 45.0, MemoryRating.Good, UnderstandingRating.Clear, false, 3)
        val six = MedScheduler.review(20.0, 5.0, 45.0, MemoryRating.Good, UnderstandingRating.Clear, false, 3, model = m6)

        assertEquals("FSRS-6 matches its own golden transition", 86.24141137779552, six.state.stability, 1e-9)
        assertNotEquals("the two models genuinely disagree", five.state.stability, six.state.stability, 1e-6)

        // FSRS-5 keeps folding understanding into the interval (legacy replay depends on it)...
        val fiveConfused = MedScheduler.review(20.0, 5.0, 45.0, MemoryRating.Good, UnderstandingRating.Confused, false, 3)
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
