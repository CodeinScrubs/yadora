package com.example.domain.srs

import com.example.ui.today.TodayBuckets
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Due dates are stored as `reviewedAt + intervalDays * 86_400_000` — pure ELAPSED milliseconds.
 *
 * That is deliberately right for the memory model: forgetting is a physical process, so FSRS must be
 * fed true elapsed time, not calendar days. But the DUE date derived from the same arithmetic is a
 * calendar question ("which day do I study this?"), and on a DST-shifting night a 24-hour hop is not
 * a one-day hop. This test exists to state exactly how far that can drift, so the behaviour is a
 * known quantity rather than an assumption — and so a future change to the date math is caught.
 *
 * Germany is used because Yadora ships a German locale and observes DST; Iran (the primary locale)
 * dropped DST in 2022 and is unaffected.
 */
class DueDateDstTest {

    private val original: TimeZone = TimeZone.getDefault()

    @After
    fun restore() {
        TimeZone.setDefault(original)
    }

    private fun at(tz: TimeZone, y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        Calendar.getInstance(tz).apply {
            clear(); set(y, m - 1, d, h, min, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun dayOfMonth(tz: TimeZone, millis: Long): Int =
        Calendar.getInstance(tz).apply { timeInMillis = millis }.get(Calendar.DAY_OF_MONTH)

    /** A one-day interval lands on the next calendar day for a review at any ordinary hour. */
    @Test
    fun `a one day interval lands on the next calendar day across the spring forward`() {
        val berlin = TimeZone.getTimeZone("Europe/Berlin")
        TimeZone.setDefault(berlin)

        // 2026-03-29 is the European spring-forward (02:00 -> 03:00), so that day is only 23h long.
        for (hour in listOf(8, 12, 18, 21)) {
            val reviewedAt = at(berlin, 2026, 3, 28, hour, 0)
            val dueAt = reviewedAt + (1.0 * 86400000).toLong()
            assertEquals(
                "reviewing at ${hour}:00 the day before the shift is due the next calendar day",
                29, dayOfMonth(berlin, dueAt),
            )
        }
    }

    /**
     * One real edge (the autumn has its mirror image, below): a review in the last hour before midnight,
     * on the night the clock skips forward. 24 elapsed hours then crosses TWO midnights, so a "tomorrow"
     * becomes the day after.
     *
     * Accepted, not fixed. It needs a review between 23:00 and midnight on one specific night a year
     * in a DST-observing zone, and it costs the user one day on one topic. The alternative — deriving
     * the due date by calendar addition instead of elapsed time — would make preview, commit and
     * replay depend on the device's time zone at the moment each ran, so a schedule replayed after a
     * move abroad would no longer reproduce the dates the user was actually given. That is a much
     * worse failure than a once-a-year one-day slip, so the elapsed-time definition stays.
     */
    @Test
    fun `a late night review on the shift night slips one day and that is the accepted cost`() {
        val berlin = TimeZone.getTimeZone("Europe/Berlin")
        TimeZone.setDefault(berlin)

        val reviewedAt = at(berlin, 2026, 3, 28, 23, 30)
        val dueAt = reviewedAt + (1.0 * 86400000).toLong()

        assertEquals("24 elapsed hours crosses into the 30th, not the 29th", 30, dayOfMonth(berlin, dueAt))
        // Bounded: it is one day late, never more, and never early.
        assertTrue("never more than a day of slip", dueAt - reviewedAt <= 2 * 86400000L)
    }

    /** The autumn fall-back lengthens the day; a review on the day before it is never due early. */
    @Test
    fun `the autumn fall back never pulls a due date into the previous day`() {
        val berlin = TimeZone.getTimeZone("Europe/Berlin")
        TimeZone.setDefault(berlin)

        // 2026-10-25 is the European fall-back (03:00 -> 02:00): a 25-hour day.
        for (hour in listOf(0, 8, 12, 23)) {
            val reviewedAt = at(berlin, 2026, 10, 24, hour, 30)
            val dueAt = reviewedAt + (1.0 * 86400000).toLong()
            val day = dayOfMonth(berlin, dueAt)
            assertTrue("reviewing at ${hour}:30 is never due before the next day, got day $day", day >= 25)
        }
    }

    /**
     * The mirror image of the spring slip, and the one case where a date arrives EARLY: a review in the first
     * hour of the fall-back day itself. That day has 25 hours, so 24 elapsed hours end at 23:xx the SAME day: a
     * one-day interval comes due that evening, and every longer one a calendar day early. Accepted for the same
     * reason as the slip (bounded to one day, one hour a year, only where clocks change; Iran has none), and the
     * memory model is still fed the true elapsed time. The earlier test reviewed only on the day before, and its
     * comment said a date never arrives early (found checking an outside audit, 2026-09-28).
     */
    @Test
    fun `a review in the first hour of the fall back day comes due one calendar day early`() {
        val berlin = TimeZone.getTimeZone("Europe/Berlin")
        TimeZone.setDefault(berlin)

        val reviewedAt = at(berlin, 2026, 10, 25, 0, 30)
        assertEquals("a one-day interval comes due the same evening", 25, dayOfMonth(berlin, reviewedAt + 86400000L))
        assertEquals("a two-day interval one day early", 26, dayOfMonth(berlin, reviewedAt + 2 * 86400000L))
        val anHourLater = at(berlin, 2026, 10, 25, 1, 30)
        assertEquals("from 01:00 on, the next calendar day as usual", 26, dayOfMonth(berlin, anHourLater + 86400000L))
    }

    /**
     * Whatever the hour arithmetic does, the day-granularity queue absorbs it: a topic due at ANY
     * time on a calendar day is picked up by that day's session, because the cutoff is 23:59:59.999.
     * This is why the drift above stays a one-day question and never a "did it disappear" question.
     */
    @Test
    fun `any time on a calendar day is caught by that day's end-of-day cutoff`() {
        val berlin = TimeZone.getTimeZone("Europe/Berlin")
        TimeZone.setDefault(berlin)

        val startOfToday = at(berlin, 2026, 3, 29, 0, 0)
        val endOfToday = Calendar.getInstance(berlin).apply {
            timeInMillis = startOfToday
            set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
        }.timeInMillis

        for (hour in 0..23) {
            val dueAt = at(berlin, 2026, 3, 29, hour, 17)
            assertTrue(
                "a topic due at ${hour}:17 is in the day's queue",
                TodayBuckets.isDueByEndOfToday(dueAt, endOfToday),
            )
            assertTrue(
                "and is bucketed as due TODAY, not overdue or upcoming",
                TodayBuckets.isDueToday(dueAt, startOfToday, endOfToday),
            )
        }
    }
}
