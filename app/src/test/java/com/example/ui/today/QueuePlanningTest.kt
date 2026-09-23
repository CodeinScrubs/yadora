package com.example.ui.today

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class QueuePlanningTest {

    private val start = 2_000_000_000_000L      // some "start of today"
    private val end = start + 86_400_000L - 1   // end of that day

    // --- TodayBuckets ---

    @Test fun overdue_is_strictly_before_start() {
        assertTrue(TodayBuckets.isOverdue(start - 1, start))
        assertFalse("exactly start is not overdue", TodayBuckets.isOverdue(start, start))
    }

    @Test fun due_today_is_inclusive_of_both_ends() {
        assertTrue(TodayBuckets.isDueToday(start, start, end))
        assertTrue(TodayBuckets.isDueToday(end, start, end))
        assertFalse(TodayBuckets.isDueToday(start - 1, start, end))
        assertFalse(TodayBuckets.isDueToday(end + 1, start, end))
    }

    @Test fun upcoming_and_due_by_end_partition_at_end_of_today() {
        assertTrue(TodayBuckets.isDueByEndOfToday(end, end))
        assertFalse(TodayBuckets.isUpcoming(end, end))
        assertTrue(TodayBuckets.isUpcoming(end + 1, end))
        assertFalse(TodayBuckets.isDueByEndOfToday(end + 1, end))
    }

    // --- OverdueRedistributor ---

    // A backlog that comfortably fits keeps the short, encouraging three-day plan.
    @Test fun perDay_spreads_over_the_minimum_window_when_capacity_allows() {
        val cap = 50
        assertEquals(1, OverdueRedistributor.perDay(0, cap))
        assertEquals(1, OverdueRedistributor.perDay(1, cap))
        assertEquals(1, OverdueRedistributor.perDay(3, cap))
        assertEquals(2, OverdueRedistributor.perDay(4, cap))
        assertEquals(2, OverdueRedistributor.perDay(6, cap))
        assertEquals(3, OverdueRedistributor.perDay(7, cap))
    }

    @Test fun dayOffset_fills_day1_then_day2_then_day3() {
        // 6 items, 2 per day -> [1,1,2,2,3,3]
        val total = 6
        val offsets = (0 until total).map { OverdueRedistributor.dayOffset(it, total, 50) }
        assertEquals(listOf(1, 1, 2, 2, 3, 3), offsets)
    }

    @Test fun dayOffset_never_exceeds_the_planned_window() {
        assertEquals(3, OverdueRedistributor.dayOffset(1000, 6, 50))
    }

    /**
     * THE case the fixed three-day window got wrong: 100 overdue topics for someone who does 10 a
     * day used to become ~34 a day. A plan the user cannot execute teaches them the dates mean
     * nothing, which is worse than leaving the backlog alone.
     */
    @Test fun the_plan_respects_the_users_daily_capacity() {
        assertEquals("100 topics at 10/day needs ten days", 10, OverdueRedistributor.recoveryDays(100, 10))
        assertEquals("and lands ten per day", 10, OverdueRedistributor.perDay(100, 10))

        val offsets = (0 until 100).map { OverdueRedistributor.dayOffset(it, 100, 10) }
        assertEquals("first ten on day one", List(10) { 1 }, offsets.take(10))
        assertEquals("last ten on day ten", List(10) { 10 }, offsets.takeLast(10))
        offsets.groupingBy { it }.eachCount().forEach { (day, count) ->
            assertTrue("day $day must not exceed capacity, got $count", count <= 10)
        }
    }

    @Test fun the_plan_never_stretches_past_the_maximum_horizon() {
        // 1000 overdue at 5/day would be 200 days; pushing memory reviews that far out is not a
        // recovery plan. The window caps and the days simply carry more.
        assertEquals(
            OverdueRedistributor.MAX_RECOVERY_DAYS,
            OverdueRedistributor.recoveryDays(1000, 5),
        )
        assertTrue("still bounded", OverdueRedistributor.dayOffset(999, 1000, 5) <= OverdueRedistributor.MAX_RECOVERY_DAYS)
    }

    @Test fun a_nonsensical_capacity_cannot_break_the_plan() {
        for (cap in listOf(0, -1, Int.MIN_VALUE)) {
            val days = OverdueRedistributor.recoveryDays(20, cap)
            assertTrue("capacity $cap must still yield a usable window, got $days", days in 1..OverdueRedistributor.MAX_RECOVERY_DAYS)
            assertTrue("and a usable per-day", OverdueRedistributor.perDay(20, cap) >= 1)
        }
    }

    @Test fun targetMillis_is_dayOffset_days_ahead_at_0800_local() {
        val now = System.currentTimeMillis()
        val t = OverdueRedistributor.targetMillis(now, 2)
        val c = Calendar.getInstance().apply { timeInMillis = t }
        assertEquals(8, c.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, c.get(Calendar.MINUTE))
        assertTrue("target is in the future", t > now)
    }

    // --- DailyPlan: the daily limit is a limit per DAY, and first ratings are never held back ---

    private val now = 2_000_000_000_000L
    private val day = 86_400_000L

    private fun topic(id: Long, reviews: Int, highYield: Boolean = false, dueDaysAgo: Int = 0) =
        com.example.data.local.entity.StudyUnitEntity(
            id = id, title = "t$id", studyType = "Topic", highYield = highYield,
            state = if (reviews == 0) "New" else "Building", reviewCount = reviews,
            studiedAt = now - 10 * day, nextReviewAt = now - dueDaysAgo * day, modelDueAt = now - dueDaysAgo * day,
        )

    @Test fun reviews_already_done_today_use_up_the_limit() {
        val due = (1L..30L).map { topic(it, reviews = 3) }
        val fresh = DailyPlan.plan(due, reviewsDoneToday = 0, dailyLimit = 10, now = now)
        assertEquals(10, fresh.reviews.size)
        assertEquals(20, fresh.heldBack)
        // The same morning, after doing 10: the second session used to load the next 10.
        val later = DailyPlan.plan(due.drop(10), reviewsDoneToday = 10, dailyLimit = 10, now = now)
        assertEquals("nothing left of today's limit", 0, later.size)
        assertEquals(20, later.heldBack)
        assertTrue("and Today says so", later.limitReached)
        // Part-way through: only what is left.
        assertEquals(4, DailyPlan.plan(due, reviewsDoneToday = 6, dailyLimit = 10, now = now).reviews.size)
    }

    @Test fun first_ratings_are_never_held_back_and_come_first() {
        val due = listOf(topic(1, reviews = 4, highYield = true, dueDaysAgo = 9)) +
            (2L..6L).map { topic(it, reviews = 0) }
        val plan = DailyPlan.plan(due, reviewsDoneToday = 10, dailyLimit = 10, now = now)
        assertEquals("every study waiting to be logged is offered", 5, plan.firstRatings.size)
        assertEquals("even with the limit used up", 0, plan.reviews.size)
        assertEquals(1, plan.heldBack)
        assertEquals("first ratings lead the session", (2L..6L).toSet(), plan.queue.take(5).map { it.id }.toSet())
        assertFalse("not 'limit reached' while something is still offered", plan.limitReached)
    }

    @Test fun the_most_urgent_reviews_get_the_slots() {
        val due = listOf(
            topic(1, reviews = 3, dueDaysAgo = 1),
            topic(2, reviews = 3, highYield = true, dueDaysAgo = 0),
            // 10 days late scores 50; an important topic scores 100 (the overdue term is uncapped on
            // purpose, so at 20 days late the two would tie — nothing neglected can starve forever).
            topic(3, reviews = 3, dueDaysAgo = 10),
        )
        val plan = DailyPlan.plan(due, reviewsDoneToday = 0, dailyLimit = 2, now = now)
        assertEquals("important first, then the most overdue", listOf(2L, 3L), plan.reviews.map { it.id })
        assertEquals(1, plan.heldBack)
    }

    @Test fun review_more_anyway_offers_everything() {
        val due = (1L..30L).map { topic(it, reviews = 2) }
        val plan = DailyPlan.plan(due, reviewsDoneToday = 50, dailyLimit = 10, now = now, ignoreLimit = true)
        assertEquals(30, plan.reviews.size)
        assertEquals(0, plan.heldBack)
    }

    @Test fun caught_up_is_not_limit_reached() {
        val plan = DailyPlan.plan(emptyList(), reviewsDoneToday = 99, dailyLimit = 10, now = now)
        assertEquals(0, plan.size)
        assertFalse(plan.limitReached)
    }

    @Test fun nonsensical_inputs_degrade_to_a_sane_plan() {
        val due = (1L..5L).map { topic(it, reviews = 1) }
        assertEquals("a zero limit reads as the minimum of one", 1, DailyPlan.plan(due, 0, 0, now).reviews.size)
        assertEquals("a negative done count is none", 5, DailyPlan.plan(due, -7, 10, now).reviews.size)
    }

    @Test fun day_bounds_cover_the_local_day() {
        val start = DayBounds.startOf(now)
        val end = DayBounds.endOf(now)
        assertTrue(start <= now && now <= end)
        val c = Calendar.getInstance().apply { timeInMillis = start }
        assertEquals(0, c.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, c.get(Calendar.MINUTE))
        c.timeInMillis = end
        assertEquals(23, c.get(Calendar.HOUR_OF_DAY))
        assertEquals(999, c.get(Calendar.MILLISECOND))
    }
}
