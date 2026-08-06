package com.example.domain.srs

import kotlin.math.exp
import kotlin.math.pow

/**
 * FSRS-6 — the current stable Free Spaced Repetition Scheduler.
 *
 * Added ALONGSIDE [Fsrs] (FSRS-5), not replacing it. Yadora is published, so every existing review
 * log was produced by FSRS-5 and must keep replaying under FSRS-5 forever; a stored memory state is
 * only meaningful together with the model that produced it. Nothing calls this yet — wiring it in
 * (state projection + `MIGRATION_5_6`) is the next step, and keeping that separate means this file
 * can be reviewed and tested on its own without changing a single user's schedule.
 *
 * WHAT ACTUALLY DIFFERS FROM FSRS-5 (it is not a cosmetic bump):
 *  - The forgetting curve's exponent is a trainable weight (w20) instead of the fixed −0.5. The
 *    default 0.1542 is a much FLATTER curve, so recall decays more slowly at long intervals.
 *  - The default weights were refit on a far larger corpus. Most consequentially for Yadora,
 *    S₀(Easy) drops 15.69 → 8.30: an "Easy" first rating no longer claims two weeks of proven
 *    stability. That is a FITTED value replacing the hand-chosen YADORA-3 damping, which is the
 *    whole reason this migration is worth doing.
 *  - The same-day update gains an S^(−w19) term, so short-term gains shrink as stability grows.
 *
 * Consequences of the flatter curve, at the same stability: intervals are ~16 % LONGER at a 0.85
 * retention target and ~13 % SHORTER at 0.95 than FSRS-5. `I(S, 0.90) == S` still holds in both, by
 * construction.
 */

/**
 * FSRS-6 configuration: 21 trainable weights, the target recall probability, and an interval ceiling.
 *
 * @param requestRetention probability of recall the schedule aims for.
 */
class Fsrs6Parameters(
    val weights: DoubleArray = DEFAULT_WEIGHTS,
    val requestRetention: Double = 0.90,
    val maximumIntervalDays: Double = 365.0,
) {
    init {
        // Exact count, not >=: FSRS-5's 19 weights silently run through FSRS-6 formulas would read
        // w19/w20 out of bounds, and a 35-weight FSRS-7 vector would be quietly truncated.
        require(weights.size == 21) { "FSRS-6 requires exactly 21 weights, got ${weights.size}" }
        require(requestRetention in 0.70..0.99) { "requestRetention $requestRetention out of sane range" }
        require(maximumIntervalDays >= 1.0) { "maximumIntervalDays must be >= 1" }
        require(weights[20] > 0.0) { "w20 (curve shape) must be positive" }
    }

    /** Forgetting-curve exponent. Trainable in FSRS-6; FSRS-5 hard-coded −0.5. */
    val decay: Double get() = -weights[20]

    /** Chosen so that R(S, S) == 0.90 exactly, whatever the curve shape is. */
    val factor: Double get() = 0.9.pow(1.0 / decay) - 1.0

    companion object {
        /**
         * Published FSRS-6 defaults (w0..w20). w0..w3 are the initial stabilities for
         * Again/Hard/Good/Easy. Frozen: this exact vector is the identity `FSRS6-DEFAULT-21`. A
         * retrained vector must be added as a NEW named set, never edited in place, or historical
         * replay silently changes meaning.
         */
        val DEFAULT_WEIGHTS = doubleArrayOf(
            0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666,
            0.796, 1.4835, 0.0614, 0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542,
        )

        /** Immutable identity of the weight vector above, stored per review log. */
        const val DEFAULT_PARAMETER_SET_ID = "FSRS6-DEFAULT-21"
    }
}

/** The FSRS-6 math. Stateless and pure, so every call is reproducible and unit-testable. */
object Fsrs6 {

    /** Same floor as FSRS-5: a stability below this is not a memory the model can reason about. */
    const val S_MIN = 0.01
    private const val D_MIN = 1.0
    private const val D_MAX = 10.0

    /** Probability of recall [elapsedDays] after the last review, given [stability]. */
    fun retrievability(elapsedDays: Double, stability: Double, p: Fsrs6Parameters = Fsrs6Parameters()): Double {
        val t = elapsedDays.coerceAtLeast(0.0)
        val s = stability.coerceAtLeast(S_MIN)
        return (1.0 + p.factor * t / s).pow(p.decay)
    }

    /** Days until recall decays to [requestRetention]. At 0.90 this equals stability, by construction. */
    fun intervalDays(stability: Double, requestRetention: Double, p: Fsrs6Parameters = Fsrs6Parameters()): Double {
        val s = stability.coerceAtLeast(S_MIN)
        return (s / p.factor) * (requestRetention.pow(1.0 / p.decay) - 1.0)
    }

    /** Initial memory state for the very first grade an item receives. */
    fun initialState(grade: Grade, p: Fsrs6Parameters = Fsrs6Parameters()): MemoryState {
        val s0 = p.weights[grade.value - 1].coerceIn(S_MIN, p.maximumIntervalDays * 10)
        return MemoryState(stability = s0, difficulty = initialDifficulty(grade, p))
    }

    /** D₀(G) = w4 − e^(w5·(G−1)) + 1, clamped to [1,10]. Same form as FSRS-5, different weights. */
    fun initialDifficulty(grade: Grade, p: Fsrs6Parameters = Fsrs6Parameters()): Double {
        val w = p.weights
        return (w[4] - exp(w[5] * (grade.value - 1)) + 1.0).coerceIn(D_MIN, D_MAX)
    }

    /**
     * The next memory state after a review.
     *
     * @param elapsedDays time since the item was last seen; drives R, and selects the same-day branch.
     */
    fun nextState(
        current: MemoryState,
        elapsedDays: Double,
        grade: Grade,
        p: Fsrs6Parameters = Fsrs6Parameters(),
    ): MemoryState {
        val r = retrievability(elapsedDays, current.stability, p)
        val newDifficulty = nextDifficulty(current.difficulty, grade, p)
        val newStability = when {
            elapsedDays < 1.0 -> shortTermStability(current.stability, grade, p)
            grade == Grade.Again -> postLapseStability(current, r, p)
            else -> recallStability(current, r, grade, p)
        }
        return MemoryState(
            stability = newStability.coerceIn(S_MIN, p.maximumIntervalDays * 10),
            difficulty = newDifficulty,
        )
    }

    // --- internals -----------------------------------------------------------------------------

    /**
     * FSRS-6 same-day update: S' = S · e^(w17·(G − 3 + w18)) · S^(−w19).
     * The S^(−w19) term is new in FSRS-6 — it damps same-day gains as stability grows, so cramming a
     * well-known topic stops paying the same dividend as cramming a shaky one.
     */
    private fun shortTermStability(stability: Double, grade: Grade, p: Fsrs6Parameters): Double {
        val w = p.weights
        val s = stability.coerceAtLeast(S_MIN)
        return s * exp(w[17] * (grade.value - 3 + w[18])) * s.pow(-w[19])
    }

    /** Linear damping of the grade delta, then mean reversion toward D₀(Easy). Unchanged from FSRS-5. */
    private fun nextDifficulty(difficulty: Double, grade: Grade, p: Fsrs6Parameters): Double {
        val w = p.weights
        val deltaD = -w[6] * (grade.value - 3)
        val damped = difficulty + deltaD * (10.0 - difficulty) / 9.0
        return (w[7] * initialDifficulty(Grade.Easy, p) + (1.0 - w[7]) * damped).coerceIn(D_MIN, D_MAX)
    }

    /** Stability growth on a successful recall (Hard/Good/Easy). Always grows S. */
    private fun recallStability(s: MemoryState, r: Double, grade: Grade, p: Fsrs6Parameters): Double {
        val w = p.weights
        val hardPenalty = if (grade == Grade.Hard) w[15] else 1.0
        val easyBonus = if (grade == Grade.Easy) w[16] else 1.0
        val increment = exp(w[8]) *
            (11.0 - s.difficulty) *
            s.stability.pow(-w[9]) *
            (exp(w[10] * (1.0 - r)) - 1.0) *
            hardPenalty *
            easyBonus
        return s.stability * (1.0 + increment)
    }

    /**
     * Stability after a lapse. Capped at the pre-lapse stability so forgetting can never strengthen
     * memory. The two bounds are applied SEPARATELY — a single `coerceIn(S_MIN, stability)` throws on
     * an empty range when the incoming stability is itself below the floor, which is reachable from
     * restored backup data (the same defect already fixed in [Fsrs]).
     */
    private fun postLapseStability(s: MemoryState, r: Double, p: Fsrs6Parameters): Double {
        val w = p.weights
        val sFail = w[11] *
            s.difficulty.pow(-w[12]) *
            ((s.stability + 1.0).pow(w[13]) - 1.0) *
            exp(w[14] * (1.0 - r))
        return sFail.coerceAtMost(s.stability).coerceAtLeast(S_MIN)
    }
}
