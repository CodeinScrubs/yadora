package com.example.domain.srs

import com.example.domain.model.MemoryRating
import com.example.domain.model.UnderstandingRating
import org.junit.Assert.assertEquals
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

    // --- 1. effectiveReviewNumber day-boundary classification --------------------------------------

    @Test
    fun `same local day first rating is a fresh first study`() {
        val studied = at(2026, 7, 4, 9)
        val rated = at(2026, 7, 4, 22) // same calendar day, hours later
        assertEquals(0, MedScheduler.effectiveReviewNumber(studied, rated, 0))
    }

    @Test
    fun `back-dated first rating is a recall after a gap`() {
        val studied = at(2026, 6, 20, 9)
        val rated = at(2026, 7, 4, 9) // 14 days later
        assertEquals(1, MedScheduler.effectiveReviewNumber(studied, rated, 0))
    }

    @Test
    fun `just before vs just after midnight flips the classification exactly at the day boundary`() {
        val studied = at(2026, 7, 3, 23) // 23:00 on the 3rd
        assertEquals(0, MedScheduler.effectiveReviewNumber(studied, at(2026, 7, 3, 23) + 59 * 60 * 1000L, 0))
        assertEquals(1, MedScheduler.effectiveReviewNumber(studied, at(2026, 7, 4, 0) + 60 * 1000L, 0))
    }

    @Test
    fun `planned-future study rated on its study day is a fresh first study`() {
        // studiedAt in the future relative to nothing — rated ON that day => fresh (not "earlier day").
        val studied = at(2026, 8, 1, 9)
        val rated = at(2026, 8, 1, 20)
        assertEquals(0, MedScheduler.effectiveReviewNumber(studied, rated, 0))
    }

    @Test
    fun `prior review count passes through untouched`() {
        val studied = at(2026, 6, 1, 9)
        val rated = at(2026, 7, 4, 9)
        assertEquals(7, MedScheduler.effectiveReviewNumber(studied, rated, 7))
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
        val first = MedScheduler.review(seed.stability, seed.difficulty, 0.0, MemoryRating.Good, UnderstandingRating.Clear, false, reviewNumber = 0)
        val sameDay = MedScheduler.review(first.state.stability, first.state.difficulty, 0.1, MemoryRating.Easy, UnderstandingRating.Clear, false, reviewNumber = 1)
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
