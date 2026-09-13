package com.example.domain.srs

import com.example.domain.model.MemoryRating
import com.example.domain.model.StudyState
import com.example.domain.model.UnderstandingRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the MedReview scheduling adaptation on top of FSRS. */
class MedSchedulerTest {

    @Test fun first_study_intervals_are_calm_one_to_three_days_and_ordered() {
        val clear = MedScheduler.firstStudy(UnderstandingRating.Clear).intervalDays
        val partial = MedScheduler.firstStudy(UnderstandingRating.Partial).intervalDays
        val confused = MedScheduler.firstStudy(UnderstandingRating.Confused).intervalDays

        for (i in listOf(clear, partial, confused)) {
            assertTrue("interval $i must be in [1,3]", i in 1.0..3.0)
        }
        assertTrue("clear($clear) >= partial($partial) >= confused($confused)", clear >= partial && partial >= confused)
        assertEquals("Confused is floored to the next day", 1.0, confused, 1e-9)
    }

    @Test fun repeated_good_reviews_expand_the_interval() {
        var outcome = MedScheduler.firstStudy(UnderstandingRating.Clear)
        val firstInterval = outcome.intervalDays
        var lastInterval = firstInterval
        var state = outcome.state
        // Intervals must never shrink on a Good review, and must grow substantially overall.
        // (They eventually plateau at the maximum-interval ceiling, so we assert >= per-step.)
        repeat(4) {
            outcome = MedScheduler.review(
                stability = state.stability,
                difficulty = state.difficulty,
                elapsedDays = lastInterval, // reviewed exactly on time
                memoryRating = MemoryRating.Good,
                understanding = UnderstandingRating.Clear,
                highYield = false,
                model = MedScheduler.CURRENT_MODEL,
            )
            assertTrue("interval must not shrink on Good: ${outcome.intervalDays} >= $lastInterval", outcome.intervalDays >= lastInterval)
            lastInterval = outcome.intervalDays
            state = outcome.state
        }
        assertTrue("overall growth $lastInterval > $firstInterval", lastInterval > firstInterval)
    }

    @Test fun forgetting_reschedules_for_the_next_day_and_drops_stability() {
        var state = MedScheduler.firstStudy(UnderstandingRating.Clear).state
        var interval = 3.0
        repeat(3) {
            val o = MedScheduler.review(state.stability, state.difficulty, interval, MemoryRating.Good, UnderstandingRating.Clear, false, model = MedScheduler.CURRENT_MODEL)
            state = o.state; interval = o.intervalDays
        }
        val stabilityBefore = state.stability
        val forgot = MedScheduler.review(state.stability, state.difficulty, interval, MemoryRating.Forgot, UnderstandingRating.Confused, false, model = MedScheduler.CURRENT_MODEL)

        assertEquals("Forgot => relearn tomorrow", 1.0, forgot.intervalDays, 1e-9)
        assertTrue("stability must drop after a lapse", forgot.state.stability < stabilityBefore)
    }

    @Test fun high_yield_items_are_scheduled_sooner_than_normal_items() {
        val s = MemoryState(stability = 20.0, difficulty = 5.0)
        val normal = MedScheduler.review(s.stability, s.difficulty, 20.0, MemoryRating.Good, UnderstandingRating.Clear, highYield = false, model = MedScheduler.CURRENT_MODEL)
        val high = MedScheduler.review(s.stability, s.difficulty, 20.0, MemoryRating.Good, UnderstandingRating.Clear, highYield = true, model = MedScheduler.CURRENT_MODEL)
        assertTrue("high-yield ${high.intervalDays} < normal ${normal.intervalDays}", high.intervalDays < normal.intervalDays)
    }

    /**
     * LEGACY (FSRS-5) behaviour. The multiplier is gone from the live path -- FSRS-6 gives
     * understanding its own clock instead -- but frozen replay of pre-migration history still
     * depends on it, so it is pinned against the model that actually used it.
     */
    @Test fun legacy_fsrs5_lower_understanding_schedules_sooner() {
        val s = 20.0; val d = 5.0; val e = 20.0
        val clear = MedScheduler.review(s, d, e, MemoryRating.Good, UnderstandingRating.Clear, false, model = MedScheduler.MemoryModel.FSRS_5).intervalDays
        val partial = MedScheduler.review(s, d, e, MemoryRating.Good, UnderstandingRating.Partial, false, model = MedScheduler.MemoryModel.FSRS_5).intervalDays
        val confused = MedScheduler.review(s, d, e, MemoryRating.Good, UnderstandingRating.Confused, false, model = MedScheduler.MemoryModel.FSRS_5).intervalDays
        assertTrue("clear($clear) > partial($partial) > confused($confused)", clear > partial && partial > confused)
        assertEquals("partial is 90% of clear", clear * 0.90, partial, 1e-6)
        assertEquals("confused is 80% of clear", clear * 0.80, confused, 1e-6)
    }

    @Test fun understanding_does_not_change_the_fsrs_memory_state() {
        // Understanding affects the interval only, never the stored stability/difficulty.
        val s = 12.0; val d = 6.0; val e = 12.0
        val clear = MedScheduler.review(s, d, e, MemoryRating.Good, UnderstandingRating.Clear, false, model = MedScheduler.CURRENT_MODEL).state
        val confused = MedScheduler.review(s, d, e, MemoryRating.Good, UnderstandingRating.Confused, false, model = MedScheduler.CURRENT_MODEL).state
        assertEquals(clear.stability, confused.stability, 1e-12)
        assertEquals(clear.difficulty, confused.difficulty, 1e-12)
    }

    @Test fun preview_interval_exactly_matches_committed_interval() {
        val s = MemoryState(stability = 8.0, difficulty = 6.0)
        val preview = MedScheduler.previewIntervalDays(s.stability, s.difficulty, 8.0, MemoryRating.Hard, UnderstandingRating.Partial, false, model = MedScheduler.CURRENT_MODEL)
        val commit = MedScheduler.review(s.stability, s.difficulty, 8.0, MemoryRating.Hard, UnderstandingRating.Partial, false, model = MedScheduler.CURRENT_MODEL).intervalDays
        assertEquals(commit, preview, 1e-12)
    }

    @Test fun mastery_state_is_derived_from_stability() {
        assertEquals(StudyState.NeedsRelearn, MedScheduler.masteryState(50.0, justForgot = true))
        assertEquals(StudyState.Learning, MedScheduler.masteryState(3.0, justForgot = false))
        assertEquals(StudyState.Building, MedScheduler.masteryState(14.0, justForgot = false))
        assertEquals(StudyState.Strong, MedScheduler.masteryState(40.0, justForgot = false))
    }

    @Test fun first_study_differentiates_ratings_but_stays_in_a_calm_window() {
        // A brand-new topic (reviewNumber = 0) must differentiate by difficulty, but the FIRST review
        // stays in a calm consolidation window — it is NOT pushed weeks out just because it "felt easy"
        // right after studying (that isn't proof of delayed recall).
        val s = 1.0; val d = 5.0; val elapsed = 0.0
        fun iv(r: MemoryRating) = MedScheduler.review(s, d, elapsed, r, UnderstandingRating.Clear, false, reviewNumber = 0, model = MedScheduler.CURRENT_MODEL).intervalDays
        val again = iv(MemoryRating.Forgot)
        val hard = iv(MemoryRating.Hard)
        val good = iv(MemoryRating.Good)
        val easy = iv(MemoryRating.Easy)
        assertEquals("Forgot relearns tomorrow", 1.0, again, 1e-9)
        assertTrue("hard($hard) < good($good) < easy($easy)", hard < good && good < easy)
        assertTrue("Easy first study stays within the calm window", easy <= MedScheduler.FIRST_STUDY_MAX_DAYS + 1e-9)
    }
}
