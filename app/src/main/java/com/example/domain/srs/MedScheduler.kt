package com.example.domain.srs

import com.example.domain.model.MemoryRating
import com.example.domain.model.StudyState
import com.example.domain.model.UnderstandingRating

/**
 * Yadora's scheduling layer on top of the memory models: [Fsrs6] is live, [Fsrs] (FSRS-5) is frozen
 * for replaying old history.
 *
 * Adapts FSRS (designed for Q/A flashcards) to Yadora's study-review model:
 *
 *  1. The FIRST event is "I just studied this topic". AddUnit seeds a neutral placeholder state via
 *     [firstStudy] and makes the topic due ON its study date. The first rating happens on the review
 *     screen (review #0): it seeds the real memory state from the rating and schedules the first
 *     check-in, capped by [FIRST_STUDY_MAX_DAYS].
 *  2. Every later [review] takes a memory rating (Forgot/Hard/Good/Easy) AND an understanding rating.
 *     - The memory model sees ONLY the memory rating: stability and difficulty never depend on
 *       understanding.
 *     - Understanding runs on a SECOND CLOCK ([remediationDays]): Partial or Confused sets a short
 *       repair deadline and the topic returns at whichever date is earlier. Legacy FSRS-5 replay still
 *       applies the old ×0.9/×0.8 multiplier, because that is the schedule those users received.
 *  3. Important (high-yield) topics schedule for a higher retention target: see [effectiveRetention].
 *
 * Preview, commit and replay all go through the SAME [review] call, so the interval is consistent.
 * The exam date never feeds this scheduler; it is decorative by design.
 */
object MedScheduler {

    /** Target recall probability for ordinary items. */
    const val BASE_RETENTION = 0.90

    /**
     * LEGACY: the fixed high-yield target from before retention became a user setting. Nothing schedules
     * with it any more; the live rule is [effectiveRetention] (the user's target + 0.03, capped at 0.97
     * and never below the normal target). Kept because older notes and exports refer to it. For scale:
     * under FSRS-6 a 0.93 target buys ~0.61x the interval that 0.90 buys for the same stability, and the
     * gap compounds, because earlier reviews happen at higher recall and grow stability less.
     */
    const val HIGH_YIELD_RETENTION = 0.93

    /**
     * The range [Fsrs] will accept. Outside it, `FsrsParameters` THROWS rather than degrading, so
     * every value reaching it must already be inside — see [safeRetention].
     */
    const val MIN_RETENTION = 0.70
    const val MAX_RETENTION = 0.99

    /** User-chosen desired retention (0.85..0.95), set from Settings at startup; defaults to BASE. */
    @Volatile
    var userRetention: Double = BASE_RETENTION

    /**
     * Last line of defence for the retention target. The Settings slider is bounded to 0.85..0.95,
     * but a RESTORED BACKUP writes `desired_retention` straight into [userRetention] with no UI in
     * the way, and `MedReviewApplication` re-reads it on every cold start. Before this clamp, one
     * hand-edited or corrupt backup made `FsrsParameters`' `require()` throw on every single review,
     * on every launch, permanently — with nothing on screen explaining why. A nonsensical setting
     * must degrade to a sane schedule, never brick the app's core loop.
     */
    private fun safeRetention(value: Double): Double =
        if (value.isFinite()) value.coerceIn(MIN_RETENTION, MAX_RETENTION) else BASE_RETENTION

    /** The schedule never asks for a review sooner than the next day. */
    const val MIN_INTERVAL_DAYS = 1.0

    /** Ceiling on how many lapses [priorityScore] will count, so old history can't outrank importance. */
    const val MAX_SCORED_LAPSES = 5

    /**
     * After a lapse (Forgot) the item enters a short relearning step the next day, regardless of the
     * computed stability — the "relearn tomorrow" behaviour the product promises.
     */
    const val RELEARN_STEP_DAYS = 1.0

    /** A freshly studied topic's first reminder stays within this calm window. */
    const val FIRST_REVIEW_MIN_DAYS = 1.0
    const val FIRST_REVIEW_MAX_DAYS = 3.0

    /**
     * The FIRST review after a brand-new study is capped to this calm window, no matter how easy the
     * topic felt. Subjective ease right after studying is NOT proof of delayed recall (the forgetting
     * curve is steepest early), so an "Easy" first study should not be pushed ~2 weeks out before its
     * first check. Later reviews (reviewNumber >= 1) use the full, unbounded FSRS interval.
     */
    const val FIRST_STUDY_MAX_DAYS = 5.0

    /**
     * Understanding multipliers applied on top of the FSRS interval (Clear = 1.0). Kept deliberately
     * MINOR — a small nudge sooner for weaker understanding, not a big swing — since understanding is a
     * product-layer signal, not part of the validated recall model.
     */
    const val UNDERSTANDING_PARTIAL_FACTOR = 0.90
    const val UNDERSTANDING_CONFUSED_FACTOR = 0.80

    /**
     * Which memory model owns a state. The ids match `ReviewLogEntity.schedulerVersion`, so a log
     * always says which model produced it and history replays under that model rather than today's.
     */
    enum class MemoryModel(val id: String) {
        FSRS_5("FSRS-5"),
        FSRS_6("FSRS-6");

        companion object {
            /** Legacy/blank ids mean pre-versioning rows, which were all FSRS-5. */
            fun of(id: String): MemoryModel = entries.firstOrNull { it.id == id } ?: FSRS_5
        }
    }

    /**
     * UNDERSTANDING REMEDIATION — the second clock, in days. Null means nothing to repair.
     *
     * Replaces the old "multiply the memory interval by 0.8/0.9" approach, which was incoherent at
     * long intervals: a topic the user says they do NOT understand still disappeared for 80 days
     * after a 100-day memory prediction. Scaling a memory prediction was never the right tool for a
     * comprehension problem — they are different questions and now get different clocks. The memory
     * prediction is preserved exactly; the topic simply surfaces at whichever date comes first.
     *
     * A Forgot already relearns tomorrow, so its entry is consistent rather than additive.
     * These day counts are POLICY, not fitted constants (see the note in CLAUDE.md).
     */
    fun remediationDays(memoryRating: MemoryRating, understanding: UnderstandingRating): Double? = when {
        memoryRating == MemoryRating.Forgot -> 1.0
        understanding == UnderstandingRating.Confused -> 1.0
        understanding == UnderstandingRating.Partial -> when (memoryRating) {
            MemoryRating.Hard -> 2.0
            MemoryRating.Good -> 3.0
            MemoryRating.Easy -> 4.0
            MemoryRating.Forgot -> 1.0 // unreachable: handled above
        }
        else -> null // Clear: memory schedule stands on its own
    }

    /** Everything the UI/persistence needs after a scheduling decision. */
    data class Outcome(
        val state: MemoryState,
        val intervalDays: Double,
        /** Recall probability measured at review time, BEFORE the state was updated. */
        val retrievabilityAtReview: Double,
        val reason: ReviewReason,
        /**
         * The FSRS interval BEFORE the understanding multiplier. Fuzz eligibility must be decided
         * from this, not from [intervalDays]: near the 3-day threshold, Clear (3.2d) would fuzz while
         * Partial (2.88d) wouldn't, silently breaking the exact ×0.9/×0.8 ratio invariant.
         */
        val baseIntervalDays: Double = intervalDays,
        /**
         * The UNDERSTANDING clock, in days, or null when there is nothing to repair. Never folded
         * into [intervalDays] — the caller stores it separately and shows the topic on whichever
         * date is earlier. FSRS-5 (legacy replay) always reports null, because under that policy
         * understanding was a multiplier and is already inside [intervalDays].
         */
        val remediationDays: Double? = null,
    )

    /**
     * Structured rationale for a scheduling decision. The sentence the user reads is built by the review
     * screen (ReviewViewModel.buildReasonText), which knows both clocks; this only carries the inputs.
     */
    data class ReviewReason(
        val memoryRating: MemoryRating?,
        val understanding: UnderstandingRating?,
        val highYield: Boolean,
        val intervalDays: Double,
        val firstStudy: Boolean = false,
    )

    fun MemoryRating.toGrade(): Grade = when (this) {
        MemoryRating.Forgot -> Grade.Again
        MemoryRating.Hard -> Grade.Hard
        MemoryRating.Good -> Grade.Good
        MemoryRating.Easy -> Grade.Easy
    }

    private fun params(highYield: Boolean, retentionOverride: Double? = null) = FsrsParameters(
        requestRetention = retentionOverride?.let { safeRetention(it) } ?: effectiveRetention(highYield),
    )

    /**
     * Daily review cap, clamped on READ.
     *
     * Same rule as [safeRetention], and for the same reason: prefs can hold anything (a corrupt
     * file, a value written by an older build), and this number reaches `List.take()`, which throws
     * on a negative count. A bad setting must degrade to a sane queue, never crash reviewing.
     */
    fun safeDailyLimit(raw: Int): Int = raw.coerceIn(1, 500)

    /**
     * The retention target actually in force for an item, logged per review for later tuning.
     *
     * Important topics get the user's target + 0.03, capped at 0.97 and never BELOW the normal target.
     * Without that floor, a target above 0.97 (reachable only through a restored backup; the slider
     * stops at 0.95) clipped the bump under the normal target, so important topics came back LATER than
     * ordinary ones.
     */
    fun effectiveRetention(highYield: Boolean): Double {
        val base = safeRetention(userRetention)
        return if (highYield) maxOf(base, (base + 0.03).coerceAtMost(0.97)) else base
    }

    /**
     * Version tag written into every NEW review log. Old logs keep the tag they were written with,
     * which is exactly what lets history replay under the model that produced it.
     */
    const val SCHEDULER_VERSION = "FSRS-6"

    /**
     * Version of the YADORA POLICY BUNDLE around the memory model — everything product-layer:
     * understanding factors (1.0/0.9/0.8), relearn step (1d), first-study caps (1–3d seed, 5d first
     * rating), high-yield retention (+0.03), fuzz (±5%, base ≥ 3d), max interval (365d). Bump this
     * whenever ANY of those numbers changes; each review log stores the version + the understanding
     * factor actually applied, so history replays under its original policy instead of the new one.
     */
    // YADORA-5: three FSRS-6 equations were corrected to match py-fsrs 6.3.1 exactly (unclamped
    // D0(Easy) in mean reversion, lapse bounded by the short-term branch, same-day Good/Easy cannot
    // shrink stability) plus the reference stability floor. Live intervals move, so the stamp moves.
    const val POLICY_VERSION = "YADORA-5"

    /**
     * Did the policy that produced a given log damp the first-study prior? Only YADORA-3 onward does.
     *
     * Replay must reproduce what ACTUALLY happened, not re-decide history under today's rules — the
     * same principle that makes each log store its own understanding factor and retention target. A
     * log stamped YADORA-1/2 therefore keeps the undamped seed when its topic's history is replayed.
     */
    fun dampsFirstStudyPrior(policyVersion: String): Boolean = when (policyVersion) {
        "YADORA-1", "YADORA-2" -> false
        else -> true
    }

    /**
     * The memory state seeded by a FIRST graded rating.
     *
     * POLICY YADORA-3: the rating's own FSRS initial stability is shrunk toward the neutral "Good"
     * prior (geometric mean) instead of being used raw. A rating given moments after studying
     * measures CURRENT FLUENCY, not durable memory — the well-documented judgment-of-learning
     * illusion — whereas FSRS's S₀(Easy) ≈ 15.7 d was fitted on genuine *delayed* recall of
     * flashcards. Treating "that felt easy" as fifteen days of proven stability claims evidence we
     * have not collected yet, and it is the value that then decides how the second review is
     * interpreted, so the error propagates.
     *
     * Shrinking keeps the ordering the user actually expressed (Hard < Medium < Easy) while halving
     * how far the most over-confident answer can reach: Easy 15.7 d → 7.1 d, Medium 3.2 d unchanged,
     * Hard 1.2 d → 1.9 d. The first interval is still capped by [FIRST_STUDY_MAX_DAYS], and the
     * second review is a real retrieval that corrects the estimate quickly in either direction.
     *
     * DIFFICULTY is deliberately left undamped: it is bounded to 1..10, mean-reverts toward D₀(Easy)
     * on every subsequent review, and does not set the interval directly the way stability does.
     */
    fun firstRatingState(grade: Grade, p: FsrsParameters, damp: Boolean = true): MemoryState {
        val raw = Fsrs.initialState(grade, p)
        if (!damp) return raw
        val neutral = Fsrs.initialState(Grade.Good, p).stability
        return raw.copy(stability = kotlin.math.sqrt(raw.stability * neutral))
    }

    /**
     * The difficulty label a first-study memory-rating stands for (the UI buttons say
     * Easy/Medium/Hard but store the FSRS grade mapping). Logged separately so first-study data
     * stays semantically honest in exports.
     */
    fun difficultyLabelFor(rating: MemoryRating): String = when (rating) {
        MemoryRating.Easy -> "Easy"
        MemoryRating.Good -> "Medium"
        MemoryRating.Hard -> "Hard"
        MemoryRating.Forgot -> "Hard" // not reachable from the first-study UI; safe fallback
    }

    /** Public so the commit path can LOG the factor it applied (per-log policy snapshot, DB v5). */
    fun understandingFactor(understanding: UnderstandingRating): Double = when (understanding) {
        UnderstandingRating.Clear -> 1.0
        UnderstandingRating.Partial -> UNDERSTANDING_PARTIAL_FACTOR
        UnderstandingRating.Confused -> UNDERSTANDING_CONFUSED_FACTOR
    }

    /**
     * Seed the memory state for a topic studied for the first time. No forced recall; the first
     * reminder is 1..3 days out. Understanding sets the initial grade (how well it stuck).
     */
    fun firstStudy(
        understanding: UnderstandingRating,
        highYield: Boolean = false,
    ): Outcome {
        val p = params(highYield)
        val seedGrade = when (understanding) {
            UnderstandingRating.Clear -> Grade.Good      // S0 ~3d
            UnderstandingRating.Partial -> Grade.Hard    // S0 ~1.2d
            UnderstandingRating.Confused -> Grade.Again  // S0 ~0.4d (floored to 1d below)
        }
        val state = Fsrs.initialState(seedGrade, p)
        val interval = Fsrs.intervalDays(state.stability, p.requestRetention)
            .coerceIn(FIRST_REVIEW_MIN_DAYS, FIRST_REVIEW_MAX_DAYS)
        return Outcome(
            state = state,
            intervalDays = interval,
            retrievabilityAtReview = 1.0,
            reason = ReviewReason(null, understanding, highYield, interval, firstStudy = true),
        )
    }

    /**
     * Compute the next schedule for a review. Single source of truth for both preview and commit.
     *
     * @param reviewNumber the unit's review count BEFORE this review. 0 = first graded recall, which
     *        establishes the FSRS initial state so the rating sets a meaningful interval instead of
     *        collapsing everything to 1 day.
     */
    fun review(
        stability: Double,
        difficulty: Double,
        elapsedDays: Double,
        memoryRating: MemoryRating,
        understanding: UnderstandingRating,
        highYield: Boolean,
        reviewNumber: Int = 1,
        // History replay passes the retention that was IN FORCE at the original review (stored per-log
        // since DB v4), so editing an old rating after changing settings replays the past faithfully
        // instead of rewriting it under today's settings. Live reviews leave this null.
        desiredRetentionOverride: Double? = null,
        // Same idea for the understanding multiplier (stored per-log since DB v5): if the policy's
        // factors ever change, replay applies the factor that was ORIGINALLY used. Live reviews and
        // rating EDITS leave this null (an edited rating should get the current policy's factor).
        understandingFactorOverride: Double? = null,
        // Replay passes the damping rule of the policy that ORIGINALLY produced the log, so editing a
        // rating never silently re-decides old history under today's policy. Live reviews use current.
        dampFirstStudyPrior: Boolean = true,
        // Which memory model computes this transition. REQUIRED, deliberately: a default here would be
        // a trap. A new call site that forgot the parameter would silently schedule on the retired model,
        // compile, run and look right. The caller always knows which model owns the state it hands in,
        // so it must say so.
        model: MemoryModel,
    ): Outcome {
        if (model == MemoryModel.FSRS_6) {
            return reviewFsrs6(
                stability, difficulty, elapsedDays, memoryRating, understanding,
                highYield, reviewNumber, desiredRetentionOverride,
            )
        }
        val p = params(highYield, desiredRetentionOverride)
        val before = MemoryState(stability = stability, difficulty = difficulty)
        val grade = memoryRating.toGrade()

        val rAtReview = Fsrs.retrievability(elapsedDays, before.stability)
        // FSRS models MEMORY only — understanding never contaminates stability/difficulty.
        val newState = if (reviewNumber <= 0) {
            firstRatingState(grade, p, dampFirstStudyPrior)
        } else {
            Fsrs.nextState(before, elapsedDays, grade, p)
        }

        val baseInterval: Double
        val interval: Double
        if (memoryRating == MemoryRating.Forgot) {
            baseInterval = RELEARN_STEP_DAYS
            interval = RELEARN_STEP_DAYS
        } else {
            // Product layer: understanding is a transparent multiplier on top of the FSRS interval.
            var fsrsInterval = Fsrs.intervalDays(newState.stability, p.requestRetention)
            // First study: keep the first check-in in a calm window (see FIRST_STUDY_MAX_DAYS) so a
            // just-studied "Easy" topic isn't scheduled ~2 weeks out before it's ever recalled.
            if (reviewNumber <= 0) fsrsInterval = fsrsInterval.coerceAtMost(FIRST_STUDY_MAX_DAYS)
            baseInterval = fsrsInterval
            val factor = understandingFactorOverride ?: understandingFactor(understanding)
            interval = (fsrsInterval * factor)
                .coerceIn(MIN_INTERVAL_DAYS, p.maximumIntervalDays)
        }

        return Outcome(
            state = newState,
            intervalDays = interval,
            retrievabilityAtReview = rAtReview,
            reason = ReviewReason(memoryRating, understanding, highYield, interval),
            baseIntervalDays = baseInterval,
        )
    }

    /**
     * FSRS-6 scheduling. Two things differ from the FSRS-5 path above, both deliberate:
     *
     *  1. NO understanding multiplier. Understanding gets its own clock ([remediationDays]) instead
     *     of scaling a memory prediction it has no business scaling. [Outcome.intervalDays] is the
     *     pure memory interval, and the caller stores the remediation deadline separately.
     *  2. NO first-study damping. YADORA-3 shrank FSRS-5's S0(Easy)=15.69 by hand because an
     *     immediate post-study rating cannot justify two weeks of proven stability. FSRS-6's own
     *     refit already lowers it to 8.30 — a FITTED value replacing a hand-chosen one, which is
     *     the main reason this migration is worth doing. Damping on top would double-count.
     *
     * The calm first-study ceiling still applies: a first rating is a self-assessment, not a
     * measured retrieval, whichever model is doing the arithmetic.
     */
    private fun reviewFsrs6(
        stability: Double,
        difficulty: Double,
        elapsedDays: Double,
        memoryRating: MemoryRating,
        understanding: UnderstandingRating,
        highYield: Boolean,
        reviewNumber: Int,
        desiredRetentionOverride: Double?,
    ): Outcome {
        val p = params6(highYield, desiredRetentionOverride)
        val before = MemoryState(stability = stability, difficulty = difficulty)
        val grade = memoryRating.toGrade()

        val modelDays = completedModelDays(elapsedDays)
        val rAtReview = Fsrs6.retrievability(modelDays, before.stability, p)
        val newState = if (reviewNumber <= 0) {
            Fsrs6.initialState(grade, p)
        } else {
            Fsrs6.nextState(before, modelDays, grade, p)
        }

        val baseInterval: Double
        val interval: Double
        if (memoryRating == MemoryRating.Forgot) {
            baseInterval = RELEARN_STEP_DAYS
            interval = RELEARN_STEP_DAYS
        } else {
            var fsrsInterval = Fsrs6.intervalDays(newState.stability, p.requestRetention, p)
            if (reviewNumber <= 0) fsrsInterval = fsrsInterval.coerceAtMost(FIRST_STUDY_MAX_DAYS)
            baseInterval = fsrsInterval
            interval = fsrsInterval.coerceIn(MIN_INTERVAL_DAYS, p.maximumIntervalDays)
        }

        return Outcome(
            state = newState,
            intervalDays = interval,
            retrievabilityAtReview = rAtReview,
            reason = ReviewReason(memoryRating, understanding, highYield, interval),
            baseIntervalDays = baseInterval,
            remediationDays = remediationDays(memoryRating, understanding),
        )
    }

    /** The model that schedules NEW reviews. Older states are projected onto it before they are used. */
    val CURRENT_MODEL = MemoryModel.FSRS_6

    /**
     * Recall probability under the model a topic is actually ON.
     *
     * Both models are built to pass through 0.9 at t = stability, but their curves diverge sharply
     * away from that point (FSRS-6's trainable decay gives a much heavier tail than FSRS-5's fixed
     * -0.5). Drawing one topic's memory on the other model's curve — or logging a retrievability
     * measured on the wrong curve — would quietly contradict the schedule the user was actually given.
     */
    fun retrievability(elapsedDays: Double, stability: Double, model: MemoryModel): Double = when (model) {
        MemoryModel.FSRS_5 -> Fsrs.retrievability(elapsedDays, stability)
        MemoryModel.FSRS_6 -> Fsrs6.retrievability(elapsedDays, stability)
    }

    /**
     * How long a stability buys at a given retention target, under the model that owns it.
     *
     * The inverse of [retrievability] and equally model-specific: at 0.93 the same stability is worth
     * ~0.67 S under FSRS-5 and ~0.61 S under FSRS-6. Any code that re-derives an interval outside
     * [review] must go through here, or it will schedule on a curve the next real review disagrees with.
     */
    fun intervalDays(stability: Double, requestRetention: Double, model: MemoryModel): Double = when (model) {
        MemoryModel.FSRS_5 -> Fsrs.intervalDays(stability, requestRetention)
        MemoryModel.FSRS_6 -> Fsrs6.intervalDays(stability, requestRetention)
    }

    /**
     * One step of a history projection: rebuild a memory state under the CURRENT model from a past
     * rating. `null` means "this is the first graded rating", which seeds rather than transitions.
     *
     * Used to carry a topic from FSRS-5 onto FSRS-6 by replaying what the user actually did, since
     * the stored stability itself is not portable between models.
     */
    fun projectStep(
        previous: MemoryState?,
        elapsedDays: Double,
        memoryRating: MemoryRating,
        highYield: Boolean,
    ): MemoryState {
        val p = params6(highYield)
        val grade = memoryRating.toGrade()
        return if (previous == null) Fsrs6.initialState(grade, p)
        else Fsrs6.nextState(previous, completedModelDays(elapsedDays), grade, p)
    }

    /**
     * Elapsed time as the memory model expects it: COMPLETED whole days.
     *
     * The reference measures a review's age with a whole-day difference, and the weights were fitted
     * on histories recorded that way. Feeding fractional days runs the model outside the domain it
     * was fitted on. It also injects noise Yadora cannot justify: on a day-granularity app, reviewing
     * a topic at 22:00 instead of 09:00 must not earn a different interval than reviewing it that
     * morning -- same calendar day, same evidence.
     *
     * Applied on the FSRS-6 path ONLY. The FSRS-5 path stays fractional because it is frozen: it has
     * to keep reproducing the schedules users were actually given, deviations included.
     *
     * The same-day branch is unaffected -- floor(x) < 1 exactly when x < 1.
     */
    /**
     * Elapsed time between two instants, measured the way the OWNING model expects.
     *
     * FSRS-6 gets whole LOCAL CALENDAR DAYS, not floored elapsed milliseconds, because that is what
     * the queue means by "due". A topic reviewed at 20:00 with a one-day interval is due at 20:00
     * tomorrow, but the day-granularity queue offers it from 00:00 tomorrow — so a learner who
     * reviews at 09:00 was, in elapsed-millisecond terms, only 13 hours late from the previous
     * review. Flooring that gives ZERO completed days, which routes a genuine next-day review into
     * the SAME-DAY branch and hands out a ~5% stability bump instead of a real one. It also leaves a
     * discontinuity at the previous review's hour: review before it and you lose a day, after it and
     * you do not. Calendar days remove both problems, and they match the fitted domain — the FSRS
     * weights come from Anki histories, where elapsed time is a difference of day numbers.
     *
     * FSRS-5 keeps fractional elapsed milliseconds because it is frozen and must keep reproducing
     * the schedules users were actually given.
     *
     * The cost, accepted: a calendar-day count depends on the device time zone, so a history
     * replayed after moving continents can differ by a day. That is rare and bounded; the queue
     * mismatch above was neither.
     */
    fun modelElapsedDays(fromMillis: Long, toMillis: Long, model: MemoryModel): Double = when (model) {
        MemoryModel.FSRS_5 -> ((toMillis - fromMillis) / 86400000.0).coerceAtLeast(0.0)
        MemoryModel.FSRS_6 -> java.time.temporal.ChronoUnit.DAYS
            .between(localDate(fromMillis), localDate(toMillis))
            .coerceAtLeast(0L).toDouble()
    }

    private fun localDate(millis: Long): java.time.LocalDate =
        java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()

    fun completedModelDays(elapsedDays: Double): Double =
        kotlin.math.floor(elapsedDays.coerceAtLeast(0.0))

    /** FSRS-6 parameters, sharing the same clamped retention policy as the FSRS-5 path. */
    private fun params6(highYield: Boolean, retentionOverride: Double? = null) = Fsrs6Parameters(
        requestRetention = retentionOverride?.let { safeRetention(it) } ?: effectiveRetention(highYield),
    )

    /**
     * Anki-style interval fuzz (±5%), applied ON TOP of [review]'s interval by the preview, commit,
     * and replay paths alike. Purpose: topics studied together stop being locked in the same review
     * cohort forever (spiky 40-review days), and the user can't "calendar-memorize" a fixed pattern.
     *
     * Design constraints this deliberately satisfies:
     *  - DETERMINISTIC per (unitId, reviewCount): preview == commit == replay, always. The seed is
     *    the unit's PRIOR review count (0, 1, 2, …) — strictly increasing per review, unlike the
     *    the reviewNumber, which is 0 for every first rating and so would not vary per review).
     *  - MULTIPLICATIVE: the same factor applies to Clear/Partial/Confused previews of the same
     *    review, so the transparent understanding ratios (×0.9 / ×0.8) are preserved exactly.
     *  - Never fuzzes short BASE intervals (< 3 days): relearn-tomorrow and other tight early
     *    reviews stay precisely where the science put them. Eligibility is decided from
     *    [baseIntervalDays] (pre-understanding), NOT the final interval — otherwise near the 3-day
     *    boundary Clear would fuzz while Partial wouldn't, breaking the exact-ratio invariant.
     *
     * NOTE: first-study intervals are NOT categorically exempt, and that is deliberate. A Good or
     * Easy first rating lands on a base interval of ~3–5 days (the [FIRST_STUDY_MAX_DAYS] cap), which
     * clears the 3-day threshold and therefore does get jittered. That is exactly the behaviour we
     * want: someone who adds five topics in one study session would otherwise have all five come due
     * on precisely the same day, forever. Only Hard/Forgot first ratings (base < 3d) stay unfuzzed.
     */
    fun fuzzedInterval(
        intervalDays: Double,
        baseIntervalDays: Double,
        unitId: Long,
        reviewCount: Int,
        // Must be the SAME condition review() used to apply the cap, not "reviewCount == 0": after a
        // merge the earliest log in a combined history can be a RECALL, so the counter is 0 while the
        // event is not a first study — clamping that to five days would corrupt a mature schedule.
        isFirstStudy: Boolean = false,
    ): Double {
        if (baseIntervalDays < 3.0) return intervalDays
        val rng = kotlin.random.Random(unitId * 31L + reviewCount)
        val factor = 1.0 + rng.nextDouble(-0.05, 0.05)
        // Bounds come from the model's own parameters, not a second hardcoded copy of them: fuzz is
        // the LAST step before a due date is written, so it must not be able to nudge an interval
        // past the ceiling review() just enforced.
        val fuzzed = (intervalDays * factor).coerceIn(MIN_INTERVAL_DAYS, FsrsParameters().maximumIntervalDays)
        // FIRST_STUDY_MAX_DAYS is a PROMISE ("your first check-in lands within five days"), not a
        // suggestion. review() capped the interval before the understanding multiplier, but fuzz runs
        // afterwards and could add up to +5% on top — turning an advertised 5.0-day ceiling into 5.25.
        return if (isFirstStudy) fuzzed.coerceAtMost(FIRST_STUDY_MAX_DAYS) else fuzzed
    }

    /** Convenience for the rating-button preview; identical math to [review]. */
    fun previewIntervalDays(
        stability: Double,
        difficulty: Double,
        elapsedDays: Double,
        memoryRating: MemoryRating,
        understanding: UnderstandingRating,
        highYield: Boolean,
        reviewNumber: Int = 1,
        model: MemoryModel,
    ): Double = review(
        stability, difficulty, elapsedDays, memoryRating, understanding, highYield, reviewNumber, model = model,
    ).intervalDays

    /**
     * Ordering score for the due queue and the overdue-redistribution plan. Higher = review sooner /
     * recover first. SINGLE source of truth for "which items matter most": the review-session daily cap
     * and the Today redistribution both call this, so their notion of priority can never drift apart.
     * Weights are deliberately coarse and additive: importance dominates, then how weak/overdue it is.
     */
    fun priorityScore(
        highYield: Boolean,
        state: String,
        lapseCount: Int,
        /**
         * The MEMORY MODEL's own due date, not the effective one.
         *
         * Deferring is a scheduling choice, not evidence about memory — so it must not lower a
         * topic's urgency. Ranking by `nextReviewAt` did exactly that: every "Not today" reset the
         * effective date to tomorrow, so a user who tapped it daily kept their topics at zero
         * overdue pressure forever, while a user who simply ignored the notification accumulated it.
         * The app rewarded active procrastination with a quieter queue.
         *
         * Whether a deferred topic is OFFERED is still governed by `nextReviewAt` — the deferral is
         * honoured. This only decides the order among topics already in today's queue.
         */
        modelDueAt: Long,
        now: Long,
        /** Fallback for a row that predates modelDueAt and was never backfilled. */
        effectiveDueAt: Long = modelDueAt,
        /**
         * The UNDERSTANDING repair deadline, when one is pending. It is the scheduler's own date, not a
         * user deferral, so a repair left unanswered builds urgency exactly like an overdue memory
         * review. Ranking used to read only [modelDueAt], so a topic whose 3-day repair had waited for
         * weeks scored no lateness at all while its memory date was still far away.
         */
        understandingDueAt: Long? = null,
    ): Double {
        var score = 0.0
        if (highYield) score += 100.0
        score += when (state) {
            "NeedsRelearn" -> 80.0
            "Learning" -> 40.0
            "Building" -> 20.0
            else -> 0.0
        }
        // Lapses matter, but they are HISTORY and never decay — a topic that was hard a year ago
        // still carries every lapse it ever had. Uncapped, `lapseCount * 10` eventually exceeds the
        // high-yield weight (100) on its own, so a now-Strong topic with an ugly past would outrank
        // a genuinely important one forever, contradicting this function's own "importance
        // dominates" contract. Capped at 5 lapses (50 points) it stays a meaningful tie-breaker
        // below importance, while the still-uncapped overdue term below keeps anything neglected
        // rising until it is actually seen.
        score += minOf(lapseCount, MAX_SCORED_LAPSES) * 10.0
        val modelReference = if (modelDueAt > 0L) modelDueAt else effectiveDueAt
        val dueReference = understandingDueAt?.takeIf { it > 0L }?.let { minOf(modelReference, it) } ?: modelReference
        val overdueDays = (now - dueReference) / 86400000.0
        if (overdueDays > 0) score += overdueDays * 5.0
        return score
    }

    /**
     * Derive a mastery state from the memory model (replacing the old review-count thresholds, which
     * were decoupled from scheduling). Mastery tracks stability so the badge and the scheduler agree.
     */
    /** Stability at which a topic stops being "Learning". POLICY, not a measured boundary. */
    const val BUILDING_STABILITY_DAYS = 7.0

    /** Stability at which a topic counts as "Strong". POLICY, not a measured boundary. */
    const val STRONG_STABILITY_DAYS = 21.0

    fun masteryState(stability: Double, justForgot: Boolean): StudyState = when {
        justForgot -> StudyState.NeedsRelearn
        stability < BUILDING_STABILITY_DAYS -> StudyState.Learning
        stability < STRONG_STABILITY_DAYS -> StudyState.Building
        else -> StudyState.Strong
    }

    /**
     * The [review] reviewNumber for a rating event, shared by the LIVE review flow and the history
     * replay so the two can never disagree.
     *
     * The FIRST graded rating is ALWAYS review #0, whether the user rates on the day they studied or
     * three weeks later. It seeds the memory model from the rating itself ([Fsrs.initialState]) and is
     * capped by [FIRST_STUDY_MAX_DAYS].
     *
     * POLICY-2 CHANGE (was: a back-dated first rating counted as a recall after a gap). The old rule
     * ran the FSRS recall update against the neutral placeholder state AddUnit seeds — an S≈1.18/D≈6.49
     * that the user never chose and no review ever measured. Because that placeholder stability is tiny,
     * any real gap produced a low retrievability, and a confident rating against it exploded the
     * interval while ALSO bypassing the first-study cap (which only applied at reviewNumber 0):
     * the same topic rated "Easy" scored 5 days when rated on its study day, but ~43 days when rated
     * five days later, and ~108 days when back-dated a month. That cliff is what made overdue topics
     * feel like the scheduler had broken.
     *
     * A first rating is a subjective difficulty judgement, not a measured recall, so it must not be fed
     * into the recall-update path at all. The elapsed gap is deliberately NOT used here: after this one
     * capped check-in the model has real measured data and intervals expand quickly and honestly.
     */
    fun effectiveReviewNumber(priorReviewCount: Int): Int = priorReviewCount
}
