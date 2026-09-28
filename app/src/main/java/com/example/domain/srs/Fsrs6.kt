package com.example.domain.srs

import kotlin.math.exp
import kotlin.math.pow

/**
 * FSRS-6 — the current stable Free Spaced Repetition Scheduler.
 *
 * Added ALONGSIDE [Fsrs] (FSRS-5), not replacing it. Yadora is published, so every existing review
 * log was produced by FSRS-5 and must keep replaying under FSRS-5 forever; a stored memory state is
 * only meaningful together with the model that produced it. This is now the LIVE model
 * (`MedScheduler.CURRENT_MODEL`); topics cross over lazily via `projectOntoCurrentModel`.
 *
 * Conformance is verified against py-fsrs 6.3.1 itself by `Fsrs6GoldenVectorTest`, not against a
 * transcription of the equations — three deviations once survived a green hand-written spec suite
 * because the same misreading produced both the code and the test.
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

        /**
         * Immutable identity stored per review log. Names the WEIGHTS **and** the implementation
         * they run through. Both matter: the same
         * vector evaluated by a subtly different set of equations produces a different stability,
         * and calibration that pooled the two would be averaging two models under one label. The
         * suffix is the exact reference release these values are verified against by
         * Fsrs6GoldenVectorTest, so a future conformance change forces a new id here.
         */
        const val DEFAULT_PARAMETER_SET_ID = "FSRS6-DEFAULT-21-PYFSRS-6.3.1"
    }
}

/** The FSRS-6 math. Stateless and pure, so every call is reproducible and unit-testable. */
object Fsrs6 {

    /**
     * The reference stability floor is 0.001 days, not the 0.01 FSRS-5 used here. It only binds after
     * a long run of consecutive lapses -- the product layer floors every INTERVAL at a day regardless
     * -- but a floor ten times too high quietly changes the transitions that follow it, so it matches
     * the reference exactly. FSRS-5 keeps 0.01 because its stored states were produced under it.
     */
    const val S_MIN = 0.001
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
        val s0 = p.weights[grade.value - 1].coerceAtLeast(S_MIN)
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
        // Minimum only, as the reference does. A CEILING on stability is not the same thing as a
        // ceiling on intervals: the interval cap belongs in MedScheduler, where it is a product
        // decision about how far ahead to schedule. Capping the STATE instead corrupts the memory
        // model itself — every later transition reads a stability the evidence does not support,
        // and the error persists after the topic is forgotten and rebuilt.
        return MemoryState(
            stability = newStability.coerceAtLeast(S_MIN),
            difficulty = newDifficulty,
        )
    }

    // --- internals -----------------------------------------------------------------------------

    /**
     * FSRS-6 same-day update: S' = S · e^(w17·(G − 3 + w18)) · S^(−w19).
     * The S^(−w19) term is new in FSRS-6 — it damps same-day gains as stability grows, so cramming a
     * well-known topic stops paying the same dividend as cramming a shaky one.
     */
    internal fun shortTermStability(stability: Double, grade: Grade, p: Fsrs6Parameters): Double {
        val w = p.weights
        val s = stability.coerceAtLeast(S_MIN)
        var increase = exp(w[17] * (grade.value - 3 + w[18])) * s.pow(-w[19])
        // The reference floors the MULTIPLIER at 1 for GOOD and EASY only: restudying something on
        // the same day and getting it right cannot make the memory weaker than not restudying it.
        // Without this the S^(-w19) damping term drives the multiplier below 1 once stability is
        // large, so a same-day Good on a mature topic silently SHRANK its stability.
        //
        // Hard is deliberately NOT floored, and that is not an oversight: py-fsrs 6.3.1 -- the pinned
        // released reference -- lists only (Good, Easy). py-fsrs 6.3.2 (released 2026-08-09) widens it
        // to include Hard, so a same-day Hard on a 100-day topic keeps 100 there and drops to 45 here.
        // The pin stays: adopting 6.3.2 is a new model identity (old history must keep replaying under
        // the rules that computed it), and the app almost never reviews a topic twice in one day
        // (CLAUDE.md, the conformance entry).
        if (grade == Grade.Good || grade == Grade.Easy) increase = increase.coerceAtLeast(1.0)
        return (s * increase).coerceAtLeast(S_MIN)
    }

    /** Linear damping of the grade delta, then mean reversion toward D₀(Easy). Unchanged from FSRS-5. */
    internal fun nextDifficulty(difficulty: Double, grade: Grade, p: Fsrs6Parameters): Double {
        val w = p.weights
        val deltaD = -w[6] * (grade.value - 3)
        val damped = difficulty + deltaD * (10.0 - difficulty) / 9.0
        // Mean reversion pulls toward the RAW D0(Easy), which with the FSRS-6 weights is -4.77 --
        // far outside the [1,10] band a stored difficulty lives in. The reference is explicit about
        // this -- py-fsrs calls _initial_difficulty(rating=Easy, clamp=False) here and clamp=True
        // only when seeding a new card: the clamp applies to a difficulty being STORED, not to the
        // reversion target. Using the clamped 1.0 instead shifts every
        // review by w7 * 5.77 in the easy direction -- tiny per review, accumulating over years.
        return (w[7] * rawInitialDifficulty(Grade.Easy, p) + (1.0 - w[7]) * damped)
            .coerceIn(D_MIN, D_MAX)
    }

    /** D0(G) before the storage clamp. Only mean reversion wants this; everything else wants [initialDifficulty]. */
    private fun rawInitialDifficulty(grade: Grade, p: Fsrs6Parameters): Double {
        val w = p.weights
        return w[4] - exp(w[5] * (grade.value - 1)) + 1.0
    }

    /** Stability growth on a successful recall (Hard/Good/Easy). Always grows S. */
    internal fun recallStability(s: MemoryState, r: Double, grade: Grade, p: Fsrs6Parameters): Double {
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
    internal fun postLapseStability(s: MemoryState, r: Double, p: Fsrs6Parameters): Double {
        val w = p.weights
        val longTerm = w[11] *
            s.difficulty.pow(-w[12]) *
            ((s.stability + 1.0).pow(w[13]) - 1.0) *
            exp(w[14] * (1.0 - r))
        // The reference bounds a lapse by the SHORT-TERM branch, S / e^(w17*w18), not by S itself.
        // With these weights that is 0.9518*S, so a forgotten topic must always come out at least
        // ~4.8% weaker. Capping at S let a mature memory survive a genuine failure untouched.
        val shortTerm = s.stability / exp(w[17] * w[18])
        return minOf(longTerm, shortTerm).coerceAtLeast(S_MIN)
    }
}
