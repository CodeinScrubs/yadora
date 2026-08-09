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
    /** Shortest plan: even a small backlog gets at least a couple of days of breathing room. */
    const val MIN_RECOVERY_DAYS = 3

    /**
     * Longest plan. A backlog that cannot fit in two weeks at the user's own pace is a signal that
     * the pace is wrong, not a reason to push memory reviews months out — past this the last days
     * simply carry more, and the honest answer is to lower the workload or accept the overload.
     */
    const val MAX_RECOVERY_DAYS = 14

    /**
     * How many days the recovery plan spans, given how much the user says they can do per day.
     *
     * The window used to be a hard three days regardless of capacity, which made the feature actively
     * dishonest at scale: 100 overdue topics became ~34 per day for someone whose daily limit is 10.
     * A plan the user cannot execute is worse than no plan, because they stop trusting the dates.
     */
    fun recoveryDays(total: Int, dailyCapacity: Int): Int {
        val cap = dailyCapacity.coerceAtLeast(1)
        val needed = Math.ceil(total.coerceAtLeast(0) / cap.toDouble()).toInt()
        return needed.coerceIn(MIN_RECOVERY_DAYS, MAX_RECOVERY_DAYS)
    }

    /** How many items land on each recovery day so [total] items fit in the planned window (min 1). */
    fun perDay(total: Int, dailyCapacity: Int): Int =
        Math.ceil(total / recoveryDays(total, dailyCapacity).toDouble()).toInt().coerceAtLeast(1)

    /** Recovery day (1..recoveryDays) for the item at [index] in a priority-ordered list of [total]. */
    fun dayOffset(index: Int, total: Int, dailyCapacity: Int): Int =
        (index / perDay(total, dailyCapacity)).coerceAtMost(recoveryDays(total, dailyCapacity) - 1) + 1

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
