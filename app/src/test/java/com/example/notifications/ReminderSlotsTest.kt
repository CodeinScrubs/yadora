package com.example.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * Snoozing must never cost the user more time than the button promised. With a reminder time of
     * 22:00 or 23:00, "Tomorrow" produced tomorrow-at-23:00, which the waking-window clamp then
     * rolled FORWARD another day — a two-day silence under a label that said one. The clamp may move
     * the hour, never the day.
     */
    @Test
    fun `clamping a late-night snooze target keeps it on its own day`() {
        for (hour in listOf(22, 23)) {
            val target = java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.DAY_OF_YEAR, 1)
                set(java.util.Calendar.HOUR_OF_DAY, hour)
                set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            }
            val clamped = java.util.Calendar.getInstance().apply {
                timeInMillis = NotificationScheduler.clampToWakingWindow(target.timeInMillis)
            }
            assertEquals(
                "a $hour:00 target tomorrow must still fire TOMORROW",
                target.get(java.util.Calendar.DAY_OF_YEAR), clamped.get(java.util.Calendar.DAY_OF_YEAR),
            )
            assertTrue(
                "and inside waking hours (got ${clamped.get(java.util.Calendar.HOUR_OF_DAY)}:00)",
                clamped.get(java.util.Calendar.HOUR_OF_DAY) in 8..21,
            )
        }
    }

    @Test
    fun `a target already inside waking hours is left exactly alone`() {
        val target = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 20)
            set(java.util.Calendar.MINUTE, 30); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(target, NotificationScheduler.clampToWakingWindow(target))
    }

    @Test
    fun `a clamped target is never armed in the past`() {
        // 03:00 today is both outside waking hours and (for most of the day) already gone.
        val earlyToday = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 3)
            set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertTrue(
            "clamping must not produce a trigger time that has already passed",
            NotificationScheduler.clampToWakingWindow(earlyToday) > System.currentTimeMillis(),
        )
    }

    /** A clock set forward past a slot must not post the same reminder twice (seen on the emulator, 2026-09-27). */
    @Test
    fun `a reminder posted moments ago is the same reminder`() {
        val now = 1_790_577_300_000L
        assertTrue(NotificationScheduler.shownJustBefore(now - 1_000, now))
        assertTrue(NotificationScheduler.shownJustBefore(now, now))
        assertFalse("five minutes later is a new nudge", NotificationScheduler.shownJustBefore(now - 5 * 60_000, now))
        assertFalse("three hours later is the next nudge", NotificationScheduler.shownJustBefore(now - 3 * 3_600_000, now))
        assertFalse("never shown", NotificationScheduler.shownJustBefore(0L, now))
        assertFalse("shown 'in the future' means the clock went back", NotificationScheduler.shownJustBefore(now + 60_000, now))
    }

    @Test
    fun `a reminder shown 'later today' after the clock went back does not count as shown`() {
        val start = 1_790_541_000_000L // a local midnight
        val now = start + 20 * 3_600_000L
        assertTrue(NotificationScheduler.shownToday(start + 10 * 3_600_000L, start, now))
        assertFalse("yesterday", NotificationScheduler.shownToday(start - 1, start, now))
        assertFalse("after now: the clock was set back", NotificationScheduler.shownToday(now + 3_600_000L, start, now))
    }

    /** The saved nudge is kept until it fires, but a clock set back must not leave it days ahead. */
    @Test
    fun `a saved nudge is kept only while it is at most one repeat away`() {
        val now = 1_790_577_300_000L
        val hour = 3_600_000L
        assertTrue(NotificationScheduler.isLiveNudge(now + 3 * hour, now))
        assertTrue(NotificationScheduler.isLiveNudge(now + 1, now))
        assertFalse("already due or past", NotificationScheduler.isLiveNudge(now, now))
        assertFalse("the clock went back two days", NotificationScheduler.isLiveNudge(now + 48 * hour, now))
        assertFalse("nothing saved", NotificationScheduler.isLiveNudge(0L, now))
    }

    @Test
    fun duplicate_slot_collision_is_coalesced() {
        val eighteen = 18L * 60 * 60 * 1000
        assertTrue(NotificationScheduler.reminderSlotsCollide(eighteen, eighteen + 30_000))
        assertFalse(NotificationScheduler.reminderSlotsCollide(eighteen, eighteen + 10 * 60_000))
    }
}
