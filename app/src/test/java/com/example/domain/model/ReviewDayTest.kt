package com.example.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The day a review happened, when the learner says it was not today, and the day of a logged review corrected later
 * (the owner's decisions, 2026-10-09). Only the day is chosen; these pin the time a review is saved with: never after
 * now, never before the topic's previous review, and inside its neighbours when corrected, so a history never changes
 * order.
 */
class ReviewDayTest {
    private val tehran = ZoneId.of("Asia/Tehran")
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun at(zone: ZoneId, y: Int, m: Int, d: Int, h: Int, min: Int = 0): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    @Test fun `today is saved as just now`() {
        val now = at(tehran, 2026, 10, 9, 21, 15)
        assertEquals(now, ReviewDay.timeForRating(LocalDate.of(2026, 10, 9), now, at(tehran, 2026, 10, 2, 9), tehran))
    }

    @Test fun `an earlier day keeps this hour and stays after the previous review`() {
        val now = at(tehran, 2026, 10, 9, 21, 15)
        // Yesterday, at this hour.
        assertEquals(at(tehran, 2026, 10, 8, 21, 15), ReviewDay.timeForRating(LocalDate.of(2026, 10, 8), now, at(tehran, 2026, 10, 2, 9), tehran))
        // The day of the previous review, which was LATER in that day than this hour: just after it, so the order holds.
        val previous = at(tehran, 2026, 10, 8, 23, 30)
        assertEquals(previous + 1, ReviewDay.timeForRating(LocalDate.of(2026, 10, 8), now, previous, tehran))
    }

    @Test fun `the earliest day is the previous review's, or the study day of a topic never rated`() {
        val now = at(tehran, 2026, 10, 9, 12)
        assertEquals(LocalDate.of(2026, 10, 5), ReviewDay.earliestForRating(at(tehran, 2026, 10, 5, 22), at(tehran, 2026, 9, 1, 9), now, tehran))
        assertEquals(LocalDate.of(2026, 10, 3), ReviewDay.earliestForRating(null, at(tehran, 2026, 10, 3, 18), now, tehran))
        // A study date in the future (a planned study) never offers a day after today.
        assertEquals(LocalDate.of(2026, 10, 9), ReviewDay.earliestForRating(null, at(tehran, 2026, 10, 20, 9), now, tehran))
    }

    @Test fun `a correction keeps the review's own hour and stays strictly between its neighbours`() {
        val now = at(tehran, 2026, 10, 9, 12)
        val previous = at(tehran, 2026, 9, 20, 20)
        val original = at(tehran, 2026, 9, 25, 19, 40)
        val next = at(tehran, 2026, 10, 1, 8)
        val range = ReviewDay.correctionRange(previous, next, now, tehran)!!
        assertEquals(LocalDate.of(2026, 9, 20), range.start)
        assertEquals(LocalDate.of(2026, 10, 1), range.endInclusive)
        assertEquals(at(tehran, 2026, 9, 23, 19, 40), ReviewDay.timeForCorrection(LocalDate.of(2026, 9, 23), original, previous, next, now, tehran))
        // Onto the previous review's day, at an earlier hour than it: just after it.
        val ontoPrevious = ReviewDay.timeForCorrection(LocalDate.of(2026, 9, 20), at(tehran, 2026, 9, 25, 7), previous, next, now, tehran)
        assertEquals(previous + 1, ontoPrevious)
        // Onto the next review's day, at a later hour than it: just before it.
        val ontoNext = ReviewDay.timeForCorrection(LocalDate.of(2026, 10, 1), original, previous, next, now, tehran)
        assertEquals(next - 1, ontoNext)
    }

    @Test fun `the last review can move up to today but never into the future`() {
        val now = at(tehran, 2026, 10, 9, 10)
        val previous = at(tehran, 2026, 10, 2, 20)
        val range = ReviewDay.correctionRange(previous, null, now, tehran)!!
        assertEquals(LocalDate.of(2026, 10, 9), range.endInclusive)
        // The review was at 19:40; moved to today at 10:00 it would be in the future, so it is saved as now.
        assertEquals(now, ReviewDay.timeForCorrection(LocalDate.of(2026, 10, 9), at(tehran, 2026, 10, 5, 19, 40), previous, null, now, tehran))
    }

    @Test fun `a topic's first log may move back a year when nothing earlier bounds it`() {
        val now = at(tehran, 2026, 10, 9, 10)
        val range = ReviewDay.correctionRange(null, at(tehran, 2026, 10, 4, 9), now, tehran)!!
        assertEquals(LocalDate.of(2026, 10, 9).minusDays(ReviewDay.FIRST_LOG_DAYS_BACK), range.start)
    }

    @Test fun `neighbours out of order leave no day to move to`() {
        // A clock set back between two reviews: the review saved before has the LATER time.
        val now = at(tehran, 2026, 10, 9, 10)
        assertNull(ReviewDay.correctionRange(at(tehran, 2026, 10, 6, 9), at(tehran, 2026, 10, 4, 9), now, tehran))
    }

    @Test fun `a review before it that lies after now leaves no day either`() {
        // The clock set back since the review before: its day is today, but every time after it lies after now. The
        // picker used to offer today, and the repository then refused the move without a word (a production review,
        // 2026-10-10).
        val now = at(tehran, 2026, 10, 9, 9)
        assertNull(ReviewDay.correctionRange(at(tehran, 2026, 10, 9, 10), null, now, tehran))
    }

    @Test fun `every day offered gives a time inside the neighbours, not after now, on that day`() {
        val now = at(tehran, 2026, 10, 9, 12)
        val cases = listOf(
            Triple(at(tehran, 2026, 10, 2, 20), at(tehran, 2026, 10, 6, 8), at(tehran, 2026, 10, 4, 21)),
            // The review after it at exactly midnight: the day it starts holds no time before it.
            Triple(at(tehran, 2026, 10, 2, 20), at(tehran, 2026, 10, 6, 0), at(tehran, 2026, 10, 4, 21)),
            // Both neighbours on one day, a minute apart.
            Triple(at(tehran, 2026, 10, 5, 9, 0), at(tehran, 2026, 10, 5, 9, 1), at(tehran, 2026, 10, 5, 23)),
            // The last review, its hour later than now's.
            Triple(at(tehran, 2026, 10, 9, 11), null, at(tehran, 2026, 10, 3, 22)),
            Triple(null, at(tehran, 2026, 10, 1, 7), at(tehran, 2026, 9, 30, 23, 59)),
        )
        for ((previous, next, original) in cases) {
            val range = ReviewDay.correctionRange(previous, next, now, tehran)!!
            var d = range.start
            while (d <= range.endInclusive) {
                val t = ReviewDay.timeForCorrection(d, original, previous, next, now, tehran)
                assertTrue("after the review before ($d)", previous == null || t > previous)
                assertTrue("before the review after ($d)", next == null || t < next)
                assertTrue("not after now ($d)", t <= now)
                assertEquals("on the day chosen", d, ReviewDay.day(t, tehran))
                d = d.plusDays(1)
            }
            if (next != null) assertTrue("no day after the one the next review's time is on", range.endInclusive <= ReviewDay.day(next - 1, tehran))
        }
    }

    @Test fun `a day in a daylight-saving gap still lands on that day`() {
        // Berlin skips 02:00-03:00 on 2026-03-29. A rating for that day at 02:30 (the clock's hour now) lands on it.
        val now = at(berlin, 2026, 3, 30, 2, 30)
        val t = ReviewDay.timeForRating(LocalDate.of(2026, 3, 29), now, null, berlin)
        assertEquals(LocalDate.of(2026, 3, 29), ReviewDay.day(t, berlin))
        assertTrue(t < now)
    }
}
