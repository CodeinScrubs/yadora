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
     * Can [unit] be reviewed ahead? Rated, active, not due today (today's plan owns those), and not deferred
     * by the learner: "Not today" and "Spread out" are the learner's own choice, and offering that topic
     * again the same evening (first, even: a deferred topic is overdue on the model's clock) contradicted it.
     * Nor a topic already reviewed (or first rated) today: its recall is near 100%, so reviewing it again buys
     * almost nothing, and FSRS-6 scores a same-day review on its short-term branch, where "Hard" cuts stability
     * by more than half (100 days to 45; py-fsrs 6.3.2 floors that, the pinned 6.3.1 does not). With a small
     * library, weakest-first still reached those topics once the rest ran out.
     * Today shows the button only when some topic passes, so the session is never empty.
     */
    fun isCandidate(unit: StudyUnitEntity, endOfToday: Long): Boolean =
        unit.reviewCount > 0 && unit.deletedAt == null && !unit.archived && unit.deferredUntil == null &&
            unit.nextReviewAt > endOfToday && (unit.lastReviewedAt ?: 0L) < DayBounds.startOf(endOfToday)

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
            .filter { isCandidate(it, endOfToday) }
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
 * Reviews are ordered by [MedScheduler.priorityScore] (Important, lateness, and how much a review now would
 * strengthen the topic), the same score the backlog plan uses.
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

    /** [MedScheduler.priorityScore] for a stored topic, the one ordering the plan and the Spread-out plan share. */
    fun priority(unit: StudyUnitEntity, now: Long): Double = MedScheduler.priorityScore(
        unit.highYield, unit.modelDueAt, now, unit.nextReviewAt, unit.understandingDueAt, queueMemory(unit),
    )

    /** Most urgent first; each score is computed once. Ties go to the earlier date, then the lower id. */
    fun byPriority(units: List<StudyUnitEntity>, now: Long): List<StudyUnitEntity> = units
        .map { it to priority(it, now) }
        .sortedWith(
            compareByDescending<Pair<StudyUnitEntity, Double>> { it.second }
                .thenBy { it.first.nextReviewAt }.thenBy { it.first.id },
        )
        .map { it.first }

    /** The memory state the review value reads; null for an unrated topic, which is ordered by lateness alone. */
    private fun queueMemory(unit: StudyUnitEntity): MedScheduler.QueueMemory? {
        val last = unit.lastReviewedAt ?: return null
        if (unit.reviewCount == 0) return null
        return MedScheduler.QueueMemory(
            unit.stability, unit.difficulty, last, MedScheduler.MemoryModel.of(unit.memoryModel), unit.parameterSetId,
        )
    }

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
        val ordered = byPriority(due, now)
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

    /**
     * Progress → Calendar Plan: whether a topic belongs to forecast day [dayIndex] (0 = today, which also holds every
     * overdue topic), given that day's first millisecond and the next day's. Half-open, like [isDueToday]: midnight
     * starts the NEXT day. The forecast tested `<= nextDayStart`, so a topic due at exactly 00:00 tomorrow was listed
     * under Today and missing from Tomorrow while Today itself said nothing was left (an outside audit, 2026-09-30).
     */
    fun isInForecastDay(nextReviewAt: Long, dayIndex: Int, dayStart: Long, nextDayStart: Long): Boolean =
        if (dayIndex == 0) nextReviewAt < nextDayStart else nextReviewAt >= dayStart && nextReviewAt < nextDayStart
}

/**
 * Pure planning math for the "recover overdue topics over 3 calm days" redistribution. Kept separate
 * from the DB write so the spread (how many per day, which day, what target time) can be tested.
 */
object OverdueRedistributor {
    /**
     * What the recovery plan spreads: overdue REVIEWS. A never-rated topic is left due: its first rating logs a
     * study that already happened and its schedule counts from that rating, so the daily plan offers it first and
     * never holds it back ([DailyPlan]). Spreading it moved that anchor days past the study (found on the emulator,
     * 2026-09-29: two first ratings deferred to the third day), and the card counted it among "reviews waiting".
     */
    fun spreadable(overdue: List<StudyUnitEntity>): List<StudyUnitEntity> = overdue.filterNot { DailyPlan.isFirstRating(it) }

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

    /**
     * Whether Today offers the recovery plan ("You were away" + Spread out): only for a backlog larger than one
     * day's limit. A smaller one is cleared by the daily plan itself, most urgent first, today or (once today's
     * limit is done) tomorrow; spreading it would only move reviews the plan could give sooner. The card used to
     * appear for ANY overdue topic: on the owner's Samsung, after twelve reviews that day, it said "You were away"
     * and offered to spread one review over three days.
     */
    fun offersRecovery(overdueCount: Int, dailyCapacity: Int): Boolean = overdueCount > dailyCapacity.coerceAtLeast(1)

    /** How many items land on each recovery day so [total] items fit in the planned window (min 1). */
    fun perDay(total: Int, dailyCapacity: Int): Int =
        Math.ceil(total / recoveryDays(total, dailyCapacity).toDouble()).toInt().coerceAtLeast(1)

    /** Recovery day (1..recoveryDays) for the item at [index] in a priority-ordered list of [total]. */
    fun dayOffset(index: Int, total: Int, dailyCapacity: Int): Int =
        (index / perDay(total, dailyCapacity)).coerceAtMost(recoveryDays(total, dailyCapacity) - 1) + 1

    /**
     * How many of a priority-ordered backlog of [total] stay due TODAY: what is left of today's limit after the reviews
     * already done and the reviews due today anyway. The plan used to start tomorrow whatever the hour, so a learner who
     * pressed it before studying got nothing from the backlog today, a day of capacity lost, while the card said "let's
     * recover the important ones first" (two outside audits, 2026-09-30). Pressed after the day's limit is used up, it
     * keeps none and starts tomorrow, as before.
     */
    fun keptToday(total: Int, dailyCapacity: Int, doneToday: Int, dueTodayReviews: Int): Int =
        (dailyCapacity.coerceAtLeast(1) - doneToday.coerceAtLeast(0) - dueTodayReviews.coerceAtLeast(0))
            .coerceIn(0, total.coerceAtLeast(0))

    /**
     * Each backlog item's day, in priority order: 0 = the first [kept] stay due today and are not deferred at all; the
     * rest are spread from tomorrow exactly as [dayOffset] spreads a backlog of their size.
     */
    fun dayOffsets(total: Int, dailyCapacity: Int, kept: Int): List<Int> {
        val keep = kept.coerceIn(0, total.coerceAtLeast(0))
        val rest = total - keep
        return List(total.coerceAtLeast(0)) { i -> if (i < keep) 0 else dayOffset(i - keep, rest, dailyCapacity) }
    }

    /** The days the spread part of the plan actually uses (0 when nothing is spread): what the card announces. */
    fun daysUsed(total: Int, dailyCapacity: Int, kept: Int): Int = dayOffsets(total, dailyCapacity, kept).maxOrNull() ?: 0

    /**
     * The whole recovery plan as the rows to write: every overdue REVIEW ([spreadable]), most urgent first
     * ([DailyPlan.byPriority]); the first [keptToday] stay as they are, due today, and the rest move to 08:00 on their
     * day ([dayOffsets]) as a USER deferral (v5: deferredUntil set, modelDueAt untouched, so the model's own date is
     * never laundered). The Today screen's "Spread out" writes exactly these rows, and OwnerYearSoakTest drives the
     * same function, so a test never keeps its own copy of the plan.
     *
     * @param overdue active topics due before today (first ratings among them are left due).
     * @param dueTodayReviews reviews due today anyway (first ratings excluded), which the kept share must leave room for.
     * @param doneToday reviews already done today, which the daily limit counts.
     */
    fun deferrals(
        overdue: List<StudyUnitEntity>,
        dueTodayReviews: Int,
        doneToday: Int,
        dailyCapacity: Int,
        now: Long,
    ): List<StudyUnitEntity> {
        val prioritized = DailyPlan.byPriority(spreadable(overdue), now)
        val total = prioritized.size
        if (total == 0) return emptyList()
        val kept = keptToday(total, dailyCapacity, doneToday, dueTodayReviews)
        val offsets = dayOffsets(total, dailyCapacity, kept)
        return prioritized.mapIndexedNotNull { index, unit ->
            if (offsets[index] == 0) return@mapIndexedNotNull null // stays due today, untouched
            val target = targetMillis(now, offsets[index])
            unit.copy(nextReviewAt = target, deferredUntil = target, updatedAt = now)
        }
    }

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
