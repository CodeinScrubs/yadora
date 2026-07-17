package com.example.ui.i18n

import java.util.Calendar

/**
 * Shared exam-countdown text so Today, Library and Progress all say the same thing. Counts whole
 * days between LOCAL calendar days, and deliberately does NOT count the exam day itself (the user
 * won't be studying on exam day) — so "exam is tomorrow" reads as 1 day left, "exam is today" as 0.
 * Returns null when no exam is set or it's already past.
 */
object ExamCountdown {

    /** Local-midnight of the day containing [millis]. */
    private fun startOfLocalDay(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Whole study-days until the exam (exam day excluded), or null if unset/past. */
    fun daysUntil(examDateMillis: Long, now: Long = System.currentTimeMillis()): Long? {
        if (examDateMillis <= 0L) return null
        val examDay = startOfLocalDay(examDateMillis)
        val today = startOfLocalDay(now)
        val days = (examDay - today) / 86_400_000L
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
            else -> when (languageCode) {
                "fa" -> "${n(days)} روز تا $name"
                "de" -> "Noch ${n(days)} Tage bis $name"
                else -> "${n(days)} days until $name"
            }
        }
    }
}
