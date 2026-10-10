package com.example.domain.srs

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Fits FSRS-6's 21 weights to ONE learner's own review history, and decides whether the fit may be used.
 *
 * WHY. The default weights are an average over Anki flashcard users. On the public benchmark, weights
 * fitted to each user's own history cut calibration error by about 30% against the defaults (RMSE over
 * bins 0.063 vs 0.091 for FSRS-7; log loss 0.340 vs 0.362), and a Yadora topic is a bigger unit than
 * the flashcards the defaults were fitted on, so the gap is unlikely to be smaller here.
 * [RecallCalibration] corrects one number; this refits the whole curve: how fast a first study fades,
 * how much each successful review buys, what a lapse costs, and the shape of the forgetting curve.
 *
 * HOW. The training procedure is py-fsrs 6.3.1's own (`optimizer.py`): binary cross-entropy between the
 * predicted recall probability and the outcome, same-day reviews excluded from the loss but still
 * updating the state, the first 64 reviews of each item, Adam (learning rate 0.04) with cosine
 * annealing over five epochs in mini-batches of 512 reviews, every weight clamped to the reference
 * bounds after each step, starting from the default weights, and no fit at all below 512 reviews. The
 * initial stabilities are fitted first, from each first grade's second reviews, as the FSRS optimizer
 * shipped in Anki does ([pretrainInitialStability]); five epochs could not move them far enough alone.
 * Gradients are exact: the forward pass runs on dual numbers carrying all 21 partial derivatives, and it
 * is checked against the verified [Fsrs6] (values) and central finite differences (gradients).
 *
 * THE GATE — Yadora's addition, because py-fsrs has none. A fit is only USED if it predicts this
 * learner's LATER reviews better than the weights already in use: the history is cut in time into five
 * chunks, each of the last four is predicted by a model fitted only on the reviews before it ([FOLDS]),
 * and per-review log loss is compared pairwise over all four. It must be better with a one-sided z of at
 * least [ACCEPT_Z]. Only then is the model refitted on everything, and it is returned only if it also keeps the
 * first rating's grades in order ([keepsGradeOrder]) and does not lengthen this learner's intervals ([lengthening]).
 * Better-on-average-by-chance is not good enough to move every future interval.
 *
 * MEASURED, not assumed (`Fsrs6OptimizerGateTest`, simulated learners reviewed on the default schedule):
 * a learner the defaults already describe was adopted 0 times in 40 refits, and refitting monthly makes
 * false adoptions add up, hence 1% rather than 5%. MODERATE departures (first studies 40% weaker, slower
 * growth) were adopted 0 times in 10: once real reviews have corrected each topic's state, the defaults'
 * predictions differ too little to be worth the risk. A STRONG departure (first studies 60% weaker, much
 * slower growth, a steeper curve) was adopted 9 times in 10 at 700 topics (about 9,000 reviews) and not
 * yet at 300. Before the pretrain and the folds it was found about half the time.
 */
object Fsrs6Optimizer {

    const val N = 21

    /** py-fsrs 6.3.1 `LOWER_BOUNDS_PARAMETERS` (scheduler.py), in weight order. */
    val LOWER = doubleArrayOf(
        0.001, 0.001, 0.001, 0.001, 1.0, 0.001, 0.001, 0.001, 0.0, 0.0,
        0.001, 0.001, 0.001, 0.001, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.1,
    )

    /** py-fsrs 6.3.1 `UPPER_BOUNDS_PARAMETERS` (scheduler.py), in weight order. */
    val UPPER = doubleArrayOf(
        100.0, 100.0, 100.0, 100.0, 10.0, 4.0, 4.0, 0.75, 4.5, 0.8,
        3.5, 5.0, 0.25, 0.9, 4.0, 1.0, 6.0, 2.0, 2.0, 0.8, 0.8,
    )

    /** py-fsrs 6.3.1's training constants. */
    data class Config(
        val learningRate: Double = 4e-2,
        val epochs: Int = 5,
        val batchSize: Int = 512,
        val minTrainReviews: Int = 512,
        val maxStepsPerHistory: Int = 64,
        val shuffleSeed: Int = 42,
    )

    /** Whether every weight is finite and inside the reference bounds. */
    fun withinBounds(w: DoubleArray): Boolean =
        w.size == N && w.indices.all { w[it].isFinite() && w[it] >= LOWER[it] && w[it] <= UPPER[it] }

    /**
     * Whether the first rating's grades keep their order: Again <= Hard <= Good <= Easy initial stability, so a harder
     * first answer never brings a topic back later. [train] keeps it after every step; before that, Adam moved each
     * weight on its own within its bounds (as py-fsrs 6.3.1 does), and an outside audit (2026-09-30) built histories in
     * which topics first rated Hard were remembered better later: the fit was adopted with S0(Hard) 3.53 d over
     * S0(Good) 2.51 d, so "Hard" previewed a later first review than "Medium". The gate checks it again before adopting.
     */
    fun keepsGradeOrder(w: DoubleArray): Boolean = w.size == N && w[0] <= w[1] && w[1] <= w[2] && w[2] <= w[3]

    /**
     * NEVER LENGTHEN, the calibration's rule (`RecallCalibration.MAX_ESTIMATE`) applied to the personal set too (the
     * owner's decision, 2026-10-02): how much longer [w] would schedule this learner's topics than the published
     * defaults. Each set replays every history to its own final state and gives the next interval at [retention], inside
     * the scheduler's bounds; the result is the geometric mean of the ratios, and above 1 the set lengthens.
     *
     * Self-ratings cannot tell a slow forgetter from a generous rater, and the fit learns from the same ratings the
     * calibration distrusts. An outside audit (2026-09-30) showed it: with 60% of forgotten topics rated Hard, a set was
     * adopted at z = 2.66 that predicted the RATINGS better and the true recall worse, and it lengthened intervals by
     * about a fifth on average. `Fsrs6OptimizerTest` keeps that case.
     *
     * The baseline is the published defaults, not the defaults times this learner's calibration, so an adopted set can
     * schedule longer than the calibrated intervals it replaces, never longer than the defaults (an outside audit,
     * 2026-10-03: a calibration of x0.51 gave way to a set at x0.70). On purpose: one calibration number cannot bend
     * the curve, so for a learner whose forgetting is steeper it over-shortens, often to its x0.5 floor, and the set that
     * models the curve predicts better. Measured (`Fsrs6OptimizerGateTest`), a calibrated baseline would have refused
     * 22 of 40 such sets the defaults' baseline adopts.
     */
    fun lengthening(histories: List<History>, w: DoubleArray, retention: Double): Double {
        if (histories.isEmpty()) return 1.0
        val fitted = Fsrs6Parameters(weights = w)
        val defaults = Fsrs6Parameters(weights = Fsrs6Parameters.DEFAULT_WEIGHTS)
        fun nextInterval(h: History, p: Fsrs6Parameters): Double {
            var state = Fsrs6.initialState(Grade.entries.first { it.value == h.grades[0] }, p)
            for (i in 1 until h.size) state = Fsrs6.nextState(state, h.elapsedDays[i], Grade.entries.first { it.value == h.grades[i] }, p, h.floorHard[i])
            return Fsrs6.intervalDays(state.stability, retention, p).coerceIn(MedScheduler.MIN_INTERVAL_DAYS, MedScheduler.MAX_INTERVAL_DAYS)
        }
        return exp(histories.sumOf { ln(nextInterval(it, fitted) / nextInterval(it, defaults)) } / histories.size)
    }

    /** Stored form of a weight vector: Kotlin's locale-independent, round-tripping Double text. */
    fun encode(w: DoubleArray): String = w.joinToString(",")

    /** A stored weight vector, or null unless it is exactly 21 finite weights inside the bounds. */
    fun decode(stored: String): DoubleArray? {
        val parts = stored.split(",")
        if (parts.size != N) return null
        val w = DoubleArray(N) { parts[it].trim().toDoubleOrNull() ?: return null }
        return w.takeIf { withinBounds(it) }
    }

    /** Below this many loss-eligible reviews no fit is attempted: 512 to train on after holding out 20%. */
    const val MIN_REVIEWS_FOR_A_FIT = 640

    // --- histories ---------------------------------------------------------------------------------

    /** One topic's graded history as the model sees it: step 0 seeds, every later step is a transition. */
    class History(
        val grades: IntArray,
        val elapsedDays: DoubleArray,
        val reviewedAt: LongArray,
        /** Saved log ids in the app; synthetic histories may use their monotone timestamps. Never elapsed time. */
        val validationOrder: LongArray = reviewedAt,
        /**
         * Per step, the same-day rule of the policy that produced the review (MedScheduler.floorsSameDayHard): true is
         * py-fsrs 6.3.2, live since YADORA-9; false is 6.3.1, for a review stamped earlier. Every replay of a history,
         * this one included, rebuilds the states the scheduler had.
         */
        val floorHard: BooleanArray = BooleanArray(grades.size) { true },
    ) {
        init {
            require(grades.isNotEmpty() && grades.size == elapsedDays.size && grades.size == reviewedAt.size)
            require(floorHard.size == grades.size)
            require(grades.all { it in 1..4 })
            require(validationOrder.size == grades.size)
            require((1 until validationOrder.size).all { validationOrder[it] >= validationOrder[it - 1] }) {
                "validation order must not run backwards; supply saved review ids for a clock rollback"
            }
        }

        val size: Int get() = grades.size

        /** Steps that count in the loss: transitions at least one whole day after the previous step. */
        fun inLoss(step: Int): Boolean = step >= 1 && elapsedDays[step] >= 1.0

        internal fun capped(maxSteps: Int): History =
            if (size <= maxSteps) this
            else History(grades.copyOf(maxSteps), elapsedDays.copyOf(maxSteps), reviewedAt.copyOf(maxSteps), validationOrder.copyOf(maxSteps), floorHard.copyOf(maxSteps))

        /** A prefix strictly before a validation boundary; wall time still determines the memory model's gaps. */
        internal fun before(order: Long): History? {
            val k = validationOrder.indexOfFirst { it >= order }.let { if (it < 0) size else it }
            return if (k == 0) null else History(grades.copyOf(k), elapsedDays.copyOf(k), reviewedAt.copyOf(k), validationOrder.copyOf(k), floorHard.copyOf(k))
        }
    }

    /** A review log as the optimizer needs it, in saved order. */
    /** [storedElapsedDays]: the day count the review was scheduled with when a replay may reuse it (MedScheduler.storedModelDays). */
    /** [policyVersion]: the policy the review was scheduled under, which decides its same-day rule ([History.floorHard]). */
    data class Event(val reviewedAt: Long, val memoryRating: String, val logType: String,
        val storedElapsedDays: Double? = null, val validationOrder: Long = reviewedAt, val policyVersion: String = "")

    /**
     * A topic's history reconstructed EXACTLY as `MedReviewRepository.projectWithHistory` rebuilds its
     * state: the first log with a valid rating seeds; a LATER first-study row is a re-encoding exposure
     * that earns nothing but re-anchors the clock; a row with an unreadable rating is skipped without
     * moving the clock; time between steps is whole days the way the live model counts it: the count each review was
     * scheduled with when it is stored, never one counted again in today's time zone.
     */
    fun historyOf(events: List<Event>, elapsedDays: (from: Long, to: Long) -> Double): History? {
        val grades = ArrayList<Int>(events.size)
        val deltas = ArrayList<Double>(events.size)
        val times = ArrayList<Long>(events.size)
        val order = ArrayList<Long>(events.size)
        val floors = ArrayList<Boolean>(events.size)
        var prevTime = 0L
        for ((index, e) in events.withIndex()) {
            if (index > 0 && e.logType == "FIRST_STUDY") {
                prevTime = MedScheduler.exposureClock(prevTime, e.reviewedAt)
                continue
            }
            val g = gradeOf(e.memoryRating) ?: continue
            deltas.add(if (grades.isEmpty()) 0.0 else MedScheduler.completedModelDays(e.storedElapsedDays ?: elapsedDays(prevTime, e.reviewedAt)))
            grades.add(g)
            times.add(e.reviewedAt)
            order.add(e.validationOrder)
            floors.add(MedScheduler.floorsSameDayHard(e.policyVersion.ifEmpty { MedScheduler.POLICY_VERSION }))
            prevTime = e.reviewedAt
        }
        if (grades.isEmpty()) return null
        return History(grades.toIntArray(), deltas.toDoubleArray(), times.toLongArray(), order.toLongArray(), floors.toBooleanArray())
    }

    private fun gradeOf(rating: String): Int? = when (rating) {
        "Forgot" -> 1
        "Hard" -> 2
        "Good" -> 3
        "Easy" -> 4
        else -> null
    }

    // --- dual numbers ------------------------------------------------------------------------------

    /** A value with its 21 partial derivatives. Never mutated, so arrays may be shared. */
    internal class Dual(@JvmField val v: Double, @JvmField val d: DoubleArray) {
        operator fun plus(o: Dual) = Dual(v + o.v, DoubleArray(N) { d[it] + o.d[it] })
        operator fun minus(o: Dual) = Dual(v - o.v, DoubleArray(N) { d[it] - o.d[it] })
        operator fun times(o: Dual) = Dual(v * o.v, DoubleArray(N) { d[it] * o.v + o.d[it] * v })
        operator fun div(o: Dual): Dual {
            val inv = 1.0 / o.v
            return Dual(v * inv, DoubleArray(N) { (d[it] - v * inv * o.d[it]) * inv })
        }
        operator fun plus(c: Double) = Dual(v + c, d)
        operator fun minus(c: Double) = Dual(v - c, d)
        operator fun times(c: Double) = Dual(v * c, DoubleArray(N) { d[it] * c })
        operator fun div(c: Double) = Dual(v / c, DoubleArray(N) { d[it] / c })
        operator fun unaryMinus() = Dual(-v, DoubleArray(N) { -d[it] })

        fun exp(): Dual {
            val e = kotlin.math.exp(v)
            return Dual(e, DoubleArray(N) { d[it] * e })
        }

        fun pow(c: Double): Dual {
            val k = c * v.pow(c - 1.0)
            return Dual(v.pow(c), DoubleArray(N) { d[it] * k })
        }

        fun pow(e: Dual): Dual {
            val p = v.pow(e.v)
            val lnV = ln(v)
            return Dual(p, DoubleArray(N) { p * (e.d[it] * lnV + e.v * d[it] / v) })
        }

        companion object {
            private val ZERO = DoubleArray(N)
            fun const(c: Double) = Dual(c, ZERO)
            fun param(w: DoubleArray, i: Int) = Dual(w[i], DoubleArray(N).also { it[i] = 1.0 })
        }
    }

    private operator fun Double.minus(o: Dual) = Dual(this - o.v, DoubleArray(N) { -o.d[it] })

    private fun atLeast(a: Dual, c: Double) = if (a.v < c) Dual.const(c) else a
    private fun smaller(a: Dual, b: Dual) = if (a.v <= b.v) a else b
    private fun clamp(a: Dual, lo: Double, hi: Double) = when {
        a.v < lo -> Dual.const(lo)
        a.v > hi -> Dual.const(hi)
        else -> a
    }

    /** FSRS-6 on dual numbers, equation for equation the same as [Fsrs6] (see its comments for why). */
    internal class DualModel(w: DoubleArray) {
        private val p = Array(N) { Dual.param(w, it) }
        private val decay = -p[20]
        private val factor = Dual(0.9.pow(1.0 / decay.v), DoubleArray(N) { i ->
            // d/dw 0.9^(1/decay) = 0.9^(1/decay) · ln 0.9 · (−1/decay²) · d decay
            0.9.pow(1.0 / decay.v) * ln(0.9) * (-1.0 / (decay.v * decay.v)) * decay.d[i]
        }) - 1.0
        private val rawEasyD0 = p[4] - (p[5] * 3.0).exp() + 1.0

        fun retrievability(t: Double, s: Dual): Dual =
            ((factor * t) / atLeast(s, Fsrs6.S_MIN) + 1.0).pow(decay)

        fun initialStability(g: Int): Dual = atLeast(p[g - 1], Fsrs6.S_MIN)

        fun initialDifficulty(g: Int): Dual = clamp(p[4] - (p[5] * (g - 1).toDouble()).exp() + 1.0, 1.0, 10.0)

        /** One transition, with [floorHard] the review's same-day rule (py-fsrs 6.3.2 when true, 6.3.1 when false). */
        fun next(s: Dual, dd: Dual, t: Double, r: Dual, g: Int, floorHard: Boolean = true): Pair<Dual, Dual> {
            val damped = dd + (p[6] * (-(g - 3).toDouble())) * (10.0 - dd) / 9.0
            val nextD = clamp(p[7] * rawEasyD0 + (1.0 - p[7]) * damped, 1.0, 10.0)
            val nextS = when {
                t < 1.0 -> {
                    val sc = atLeast(s, Fsrs6.S_MIN)
                    var inc = (p[17] * (p[18] + (g - 3).toDouble())).exp() * sc.pow(-p[19])
                    if (g >= 3 || (floorHard && g == 2)) inc = atLeast(inc, 1.0)
                    atLeast(sc * inc, Fsrs6.S_MIN)
                }
                g == 1 -> {
                    val longTerm = p[11] * dd.pow(-p[12]) * ((s + 1.0).pow(p[13]) - 1.0) * (p[14] * (1.0 - r)).exp()
                    val shortTerm = s / (p[17] * p[18]).exp()
                    atLeast(smaller(longTerm, shortTerm), Fsrs6.S_MIN)
                }
                else -> {
                    var increment = p[8].exp() * (11.0 - dd) * s.pow(-p[9]) * ((p[10] * (1.0 - r)).exp() - 1.0)
                    if (g == 2) increment = increment * p[15]
                    if (g == 4) increment = increment * p[16]
                    s * (increment + 1.0)
                }
            }
            return atLeast(nextS, Fsrs6.S_MIN) to nextD
        }
    }

    // --- loss --------------------------------------------------------------------------------------

    private const val LOG_FLOOR = -100.0 // torch.nn.BCELoss clamps its logarithms here

    private fun bce(r: Double, recalled: Boolean): Double =
        -(if (recalled) maxOf(ln(r), LOG_FLOOR) else maxOf(ln(1.0 - r), LOG_FLOOR))

    /** d(bce)/dr, zero where the logarithm is clamped. */
    private fun bceSlope(r: Double, recalled: Boolean): Double =
        if (recalled) { if (ln(r) > LOG_FLOOR) -1.0 / r else 0.0 } else { if (ln(1.0 - r) > LOG_FLOOR) 1.0 / (1.0 - r) else 0.0 }

    internal class LossSum(val loss: Double, val gradient: DoubleArray, val reviews: Int)

    /** Summed loss and gradient over the steps [include] selects, at weights [w]. */
    internal fun lossAndGradient(histories: List<History>, w: DoubleArray, include: (History, Int) -> Boolean): LossSum {
        val model = DualModel(w)
        var loss = 0.0
        val grad = DoubleArray(N)
        var reviews = 0
        for (h in histories) {
            var s = model.initialStability(h.grades[0])
            var dd = model.initialDifficulty(h.grades[0])
            for (i in 1 until h.size) {
                val t = h.elapsedDays[i]
                val r = model.retrievability(t, s)
                if (include(h, i)) {
                    val recalled = h.grades[i] > 1
                    loss += bce(r.v, recalled)
                    val slope = bceSlope(r.v, recalled)
                    for (k in 0 until N) grad[k] += slope * r.d[k]
                    reviews++
                }
                val (ns, nd) = model.next(s, dd, t, r, h.grades[i], h.floorHard[i])
                s = ns
                dd = nd
            }
        }
        return LossSum(loss, grad, reviews)
    }

    // --- training ----------------------------------------------------------------------------------

    /** Pseudo-reviews of belief in the default initial stability of each first grade. */
    const val PRETRAIN_PRIOR = 10.0

    /**
     * Initial stabilities (w0–w3) fitted before gradient descent, the way the FSRS optimizer that ships in
     * Anki does it: every topic is grouped by its FIRST grade, and the stability that best explains the
     * outcomes of the SECOND reviews in that group, on the current curve, is found by a one-dimensional
     * search. Thin evidence is shrunk toward the default in log space, and initial stability is kept
     * from falling as the first grade rises. Without this, five epochs at a learning rate of 0.04 cannot
     * move them far enough: S₀(Easy) would need to travel five units in about seventy steps. For Yadora
     * this matters more than for flashcards, because the first rating is a check-in given right after
     * studying — exactly the value a topic learner is most likely to disagree with the defaults about.
     */
    internal fun pretrainInitialStability(histories: List<History>, start: DoubleArray): DoubleArray {
        val decay = -start[20]
        val factor = 0.9.pow(1.0 / decay) - 1.0
        val logS = DoubleArray(4)
        val weight = DoubleArray(4)
        for (g in 1..4) {
            val t = ArrayList<Double>()
            val y = ArrayList<Boolean>()
            for (h in histories) {
                if (h.size >= 2 && h.grades[0] == g && h.inLoss(1)) {
                    t += h.elapsedDays[1]
                    y += h.grades[1] > 1
                }
            }
            val prior = ln(start[g - 1])
            if (t.isEmpty()) {
                logS[g - 1] = prior
                weight[g - 1] = PRETRAIN_PRIOR
                continue
            }
            fun loss(ls: Double): Double {
                val s = exp(ls)
                var sum = 0.0
                for (i in t.indices) sum += bce((1.0 + factor * t[i] / s).pow(decay), y[i])
                return sum
            }
            // Golden-section search on ln S inside the reference bounds.
            val phi = (sqrt(5.0) - 1.0) / 2.0
            var lo = ln(LOWER[g - 1])
            var hi = ln(UPPER[g - 1])
            var x1 = hi - phi * (hi - lo)
            var x2 = lo + phi * (hi - lo)
            var f1 = loss(x1)
            var f2 = loss(x2)
            repeat(80) {
                if (f1 <= f2) {
                    hi = x2; x2 = x1; f2 = f1; x1 = hi - phi * (hi - lo); f1 = loss(x1)
                } else {
                    lo = x1; x1 = x2; f1 = f2; x2 = lo + phi * (hi - lo); f2 = loss(x2)
                }
            }
            val n = t.size.toDouble()
            logS[g - 1] = (n * (lo + hi) / 2.0 + PRETRAIN_PRIOR * prior) / (n + PRETRAIN_PRIOR)
            weight[g - 1] = n + PRETRAIN_PRIOR
        }
        val w = start.copyOf()
        poolInOrder(logS, weight, w)
        return w
    }

    /** Evidence behind each first grade's initial stability: the second reviews the pretrain fits it on, plus the prior. */
    internal fun initialStabilityEvidence(histories: List<History>): DoubleArray = DoubleArray(4) { i ->
        histories.count { h -> h.size >= 2 && h.grades[0] == i + 1 && h.inLoss(1) } + PRETRAIN_PRIOR
    }

    /**
     * Pool adjacent violators on ln S0, weighted by [weight], into [w]'s first four weights:
     * S0(Again) <= S0(Hard) <= S0(Good) <= S0(Easy). Where two grades disagree, the one with less evidence moves most, so
     * a first grade the learner never uses follows the ones they do.
     */
    private fun poolInOrder(logS: DoubleArray, weight: DoubleArray, w: DoubleArray) {
        val blocks = ArrayList<DoubleArray>() // [mean ln S, weight, grades pooled]
        for (i in 0 until 4) {
            blocks.add(doubleArrayOf(logS[i], weight[i], 1.0))
            while (blocks.size >= 2 && blocks[blocks.size - 2][0] > blocks[blocks.size - 1][0]) {
                val b = blocks.removeAt(blocks.size - 1)
                val a = blocks.removeAt(blocks.size - 1)
                val total = a[1] + b[1]
                blocks.add(doubleArrayOf((a[0] * a[1] + b[0] * b[1]) / total, total, a[2] + b[2]))
            }
        }
        var i = 0
        for (block in blocks) repeat(block[2].toInt()) {
            w[i] = exp(block[0]).coerceIn(LOWER[i], UPPER[i])
            i++
        }
    }

    /**
     * py-fsrs 6.3.1's procedure from the default weights, after [pretrainInitialStability], or null below
     * [Config.minTrainReviews]. One addition: after every step the initial stabilities are put back in grade order
     * ([poolInOrder]), the constraint the pretrain already applies. py-fsrs clamps each weight on its own, and here
     * that let S0(Good) fall below an S0(Hard) no review moved (a learner who never rates a first study Hard), or let
     * the data lift Hard above Good (an outside audit, 2026-09-30): either way "Hard" would bring a new topic back later
     * than "Medium".
     */
    fun train(histories: List<History>, cfg: Config = Config()): DoubleArray? {
        val items = histories.map { it.capped(cfg.maxStepsPerHistory) }
        val reviews = items.sumOf { h -> (1 until h.size).count { h.inLoss(it) } }
        if (reviews < cfg.minTrainReviews) return null
        val w = pretrainInitialStability(items, Fsrs6Parameters.DEFAULT_WEIGHTS)
        val evidence = initialStabilityEvidence(items)
        val m = DoubleArray(N)
        val v = DoubleArray(N)
        val totalSteps = ceil(reviews / cfg.batchSize.toDouble()).toInt() * cfg.epochs
        var step = 0
        val inLoss: (History, Int) -> Boolean = { h, i -> h.inLoss(i) }
        repeat(cfg.epochs) { epoch ->
            val order = items.indices.shuffled(kotlin.random.Random(cfg.shuffleSeed + epoch))
            val batch = ArrayList<History>()
            var inBatch = 0
            fun takeStep() {
                val sum = lossAndGradient(batch, w, inLoss)
                if (sum.reviews > 0 && sum.gradient.all { it.isFinite() }) {
                    val lr = cfg.learningRate * 0.5 * (1.0 + cos(PI * step / totalSteps))
                    val t = step + 1
                    for (k in 0 until N) {
                        val g = sum.gradient[k] / sum.reviews
                        m[k] = 0.9 * m[k] + 0.1 * g
                        v[k] = 0.999 * v[k] + 0.001 * g * g
                        val mHat = m[k] / (1.0 - 0.9.pow(t))
                        val vHat = v[k] / (1.0 - 0.999.pow(t))
                        w[k] = (w[k] - lr * mHat / (sqrt(vHat) + 1e-8)).coerceIn(LOWER[k], UPPER[k])
                    }
                    if (!keepsGradeOrder(w)) poolInOrder(DoubleArray(4) { ln(w[it]) }, evidence, w)
                }
                step++
                batch.clear()
                inBatch = 0
            }
            for (index in order) {
                val h = items[index]
                batch.add(h)
                inBatch += (1 until h.size).count { h.inLoss(it) }
                if (inBatch >= cfg.batchSize) takeStep()
            }
            if (inBatch > 0) takeStep()
        }
        return w
    }

    // --- evaluation --------------------------------------------------------------------------------

    /** How well a set of predictions matched what happened, on the benchmark's three measures. */
    data class Scores(val reviews: Int, val logLoss: Double, val rmseBins: Double, val auc: Double)

    internal class Evaluation(val predicted: DoubleArray, val recalled: BooleanArray, val losses: DoubleArray, val bins: LongArray)

    /** Predictions from the verified [Fsrs6] at weights [w] for the steps [include] selects. */
    internal fun evaluate(histories: List<History>, w: DoubleArray, include: (History, Int) -> Boolean): Evaluation {
        val params = Fsrs6Parameters(weights = w)
        val predicted = ArrayList<Double>()
        val recalled = ArrayList<Boolean>()
        val bins = ArrayList<Long>()
        for (h in histories) {
            var state = Fsrs6.initialState(Grade.entries.first { it.value == h.grades[0] }, params)
            var lapses = 0
            for (i in 1 until h.size) {
                val t = h.elapsedDays[i]
                val grade = Grade.entries.first { it.value == h.grades[i] }
                if (include(h, i)) {
                    predicted.add(Fsrs6.retrievability(t, state.stability, params))
                    recalled.add(h.grades[i] > 1)
                    bins.add(binOf(t, i, lapses))
                }
                if (h.grades[i] == 1) lapses++
                state = Fsrs6.nextState(state, t, grade, params, h.floorHard[i])
            }
        }
        val p = predicted.toDoubleArray()
        val y = recalled.toBooleanArray()
        return Evaluation(p, y, DoubleArray(p.size) { bce(p[it], y[it]) }, bins.toLongArray())
    }

    /** Interval (20% log steps), review number and lapse count: the three features the benchmark bins by. */
    private fun binOf(elapsedDays: Double, reviewNumber: Int, lapses: Int): Long {
        val intervalBin = Math.round(ln(maxOf(elapsedDays, 1.0)) / ln(1.2))
        return (intervalBin * 64 + minOf(reviewNumber, 63)) * 16 + minOf(lapses, 15)
    }

    fun score(predicted: DoubleArray, recalled: BooleanArray, bins: LongArray): Scores {
        val n = predicted.size
        if (n == 0) return Scores(0, Double.NaN, Double.NaN, Double.NaN)
        val logLoss = predicted.indices.sumOf { bce(predicted[it], recalled[it]) } / n
        val groups = HashMap<Long, DoubleArray>() // [count, sum predicted, sum observed]
        for (i in 0 until n) {
            val g = groups.getOrPut(bins[i]) { DoubleArray(3) }
            g[0] += 1.0
            g[1] += predicted[i]
            g[2] += if (recalled[i]) 1.0 else 0.0
        }
        val rmse = sqrt(groups.values.sumOf { g -> g[0] * ((g[1] - g[2]) / g[0]).pow(2) } / n)
        return Scores(n, logLoss, rmse, auc(predicted, recalled))
    }

    /** Area under the ROC curve by ranks (Mann–Whitney), ties shared. NaN when only one outcome occurs. */
    internal fun auc(predicted: DoubleArray, recalled: BooleanArray): Double {
        val positives = recalled.count { it }
        val negatives = recalled.size - positives
        if (positives == 0 || negatives == 0) return Double.NaN
        val order = predicted.indices.sortedBy { predicted[it] }
        var rankSumPositives = 0.0
        var i = 0
        while (i < order.size) {
            var j = i
            while (j + 1 < order.size && predicted[order[j + 1]] == predicted[order[i]]) j++
            val averageRank = (i + j) / 2.0 + 1.0
            for (k in i..j) if (recalled[order[k]]) rankSumPositives += averageRank
            i = j + 1
        }
        return (rankSumPositives - positives * (positives + 1) / 2.0) / (positives.toDouble() * negatives)
    }

    // --- the gate ----------------------------------------------------------------------------------

    enum class Verdict { ACCEPTED, REJECTED, NOT_ENOUGH_DATA }

    data class FitReport(
        val verdict: Verdict,
        /** The weights to use: fitted on ALL reviews, and only when the held-out comparison accepted. */
        val weights: DoubleArray?,
        /** Reviews the final fit trains on (all of them, capped per topic like the reference). */
        val trainReviews: Int,
        /** Held-out reviews the verdict was reached on, pooled over every fold. */
        val testReviews: Int,
        /** The weights in use, scored on the held-out reviews. */
        val current: Scores?,
        /** Each fold's fit on earlier reviews, scored on the same held-out later reviews. */
        val candidate: Scores?,
        /** One-sided paired z of the per-review log-loss improvement. */
        val zScore: Double,
        /** How many time-series folds had enough earlier reviews to train on. */
        val folds: Int = 0,
        /** [lengthening] of the refit on everything, when the held-out comparison passed; NaN when it was not reached. */
        val lengthening: Double = Double.NaN,
    )

    /**
     * Time-series folds, as the FSRS benchmark evaluates: the reviews are cut in time into FOLDS + 1
     * chunks, and fold k trains on everything before chunk k + 1 and is judged on chunk k + 1. Pooling
     * the folds judges the FITTING PROCEDURE on most of the history rather than on its last fifth, which
     * roughly doubles the evidence the gate sees without ever letting a fold peek at its own future.
     */
    const val FOLDS = 4
    const val MIN_TEST_REVIEWS = 100
    const val ACCEPT_Z = 2.33

    fun fitAndValidate(
        histories: List<History>,
        current: DoubleArray,
        cfg: Config = Config(),
        /** The learner's retention target, at which [lengthening] compares the intervals. */
        retention: Double = 0.9,
    ): FitReport {
        val times = histories.flatMap { h -> (1 until h.size).filter { h.inLoss(it) }.map { h.validationOrder[it] } }.sorted()
        val allTrain = histories.sumOf { h -> (1 until minOf(h.size, cfg.maxStepsPerHistory)).count { h.inLoss(it) } }
        fun notEnough(test: Int, folds: Int) = FitReport(Verdict.NOT_ENOUGH_DATA, null, allTrain, test, null, null, 0.0, folds)
        if (times.size < MIN_REVIEWS_FOR_A_FIT) return notEnough(0, 0)

        val diffs = ArrayList<Double>()
        val before = ArrayList<Evaluation>()
        val after = ArrayList<Evaluation>()
        for (k in 1..FOLDS) {
            val start = times[times.size * k / (FOLDS + 1)]
            val end = if (k == FOLDS) Long.MAX_VALUE else times[times.size * (k + 1) / (FOLDS + 1)]
            val earlier = histories.mapNotNull { it.before(start) }
            val candidate = train(earlier, cfg) ?: continue
            val window: (History, Int) -> Boolean = { h, i -> h.inLoss(i) && h.validationOrder[i] >= start && h.validationOrder[i] < end }
            val b = evaluate(histories, current, window)
            val a = evaluate(histories, candidate, window)
            if (b.losses.isEmpty()) continue
            for (i in b.losses.indices) diffs += b.losses[i] - a.losses[i]
            before += b
            after += a
        }
        if (before.isEmpty() || diffs.size < MIN_TEST_REVIEWS) return notEnough(diffs.size, before.size)

        val mean = diffs.average()
        val sd = sqrt(diffs.sumOf { (it - mean).pow(2) } / (diffs.size - 1).coerceAtLeast(1))
        val z = when {
            sd > 0.0 -> mean / (sd / sqrt(diffs.size.toDouble()))
            mean > 0.0 -> Double.POSITIVE_INFINITY
            else -> 0.0
        }
        // Better held-out predictions are necessary, not sufficient: the set must also keep the grades in order and
        // must not lengthen this learner's intervals.
        val refit = if (mean > 0.0 && z >= ACCEPT_Z) train(histories, cfg) else null
        val longer = refit?.let { lengthening(histories, it, retention) } ?: Double.NaN
        val adopted = refit?.takeIf { withinBounds(it) && keepsGradeOrder(it) && longer <= 1.0 }
        fun pooled(e: List<Evaluation>) = score(
            e.flatMap { it.predicted.asList() }.toDoubleArray(),
            e.flatMap { it.recalled.asList() }.toBooleanArray(),
            e.flatMap { it.bins.asList() }.toLongArray(),
        )
        return FitReport(
            verdict = if (adopted != null) Verdict.ACCEPTED else Verdict.REJECTED,
            weights = adopted,
            trainReviews = allTrain,
            testReviews = diffs.size,
            current = pooled(before),
            candidate = pooled(after),
            zScore = z,
            folds = before.size,
            lengthening = longer,
        )
    }
}
