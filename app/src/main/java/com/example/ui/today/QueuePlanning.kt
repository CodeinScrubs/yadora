package com.example.ui.today

import java.util.Calendar

/**
 * Pure due-bucket predicates for the Today screen. Extracted from TodayViewModel's flow lambdas so
 * the overdue / due-today / upcoming boundaries are unit-testable and can't silently drift.
 */
object TodayBuckets {
    /** Due strictly before today's start = overdue. */
    fun isOverdue(nextReviewAt: Long, startOfToday: Long): Boolean = nextReviewAt < startOfToday

    /** Due at some point within today (inclusive of both ends). */
    fun isDueToday(nextReviewAt: Long, startOfToday: Long, endOfToday: Long): Boolean =
        nextReviewAt in startOfToday..endOfToday

    /** Anything due by the end of today (overdue + due-today) — the "load these now" set. */
    fun isDueByEndOfToday(nextReviewAt: Long, endOfToday: Long): Boolean = nextReviewAt <= endOfToday

    /** Not yet due today = upcoming. */
    fun isUpcoming(nextReviewAt: Long, endOfToday: Long): Boolean = nextReviewAt > endOfToday
}

/**
 * Pure planning math for the "recover overdue topics over 3 calm days" redistribution. Kept separate
 * from the DB write so the spread (how many per day, which day, what target time) can be tested.
 */
object OverdueRedistributor {
    const val RECOVERY_DAYS = 3

    /** How many items land on each recovery day so [total] items fit in RECOVERY_DAYS (min 1). */
    fun perDay(total: Int): Int =
        Math.ceil(total / RECOVERY_DAYS.toDouble()).toInt().coerceAtLeast(1)

    /** Recovery day (1..RECOVERY_DAYS) for the item at [index] in a priority-ordered list of [total]. */
    fun dayOffset(index: Int, total: Int): Int =
        (index / perDay(total)).coerceAtMost(RECOVERY_DAYS - 1) + 1

    /** Absolute due time: [dayOffset] days after [now], pinned to 08:00 local. */
    fun targetMillis(now: Long, dayOffset: Int): Long = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, dayOffset)
        set(Calendar.HOUR_OF_DAY, 8)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
