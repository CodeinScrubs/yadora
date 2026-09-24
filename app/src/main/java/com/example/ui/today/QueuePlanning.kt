package com.example.ui.today

import com.example.data.local.entity.StudyUnitEntity
import com.example.domain.srs.MedScheduler
import java.util.Calendar

/** Local-day boundaries, shared by everything that asks "what is due today". */
object DayBounds {
    fun startOf(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun endOf(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
    }.timeInMillis
}

/**
 * REVIEW AHEAD: topics that are not due yet, the ones the model thinks are weakest first.
 *
 * For spare time and, above all, the weeks before an exam. The twin simulation in tools/pilot/simulate.py
 * found the one case where a learner without a schedule beats Yadora on a single test: an ANNOUNCED exam,
 * when they save their review time for a final push. With the same final push -- the same topics per day
 * in the last four weeks -- a Yadora learner who spends it weakest-first comes out ahead again, and stays
 * ahead all year. Before this, reviewing ahead meant opening topics one by one from the Library.
 *
 * It reads NO exam date and compresses no interval: every review it offers is an ordinary early review
 * that FSRS scores honestly (a high predicted recall earns a small stability gain), and the next interval
 * is computed from it like any other. Topics due today are left to today's plan, and never-rated topics
 * are left out: their first rating belongs on the day they were studied.
 */
object ReviewAhead {
    /** Topics per review-ahead session. The learner can start another. */
    const val SESSION_SIZE = 20

    /**
     * @param active non-archived, non-deleted topics.
     * @param recall the model's current recall probability for a topic, or null when it cannot be computed
     *   (such a topic is left out rather than guessed at).
     */
    fun order(
        active: List<StudyUnitEntity>,
        now: Long,
        recall: (StudyUnitEntity) -> Double?,
        limit: Int = SESSION_SIZE,
    ): List<StudyUnitEntity> {
        val endOfToday = DayBounds.endOf(now)
        return active.asSequence()
            .filter { it.reviewCount > 0 && it.deletedAt == null && !it.archived && it.nextReviewAt > endOfToday }
            .mapNotNull { u -> recall(u)?.takeIf { it.isFinite() }?.let { u to it } }
            .sortedWith(compareBy<Pair<StudyUnitEntity, Double>>({ it.second }, { it.first.nextReviewAt }, { it.first.id }))
            .take(limit.coerceAtLeast(0))
            .map { it.first }
            .toList()
    }
}

/**
 * TODAY'S PLAN: which due topics today's session offers.
 *
 * The daily limit is a limit per DAY. It used to be applied per session: the queue took the top N due
 * topics, so finishing N and starting again loaded the next N, while Today said the rest were "held for
 * later by your daily limit". Now the reviews already done today count against it, and once it is used
 * up Today says so and offers "review more anyway" instead of quietly serving more.
 *
 * FIRST RATINGS ARE NEVER HELD BACK. A topic that has never been rated is waiting for the learner to log
 * a study that already happened, and its schedule is counted from the moment it is rated. Holding it
 * behind the limit would push that anchor to another day, so every first rating is offered, first.
 *
 * Reviews are ordered by [MedScheduler.priorityScore], the same score the backlog plan uses.
 */
object DailyPlan {

    data class Plan(
        /** Never-rated topics: logging a study that already happened. Not counted against the limit. */
        val firstRatings: List<StudyUnitEntity>,
        /** Due reviews that fit in what is left of today's limit, most urgent first. */
        val reviews: List<StudyUnitEntity>,
        /** Due reviews today's limit holds for later. */
        val heldBack: Int,
        /** Reviews already done today (first ratings excluded), which the limit counts. */
        val doneToday: Int,
    ) {
        /** The session order: first ratings, then reviews. */
        val queue: List<StudyUnitEntity> get() = firstRatings + reviews
        val size: Int get() = firstRatings.size + reviews.size

        /** Today's limit is used up while reviews are still waiting. */
        val limitReached: Boolean get() = size == 0 && heldBack > 0
    }

    fun isFirstRating(unit: StudyUnitEntity): Boolean = unit.reviewCount == 0

    /**
     * @param due every active topic due by the end of today.
     * @param reviewsDoneToday reviews (not first ratings) already committed today.
     * @param ignoreLimit the learner chose "review more anyway": every due review is offered.
     */
    fun plan(
        due: List<StudyUnitEntity>,
        reviewsDoneToday: Int,
        dailyLimit: Int,
        now: Long,
        ignoreLimit: Boolean = false,
    ): Plan {
        val ordered = due.sortedWith(
            compareByDescending<StudyUnitEntity> {
                MedScheduler.priorityScore(
                    it.highYield, it.state, it.lapseCount, it.modelDueAt, now, it.nextReviewAt, it.understandingDueAt,
                )
            }.thenBy { it.nextReviewAt }.thenBy { it.id },
        )
        val (firstRatings, reviews) = ordered.partition { isFirstRating(it) }
        val done = reviewsDoneToday.coerceAtLeast(0)
        val allowance = if (ignoreLimit) reviews.size else (MedScheduler.safeDailyLimit(dailyLimit) - done).coerceAtLeast(0)
        val offered = reviews.take(allowance)
        return Plan(firstRatings, offered, reviews.size - offered.size, done)
    }
}

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
