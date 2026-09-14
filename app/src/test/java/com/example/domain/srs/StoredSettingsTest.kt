package com.example.domain.srs

import org.junit.Assert.assertEquals
import org.junit.Test

/** Settings are stored as Floats by Compose sliders; reading them must give back the stop the user chose. */
class StoredSettingsTest {

    @Test
    fun `a daily limit stored by the slider reads back as the stop the user chose`() {
        // What Compose's Float interpolation actually stores for the 70 and 130 stops.
        assertEquals(70, MedScheduler.safeDailyLimit(69.99999f))
        assertEquals(130, MedScheduler.safeDailyLimit(129.99998f))
        assertEquals(50, MedScheduler.safeDailyLimit(50f))
        assertEquals(200, MedScheduler.safeDailyLimit(200f))
    }

    @Test
    fun `a corrupt stored limit degrades to a sane one`() {
        assertEquals(MedScheduler.DEFAULT_DAILY_LIMIT, MedScheduler.safeDailyLimit(Float.NaN))
        assertEquals(MedScheduler.DEFAULT_DAILY_LIMIT, MedScheduler.safeDailyLimit(Float.POSITIVE_INFINITY))
        assertEquals(500, MedScheduler.safeDailyLimit(1e9f))
        assertEquals(1, MedScheduler.safeDailyLimit(-5f))
    }
}
