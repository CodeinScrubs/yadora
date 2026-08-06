package com.example.ui.i18n

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The exam countdown is a CALENDAR count ("how many study days do I have left"), not a duration.
 *
 * It used to be computed as `(examMidnight - todayMidnight) / 86_400_000`. Across a daylight-saving
 * spring-forward, two local midnights a week apart are only 167 hours apart, and integer division
 * truncates 167/24 to 6 — so a user exactly one week from their exam was told they had six days.
 * Iran no longer observes DST, so this only ever misled German/European users, silently.
 *
 * These tests pin real DST transitions in Europe/Berlin rather than a synthetic offset.
 */
class ExamCountdownDstTest {

    private val berlin = ZoneId.of("Europe/Berlin")
    private lateinit var originalZone: java.util.TimeZone

    /**
     * ExamCountdown reads ZoneId.systemDefault() — correct in production (the user's own device
     * zone), but it means the test must actually RUN in the zone whose DST it is exercising.
     * Building instants in Berlin while the JVM sits in another zone tests nothing about DST.
     */
    @org.junit.Before
    fun useBerlin() {
        originalZone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(berlin))
    }

    @org.junit.After
    fun restoreZone() {
        java.util.TimeZone.setDefault(originalZone)
    }

    /** Local midnight of [date] in Berlin, as epoch millis. */
    private fun midnight(date: LocalDate): Long =
        date.atStartOfDay(berlin).toInstant().toEpochMilli()

    /** A realistic mid-morning "now", to prove the count doesn't depend on time of day. */
    private fun morning(date: LocalDate): Long =
        date.atTime(LocalTime.of(9, 30)).atZone(berlin).toInstant().toEpochMilli()

    @Test
    fun `a week spanning the spring-forward transition still counts seven days`() {
        // Europe/Berlin springs forward on the last Sunday of March 2026 (2026-03-29).
        val now = LocalDate.of(2026, 3, 25)
        val exam = LocalDate.of(2026, 4, 1) // exactly 7 calendar days later, but only 167 hours
        assertEquals(7L, ExamCountdown.daysUntil(midnight(exam), midnight(now)))
        assertEquals(7L, ExamCountdown.daysUntil(midnight(exam), morning(now)))
    }

    @Test
    fun `a week spanning the autumn fall-back still counts seven days`() {
        // Europe/Berlin falls back on the last Sunday of October 2026 (2026-10-25): a 169-hour week.
        val now = LocalDate.of(2026, 10, 22)
        val exam = LocalDate.of(2026, 10, 29)
        assertEquals(7L, ExamCountdown.daysUntil(midnight(exam), midnight(now)))
        assertEquals(7L, ExamCountdown.daysUntil(midnight(exam), morning(now)))
    }

    @Test
    fun `the count is stable at every hour of the day before a transition`() {
        val now = LocalDate.of(2026, 3, 28) // the day before spring-forward
        val exam = LocalDate.of(2026, 3, 31)
        for (hour in 0..23) {
            val at = now.atTime(LocalTime.of(hour, 0)).atZone(berlin).toInstant().toEpochMilli()
            assertEquals(
                "countdown must not depend on the hour it is read (h=$hour)",
                3L, ExamCountdown.daysUntil(midnight(exam), at),
            )
        }
    }

    @Test
    fun `same day is zero, tomorrow is one, and a past exam is null`() {
        val today = LocalDate.of(2026, 6, 10)
        assertEquals(0L, ExamCountdown.daysUntil(midnight(today), morning(today)))
        assertEquals(1L, ExamCountdown.daysUntil(midnight(today.plusDays(1)), morning(today)))
        assertEquals(null, ExamCountdown.daysUntil(midnight(today.minusDays(1)), morning(today)))
        assertEquals("unset exam has no countdown", null, ExamCountdown.daysUntil(0L, morning(today)))
    }

    @Test
    fun `a long horizon across both transitions counts calendar days exactly`() {
        val now = LocalDate.of(2026, 3, 1)
        val exam = LocalDate.of(2026, 11, 1) // spans spring-forward AND fall-back
        val expected = java.time.temporal.ChronoUnit.DAYS.between(now, exam)
        assertEquals(expected, ExamCountdown.daysUntil(midnight(exam), midnight(now)))
    }
}
