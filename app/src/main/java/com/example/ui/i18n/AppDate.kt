package com.example.ui.i18n

import androidx.compose.runtime.compositionLocalOf

/**
 * Whether dates should render in the Jalali (Solar Hijri / شمسی) calendar. Set independently of the
 * UI language, so a user can keep the English interface but still see Jalali dates (or vice-versa).
 * Provided from the "calendar_format" preference in MainActivity; defaults to Gregorian.
 */
val LocalUseJalali = compositionLocalOf { false }

/**
 * Central date formatting so the calendar preference is honored consistently everywhere.
 * Jalali → Persian-script month names (e.g. "14 تیر 1403"). Gregorian → English (e.g. "Jul 14, 2026"),
 * pinned to Locale.ENGLISH so a device's own locale can't leak a third format into the app.
 *
 * The DIGITS follow the interface language, not the calendar: [persianDigits] (the Persian interface) gives
 * "۱۴ تیر ۱۴۰۳", while an English interface on the Jalali calendar keeps Latin digits. Every caller passes it.
 * It used to be missing, so the Persian interface printed every Jalali date in Latin digits beside Persian ones.
 */
object AppDate {
    private fun digits(s: String, persianDigits: Boolean) = if (persianDigits) PersianDate.faDigits(s) else s

    fun date(useJalali: Boolean, millis: Long, persianDigits: Boolean): String = digits(
        if (useJalali) PersianDate.formatDate(millis)
        else java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.ENGLISH).format(java.util.Date(millis)),
        persianDigits,
    )

    fun dateTime(useJalali: Boolean, millis: Long, persianDigits: Boolean): String = digits(
        if (useJalali) PersianDate.formatDateTime(millis)
        else java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.ENGLISH).format(java.util.Date(millis)),
        persianDigits,
    )

    /**
     * The value Material's DatePicker expects for "select this LOCAL day": UTC midnight of that date.
     * Handing it local epoch millis selected the UTC date instead, which is the previous day for any
     * zone east of UTC in its first hours after midnight — a German user adding a topic at 00:30 saw
     * yesterday preselected, and confirming it back-dated the study by a day.
     */
    fun pickerSelection(localMillis: Long): Long {
        val local = java.time.Instant.ofEpochMilli(localMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        return local.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
    }

    /** "EEE, MMM d" style (weekday + short date) for the forecast headers. */
    fun weekdayDate(useJalali: Boolean, millis: Long, persianDigits: Boolean): String = digits(
        if (useJalali) PersianDate.formatDate(millis)
        else java.text.SimpleDateFormat("EEE, MMM d", java.util.Locale.ENGLISH).format(java.util.Date(millis)),
        persianDigits,
    )
}
