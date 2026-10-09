package com.example.domain.srs

import org.junit.Assert.*
import org.junit.Test

class OptimizerValidationOrderTest {
    @Test fun `a validation prefix never includes a later saved answer even when wall time goes back`() {
        val h = Fsrs6Optimizer.History(intArrayOf(3, 3, 1, 4), doubleArrayOf(0.0, 4.0, 0.0, 1.0),
            longArrayOf(100, 500, 200, 300), longArrayOf(10, 20, 30, 40))
        assertNull(h.before(10))
        assertArrayEquals(intArrayOf(3, 3), h.before(30)!!.grades)
        assertArrayEquals(longArrayOf(100, 500), h.before(30)!!.reviewedAt)
        assertArrayEquals(longArrayOf(10, 20), h.capped(2).validationOrder)
    }

    @Test fun `changing wall timestamps cannot change a held-out verdict when saved order and elapsed days agree`() {
        // 80 topics, 9 transitions each: crosses the real gate's 640-review floor. Small epoch count
        // keeps this a regression test of partitioning, not another expensive statistical experiment.
        val original = List(80) { topic ->
            val order = LongArray(10) { step -> step * 80L + topic + 1 }
            Fsrs6Optimizer.History(IntArray(10) { step -> if ((topic + step) % 7 == 0) 1 else 3 },
                DoubleArray(10) { if (it == 0) 0.0 else 3.0 }, order, order)
        }
        val rolledBack = original.map { h -> Fsrs6Optimizer.History(h.grades, h.elapsedDays,
            LongArray(h.size) { i -> if (i >= 4) h.reviewedAt[i] - 900 else h.reviewedAt[i] }, h.validationOrder) }
        val config = Fsrs6Optimizer.Config(epochs = 1)
        val a = Fsrs6Optimizer.fitAndValidate(original, Fsrs6Parameters.DEFAULT_WEIGHTS, config)
        val b = Fsrs6Optimizer.fitAndValidate(rolledBack, Fsrs6Parameters.DEFAULT_WEIGHTS, config)
        assertNotEquals(Fsrs6Optimizer.Verdict.NOT_ENOUGH_DATA, a.verdict)
        assertEquals(a.verdict, b.verdict)
        assertEquals(a.folds, b.folds)
        assertEquals(a.testReviews, b.testReviews)
        assertEquals(a.current!!.logLoss, b.current!!.logLoss, 0.0)
        assertEquals(a.candidate!!.logLoss, b.candidate!!.logLoss, 0.0)
        assertEquals(a.zScore, b.zScore, 0.0)
    }
}
