package com.example.domain.srs

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln

/**
 * The personal-model optimizer. What must hold: its model IS the verified FSRS-6, its gradients are the
 * true derivatives of its loss, it rebuilds histories the way projection does, it finds a learner the
 * defaults do not describe, and it refuses to replace defaults that already describe the learner.
 */
class Fsrs6OptimizerTest {

    private val day = 86_400_000L
    private fun grade(v: Int) = Grade.entries.first { it.value == v }

    /** A learner whose first studies fade faster, whose reviews buy less, on a steeper curve. */
    private val planted = Fsrs6Parameters.DEFAULT_WEIGHTS.copyOf().also {
        for (i in 0..3) it[i] *= 0.4
        it[8] = 1.3
        it[20] = 0.35
    }

    /** Includes same-day steps, which the scheduled simulations never produce. */
    private val handcrafted = Fsrs6Optimizer.History(
        intArrayOf(3, 3, 1, 4, 2, 3, 1, 3),
        doubleArrayOf(0.0, 0.0, 5.0, 0.0, 12.0, 30.0, 0.0, 9.0),
        LongArray(8) { it * day },
    )

    /**
     * Topics reviewed on the DEFAULT model's schedule (as the app would), 30% early to 60% late, with
     * recall drawn from [truth] and a fixed Hard/Good/Easy mix on success.
     */
    private fun simulate(truth: DoubleArray, topics: Int, seed: Int): List<Fsrs6Optimizer.History> {
        val rng = kotlin.random.Random(seed)
        val truthP = Fsrs6Parameters(weights = truth)
        val appP = Fsrs6Parameters()
        return List(topics) {
            val start = rng.nextInt(0, 120)
            val u0 = rng.nextDouble()
            val first = if (u0 < 0.25) 4 else if (u0 < 0.8) 3 else 2
            var trueState = Fsrs6.initialState(grade(first), truthP)
            var appState = Fsrs6.initialState(grade(first), appP)
            val grades = mutableListOf(first)
            val deltas = mutableListOf(0.0)
            val times = mutableListOf(start * day)
            var today = start
            while (grades.size < 14) {
                val planned = Fsrs6.intervalDays(appState.stability, 0.9, appP).coerceIn(1.0, 365.0)
                val gap = maxOf(1, Math.round(planned * (0.7 + rng.nextDouble() * 0.9)).toInt())
                today += gap
                if (today > start + 900) break
                val r = Fsrs6.retrievability(gap.toDouble(), trueState.stability, truthP)
                val g = if (rng.nextDouble() >= r) 1 else rng.nextDouble().let { if (it < 0.2) 2 else if (it < 0.9) 3 else 4 }
                trueState = Fsrs6.nextState(trueState, gap.toDouble(), grade(g), truthP)
                appState = Fsrs6.nextState(appState, gap.toDouble(), grade(g), appP)
                grades += g
                deltas += gap.toDouble()
                times += today * day
            }
            Fsrs6Optimizer.History(grades.toIntArray(), deltas.toDoubleArray(), times.toLongArray())
        }
    }

    @Test
    fun `the differentiable model computes exactly what the verified FSRS-6 computes`() {
        val histories = simulate(planted, topics = 40, seed = 3) + handcrafted
        for (w in listOf(Fsrs6Parameters.DEFAULT_WEIGHTS, planted)) {
            val model = Fsrs6Optimizer.DualModel(w)
            val p = Fsrs6Parameters(weights = w)
            for (h in histories) {
                var s = model.initialStability(h.grades[0])
                var d = model.initialDifficulty(h.grades[0])
                var ref = Fsrs6.initialState(grade(h.grades[0]), p)
                assertEquals(ref.stability, s.v, 0.0)
                assertEquals(ref.difficulty, d.v, 0.0)
                for (i in 1 until h.size) {
                    val t = h.elapsedDays[i]
                    val r = model.retrievability(t, s)
                    assertEquals("recall at step $i", Fsrs6.retrievability(t, ref.stability, p), r.v, 1e-15)
                    val (ns, nd) = model.next(s, d, t, r, h.grades[i])
                    s = ns
                    d = nd
                    ref = Fsrs6.nextState(ref, t, grade(h.grades[i]), p)
                    assertEquals("stability at step $i", ref.stability, s.v, 1e-12 * maxOf(1.0, ref.stability))
                    assertEquals("difficulty at step $i", ref.difficulty, d.v, 1e-12)
                }
            }
        }
    }

    @Test
    fun `gradients are the true derivatives of the loss`() {
        val histories = simulate(planted, topics = 25, seed = 5) + handcrafted
        val include: (Fsrs6Optimizer.History, Int) -> Boolean = { h, i -> h.inLoss(i) || h.elapsedDays[i] == 0.0 && i > 0 }
        for (w in listOf(Fsrs6Parameters.DEFAULT_WEIGHTS, planted)) {
            val analytic = Fsrs6Optimizer.lossAndGradient(histories, w, include).gradient
            fun loss(x: DoubleArray) = Fsrs6Optimizer.lossAndGradient(histories, x, include).loss
            for (k in 0 until Fsrs6Optimizer.N) {
                val h = 1e-6 * maxOf(1.0, abs(w[k]))
                val numeric = (loss(w.copyOf().also { it[k] += h }) - loss(w.copyOf().also { it[k] -= h })) / (2 * h)
                assertEquals("d loss / d w$k", numeric, analytic[k], 1e-4 * maxOf(1.0, abs(numeric)))
            }
        }
    }

    @Test
    fun `histories are rebuilt the way projection rebuilds them`() {
        val t0 = 1_800_000_000_000L
        val events = listOf(
            Fsrs6Optimizer.Event(t0, "Good", "FIRST_STUDY"),
            Fsrs6Optimizer.Event(t0 + 3 * day, "Hard", "RECALL"),
            Fsrs6Optimizer.Event(t0 + 5 * day, "Easy", "FIRST_STUDY"), // a re-study: re-anchors the clock only
            Fsrs6Optimizer.Event(t0 + 9 * day, "WAT", "RECALL"),       // unreadable: skipped, clock untouched
            Fsrs6Optimizer.Event(t0 + 12 * day, "Forgot", "RECALL"),
        )
        val h = Fsrs6Optimizer.historyOf(events) { from, to -> (to - from) / day.toDouble() }!!
        assertArrayEquals(intArrayOf(3, 2, 1), h.grades)
        assertArrayEquals("measured from the re-study, not the unreadable row", doubleArrayOf(0.0, 3.0, 7.0), h.elapsedDays, 0.0)
        assertNull(Fsrs6Optimizer.historyOf(listOf(Fsrs6Optimizer.Event(t0, "WAT", "RECALL"))) { _, _ -> 0.0 })
    }

    @Test
    fun `below 512 reviews nothing is fitted and nothing is judged`() {
        val few = simulate(planted, topics = 20, seed = 1)
        assertNull(Fsrs6Optimizer.train(few))
        val report = Fsrs6Optimizer.fitAndValidate(few, Fsrs6Parameters.DEFAULT_WEIGHTS)
        assertEquals(Fsrs6Optimizer.Verdict.NOT_ENOUGH_DATA, report.verdict)
        assertNull(report.weights)
    }

    @Test
    fun `a learner the defaults do not describe gets a fit that predicts their later reviews better`() {
        val report = Fsrs6Optimizer.fitAndValidate(simulate(planted, topics = 700, seed = 11), Fsrs6Parameters.DEFAULT_WEIGHTS)
        assertEquals("z = ${report.zScore}", Fsrs6Optimizer.Verdict.ACCEPTED, report.verdict)
        val before = report.current!!
        val after = report.candidate!!
        assertTrue("log loss ${after.logLoss} < ${before.logLoss}", after.logLoss < before.logLoss)
        assertTrue("binned error ${after.rmseBins} < ${before.rmseBins}", after.rmseBins < before.rmseBins)
        val w = report.weights!!
        assertTrue("inside the reference bounds", Fsrs6Optimizer.withinBounds(w))
        assertTrue("found the steeper curve: w20 = ${w[20]}", w[20] > Fsrs6Parameters.DEFAULT_WEIGHTS[20])
        assertTrue("found that reviews buy less: w8 = ${w[8]}", w[8] < Fsrs6Parameters.DEFAULT_WEIGHTS[8])
        println("GATE: faster forgetter z = ${report.zScore}, lengthening = ${report.lengthening}, ${report.verdict}")
        assertTrue("a faster forgetter's set shortens, so never-lengthen lets it through (${report.lengthening})", report.lengthening < 1.0)
    }

    @Test
    fun `a learner the defaults already describe keeps the defaults`() {
        val report = Fsrs6Optimizer.fitAndValidate(simulate(Fsrs6Parameters.DEFAULT_WEIGHTS, topics = 700, seed = 13), Fsrs6Parameters.DEFAULT_WEIGHTS)
        assertEquals("z = ${report.zScore}", Fsrs6Optimizer.Verdict.REJECTED, report.verdict)
        assertNull("nothing to use", report.weights)
    }

    @Test
    fun `the three scores`() {
        val y = booleanArrayOf(true, true, false, false)
        assertEquals("perfect separation", 1.0, Fsrs6Optimizer.auc(doubleArrayOf(0.9, 0.8, 0.3, 0.2), y), 0.0)
        assertEquals("no separation", 0.5, Fsrs6Optimizer.auc(DoubleArray(4) { 0.5 }, y), 0.0)
        val s = Fsrs6Optimizer.score(DoubleArray(4) { 0.75 }, booleanArrayOf(true, true, true, false), LongArray(4) { 7L })
        assertEquals("a bin whose mean prediction equals its recall rate has no error", 0.0, s.rmseBins, 1e-12)
        assertEquals(-(3 * ln(0.75) + ln(0.25)) / 4, s.logLoss, 1e-12)
        assertEquals(4, s.reviews)
    }

    /**
     * The first rating's grades stay in order whatever the data says. The case is an outside audit's (2026-09-30):
     * topics first rated Hard are remembered 95% of the time a week later, topics first rated Good only 65%. Trained
     * without the constraint the fit put S0(Hard) at 3.53 days over S0(Good) at 2.51, and it was adopted, so "Hard"
     * brought a new topic back LATER than "Medium". Now training pools the two, and whatever is adopted keeps the order.
     */
    @Test
    fun a_fit_never_puts_hard_above_good() {
        val rng = java.util.Random(7331)
        val histories = (0 until 600).map { item ->
            val first = if (item < 300) 2 else 3
            val recall = if (first == 2) 0.95 else 0.65
            val grades = IntArray(5) { if (it == 0) first else if (rng.nextDouble() < recall) 3 else 1 }
            Fsrs6Optimizer.History(grades, DoubleArray(5) { if (it == 0) 0.0 else 7.0 }, LongArray(5) { it * 7 * 86_400_000L + item })
        }
        val fit = Fsrs6Optimizer.train(histories)!!
        assertTrue("trained, Hard ${fit[1]} stays at or below Good ${fit[2]}", Fsrs6Optimizer.keepsGradeOrder(fit))

        val report = Fsrs6Optimizer.fitAndValidate(histories, Fsrs6Parameters.DEFAULT_WEIGHTS)
        report.weights?.let { assertTrue("whatever is adopted keeps the order", Fsrs6Optimizer.keepsGradeOrder(it)) }
        assertTrue("the published defaults keep their order", Fsrs6Optimizer.keepsGradeOrder(Fsrs6Parameters.DEFAULT_WEIGHTS))
        assertTrue(!Fsrs6Optimizer.keepsGradeOrder(Fsrs6Parameters.DEFAULT_WEIGHTS.copyOf().also { it[1] = it[2] + 0.1 }))
    }

    /**
     * NEVER LENGTHEN reaches the personal set (the owner's decision, 2026-10-02). This learner, from an outside audit
     * (2026-09-30), forgets exactly as the defaults say but calls 60% of forgotten topics "Hard". The fit predicts those
     * RATINGS better than the defaults do, so the held-out comparison passed and the set was adopted, lengthening
     * intervals by about a fifth while it predicted true recall worse. Now it is refused. The learner above, who really
     * forgets faster, still gets a set: it shortens.
     */
    @Test
    fun `a fit learned from generous ratings that would lengthen intervals is not adopted`() {
        val rr = kotlin.random.Random(99101)
        val histories = (0 until 700).map {
            val first = rr.nextDouble().let { if (it < .25) 2 else if (it < .8) 3 else 4 }
            val start = rr.nextInt(0, 120)
            var nowDay = start
            var truth = Fsrs6.initialState(Grade.entries.first { it.value == first })
            var belief = truth
            val grades = mutableListOf(first)
            val elapsed = mutableListOf(0.0)
            val times = mutableListOf(start * 86_400_000L)
            while (grades.size < 14) {
                val planned = Fsrs6.intervalDays(belief.stability, .9).coerceIn(1.0, 365.0)
                val gap = maxOf(1, kotlin.math.round(planned * (.7 + rr.nextDouble() * .9)).toInt())
                nowDay += gap
                if (nowDay > start + 900) break
                val recalled = rr.nextDouble() < Fsrs6.retrievability(gap.toDouble(), truth.stability)
                val honest = if (!recalled) 1 else rr.nextDouble().let { if (it < .2) 2 else if (it < .9) 3 else 4 }
                val said = if (!recalled && rr.nextDouble() < 0.6) 2 else honest
                truth = Fsrs6.nextState(truth, gap.toDouble(), Grade.entries.first { it.value == honest })
                belief = Fsrs6.nextState(belief, gap.toDouble(), Grade.entries.first { it.value == said })
                grades += said
                elapsed += gap.toDouble()
                times += nowDay * 86_400_000L
            }
            Fsrs6Optimizer.History(grades.toIntArray(), elapsed.toDoubleArray(), times.toLongArray())
        }
        val report = Fsrs6Optimizer.fitAndValidate(histories, Fsrs6Parameters.DEFAULT_WEIGHTS)
        println("GATE: generous rater z = ${report.zScore}, lengthening = ${report.lengthening}, ${report.verdict}")
        assertTrue("it predicts the generous ratings better (z = ${report.zScore})", report.zScore >= Fsrs6Optimizer.ACCEPT_Z)
        assertTrue("and would lengthen this learner's intervals (${report.lengthening})", report.lengthening > 1.0)
        assertEquals(Fsrs6Optimizer.Verdict.REJECTED, report.verdict)
        assertNull("the defaults stay", report.weights)
    }

    @Test
    fun `lengthening compares the next intervals each set would give`() {
        val histories = simulate(Fsrs6Parameters.DEFAULT_WEIGHTS, topics = 50, seed = 5)
        assertEquals(1.0, Fsrs6Optimizer.lengthening(histories, Fsrs6Parameters.DEFAULT_WEIGHTS, 0.9), 1e-12)
        val reviewsBuyLess = Fsrs6Parameters.DEFAULT_WEIGHTS.copyOf().also { it[8] = it[8] - 0.5 }
        assertTrue(Fsrs6Optimizer.lengthening(histories, reviewsBuyLess, 0.9) < 1.0)
        val reviewsBuyMore = Fsrs6Parameters.DEFAULT_WEIGHTS.copyOf().also { it[8] = it[8] + 0.5 }
        assertTrue(Fsrs6Optimizer.lengthening(histories, reviewsBuyMore, 0.9) > 1.0)
        assertEquals("nothing to compare", 1.0, Fsrs6Optimizer.lengthening(emptyList(), reviewsBuyMore, 0.9), 0.0)
    }
}
