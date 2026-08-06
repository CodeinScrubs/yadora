package com.example.ui.i18n

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Shared exam-countdown text so Today, Library and Progress all say the same thing. Counts whole
 * days between LOCAL calendar days, and deliberately does NOT count the exam day itself (the user
 * won't be studying on exam day) — so "exam is tomorrow" reads as 1 day left, "exam is today" as 0.
 * Returns null when no exam is set or it's already past.
 */
object ExamCountdown {

    private fun localDate(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

    /**
     * Whole study-days until the exam (exam day excluded), or null if unset/past.
     *
     * Counted between CALENDAR DATES, not by dividing a millisecond difference by 86,400,000. A
     * fixed-24h division is wrong across a daylight-saving change: two local midnights a week apart
     * are 167 hours apart in spring, and 167/24 truncates to 6 — so a German user a week before their
     * exam was told they had six days. Iran no longer observes DST, so this only ever misled the
     * German/European users, silently and in the direction that matters most.
     */
    fun daysUntil(examDateMillis: Long, now: Long = System.currentTimeMillis()): Long? {
        if (examDateMillis <= 0L) return null
        val days = ChronoUnit.DAYS.between(localDate(now), localDate(examDateMillis))
        return if (days < 0) null else days
    }

    /** Localized "N days until <exam>" (or "exam day is here" at 0), or null if unset/past. */
    fun text(examName: String, examDateMillis: Long, languageCode: String, now: Long = System.currentTimeMillis()): String? {
        val days = daysUntil(examDateMillis, now) ?: return null
        fun n(v: Long) = if (languageCode == "fa") PersianDate.faDigits(v) else v.toString()
        val name = examName.ifBlank {
            when (languageCode) { "fa" -> "امتحان"; "de" -> "Prüfung"; else -> "exam" }
        }
        return when {
            days == 0L -> when (languageCode) {
                "fa" -> "$name امروز است"
                "de" -> "$name ist heute"
                else -> "$name is today"
            }
            // English and German inflect the noun at exactly one; Persian does not ("۱ روز" is correct).
            days == 1L -> when (languageCode) {
                "fa" -> "${n(days)} روز تا $name"
                "de" -> "Noch ${n(days)} Tag bis $name"
                else -> "${n(days)} day until $name"
            }
            else -> when (languageCode) {
                "fa" -> "${n(days)} روز تا $name"
                "de" -> "Noch ${n(days)} Tage bis $name"
                else -> "${n(days)} days until $name"
            }
        }
    }
}
