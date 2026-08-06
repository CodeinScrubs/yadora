package com.example.domain.srs

import com.example.domain.model.MemoryRating
import com.example.domain.model.UnderstandingRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariants of the PRODUCT layer that sits on top of [Fsrs].
 *
 * [FsrsSpecComplianceTest] proves the memory model matches the published FSRS-5 equations. This file
 * proves the thin layer Yadora wraps around it — understanding multipliers, the high-yield retention
 * bump, the first-study window, the relearn step, fuzz — cannot produce a schedule that is unsafe,
 * dishonest, or discontinuous. These are swept across the whole reachable input space rather than
 * spot-checked, because a scheduling defect is silent: it shows up months later as "why did I forget
 * this?", never as a crash.
 */
class SchedulerInvariantsTest {

    private val ratings = MemoryRating.entries
    private val understandings = UnderstandingRating.entries
    private val stabilities = listOf(0.01, 0.5, 3.173, 15.69105, 60.0, 400.0, 3000.0)
    private val difficulties = listOf(1.0, 3.2245, 5.5, 8.0, 10.0)
    private val elapsed = listOf(0.0, 0.5, 1.0, 7.0, 45.0, 365.0, 2000.0)

    private fun sweep(block: (s: Double, d: Double, t: Double, m: MemoryRating, u: UnderstandingRating, hy: Boolean, rn: Int) -> Unit) {
        for (s in stabilities) for (d in difficulties) for (t in elapsed)
            for (m in ratings) for (u in understandings) for (hy in listOf(false, true)) for (rn in listOf(0, 1, 7)) {
                block(s, d, t, m, u, hy, rn)
            }
    }

    @Test
    fun `every reachable review produces a sane, bounded, finite interval`() {
        val max = FsrsParameters().maximumIntervalDays
        sweep { s, d, t, m, u, hy, rn ->
            val o = MedScheduler.review(s, d, t, m, u, hy, rn)
            val ctx = "S=$s D=$d t=$t $m/$u hy=$hy rn=$rn"
            assertTrue("interval finite ($ctx)", o.intervalDays.isFinite())
            assertTrue("interval >= 1 day ($ctx) got ${o.intervalDays}", o.intervalDays >= MedScheduler.MIN_INTERVAL_DAYS - 1e-9)
            assertTrue("interval <= cap ($ctx) got ${o.intervalDays}", o.intervalDays <= max + 1e-9)
            assertTrue("stability finite ($ctx)", o.state.stability.isFinite() && o.state.stability > 0.0)
            assertTrue("difficulty in range ($ctx)", o.state.difficulty in 1.0..10.0)
            assertTrue("R at review in (0,1] ($ctx)", o.retrievabilityAtReview in 0.0..1.0)

            // Fuzz is the last step before a due date is stored; it must not escape the same bounds.
            val fuzzed = MedScheduler.fuzzedInterval(o.intervalDays, o.baseIntervalDays, unitId = 7L, reviewCount = rn)
            assertTrue("fuzzed finite ($ctx)", fuzzed.isFinite())
            assertTrue("fuzzed >= 1 ($ctx) got $fuzzed", fuzzed >= MedScheduler.MIN_INTERVAL_DAYS - 1e-9)
            assertTrue("fuzzed <= cap ($ctx) got $fuzzed", fuzzed <= max + 1e-9)
        }
    }

    @Test
    fun `forgetting always brings the topic back tomorrow, however long it had been`() {
        sweep { s, d, t, _, u, hy, rn ->
            val o = MedScheduler.review(s, d, t, MemoryRating.Forgot, u, hy, rn)
            assertEquals(
                "a lapse relearns tomorrow regardless of lateness (S=$s t=$t)",
                MedScheduler.RELEARN_STEP_DAYS, o.intervalDays, 1e-9,
            )
            // Understanding must not move a relearn step: the user forgot, that is the whole signal.
            assertEquals("understanding cannot alter the relearn step", MedScheduler.RELEARN_STEP_DAYS, o.baseIntervalDays, 1e-9)
        }
    }

    @Test
    fun `understanding is an exact, transparent multiplier and never touches the memory model`() {
        for (s in stabilities) for (d in difficulties) for (t in elapsed)
            for (m in listOf(MemoryRating.Hard, MemoryRating.Good, MemoryRating.Easy)) for (rn in listOf(1, 7)) {
                val clear = MedScheduler.review(s, d, t, m, UnderstandingRating.Clear, false, rn)
                val partial = MedScheduler.review(s, d, t, m, UnderstandingRating.Partial, false, rn)
                val confused = MedScheduler.review(s, d, t, m, UnderstandingRating.Confused, false, rn)
                val ctx = "S=$s D=$d t=$t $m rn=$rn"

                // FSRS models MEMORY only: the same recall grade must yield the same memory state no
                // matter what the user said about their understanding.
                assertEquals("understanding must not change stability ($ctx)", clear.state.stability, partial.state.stability, 1e-12)
                assertEquals("understanding must not change stability ($ctx)", clear.state.stability, confused.state.stability, 1e-12)
                assertEquals("understanding must not change difficulty ($ctx)", clear.state.difficulty, confused.state.difficulty, 1e-12)

                // And the advertised ratios hold exactly — EXCEPT where a clamp legitimately binds.
                // The 1-day floor rightly wins over the multiplier (nothing may be due sooner than
                // tomorrow), so a Clear interval just above 1.0 can have its Confused sibling floored
                // while Clear itself is untouched. Only assert the ratio when the whole family is
                // strictly inside [1 day, cap]; the clamped cases are covered by the bounds test.
                val ceiling = FsrsParameters().maximumIntervalDays
                val nothingClamps = confused.intervalDays > MedScheduler.MIN_INTERVAL_DAYS + 1e-9 &&
                    clear.intervalDays < ceiling - 1e-9
                if (nothingClamps) {
                    assertEquals("Partial is exactly x0.90 of Clear ($ctx)",
                        clear.intervalDays * MedScheduler.UNDERSTANDING_PARTIAL_FACTOR, partial.intervalDays, 1e-9)
                    assertEquals("Confused is exactly x0.80 of Clear ($ctx)",
                        clear.intervalDays * MedScheduler.UNDERSTANDING_CONFUSED_FACTOR, confused.intervalDays, 1e-9)
                }
            }
    }

    @Test
    fun `the first graded rating is always capped, and identical however late it happens`() {
        for (m in listOf(MemoryRating.Hard, MemoryRating.Good, MemoryRating.Easy)) for (u in understandings) {
            val seed = MedScheduler.firstStudy(UnderstandingRating.Partial, false).state
            val onTime = MedScheduler.review(seed.stability, seed.difficulty, 0.0, m, u, false, 0)
            assertTrue(
                "first rating must stay inside the calm first-study window ($m/$u) got ${onTime.baseIntervalDays}",
                onTime.baseIntervalDays <= MedScheduler.FIRST_STUDY_MAX_DAYS + 1e-9,
            )
            for (late in listOf(1.0, 5.0, 40.0, 400.0, 5000.0)) {
                val delayed = MedScheduler.review(seed.stability, seed.difficulty, late, m, u, false, 0)
                assertEquals(
                    "a first rating $late days late must schedule exactly like an on-time one ($m/$u)",
                    onTime.intervalDays, delayed.intervalDays, 1e-12,
                )
                assertEquals("and seed the same memory state", onTime.state.stability, delayed.state.stability, 1e-12)
            }
        }
    }

    @Test
    fun `high-yield topics are always reviewed at least as often, never less`() {
        sweep { s, d, t, m, u, _, rn ->
            val normal = MedScheduler.review(s, d, t, m, u, highYield = false, reviewNumber = rn)
            val important = MedScheduler.review(s, d, t, m, u, highYield = true, reviewNumber = rn)
            assertTrue(
                "marking a topic important must never push it FURTHER away (S=$s t=$t $m rn=$rn): " +
                    "${normal.intervalDays} -> ${important.intervalDays}",
                important.intervalDays <= normal.intervalDays + 1e-9,
            )
        }
    }

    @Test
    fun `raising desired retention never lengthens an interval`() {
        val original = MedScheduler.userRetention
        try {
            for (s in stabilities) for (d in difficulties) for (m in listOf(MemoryRating.Good, MemoryRating.Easy)) {
                var previous = Double.MAX_VALUE
                for (r in listOf(0.70, 0.80, 0.85, 0.90, 0.95, 0.99)) {
                    MedScheduler.userRetention = r
                    val got = MedScheduler.review(s, d, 10.0, m, UnderstandingRating.Clear, false, 3).intervalDays
                    assertTrue("wanting to remember MORE must not mean reviewing later (S=$s r=$r)", got <= previous + 1e-9)
                    previous = got
                }
            }
        } finally {
            MedScheduler.userRetention = original
        }
    }

    @Test
    fun `fuzz is deterministic, bounded, and preserves the understanding ratios exactly`() {
        for (unitId in listOf(1L, 7L, 12345L)) for (rn in 0..6) {
            val clear = 20.0
            val partial = clear * MedScheduler.UNDERSTANDING_PARTIAL_FACTOR
            val a = MedScheduler.fuzzedInterval(clear, clear, unitId, rn)
            val b = MedScheduler.fuzzedInterval(clear, clear, unitId, rn)
            assertEquals("same (unit, reviewCount) must always fuzz identically — preview == commit == replay", a, b, 0.0)
            assertTrue("fuzz stays within +/-5%", a in clear * 0.95 - 1e-9..clear * 1.05 + 1e-9)

            // Multiplicative fuzz keeps the transparent x0.9 ratio intact between previews.
            val fp = MedScheduler.fuzzedInterval(partial, clear, unitId, rn)
            assertEquals("understanding ratio survives fuzz", a * MedScheduler.UNDERSTANDING_PARTIAL_FACTOR, fp, 1e-9)
        }
    }

    @Test
    fun `short intervals are never fuzzed, so relearn steps land exactly where the science put them`() {
        for (base in listOf(1.0, 1.5, 2.0, 2.999)) {
            assertEquals("base $base must not be fuzzed", base, MedScheduler.fuzzedInterval(base, base, 3L, 1), 0.0)
        }
    }
}
