package com.example.domain.srs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One ceiling, three places. `fuzzedInterval` used to clamp to the FSRS-5 parameter class's default
 * while the live path clamped to the FSRS-6 one — equal today, and a silent split the day either
 * moved. Now all three are the same constant, and this test says so out loud.
 */
class IntervalCeilingTest {

    @Test
    fun `the FSRS-6 parameters, the FSRS-5 parameters and the fuzz share one ceiling`() {
        assertEquals(MedScheduler.MAX_INTERVAL_DAYS, Fsrs6Parameters().maximumIntervalDays, 0.0)
        assertEquals(MedScheduler.MAX_INTERVAL_DAYS, FsrsParameters().maximumIntervalDays, 0.0)
    }

    @Test
    fun `fuzz never lifts an interval past the ceiling and never below the floor`() {
        for (reviewCount in 0 until 300) {
            val top = MedScheduler.fuzzedInterval(MedScheduler.MAX_INTERVAL_DAYS, 9999.0, 7L, reviewCount)
            assertTrue("at the ceiling (count $reviewCount): $top", top <= MedScheduler.MAX_INTERVAL_DAYS + 1e-12)
            val bottom = MedScheduler.fuzzedInterval(1.0, 3.0, 7L, reviewCount)
            assertTrue("at the floor (count $reviewCount): $bottom", bottom >= MedScheduler.MIN_INTERVAL_DAYS - 1e-12)
        }
    }
}
