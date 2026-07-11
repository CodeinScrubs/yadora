package com.example.domain.srs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the single source-of-truth queue/redistribution ordering score. */
class PriorityScoreTest {

    private val now = 1_000_000_000_000L
    private val day = 86_400_000L

    @Test fun high_yield_dominates_ordinary_items() {
        val hy = MedScheduler.priorityScore(highYield = true, state = "New", lapseCount = 0, nextReviewAt = now, now = now)
        val normal = MedScheduler.priorityScore(highYield = false, state = "Building", lapseCount = 3, nextReviewAt = now - 2 * day, now = now)
        assertTrue("high-yield ($hy) outranks a non-high-yield item ($normal)", hy > normal)
    }

    @Test fun state_weights_are_ordered_relearn_gt_learning_gt_building_gt_other() {
        fun s(state: String) = MedScheduler.priorityScore(false, state, 0, now, now)
        assertTrue(s("NeedsRelearn") > s("Learning"))
        assertTrue(s("Learning") > s("Building"))
        assertTrue(s("Building") > s("Strong"))
        assertEquals("unknown/Strong state adds nothing", 0.0, s("Strong"), 1e-9)
    }

    @Test fun more_lapses_raise_the_score() {
        val few = MedScheduler.priorityScore(false, "New", 1, now, now)
        val many = MedScheduler.priorityScore(false, "New", 5, now, now)
        assertEquals("each lapse adds 10", 40.0, many - few, 1e-9)
    }

    @Test fun more_overdue_raises_the_score_but_future_due_does_not() {
        val onTime = MedScheduler.priorityScore(false, "New", 0, now, now)
        val overdue5 = MedScheduler.priorityScore(false, "New", 0, now - 5 * day, now)
        val future = MedScheduler.priorityScore(false, "New", 0, now + 5 * day, now)
        assertEquals("5 days overdue adds 25", 25.0, overdue5 - onTime, 1e-9)
        assertEquals("not-yet-due items get no overdue bonus", 0.0, future, 1e-9)
    }

    @Test fun score_is_pure_and_deterministic() {
        val a = MedScheduler.priorityScore(true, "Learning", 2, now - day, now)
        val b = MedScheduler.priorityScore(true, "Learning", 2, now - day, now)
        assertEquals(a, b, 0.0)
    }
}
