package com.example.domain.srs

import com.example.domain.model.MemoryRating
import com.example.domain.model.UnderstandingRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * The per-user interval correction. Three things have to be true of it: with no evidence it does
 * nothing; with enough evidence it recovers the learner's real stability scale; and it can never
 * take the schedule anywhere unreasonable. Then it must reach the interval, and only the interval.
 */
class RecallCalibrationTest {

    private val p = Fsrs6Parameters()

    /** The default curve, written out: R = (1 + F·t/S)^decay. */
    private fun predictedFor(tOverS: Double) = (1.0 + p.factor * tOverS).pow(p.decay)

    /** Outcomes drawn from a learner whose true stability is [trueScale] times the model's. */
    private fun plant(trueScale: Double, n: Int, seed: Int): Pair<DoubleArray, BooleanArray> {
        val rng = java.util.Random(seed.toLong())
        val predicted = DoubleArray(n)
        val recalled = BooleanArray(n)
        for (i in 0 until n) {
            val tOverS = 0.2 + rng.nextDouble() * 2.8 // reviews from early to well overdue: predictions ~0.72..0.97
            predicted[i] = predictedFor(tOverS)
            val truth = predictedFor(tOverS / trueScale)
            recalled[i] = rng.nextDouble() < truth
        }
        return predicted to recalled
    }

    // --- No evidence, no correction ---------------------------------------------------------------

    @Test
    fun `no reviews means no correction`() {
        assertEquals(1.0, RecallCalibration.momentScale(DoubleArray(0), BooleanArray(0)), 0.0)
        assertEquals(1.0, RecallCalibration.scale(DoubleArray(0), BooleanArray(0)), 0.0)
    }

    @Test
    fun `a perfectly calibrated history yields exactly one`() {
        val predicted = DoubleArray(100) { 0.9 }
        val recalled = BooleanArray(100) { it < 90 }
        assertEquals(1.0, RecallCalibration.momentScale(predicted, recalled), 1e-9)
        assertEquals(1.0, RecallCalibration.scale(predicted, recalled), 1e-9)
    }

    // --- The estimator recovers what was planted -------------------------------------------------

    @Test
    fun `the raw estimate recovers a planted stability scale`() {
        for ((k, seed) in listOf(0.6 to 1, 1.0 to 2, 1.5 to 3)) {
            val (predicted, recalled) = plant(k, 20_000, seed)
            val estimate = RecallCalibration.momentScale(predicted, recalled)
            assertTrue("planted $k, estimated $estimate", Math.abs(estimate / k - 1.0) < 0.08)
        }
    }

    @Test
    fun `the raw estimate is monotone in the evidence`() {
        val predicted = DoubleArray(400) { 0.85 + 0.1 * (it % 10) / 10.0 }
        var previous = 0.0
        for (recalls in listOf(300, 340, 360, 380, 395)) {
            val recalled = BooleanArray(400) { it < recalls }
            val k = RecallCalibration.momentScale(predicted, recalled)
            assertTrue("more recalls, larger scale ($recalls: $k > $previous)", k > previous)
            previous = k
        }
    }

    @Test
    fun `implied ratio and recalibration invert the default curve`() {
        for (r in listOf(0.7, 0.85, 0.9, 0.95, 0.99, 1.0)) {
            val ratio = RecallCalibration.impliedRatio(r)
            assertEquals("R -> F·t/S -> R round-trips", r, (1.0 + ratio).pow(p.decay), 1e-12)
            assertEquals("and it is the curve's own F·t/S", r, predictedFor(ratio / p.factor), 1e-12)
            assertEquals("scale 1 changes nothing", r, RecallCalibration.recalibrated(r, 1.0), 1e-12)
            if (r < 1.0) {
                assertTrue("a faster-forgetting learner recalls less", RecallCalibration.recalibrated(r, 0.5) < r)
                assertTrue("a slower-forgetting learner recalls more", RecallCalibration.recalibrated(r, 2.0) > r)
            }
        }
        assertEquals("R = 1 means no time has passed", 0.0, RecallCalibration.impliedRatio(1.0), 0.0)
    }

    // --- Shrinkage and bounds ---------------------------------------------------------------------

    @Test
    fun `the schedule's scale is the raw estimate shrunk toward one by the prior`() {
        val (predicted, recalled) = plant(0.6, 300, 11)
        val raw = RecallCalibration.momentScale(predicted, recalled)
        val weight = 300.0 / (300 + RecallCalibration.PRIOR_REVIEWS)
        assertEquals(exp(weight * ln(raw)), RecallCalibration.scale(predicted, recalled), 1e-12)
        assertTrue("shrunk lies between the raw estimate and one", RecallCalibration.scale(predicted, recalled) in raw..1.0)
    }

    @Test
    fun `thin evidence barely moves the schedule, overwhelming evidence is still clamped`() {
        val forgotEverything = BooleanArray(10) { false }
        val tenPredictions = DoubleArray(10) { 0.9 }
        val thin = RecallCalibration.scale(tenPredictions, forgotEverything)
        assertTrue("ten total failures move the scale by well under a third ($thin)", thin > 0.7 && thin < 1.0)

        val many = 100_000
        assertEquals(RecallCalibration.MIN_SCALE, RecallCalibration.scale(DoubleArray(many) { 0.9 }, BooleanArray(many) { false }), 1e-12)
        assertEquals(RecallCalibration.MAX_ESTIMATE, RecallCalibration.scale(DoubleArray(many) { 0.9 }, BooleanArray(many) { true }), 1e-12)
    }

    /**
     * The correction only ever shortens intervals. A learner who reports more recalls than predicted may
     * really forget slower, or may be calling forgotten topics "Hard"; the ratings cannot tell which, and
     * lengthening for the second is what inflated ratings cost most (experiments.py section 7, 2026-09-28).
     */
    @Test
    fun `a learner who seems to remember longer is never given longer intervals, a faster forgetter still gets shorter ones`() {
        val (slowP, slowR) = plant(1.7, 20_000, 21)
        assertTrue("the raw estimate still sees the slower forgetting", RecallCalibration.momentScale(slowP, slowR) > 1.5)
        assertEquals("but the schedule's scale stays at one", 1.0, RecallCalibration.scale(slowP, slowR), 0.0)

        val (fastP, fastR) = plant(0.6, 20_000, 22)
        val fast = RecallCalibration.scale(fastP, fastR)
        assertTrue("a faster forgetter's intervals still shrink ($fast)", fast < 0.7 && fast >= RecallCalibration.MIN_SCALE)
    }

    @Test
    fun `whatever was stored, the scheduler only ever multiplies by a sane number`() {
        assertEquals(1.0, RecallCalibration.safeScale(Double.NaN), 0.0)
        assertEquals(1.0, RecallCalibration.safeScale(Double.POSITIVE_INFINITY), 0.0)
        assertEquals(RecallCalibration.MAX_SCALE, RecallCalibration.safeScale(10.0), 0.0)
        assertEquals(RecallCalibration.MIN_SCALE, RecallCalibration.safeScale(0.01), 0.0)
        assertEquals(0.83, RecallCalibration.safeScale(0.83), 0.0)
        assertEquals("a scale an older build stored replays unchanged", 1.7, RecallCalibration.safeScale(1.7), 0.0)
    }

    // --- Where it reaches ---------------------------------------------------------------------------

    @Test
    fun `the scale multiplies the FSRS-6 memory interval and touches nothing else`() {
        val one = MedScheduler.review(30.0, 4.0, 30.0, MemoryRating.Good, UnderstandingRating.Clear, false, 3,
            model = MedScheduler.MemoryModel.FSRS_6, calibrationScaleOverride = 1.0)
        val half = MedScheduler.review(30.0, 4.0, 30.0, MemoryRating.Good, UnderstandingRating.Clear, false, 3,
            model = MedScheduler.MemoryModel.FSRS_6, calibrationScaleOverride = 0.5)
        assertEquals("interval halves", one.intervalDays * 0.5, half.intervalDays, 1e-9)
        assertEquals("base interval halves", one.baseIntervalDays * 0.5, half.baseIntervalDays, 1e-9)
        assertEquals("stability is the model's own", one.state.stability, half.state.stability, 0.0)
        assertEquals("difficulty is the model's own", one.state.difficulty, half.state.difficulty, 0.0)
        assertEquals("recall at review is the model's own", one.retrievabilityAtReview, half.retrievabilityAtReview, 0.0)
    }

    @Test
    fun `the caps still hold after scaling`() {
        val seed = MedScheduler.firstStudy(UnderstandingRating.Partial, false).state
        val first = MedScheduler.review(seed.stability, seed.difficulty, 0.0, MemoryRating.Easy, UnderstandingRating.Clear, false, 0,
            model = MedScheduler.MemoryModel.FSRS_6, calibrationScaleOverride = 2.0)
        assertTrue("first check-in stays within five days", first.intervalDays <= MedScheduler.FIRST_STUDY_MAX_DAYS + 1e-9)
        val tiny = MedScheduler.review(1.2, 8.0, 1.0, MemoryRating.Hard, UnderstandingRating.Clear, false, 3,
            model = MedScheduler.MemoryModel.FSRS_6, calibrationScaleOverride = 0.5)
        assertTrue("never sooner than tomorrow", tiny.intervalDays >= MedScheduler.MIN_INTERVAL_DAYS - 1e-9)
        val huge = MedScheduler.review(900.0, 2.0, 900.0, MemoryRating.Easy, UnderstandingRating.Clear, false, 9,
            model = MedScheduler.MemoryModel.FSRS_6, calibrationScaleOverride = 2.0)
        assertEquals("never past the ceiling", 365.0, huge.intervalDays, 1e-9)
        val lapse = MedScheduler.review(30.0, 4.0, 30.0, MemoryRating.Forgot, UnderstandingRating.Partial, false, 3,
            model = MedScheduler.MemoryModel.FSRS_6, calibrationScaleOverride = 2.0)
        assertEquals("a lapse relearns tomorrow regardless", MedScheduler.RELEARN_STEP_DAYS, lapse.intervalDays, 1e-12)
    }

    @Test
    fun `the frozen FSRS-5 path ignores the scale entirely`() {
        val a = MedScheduler.review(30.0, 4.0, 30.0, MemoryRating.Good, UnderstandingRating.Clear, false, 3,
            model = MedScheduler.MemoryModel.FSRS_5, calibrationScaleOverride = 1.0)
        val b = MedScheduler.review(30.0, 4.0, 30.0, MemoryRating.Good, UnderstandingRating.Clear, false, 3,
            model = MedScheduler.MemoryModel.FSRS_5, calibrationScaleOverride = 0.5)
        assertEquals(a.intervalDays, b.intervalDays, 0.0)
    }

    @Test
    fun `live reviews read the scale in force and a stored one overrides it`() {
        val before = MedScheduler.calibrationScale
        try {
            MedScheduler.calibrationScale = 0.7
            val live = MedScheduler.review(30.0, 4.0, 30.0, MemoryRating.Good, UnderstandingRating.Clear, false, 3,
                model = MedScheduler.MemoryModel.FSRS_6)
            val unscaled = MedScheduler.review(30.0, 4.0, 30.0, MemoryRating.Good, UnderstandingRating.Clear, false, 3,
                model = MedScheduler.MemoryModel.FSRS_6, calibrationScaleOverride = 1.0)
            assertEquals(unscaled.intervalDays * 0.7, live.intervalDays, 1e-9)
            assertEquals("the Important toggle's reschedule scales the same way",
                MedScheduler.intervalDays(30.0, 0.93, MedScheduler.MemoryModel.FSRS_6) * 0.7,
                MedScheduler.scheduledIntervalDays(30.0, 0.93, MedScheduler.MemoryModel.FSRS_6), 1e-9)
            assertEquals("and never on the frozen model",
                MedScheduler.intervalDays(30.0, 0.93, MedScheduler.MemoryModel.FSRS_5),
                MedScheduler.scheduledIntervalDays(30.0, 0.93, MedScheduler.MemoryModel.FSRS_5), 0.0)
        } finally {
            MedScheduler.calibrationScale = before
        }
    }
}
