package com.example.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two-fires-per-day guarantee rests on the secondary slot being on the OPPOSITE half of the day
 * from the user's chosen time — these pin that mapping so a refactor can't quietly break it.
 */
class ReminderSlotsTest {

    @Test
    fun `evening reminder times get a morning second slot`() {
        assertEquals(10, NotificationScheduler.secondaryReminderHour(20)) // default 20:00 → 10:00
        assertEquals(10, NotificationScheduler.secondaryReminderHour(14))
        assertEquals(10, NotificationScheduler.secondaryReminderHour(23))
    }

    @Test
    fun `morning reminder times get an evening second slot`() {
        assertEquals(18, NotificationScheduler.secondaryReminderHour(8))
        assertEquals(18, NotificationScheduler.secondaryReminderHour(0))
        assertEquals(18, NotificationScheduler.secondaryReminderHour(13))
    }

    @Test
    fun `the two slots are always well separated`() {
        for (h in 0..23) {
            val sec = NotificationScheduler.secondaryReminderHour(h)
            assertTrue("slots for $h:00 and $sec:00 are at least 4h apart",
                kotlin.math.abs(h - sec) >= 4)
        }
    }
}
