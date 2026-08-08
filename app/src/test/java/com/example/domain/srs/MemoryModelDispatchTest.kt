package com.example.domain.srs

import com.example.domain.srs.MedScheduler.MemoryModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * A stored stability is only meaningful together with the model that produced it. Anything that
 * re-derives a recall probability or an interval OUTSIDE `MedScheduler.review` has to say which
 * model it is talking about, or it computes on a curve the next real review will disagree with.
 *
 * This pins that the two dispatchers really dispatch. They are easy to "simplify" back into a single
 * `Fsrs.` call — the code still compiles, still runs, and silently mis-times every affected topic.
 */
class MemoryModelDispatchTest {

    private val stabilities = listOf(0.5, 1.0, 3.7, 12.0, 68.9, 250.0)
    private val retentions = listOf(0.80, 0.90, 0.93, 0.95)

    /** Both models are BUILT to hit 0.9 exactly at t = stability. That is the one point they agree. */
    @Test
    fun `both models cross 0-9 at t equals stability`() {
        for (s in stabilities) {
            for (model in MemoryModel.values()) {
                assertEquals(
                    "$model at t = S must be 0.9 by construction",
                    0.9, MedScheduler.retrievability(s, s, model), 1e-9,
                )
            }
        }
    }

    /** Away from that point they are genuinely different curves — not a relabelling of one. */
    @Test
    fun `the two curves diverge away from t equals stability`() {
        var maxGap = 0.0
        for (s in stabilities) {
            for (mult in listOf(0.1, 0.5, 2.0, 5.0, 20.0)) {
                val t = s * mult
                val r5 = MedScheduler.retrievability(t, s, MemoryModel.FSRS_5)
                val r6 = MedScheduler.retrievability(t, s, MemoryModel.FSRS_6)
                maxGap = maxOf(maxGap, abs(r5 - r6))
            }
        }
        assertTrue(
            "if this is ~0 the dispatcher has been collapsed to one model (max gap $maxGap)",
            maxGap > 0.05,
        )
    }

    /**
     * The models are defined so that stability MEANS "the time at which recall is 0.9", so at exactly
     * 0.9 they must agree — and only there. Either side of it they disagree in opposite directions:
     * FSRS-6's flatter decay lets you wait longer when you are willing to forget more, and forces you
     * back sooner when you want to forget less. Both sides are reachable from the app's 0.85-0.95
     * slider (plus the +0.03 high-yield bonus), so the crossing is not academic.
     */
    @Test
    fun `the models agree exactly at 0-9 retention and cross there`() {
        for (s in stabilities) {
            assertEquals(
                "at R=0.9 both models must return the stability itself",
                s, MedScheduler.intervalDays(s, 0.90, MemoryModel.FSRS_5), 1e-9,
            )
            assertEquals(
                "at R=0.9 both models must return the stability itself",
                s, MedScheduler.intervalDays(s, 0.90, MemoryModel.FSRS_6), 1e-9,
            )

            val lowI5 = MedScheduler.intervalDays(s, 0.80, MemoryModel.FSRS_5)
            val lowI6 = MedScheduler.intervalDays(s, 0.80, MemoryModel.FSRS_6)
            assertTrue("below 0.9 FSRS-6 grants MORE time ($lowI6 vs $lowI5)", lowI6 > lowI5)

            val highI5 = MedScheduler.intervalDays(s, 0.95, MemoryModel.FSRS_5)
            val highI6 = MedScheduler.intervalDays(s, 0.95, MemoryModel.FSRS_6)
            assertTrue("above 0.9 FSRS-6 grants LESS time ($highI6 vs $highI5)", highI6 < highI5)
        }
    }

    /** Whatever the model and target, an interval must be a usable positive number of days. */
    @Test
    fun `every model and retention combination yields a usable interval`() {
        for (model in MemoryModel.values()) {
            for (s in stabilities) {
                for (r in retentions) {
                    val i = MedScheduler.intervalDays(s, r, model)
                    assertTrue("$model S=$s R=$r produced $i", i > 0.0 && i.isFinite())
                }
            }
        }
    }

    /**
     * The real correctness property: within one model, intervalDays and retrievability are inverses.
     * Scheduling at the interval the model chose must land the user exactly on the retention target
     * that was asked for. A mismatched pair would miss the target on every single review.
     */
    @Test
    fun `scheduling at the chosen interval lands on the requested retention in each model`() {
        for (model in MemoryModel.values()) {
            for (s in stabilities) {
                for (r in retentions) {
                    val t = MedScheduler.intervalDays(s, r, model)
                    assertEquals(
                        "$model: S=$s scheduled at $t days must read back as R=$r",
                        r, MedScheduler.retrievability(t, s, model), 1e-9,
                    )
                }
            }
        }
    }

    /** Unknown or corrupt model names must degrade to the legacy model, never throw. */
    @Test
    fun `an unrecognised model name fails safe to the legacy model`() {
        for (name in listOf("", "FSRS-7", "garbage", "fsrs-6")) {
            assertEquals(
                "'$name' must fall back rather than crash reviewing",
                MemoryModel.FSRS_5, MemoryModel.of(name),
            )
        }
        assertEquals(MemoryModel.FSRS_5, MemoryModel.of("FSRS-5"))
        assertEquals(MemoryModel.FSRS_6, MemoryModel.of("FSRS-6"))
    }
}
