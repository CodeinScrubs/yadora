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

    @Test fun perDay_spreads_over_three_days_and_is_at_least_one() {
        assertEquals(1, OverdueRedistributor.perDay(0))
        assertEquals(1, OverdueRedistributor.perDay(1))
        assertEquals(1, OverdueRedistributor.perDay(3))
        assertEquals(2, OverdueRedistributor.perDay(4))
        assertEquals(2, OverdueRedistributor.perDay(6))
        assertEquals(3, OverdueRedistributor.perDay(7))
    }

    @Test fun dayOffset_fills_day1_then_day2_then_day3() {
        // 6 items, 2 per day -> [1,1,2,2,3,3]
        val total = 6
        val offsets = (0 until total).map { OverdueRedistributor.dayOffset(it, total) }
        assertEquals(listOf(1, 1, 2, 2, 3, 3), offsets)
    }

    @Test fun dayOffset_never_exceeds_three() {
        assertEquals(3, OverdueRedistributor.dayOffset(1000, 6))
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
