package com.example.domain.srs

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The adoption gate's error rates, measured over many simulated learners instead of asserted on one seed.
 * A false adoption rewrites every future interval on noise; a missed strong difference leaves a learner
 * on a model that mispredicts them. [Fsrs6Optimizer.ACCEPT_Z], the initial-stability pretrain and the
 * time-series folds were chosen from exactly these numbers (measured 2026-09-14: defaults 0 of 40,
 * moderate departures 0 of 10, strong departures 9 of 10 at 700 topics and 0 of 5 at 300).
 */
class Fsrs6OptimizerGateTest {

    private val day = 86_400_000L
    private fun grade(v: Int) = Grade.entries.first { it.value == v }

    /** Topics reviewed on the DEFAULT model's schedule, 30% early to 60% late, recall drawn from [truth]. */
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

    private fun adoptions(truth: DoubleArray, topics: Int, seeds: IntRange): Pair<Int, List<String>> {
        var adopted = 0
        val zs = ArrayList<String>()
        for (seed in seeds) {
            val report = Fsrs6Optimizer.fitAndValidate(simulate(truth, topics, 3000 + seed), Fsrs6Parameters.DEFAULT_WEIGHTS)
            if (report.verdict == Fsrs6Optimizer.Verdict.ACCEPTED) adopted++
            zs += "%.2f".format(java.util.Locale.ROOT, report.zScore)
        }
        return adopted to zs
    }

    @Test
    fun `a learner the defaults already describe is almost never given a personal model`() {
        val (small, smallZ) = adoptions(Fsrs6Parameters.DEFAULT_WEIGHTS, 150, 1..20)
        val (large, largeZ) = adoptions(Fsrs6Parameters.DEFAULT_WEIGHTS, 700, 1..20)
        println("GATE: a learner the defaults describe adopted ${small + large} of 40")
        assertTrue("adopted ${small + large} of 40 (z at 150 topics $smallZ, at 700 $largeZ)", small + large <= 1)
    }

    @Test
    fun `a learner who differs strongly from the defaults is found nearly every time with enough reviews`() {
        val strong = Fsrs6Parameters.DEFAULT_WEIGHTS.copyOf().also {
            for (i in 0..3) it[i] *= 0.4
            it[8] = 1.3
            it[20] = 0.35
        }
        val (adopted, zs) = adoptions(strong, 700, 1..10)
        println("GATE: strong departure adopted $adopted of 10 (z $zs)")
        assertTrue("adopted $adopted of 10 (z $zs)", adopted >= 8)
    }
}
