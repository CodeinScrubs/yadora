package com.example.domain.srs

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * Learns ONE number from the user's own recall outcomes: how far their real memory stability sits
 * from what the default FSRS-6 weights predict.
 *
 * WHY ONE NUMBER. The published weights are an average over millions of Anki flashcard reviews.
 * A Yadora topic is a larger unit than a flashcard, and any single learner forgets faster or slower
 * than that average. The fuller cure is refitting all 21 weights on the learner's own history, which the app
 * also does since DB v9 ([Fsrs6Optimizer], the personal weight set), but a set is adopted only after hundreds of
 * reviews and a held-out test. A single stability scale is the first-order correction, until then and on top of
 * whichever set is active: it is exactly what
 * moving the retention target does (`interval = S/F·(r^(1/decay) − 1)` scales the same way in S and
 * in the retention term), so it corrects the schedule without touching the model's state, it is
 * identifiable from a few hundred reviews, and it is easy to reason about.
 *
 * HOW. Every FSRS-6 recall log stores the recall probability the model predicted at that moment
 * (`retrievabilityAtReview`) and whether the learner in fact recalled. Under the default curve
 * `R = (1 + F·t/S)^decay`, a prediction R pins down `t/S`; if the learner's true stability is `k·S`,
 * their true recall probability was `(1 + (t/S)/k)^decay`. [momentScale] finds the k at which the
 * model's predicted number of recalls matches the number observed — calibration-in-the-large,
 * a one-dimensional monotone root — and [scale] shrinks that toward 1 in log space by
 * `n / (n + PRIOR_REVIEWS)` and clamps it. The predictions it reads are the RAW model's, never
 * corrected ones, so the estimate does not chase its own tail as the schedule adapts.
 *
 * WHY SHRINK THAT HARD. The FSRS-6 curve is flat: at a 0.90 prediction, a one-point change in the
 * recall rate corresponds to a ~15 % change in stability, and the recall rate itself has a standard
 * error of about 3 points from 100 reviews. Unshrunk, 100 reviews would swing the scale by a factor
 * of ~1.5 either way on noise alone. A prior of 120 pseudo-reviews at k = 1 gives roughly the
 * posterior mean under a plausible spread of real learners (±40 % in log stability), so the
 * correction earns its weight only as evidence accumulates: ~45 % of the raw estimate at 100
 * reviews, ~77 % at 400, ~93 % at 1,600.
 *
 * IT NEVER LENGTHENS INTERVALS (2026-09-28, the owner's decision on the simulation below). A learner
 * who calls some forgotten topics "Hard" produces exactly the evidence a slow forgetter produces: more
 * "recalls" than predicted. Self-ratings alone cannot tell the two apart, and lengthening every interval
 * for the first is what inflated ratings cost most (`tools/pilot/experiments.py`, section 7): with 30%
 * of lapses rated Hard it caused 1.8 of the 2.9 points lost at the year-end quiz and sank the weakest
 * tenth of topics to 75%; at 60%, 4.1 of 7.2 points and 59%. Capped at x1, those learners keep almost
 * all of it back, an honest average learner knows 0.3 points more for 3% more reviews (the estimate's
 * upward noise no longer stretches anything), and a fast forgetter keeps its full correction. The cost
 * falls on the honest slow forgetter, who now reviews about 16% more than strictly needed and knows 1.1
 * points more for it. Under-reviewing is what fails an exam; over-reviewing costs time.
 *
 * All constants are POLICY, chosen by the reasoning above, not fitted. Real Yadora data could
 * later replace them; the estimator's shape would not change.
 */
object RecallCalibration {

    /** Pseudo-reviews of prior belief in k = 1. See the class note for the derivation. */
    const val PRIOR_REVIEWS = 120

    /** The most recent recall reviews the estimate is computed from. Memory changes; old evidence ages out. */
    const val WINDOW = 600

    /**
     * Only reviews at least this many calendar days after the previous one count as evidence.
     *
     * FSRS-6 is fed WHOLE local calendar days, so a review 2.5 days after the last one is predicted
     * at t = 3. At short intervals that rounding is a large fraction of the interval, the stored
     * prediction runs pessimistic, and the learner "beats" it for a reason that has nothing to do
     * with their memory. Fed those rows, the estimator pushed a perfectly average simulated learner
     * to a 1.58 scale within three months. From three days on the rounding is a minor share of the
     * interval, and the reviews that remain are the ones the scale is actually applied to.
     */
    const val MIN_ELAPSED_DAYS = 3.0

    /**
     * A review that happened before this fraction of its memory interval had passed was not the
     * memory clock's doing — an understanding repair deadline or an on-demand review from the
     * Library brought it forward — and it is left out of the evidence. At a 90% target such a review
     * sits above ~0.94 predicted recall, where an outcome carries a fraction of an on-time review's
     * information, and they are a selected set (topics the learner was unsure of, or chose to drill).
     *
     * Backed-off repairs that land later than half the interval DO pass, and that is accepted. A
     * stricter rule ("not before the due day") was simulated over two years: no measurable gain, and
     * a noisier estimate for an average learner (0.67–1.74 against 0.76–1.25). Raising this fraction
     * to 0.8 would be wrong too: an on-time review of a 3.99-day interval happens 3 whole calendar
     * days later (0.75), so on-time reviews would be dropped while late repairs still passed.
     */
    const val EARLY_REVIEW_FRACTION = 0.5

    /**
     * Whether a recall review may teach the calibration anything, from what its log stores: at least
     * [MIN_ELAPSED_DAYS] after the previous review, and at least [EARLY_REVIEW_FRACTION] of the memory
     * interval that review set. `ReviewLogDao.getRecentRecallLogsOnce` states the same rule in SQL;
     * RecallCalibrationEvidenceTest pins the two together, and the Progress card uses this one.
     */
    fun isEvidence(elapsedDays: Double, previousIntervalDays: Double): Boolean =
        elapsedDays >= MIN_ELAPSED_DAYS && elapsedDays >= EARLY_REVIEW_FRACTION * previousIntervalDays

    /**
     * The band the scheduler may multiply by (see [safeScale]). Beyond these the model is simply wrong for
     * this learner in a way one scale cannot express. MAX_SCALE stays 2 because builds before 2026-09-28
     * stored scales up to 2 on their logs, and replay must reproduce those intervals exactly.
     */
    const val MIN_SCALE = 0.5
    const val MAX_SCALE = 2.0

    /** The most a NEW estimate may be: 1, so the correction only ever shortens intervals (class note). */
    const val MAX_ESTIMATE = 1.0

    /** The raw root is searched inside a wider band before shrinking, so a strong signal is not clipped twice. */
    private const val SEARCH_MIN = 0.25
    private const val SEARCH_MAX = 4.0

    private val defaults = Fsrs6Parameters()

    /** A scale the scheduler may multiply by: finite and inside the clamp band, whatever was stored. */
    fun safeScale(scale: Double): Double =
        if (scale.isFinite()) scale.coerceIn(MIN_SCALE, MAX_SCALE) else 1.0

    /**
     * The `F·t/S` a stored prediction implies under the default curve `R = (1 + F·t/S)^decay`
     * (0 at R = 1). Kept with the curve's own factor folded in: scaling S by k divides it by k.
     */
    fun impliedRatio(predicted: Double, p: Fsrs6Parameters = defaults): Double =
        predicted.coerceIn(1e-9, 1.0).pow(1.0 / p.decay) - 1.0

    /** What the model would have predicted had the learner's stability been [scale] times its own. */
    fun recalibrated(predicted: Double, scale: Double, p: Fsrs6Parameters = defaults): Double =
        (1.0 + impliedRatio(predicted, p) / safeScale(scale)).pow(p.decay)

    /**
     * The raw scale: the k at which Σ R_i(k) equals the number of recalls observed. Monotone in k,
     * so bisection on ln k converges to it; clipped to the search band when no k inside it can
     * explain the outcomes. 1.0 with no evidence.
     */
    fun momentScale(predicted: DoubleArray, recalled: BooleanArray, p: Fsrs6Parameters = defaults): Double {
        require(predicted.size == recalled.size) { "one outcome per prediction" }
        if (predicted.isEmpty()) return 1.0
        val ratios = DoubleArray(predicted.size) { impliedRatio(predicted[it], p) }
        val observed = recalled.count { it }.toDouble()
        fun predictedTotal(k: Double) = ratios.sumOf { (1.0 + it / k).pow(p.decay) }
        if (predictedTotal(SEARCH_MAX) <= observed) return SEARCH_MAX
        if (predictedTotal(SEARCH_MIN) >= observed) return SEARCH_MIN
        var lo = ln(SEARCH_MIN)
        var hi = ln(SEARCH_MAX)
        repeat(60) {
            val mid = (lo + hi) / 2
            if (predictedTotal(exp(mid)) < observed) lo = mid else hi = mid
        }
        return exp((lo + hi) / 2)
    }

    /**
     * The scale the schedule uses: [momentScale] shrunk toward 1 by the prior, then clamped to
     * [MIN_SCALE]..[MAX_ESTIMATE], so it shortens intervals for a learner who forgets faster than predicted
     * and never lengthens them (class note). [momentScale] stays unclamped for analysis.
     */
    fun scale(predicted: DoubleArray, recalled: BooleanArray, p: Fsrs6Parameters = defaults): Double {
        val n = predicted.size
        if (n == 0) return 1.0
        val raw = momentScale(predicted, recalled, p)
        val weight = n.toDouble() / (n + PRIOR_REVIEWS)
        return exp(weight * ln(raw)).coerceIn(MIN_SCALE, MAX_ESTIMATE)
    }
}
