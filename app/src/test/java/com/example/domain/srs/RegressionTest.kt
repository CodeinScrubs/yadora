package com.example.domain.srs

import com.example.domain.model.MemoryRating
import com.example.domain.model.UnderstandingRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Regression tests pinning the three areas where earlier review rounds found REAL bugs, so a future
 * refactor can't silently reintroduce them:
 *  1. [MedScheduler.effectiveReviewNumber] day-boundary classification (fresh vs back-dated first rating)
 *  2. the FSRS-5 same-day (short-term) stability branch
 *  3. replay==live consistency of the shared seed (the full DB replay is covered in ReplayEqualsLiveTest)
 */
class RegressionTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int): Long =
        Calendar.getInstance().apply { clear(); set(year, month - 1, day, hour, 0, 0) }.timeInMillis

    // --- 0. POLICY YADORA-3: the first-study prior is damped toward neutral -----------------------

    /**
     * A rating given moments after studying measures current FLUENCY, not durable memory (the
     * judgment-of-learning illusion). FSRS's S₀(Easy) ≈ 15.7 d was fitted on genuine *delayed*
     * recall, so taking it at face value from an immediate self-rating claims evidence that has not
     * been collected — and it is the value that decides how the second review gets interpreted, so
     * the error propagates. YADORA-3 shrinks it toward the neutral Good prior.
     */
    @Test
    fun `the first-study prior is shrunk toward neutral but keeps the user's ordering`() {
        val p = FsrsParameters()
        val w = FsrsParameters.DEFAULT_WEIGHTS
        fun damped(g: Grade) = MedScheduler.firstRatingState(g, p, damp = true).stability

        // Geometric mean with the Good prior: preserves order, halves the reach of the boldest answer.
        assertEquals("Easy is damped", kotlin.math.sqrt(w[3] * w[2]), damped(Grade.Easy), 1e-9)
        assertEquals("Hard is damped", kotlin.math.sqrt(w[1] * w[2]), damped(Grade.Hard), 1e-9)
        assertEquals("Good is the neutral anchor and must not move", w[2], damped(Grade.Good), 1e-9)

        // Hand anchors, so a future refactor cannot quietly change the numbers.
        assertEquals("Easy ~ 7.06d, not 15.69d", 7.056, damped(Grade.Easy), 1e-3)
        assertEquals("Hard ~ 1.94d", 1.938, damped(Grade.Hard), 1e-3)

        assertTrue("ordering the user expressed is preserved", damped(Grade.Hard) < damped(Grade.Good))
        assertTrue("ordering the user expressed is preserved", damped(Grade.Good) < damped(Grade.Easy))
        assertTrue("and Easy is strictly below its undamped value", damped(Grade.Easy) < w[3])
    }

    @Test
    fun `damping never lets a first rating exceed the calm first-study window`() {
        val seed = MedScheduler.firstStudy(UnderstandingRating.Partial, highYield = false).state
        for (m in listOf(MemoryRating.Hard, MemoryRating.Good, MemoryRating.Easy)) {
            val o = MedScheduler.review(
                seed.stability, seed.difficulty, elapsedDays = 0.0,
                memoryRating = m, understanding = UnderstandingRating.Clear,
                highYield = false, reviewNumber = 0,
                model = MedScheduler.CURRENT_MODEL,
            )
            assertTrue("$m first rating stays within the cap", o.baseIntervalDays <= MedScheduler.FIRST_STUDY_MAX_DAYS + 1e-9)
            assertTrue("$m first rating is still at least a day out", o.intervalDays >= MedScheduler.MIN_INTERVAL_DAYS - 1e-9)
        }
    }

    /**
     * Replay must reproduce the schedule the user ACTUALLY had, not re-decide it under today's rules.
     * A log written under YADORA-1/2 therefore keeps the undamped seed when its history is replayed.
     */
    @Test
    fun `replay of an older policy keeps the undamped seed`() {
        assertTrue("YADORA-3 damps", MedScheduler.dampsFirstStudyPrior("YADORA-3"))
        assertTrue("unknown/newer policies damp by default", MedScheduler.dampsFirstStudyPrior("YADORA-9"))
        assertFalse("YADORA-1 predates damping", MedScheduler.dampsFirstStudyPrior("YADORA-1"))
        assertFalse("YADORA-2 predates damping", MedScheduler.dampsFirstStudyPrior("YADORA-2"))

        val p = FsrsParameters()
        assertEquals(
            "an old log replays from the raw FSRS prior",
            FsrsParameters.DEFAULT_WEIGHTS[3],
            MedScheduler.firstRatingState(Grade.Easy, p, damp = false).stability, 1e-12,
        )
    }

    // --- 1. The first graded rating is always review #0 (no overdue cliff) -------------------------

    @Test
    fun `the first graded rating is review zero however late it happens`() {
        assertEquals(0, MedScheduler.effectiveReviewNumber(0))
    }

    @Test
    fun `prior review count passes through untouched`() {
        assertEquals(7, MedScheduler.effectiveReviewNumber(7))
    }

    /**
     * THE overdue regression. Before POLICY YADORA-2, a first rating that happened on a later day than
     * the study date was treated as a recall against the neutral placeholder state AddUnit seeds
     * (S≈1.18/D≈6.49 — never chosen by the user, never measured). That both skipped the first-study cap
     * and, because the placeholder stability is tiny, exploded the interval the longer the topic sat:
     * "Easy" gave ~5 days when rated on the study day but ~43 days at 5 days late and ~108 days at a
     * month late. The user experienced that discontinuity as "the algorithm goes wrong when a topic
     * goes overdue". The first rating must now be identical regardless of when it happens.
     */
    @Test
    fun `a first rating gives the same interval whether it is on time or badly overdue`() {
        val seed = MedScheduler.firstStudy(UnderstandingRating.Partial, highYield = false).state
        fun firstRatingInterval(daysLate: Double): Double = MedScheduler.review(
            stability = seed.stability,
            difficulty = seed.difficulty,
            elapsedDays = daysLate,
            memoryRating = MemoryRating.Easy,
            understanding = UnderstandingRating.Clear,
            highYield = false,
            reviewNumber = MedScheduler.effectiveReviewNumber(0),
            model = MedScheduler.CURRENT_MODEL,
        ).intervalDays

        val onTime = firstRatingInterval(0.0)
        for (late in listOf(1.0, 5.0, 14.0, 30.0, 365.0)) {
            assertEquals(
                "first rating $late days late must schedule like an on-time one",
                onTime, firstRatingInterval(late), 1e-9,
            )
        }
        // …and it must still respect the calm first-study window rather than shooting months out.
        assertTrue("first rating stayed within the first-study cap", onTime <= MedScheduler.FIRST_STUDY_MAX_DAYS + 1e-9)
    }

    @Test
    fun `an overdue later review stays continuous as lateness grows`() {
        // For REAL reviews (reviewNumber >= 1) lateness legitimately increases the interval, but it must
        // move smoothly — no jump discontinuity of the kind the first-rating bug produced.
        fun interval(daysLate: Double): Double = MedScheduler.review(
            stability = 15.69105,
            difficulty = 3.2245,
            elapsedDays = daysLate,
            memoryRating = MemoryRating.Good,
            understanding = UnderstandingRating.Clear,
            highYield = false,
            reviewNumber = 1,
            model = MedScheduler.CURRENT_MODEL,
        ).intervalDays

        var previous = interval(1.0)
        var step = 2.0
        while (step <= 60.0) {
            val current = interval(step)
            assertTrue("interval must not shrink as a review gets later ($step d)", current >= previous - 1e-9)
            assertTrue("interval must not more than double for one extra day late ($step d)", current <= previous * 2.0)
            previous = current
            step += 1.0
        }
    }

    // --- 2. FSRS-5 same-day (short-term) branch ----------------------------------------------------

    @Test
    fun `same-day Good review grows stability instead of being a no-op`() {
        val before = MemoryState(stability = 5.0, difficulty = 5.0)
        val after = Fsrs.nextState(before, elapsedDays = 0.2, grade = Grade.Good)
        assertTrue("same-day Good must grow stability (was ${before.stability}, got ${after.stability})",
            after.stability > before.stability)
        // And it must be the short-term formula: S' = S · e^(w17·(G−3+w18))
        val w = FsrsParameters().weights
        val expected = 5.0 * Math.exp(w[17] * (3 - 3 + w[18]))
        assertEquals(expected, after.stability, 1e-9)
    }

    @Test
    fun `same-day Easy grows more than Good, and Again shrinks`() {
        val before = MemoryState(stability = 5.0, difficulty = 5.0)
        val good = Fsrs.nextState(before, 0.2, Grade.Good).stability
        val easy = Fsrs.nextState(before, 0.2, Grade.Easy).stability
        val again = Fsrs.nextState(before, 0.2, Grade.Again).stability
        assertTrue("easy($easy) > good($good)", easy > good)
        assertTrue("again($again) < before(5.0)", again < 5.0)
    }

    @Test
    fun `same-day review changes the scheduled interval`() {
        // The original bug: same-day re-review left the schedule unchanged (cramming was a no-op).
        val seed = MedScheduler.firstStudy(UnderstandingRating.Partial, false).state
        val first = MedScheduler.review(seed.stability, seed.difficulty, 0.0, MemoryRating.Good, UnderstandingRating.Clear, false, reviewNumber = 0, model = MedScheduler.CURRENT_MODEL)
        val sameDay = MedScheduler.review(first.state.stability, first.state.difficulty, 0.1, MemoryRating.Easy, UnderstandingRating.Clear, false, reviewNumber = 1, model = MedScheduler.CURRENT_MODEL)
        assertTrue("same-day Easy must lengthen the interval (${sameDay.intervalDays} vs ${first.intervalDays})",
            sameDay.intervalDays > first.intervalDays)
    }

    // --- 3. Live/replay share one seed ---------------------------------------------------------------

    @Test
    fun `the AddUnit seed is deterministic so live and replay start identically`() {
        val a = MedScheduler.firstStudy(UnderstandingRating.Partial, false).state
        val b = MedScheduler.firstStudy(UnderstandingRating.Partial, false).state
        assertEquals(a.stability, b.stability, 0.0)
        assertEquals(a.difficulty, b.difficulty, 0.0)
    }

    // --- 4. Interval fuzz invariants -----------------------------------------------------------------

    @Test
    fun `fuzz is deterministic and bounded to plus-minus five percent`() {
        val a = MedScheduler.fuzzedInterval(20.0, 20.0, unitId = 42L, reviewCount = 3)
        val b = MedScheduler.fuzzedInterval(20.0, 20.0, unitId = 42L, reviewCount = 3)
        assertEquals("same (unit, reviewCount) must fuzz identically — preview==commit==replay", a, b, 0.0)
        assertTrue("fuzz must stay within ±5% (got $a for 20.0)", a in 19.0..21.0)
    }

    @Test
    fun `short intervals are never fuzzed`() {
        // Relearn-tomorrow and tight early reviews must stay exactly where the science put them.
        assertEquals(1.0, MedScheduler.fuzzedInterval(1.0, 1.0, 7L, 1), 0.0)
        assertEquals(2.9, MedScheduler.fuzzedInterval(2.9, 2.9, 7L, 1), 0.0)
    }

    @Test
    fun `fuzz preserves the exact understanding-multiplier ratios`() {
        // Multiplicative fuzz with one seed per (unit, reviewNumber): Partial stays exactly 0.9×Clear.
        val clear = MedScheduler.fuzzedInterval(20.0, 20.0, 5L, 2)
        val partial = MedScheduler.fuzzedInterval(20.0 * 0.9, 20.0, 5L, 2)
        assertEquals("partial must remain exactly 90% of clear after fuzz", clear * 0.9, partial, 1e-9)
    }

    @Test
    fun `fuzz eligibility comes from the BASE interval so ratios survive the 3-day boundary`() {
        // Base = 3.2d: Clear final = 3.2, Partial final = 2.88. If eligibility were decided from the
        // FINAL interval, Clear would fuzz and Partial wouldn't — breaking the exact 0.9 ratio.
        val clear = MedScheduler.fuzzedInterval(3.2, 3.2, 11L, 2)
        val partial = MedScheduler.fuzzedInterval(3.2 * 0.9, 3.2, 11L, 2)
        assertEquals("ratio must survive the boundary", clear * 0.9, partial, 1e-9)
        // And below the threshold, neither fuzzes.
        assertEquals(2.9, MedScheduler.fuzzedInterval(2.9, 2.9, 11L, 2), 0.0)
        assertEquals(2.9 * 0.9, MedScheduler.fuzzedInterval(2.9 * 0.9, 2.9, 11L, 2), 0.0)
    }

    @Test
    fun `fuzz actually varies across units so cohorts de-clump`() {
        val intervals = (1L..50L).map { MedScheduler.fuzzedInterval(20.0, 20.0, it, 1) }.distinct()
        assertTrue("50 units with the same raw interval should land on many different values (got ${intervals.size})",
            intervals.size > 10)
        // And consecutive reviews of the SAME unit get different factors (seeded by prior review
        // count, which is strictly increasing — unlike effectiveReviewNumber, which repeats 1 for a
        // back-dated topic's first two reviews and would have made them share a factor).
        assertTrue("consecutive reviews of one unit must not share a fuzz factor",
            MedScheduler.fuzzedInterval(20.0, 20.0, 9L, 0) != MedScheduler.fuzzedInterval(20.0, 20.0, 9L, 1))
    }
}
