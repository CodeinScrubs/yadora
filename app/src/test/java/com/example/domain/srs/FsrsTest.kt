package com.example.domain.srs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure FSRS engine. Where output depends on the (tunable) weights we assert
 * INVARIANTS (ordering, monotonicity, bounds); where it depends only on the curve definition we
 * assert EXACT values. This is deliberate: the weights may be retrained, but the invariants and the
 * forgetting-curve identities must always hold.
 */
class FsrsTest {

    private val p = FsrsParameters()
    private val eps = 1e-9

    @Test fun factor_equals_19_over_81() {
        assertEquals(19.0 / 81.0, Fsrs.FACTOR, eps)
    }

    @Test fun retrievability_is_one_at_zero_elapsed() {
        assertEquals(1.0, Fsrs.retrievability(0.0, 10.0), eps)
    }

    @Test fun retrievability_is_0_9_when_elapsed_equals_stability() {
        assertEquals(0.9, Fsrs.retrievability(10.0, 10.0), eps)
        assertEquals(0.9, Fsrs.retrievability(3.5, 3.5), eps)
    }

    @Test fun retrievability_decreases_with_time_and_stays_in_unit_range() {
        val r1 = Fsrs.retrievability(1.0, 10.0)
        val r2 = Fsrs.retrievability(5.0, 10.0)
        val r3 = Fsrs.retrievability(40.0, 10.0)
        assertTrue(r1 > r2 && r2 > r3)
        assertTrue(r3 > 0.0 && r1 <= 1.0)
    }

    @Test fun interval_equals_stability_at_default_retention() {
        assertEquals(10.0, Fsrs.intervalDays(10.0, 0.9), 1e-6)
        assertEquals(42.0, Fsrs.intervalDays(42.0, 0.9), 1e-6)
    }

    @Test fun interval_increases_with_stability() {
        assertTrue(Fsrs.intervalDays(20.0, 0.9) > Fsrs.intervalDays(10.0, 0.9))
    }

    @Test fun higher_desired_retention_means_shorter_interval() {
        assertTrue(Fsrs.intervalDays(10.0, 0.95) < Fsrs.intervalDays(10.0, 0.90))
    }

    @Test fun initial_stability_is_ordered_again_hard_good_easy() {
        val again = Fsrs.initialState(Grade.Again, p).stability
        val hard = Fsrs.initialState(Grade.Hard, p).stability
        val good = Fsrs.initialState(Grade.Good, p).stability
        val easy = Fsrs.initialState(Grade.Easy, p).stability
        assertTrue(again < hard && hard < good && good < easy)
    }

    @Test fun initial_difficulty_is_in_bounds_and_again_is_harder_than_easy() {
        val again = Fsrs.initialDifficulty(Grade.Again, p)
        val easy = Fsrs.initialDifficulty(Grade.Easy, p)
        assertTrue(again in 1.0..10.0 && easy in 1.0..10.0)
        assertTrue(again > easy)
    }

    @Test fun successful_recall_grows_stability() {
        val s = Fsrs.initialState(Grade.Good, p)
        val next = Fsrs.nextState(s, elapsedDays = s.stability, grade = Grade.Good, p = p)
        assertTrue(next.stability >= s.stability)
    }

    @Test fun lapse_shrinks_stability_below_previous() {
        var s = Fsrs.initialState(Grade.Good, p)
        repeat(3) { s = Fsrs.nextState(s, s.stability, Grade.Good, p) }
        val lapsed = Fsrs.nextState(s, s.stability, Grade.Again, p)
        assertTrue(lapsed.stability < s.stability)
    }

    @Test fun easy_grows_more_than_good_grows_more_than_hard() {
        val s = MemoryState(stability = 5.0, difficulty = 5.0)
        val hard = Fsrs.nextState(s, 5.0, Grade.Hard, p).stability
        val good = Fsrs.nextState(s, 5.0, Grade.Good, p).stability
        val easy = Fsrs.nextState(s, 5.0, Grade.Easy, p).stability
        assertTrue(hard < good && good < easy)
    }

    @Test fun difficulty_stays_in_bounds_under_extreme_sequences() {
        var s = MemoryState(stability = 5.0, difficulty = 9.5)
        repeat(15) { s = Fsrs.nextState(s, s.stability, Grade.Again, p) }
        assertTrue(s.difficulty in 1.0..10.0)
        repeat(30) { s = Fsrs.nextState(s, s.stability, Grade.Easy, p) }
        assertTrue(s.difficulty in 1.0..10.0)
    }

    @Test fun again_raises_difficulty_easy_lowers_it() {
        val s = MemoryState(stability = 5.0, difficulty = 5.0)
        assertTrue(Fsrs.nextState(s, 5.0, Grade.Again, p).difficulty > 5.0)
        assertTrue(Fsrs.nextState(s, 5.0, Grade.Easy, p).difficulty < 5.0)
    }

    // --- reference vectors: exact numeric output pinned to the documented formulas + defaults -----

    @Test fun reference_initial_stability_equals_the_default_weight() {
        // FSRS: initial stability S0(grade) == w[grade-1] with the default parameters.
        val w = FsrsParameters.DEFAULT_WEIGHTS
        assertEquals(w[0], Fsrs.initialState(Grade.Again, p).stability, 1e-12)
        assertEquals(w[1], Fsrs.initialState(Grade.Hard, p).stability, 1e-12)
        assertEquals(w[2], Fsrs.initialState(Grade.Good, p).stability, 1e-12)
        assertEquals(w[3], Fsrs.initialState(Grade.Easy, p).stability, 1e-12)
    }

    @Test fun reference_initial_difficulty_values() {
        // D0(G) = w4 - e^(w5*(G-1)) + 1. For Again: e^0 = 1, so D0 == w4 exactly.
        assertEquals(7.1949, Fsrs.initialDifficulty(Grade.Again, p), 1e-9)
        assertEquals(5.282, Fsrs.initialDifficulty(Grade.Good, p), 0.005)
    }

    @Test fun reference_retrievability_power_curve() {
        // R(t,S) = (1 + (19/81)*t/S)^-0.5. At t = 2S this is (1 + 38/81)^-0.5.
        val expected = Math.pow(1.0 + (19.0 / 81.0) * 2.0, -0.5)
        assertEquals(expected, Fsrs.retrievability(20.0, 10.0), 1e-12)
    }
}
