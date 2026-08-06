package com.example.domain.srs

import com.example.domain.model.MemoryRating
import com.example.domain.model.StudyState
import com.example.domain.model.UnderstandingRating

/**
 * MedReview's scheduling layer on top of [Fsrs].
 *
 * Adapts FSRS (designed for Q/A flashcards) to MedReview's study-review model:
 *
 *  1. The FIRST event is "I just studied this topic". PRODUCT TRUTH: AddUnit seeds a neutral
 *     placeholder state via [firstStudy] and makes the topic due ON its study date — the user's first
 *     difficulty+understanding rating happens in the review screen (reviewNumber 0), which re-seeds
 *     the real FSRS state and schedules the first future review (capped by [FIRST_STUDY_MAX_DAYS]).
 *     [firstStudy]'s own 1..3-day interval is only used as the placeholder's pre-rating window.
 *  2. Each [review] supplies a memory rating (Forgot/Hard/Good/Easy) AND an understanding rating.
 *     - FSRS models MEMORY only: stability/difficulty come purely from the memory grade.
 *     - UNDERSTANDING is a transparent product-layer multiplier applied ON TOP of the FSRS interval
 *       (Clear keeps it, Partial ~90%, Confused ~80%). You remembered it, but if you didn't really
 *       understand it, it comes back sooner. This never contaminates the FSRS memory state.
 *  3. High-yield items schedule for a higher desired retention (~30% shorter intervals — see
 *     [HIGH_YIELD_RETENTION] for the honest math).
 *
 * Preview and commit go through the SAME [review] call, so the interval is consistent.
 * Exam-deadline compression is a Phase 3 feature.
 */
object MedScheduler {

    /** Target recall probability for ordinary items. */
    const val BASE_RETENTION = 0.90

    /**
     * Tighter target for high-yield items. Deliberate and NOT subtle: on the FSRS forgetting curve,
     * 0.93 vs 0.90 shortens intervals by roughly a third (ln 0.93 / ln 0.90 ≈ 0.69), i.e. important
     * topics come back ~30% sooner and cost ~40–50% more reviews. That extra workload IS the feature —
     * "important" should mean "seen more often" — but keep the trade-off in mind before widening it.
     */
    const val HIGH_YIELD_RETENTION = 0.93

    /** User-chosen desired retention (0.85..0.95), set from Settings at startup; defaults to BASE. */
    @Volatile
    var userRetention: Double = BASE_RETENTION

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
    )

    /** Structured rationale so the "why was this scheduled again?" sentence can be localized later. */
    data class ReviewReason(
        val memoryRating: MemoryRating?,
        val understanding: UnderstandingRating?,
        val highYield: Boolean,
        val intervalDays: Double,
        val firstStudy: Boolean = false,
    ) {
        /** Plain-English cause -> effect -> principle sentence (FA localization is Phase 3). */
        fun defaultText(): String {
            if (firstStudy) {
                return "Logged as studied today — first check-in in ${days(intervalDays)} while it's still fresh."
            }
            val core = when (memoryRating) {
                MemoryRating.Forgot -> "You forgot it, so it's back in ${days(intervalDays)} to relearn."
                MemoryRating.Hard -> "It felt hard, so the gap stayed short (${days(intervalDays)})."
                MemoryRating.Good -> "You recalled it well, so the next review is in ${days(intervalDays)}."
                MemoryRating.Easy -> "You found it easy, so it's pushed out to ${days(intervalDays)}."
                null -> "Next review in ${days(intervalDays)}."
            }
            val understandingNote = if (memoryRating != MemoryRating.Forgot) {
                when (understanding) {
                    UnderstandingRating.Confused -> " You marked it Confused, so it's sooner than memory alone would suggest."
                    UnderstandingRating.Partial -> " You marked it Partial, so it's a little sooner."
                    else -> ""
                }
            } else ""
            val yieldNote = if (highYield) " (Kept tighter because it's important.)" else ""
            return core + understandingNote + yieldNote
        }

        private fun days(d: Double): String {
            val rounded = Math.round(d).toInt()
            return if (rounded <= 1) "1 day" else "$rounded days"
        }
    }

    fun MemoryRating.toGrade(): Grade = when (this) {
        MemoryRating.Forgot -> Grade.Again
        MemoryRating.Hard -> Grade.Hard
        MemoryRating.Good -> Grade.Good
        MemoryRating.Easy -> Grade.Easy
    }

    private fun params(highYield: Boolean, retentionOverride: Double? = null) = FsrsParameters(
        requestRetention = retentionOverride?.coerceIn(0.70, 0.99)
            ?: if (highYield) (userRetention + 0.03).coerceAtMost(0.97) else userRetention,
    )

    /** The retention target actually in force for an item — logged per review for later tuning. */
    fun effectiveRetention(highYield: Boolean): Double =
        if (highYield) (userRetention + 0.03).coerceAtMost(0.97) else userRetention

    /** Version tag written into every review log so exported data is analyzable across upgrades. */
    const val SCHEDULER_VERSION = "FSRS-5"

    /**
     * Version of the YADORA POLICY BUNDLE around the memory model — everything product-layer:
     * understanding factors (1.0/0.9/0.8), relearn step (1d), first-study caps (1–3d seed, 5d first
     * rating), high-yield retention (+0.03), fuzz (±5%, base ≥ 3d), max interval (365d). Bump this
     * whenever ANY of those numbers changes; each review log stores the version + the understanding
     * factor actually applied, so history replays under its original policy instead of the new one.
     */
    const val POLICY_VERSION = "YADORA-2"

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
    ): Outcome {
        val p = params(highYield, desiredRetentionOverride)
        val before = MemoryState(stability = stability, difficulty = difficulty)
        val grade = memoryRating.toGrade()

        val rAtReview = Fsrs.retrievability(elapsedDays, before.stability)
        // FSRS models MEMORY only — understanding never contaminates stability/difficulty.
        val newState = if (reviewNumber <= 0) {
            Fsrs.initialState(grade, p)
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
    fun fuzzedInterval(intervalDays: Double, baseIntervalDays: Double, unitId: Long, reviewCount: Int): Double {
        if (baseIntervalDays < 3.0) return intervalDays
        val rng = kotlin.random.Random(unitId * 31L + reviewCount)
        val factor = 1.0 + rng.nextDouble(-0.05, 0.05)
        return (intervalDays * factor).coerceIn(1.0, 365.0)
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
    ): Double = review(stability, difficulty, elapsedDays, memoryRating, understanding, highYield, reviewNumber).intervalDays

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
        nextReviewAt: Long,
        now: Long,
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
        val overdueDays = (now - nextReviewAt) / 86400000.0
        if (overdueDays > 0) score += overdueDays * 5.0
        return score
    }

    /**
     * Derive a mastery state from the memory model (replacing the old review-count thresholds, which
     * were decoupled from scheduling). Mastery tracks stability so the badge and the scheduler agree.
     */
    fun masteryState(stability: Double, justForgot: Boolean): StudyState = when {
        justForgot -> StudyState.NeedsRelearn
        stability < 7.0 -> StudyState.Learning
        stability < 21.0 -> StudyState.Building
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
