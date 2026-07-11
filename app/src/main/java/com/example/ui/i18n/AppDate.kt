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
 * Jalali → authentic Persian-script month names + Persian digits (e.g. "۱۴ تیر ۱۴۰۳").
 * Gregorian → English, Latin digits (e.g. "Jul 14, 2026") — pinned to Locale.ENGLISH so a device's
 * own locale can't leak a third format into the app.
 */
object AppDate {
    fun date(useJalali: Boolean, millis: Long): String =
        if (useJalali) PersianDate.formatDate(millis)
        else java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.ENGLISH).format(java.util.Date(millis))

    fun dateTime(useJalali: Boolean, millis: Long): String =
        if (useJalali) PersianDate.formatDateTime(millis)
        else java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.ENGLISH).format(java.util.Date(millis))

    /** "EEE, MMM d" style (weekday + short date) for the forecast headers. */
    fun weekdayDate(useJalali: Boolean, millis: Long): String =
        if (useJalali) PersianDate.formatDate(millis)
        else java.text.SimpleDateFormat("EEE, MMM d", java.util.Locale.ENGLISH).format(java.util.Date(millis))
}
