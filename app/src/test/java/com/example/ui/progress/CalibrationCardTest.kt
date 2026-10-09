package com.example.ui.progress

import com.example.data.local.entity.ReviewLogEntity
import com.example.domain.srs.MedScheduler
import com.example.domain.srs.RecallCalibration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The calibration card must describe one sample — the rows the correction is fitted on — and compare
 * the DEFAULT model with what happened, or it shows agreement by construction.
 */
class CalibrationCardTest {

    private fun log(
        i: Int, recalled: Boolean, predicted: Double = 0.91, elapsed: Double = 20.0, previous: Double = 20.0,
        type: String = "RECALL", model: String = MedScheduler.CURRENT_MODEL.id,
    ) = ReviewLogEntity(
        id = i.toLong(), studyUnitId = 1, reviewedAt = 1_000L + i,
        memoryRating = if (recalled) "Good" else "Forgot", understandingRating = if (recalled) "Clear" else "NotAsked",
        previousIntervalDays = previous, nextIntervalDays = 20.0, previousState = "Strong", nextState = "Strong",
        retrievabilityAtReview = predicted, elapsedDays = elapsed, logType = type, schedulerVersion = model,
    )

    @Test
    fun `the card shows the default model's gap on the rows the factor is fitted on`() {
        // 300 on-schedule reviews predicted at 91%, 87% recalled: a learner who forgets faster.
        val logs = (0 until 300).map { log(it, recalled = it % 100 < 87) }
        val card = calibrationStatsOf(logs)!!
        assertEquals(300, card.n)
        assertEquals("the default model's prediction, not the corrected one", 91, card.predictedPct)
        assertEquals(87, card.actualPct)
        val expected = RecallCalibration.scale(DoubleArray(300) { 0.91 }, BooleanArray(300) { it % 100 < 87 })
        assertEquals("the factor the scheduler would use", expected, card.scale, 1e-12)
        assertTrue("and it shortens intervals to close the gap the card shows", card.scale < 1.0)
    }

    @Test
    fun `rows the calibration does not use are not counted`() {
        val evidence = (0 until 20).map { log(it, recalled = true) }
        val ignored = listOf(
            log(100, recalled = false, elapsed = 2.0, previous = 2.0),  // too soon after the last review
            log(101, recalled = false, elapsed = 3.0, previous = 40.0), // brought forward by a repair
            log(102, recalled = false, type = "FIRST_STUDY"),           // a self-assessment
            log(103, recalled = false, model = "FSRS-5"),               // the frozen model's curve
            log(104, recalled = false, predicted = -1.0),               // no stored prediction
        )
        val card = calibrationStatsOf(evidence + ignored)!!
        assertEquals(20, card.n)
        assertEquals(100, card.actualPct)
    }

    @Test
    fun `too little evidence shows no card`() {
        assertNull(calibrationStatsOf((0 until 9).map { log(it, recalled = true) }))
        assertNull(calibrationStatsOf(emptyList()))
    }

    @Test
    fun `only the newest window of evidence counts`() {
        val old = (0 until RecallCalibration.WINDOW).map { log(it, recalled = false) }
        val recent = (RecallCalibration.WINDOW until 2 * RecallCalibration.WINDOW).map { log(it, recalled = true) }
        val card = calibrationStatsOf(old + recent)!!
        assertEquals(RecallCalibration.WINDOW, card.n)
        assertEquals("old failures have aged out", 100, card.actualPct)
    }

    @Test
    fun `the card and scheduler agree on saved order after clock rollback regardless of display order`() {
        val old = (1..RecallCalibration.WINDOW).map { log(it, true).copy(reviewedAt = 100_000L + it) }
        val recent = (RecallCalibration.WINDOW + 1..2 * RecallCalibration.WINDOW)
            .map { log(it, it % 100 < 80).copy(reviewedAt = it.toLong()) }
        val expected = RecallCalibration.scale(DoubleArray(RecallCalibration.WINDOW) { 0.91 },
            recent.map { it.memoryRating != "Forgot" }.toBooleanArray())
        for (logs in listOf(old + recent, (old + recent).sortedBy { it.reviewedAt }, (old + recent).reversed())) {
            val card = calibrationStatsOf(logs)!!
            assertEquals(RecallCalibration.WINDOW, card.n)
            assertEquals("saved last rather than greatest timestamp", 80, card.actualPct)
            assertEquals("same evidence regardless of input/display order", expected, card.scale, 1e-12)
        }
    }
}
