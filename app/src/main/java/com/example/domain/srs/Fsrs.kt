package com.example.domain.srs

import kotlin.math.exp
import kotlin.math.pow

/**
 * FSRS — the Free Spaced Repetition Scheduler (the modern DSR memory model).
 *
 * This is a faithful Kotlin implementation of the **FSRS-5** update rules. It is pure Kotlin
 * (no Android dependencies) so it is fully deterministic and unit-testable.
 *
 * A study unit's memory is summarised by two stored values:
 *  - **stability**  S : the number of days for recall probability to fall from 100% to 90%.
 *  - **difficulty** D : 1..10, the intrinsic hardness; higher D => slower stability growth.
 *
 * **Retrievability** R (current recall probability) is always COMPUTED from elapsed time and S
 * via [retrievability]; it is never stored, so it can never go stale (a real bug in the previous
 * scheduler was storing R computed *after* updating S).
 *
 * IMPORTANT: [FsrsParameters.DEFAULT_WEIGHTS] are the published FSRS-5 defaults. They are tunable
 * constants meant to be re-trained on real review data later. Before relying on exact numeric
 * output in production, cross-check the formulas and defaults against the reference implementation
 * (github.com/open-spaced-repetition/py-fsrs).
 */

/** A review grade. [value] is the canonical FSRS grade: Again=1, Hard=2, Good=3, Easy=4. */
enum class Grade(val value: Int) {
    Again(1), Hard(2), Good(3), Easy(4);

    companion object {
        fun of(value: Int): Grade = entries.first { it.value == value }
    }
}

/** The two stored memory components for a study unit. */
data class MemoryState(val stability: Double, val difficulty: Double)

/**
 * FSRS configuration: the 19 model weights, the target recall probability, and an interval ceiling.
 *
 * @param requestRetention the probability of recall we schedule for (0.90 is the standard default;
 *        higher means more frequent reviews). MedReview raises this for high-yield items.
 */
class FsrsParameters(
    val weights: DoubleArray = DEFAULT_WEIGHTS,
    val requestRetention: Double = 0.90,
    val maximumIntervalDays: Double = 365.0,
) {
    init {
        // Exact count, not >=: FSRS-6 weights (21) silently truncated through FSRS-5 formulas would
        // produce wrong schedules while still claiming to be FSRS-5.
        require(weights.size == 19) { "FSRS-5 requires exactly 19 weights, got ${weights.size}" }
        require(requestRetention in 0.70..0.99) { "requestRetention $requestRetention out of sane range" }
        require(maximumIntervalDays >= 1.0) { "maximumIntervalDays must be >= 1" }
    }

    companion object {
        /**
         * Published FSRS-5 default parameters (w0..w18). w0..w3 are the initial stabilities for
         * Again/Hard/Good/Easy. These are defaults; retrain on user data for best accuracy.
         */
        val DEFAULT_WEIGHTS = doubleArrayOf(
            0.40255, 1.18385, 3.173, 15.69105, 7.1949, 0.5345, 1.4604, 0.0046, 1.54575,
            0.1192, 1.01925, 1.9395, 0.11, 0.29605, 2.2698, 0.2315, 2.9898, 0.51655, 0.6621,
        )
    }
}

/** The core FSRS math. Stateless; all inputs are explicit, so every call is reproducible. */
object Fsrs {

    /** Forgetting-curve shape constant. With DECAY = -0.5 the curve is power-law (FSRS-4.5+). */
    const val DECAY = -0.5

    /** FACTOR is chosen so that R = 0.9 exactly when elapsed time == stability. Equals 19/81. */
    val FACTOR: Double = 0.9.pow(1.0 / DECAY) - 1.0

    private const val S_MIN = 0.01
    private const val D_MIN = 1.0
    private const val D_MAX = 10.0

    /**
     * Probability of recalling an item [elapsedDays] after it was last seen, given [stability].
     * R(0) = 1, R(stability) = 0.9, monotonically decreasing in elapsed time.
     */
    fun retrievability(elapsedDays: Double, stability: Double): Double {
        val t = elapsedDays.coerceAtLeast(0.0)
        val s = stability.coerceAtLeast(S_MIN)
        return (1.0 + FACTOR * t / s).pow(DECAY)
    }

    /**
     * The number of days until recall probability decays to [requestRetention] for the given
     * [stability]. This is the interval the scheduler hands back. (At requestRetention = 0.9 the
     * interval equals the stability by construction.)
     */
    fun intervalDays(stability: Double, requestRetention: Double): Double {
        val s = stability.coerceAtLeast(S_MIN)
        return (s / FACTOR) * (requestRetention.pow(1.0 / DECAY) - 1.0)
    }

    /** Initial memory state for the very first grade an item receives. */
    fun initialState(grade: Grade, p: FsrsParameters = FsrsParameters()): MemoryState {
        val s0 = p.weights[grade.value - 1].coerceIn(S_MIN, p.maximumIntervalDays * 10)
        return MemoryState(stability = s0, difficulty = initialDifficulty(grade, p))
    }

    /** FSRS-5 initial difficulty: D0(G) = w4 - e^(w5*(G-1)) + 1, clamped to [1,10]. */
    fun initialDifficulty(grade: Grade, p: FsrsParameters = FsrsParameters()): Double {
        val w = p.weights
        val d = w[4] - exp(w[5] * (grade.value - 1)) + 1.0
        return d.coerceIn(D_MIN, D_MAX)
    }

    /**
     * The next memory state after a review.
     *
     * @param current      the state before this review.
     * @param elapsedDays  time since the item was last seen (used to compute R at review time).
     * @param grade        the user's recall grade for this review.
     */
    fun nextState(
        current: MemoryState,
        elapsedDays: Double,
        grade: Grade,
        p: FsrsParameters = FsrsParameters(),
    ): MemoryState {
        val r = retrievability(elapsedDays, current.stability)
        val newDifficulty = nextDifficulty(current.difficulty, grade, p)
        val newStability = when {
            // FSRS-5 short-term memory: a same-day re-review still strengthens stability. Without this,
            // the normal recall/lapse growth term is ~0 when elapsed ≈ 0, so re-reviewing the same day
            // (e.g. cramming before an exam) would be a no-op.
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

    /** FSRS-5 short-term (same-day, elapsed < 1d) stability: S' = S · e^(w17 · (G − 3 + w18)). */
    private fun shortTermStability(stability: Double, grade: Grade, p: FsrsParameters): Double {
        val w = p.weights
        return stability * exp(w[17] * (grade.value - 3 + w[18]))
    }

    /** FSRS-5 difficulty update: linear damping of the grade delta, then mean reversion to D0(Easy). */
    private fun nextDifficulty(difficulty: Double, grade: Grade, p: FsrsParameters): Double {
        val w = p.weights
        val deltaD = -w[6] * (grade.value - 3)
        val damped = difficulty + deltaD * (10.0 - difficulty) / 9.0
        val reverted = w[7] * initialDifficulty(Grade.Easy, p) + (1.0 - w[7]) * damped
        return reverted.coerceIn(D_MIN, D_MAX)
    }

    /** FSRS-5 stability growth on a successful recall (Hard/Good/Easy). Always grows S (>= old S). */
    private fun recallStability(s: MemoryState, r: Double, grade: Grade, p: FsrsParameters): Double {
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
     * FSRS-5 stability after a lapse (Again). Clamped to never exceed the pre-lapse stability, so
     * forgetting always shortens the next interval.
     */
    private fun postLapseStability(s: MemoryState, r: Double, p: FsrsParameters): Double {
        val w = p.weights
        val sFail = w[11] *
            s.difficulty.pow(-w[12]) *
            ((s.stability + 1.0).pow(w[13]) - 1.0) *
            exp(w[14] * (1.0 - r))
        return sFail.coerceIn(S_MIN, s.stability)
    }
}
