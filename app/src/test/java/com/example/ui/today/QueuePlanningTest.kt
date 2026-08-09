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
}
