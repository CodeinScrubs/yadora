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

    /**
     * The gate's held-out comparison, re-run with each held-out review tagged by its topic: the paired z as the gate
     * computes it (every review independent) and a cluster-robust z that counts each topic's reviews as one cluster.
     */
    private fun naiveAndClusteredZ(histories: List<Fsrs6Optimizer.History>): Pair<Double, Double> {
        val times = histories.flatMap { h -> (1 until h.size).filter { h.inLoss(it) }.map { h.reviewedAt[it] } }.sorted()
        val diffs = ArrayList<Double>()
        val topic = ArrayList<Int>()
        for (k in 1..Fsrs6Optimizer.FOLDS) {
            val start = times[times.size * k / (Fsrs6Optimizer.FOLDS + 1)]
            val end = if (k == Fsrs6Optimizer.FOLDS) Long.MAX_VALUE else times[times.size * (k + 1) / (Fsrs6Optimizer.FOLDS + 1)]
            val candidate = Fsrs6Optimizer.train(histories.mapNotNull { it.before(start) }) ?: continue
            val window: (Fsrs6Optimizer.History, Int) -> Boolean = { h, i -> h.inLoss(i) && h.reviewedAt[i] >= start && h.reviewedAt[i] < end }
            val b = Fsrs6Optimizer.evaluate(histories, Fsrs6Parameters.DEFAULT_WEIGHTS, window)
            val a = Fsrs6Optimizer.evaluate(histories, candidate, window)
            histories.forEachIndexed { j, h -> for (i in 1 until h.size) if (window(h, i)) topic += j }
            for (i in b.losses.indices) diffs += b.losses[i] - a.losses[i]
        }
        val n = diffs.size.toDouble()
        val mean = diffs.average()
        val naiveVariance = diffs.sumOf { (it - mean) * (it - mean) } / (n - 1) / n
        val byTopic = HashMap<Int, Double>()
        diffs.forEachIndexed { i, d -> byTopic.merge(topic[i], d - mean, Double::plus) }
        val g = byTopic.size.toDouble()
        val clusteredVariance = g / (g - 1) * byTopic.values.sumOf { it * it } / (n * n)
        return mean / kotlin.math.sqrt(naiveVariance) to mean / kotlin.math.sqrt(clusteredVariance)
    }

    /**
     * Outside researchers (2026-10-03) said the paired z overstates the evidence because one topic's reviews are not
     * independent, by about a third. Measured on these learners it does not: a held-out review's log-loss difference
     * barely moves with the others of its topic (design effect 0.88 to 1.2, median about 1.0, over 27 learners on
     * 2026-10-03), so the gate keeps the plain z, and a clustered z would reach the same verdict every time.
     */
    @Test
    fun `reviews of one topic barely move together in the held-out comparison, so the gate needs no clustering correction`() {
        val strong = Fsrs6Parameters.DEFAULT_WEIGHTS.copyOf().also {
            for (i in 0..3) it[i] *= 0.4
            it[8] = 1.3
            it[20] = 0.35
        }
        val effects = ArrayList<Double>()
        for ((truth, seeds) in listOf(Fsrs6Parameters.DEFAULT_WEIGHTS to 1..4, strong to 1..4)) {
            for (seed in seeds) {
                val (naive, clustered) = naiveAndClusteredZ(simulate(truth, 700, 3000 + seed))
                effects += (naive / clustered) * (naive / clustered)
                assertTrue("seed $seed: naive z $naive, clustered z $clustered", (naive >= Fsrs6Optimizer.ACCEPT_Z) == (clustered >= Fsrs6Optimizer.ACCEPT_Z))
            }
        }
        val median = effects.sorted().let { (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
        println("GATE: design effect of topic clustering ${effects.map { "%.2f".format(java.util.Locale.ROOT, it) }}, median %.2f".format(java.util.Locale.ROOT, median))
        assertTrue("median design effect $median", median in 0.7..1.3)
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
