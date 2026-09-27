package com.example.ui.i18n

import java.util.Calendar

/**
 * Jalali (Solar Hijri / تقویم شمسی) date formatting for the Persian UI.
 *
 * Uses the standard day-count Gregorian↔Jalali conversion. It is correct across all practical dates and
 * handles **leap years (سال کبیسه) exactly**: because the month and day are derived from the true elapsed
 * day count (not a fixed month table), a leap Jalali year yields Esfand (اسفند) = 30 days and a common
 * year = 29. Verified against Nowruz boundaries:
 *   2024-03-20 → 1403/01/01,  and  2025-03-20 → 1403/12/30  (1403 is کبیسه, so Esfand has 30 days).
 *
 * Only integer arithmetic on positive values is used (dates are always after 621 AD), so Kotlin's
 * truncating integer division equals floor — no negative-division pitfalls.
 */
object PersianDate {
    private val MONTHS = arrayOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
    )
    private val FA_DIGITS = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')

    /** Convert Gregorian (year, month 1..12, day) to Jalali as Triple(year, month 1..12, day). */
    fun gregorianToJalali(gYear: Int, gMonth: Int, gDay: Int): Triple<Int, Int, Int> {
        val gdm = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        var jy: Int
        var gy = gYear
        if (gy > 1600) { jy = 979; gy -= 1600 } else { jy = 0; gy -= 621 }
        // Gregorian leap-day handling (accounts for the 4/100/400 rule) baked into the day count.
        val gy2 = if (gMonth > 2) gy + 1 else gy
        var days = 365 * gy + (gy2 + 3) / 4 - (gy2 + 99) / 100 + (gy2 + 399) / 400 - 80 + gDay + gdm[gMonth - 1]
        jy += 33 * (days / 12053)   // 12053 days = one 33-year Jalali cycle (contains 8 leap years)
        days %= 12053
        jy += 4 * (days / 1461)     // 1461 days = 4 years (one leap)
        days %= 1461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }
        return if (days < 186) {
            // First 6 months have 31 days each.
            Triple(jy, 1 + days / 31, 1 + days % 31)
        } else {
            // Months 7..11 have 30 days; Esfand gets the remainder (29 or 30 in a leap year).
            Triple(jy, 7 + (days - 186) / 30, 1 + (days - 186) % 30)
        }
    }

    /**
     * Convert Latin digits in a string to Persian digits. A '.' BETWEEN two digits is a decimal point and becomes
     * the Persian decimal separator '٫' ("11.2" → "۱۱٫۲"): printing "۱۱.۲" mixed two writing systems in one number.
     * Everything else is left as-is: a sentence's full stop, a time's ':' and a date's '/' never sit between two digits
     * as a decimal point does.
     */
    fun faDigits(s: String): String {
        val sb = StringBuilder(s.length)
        for ((i, ch) in s.withIndex()) {
            sb.append(
                when {
                    ch in '0'..'9' -> FA_DIGITS[ch - '0']
                    ch == '.' && i > 0 && i < s.length - 1 && s[i - 1] in '0'..'9' && s[i + 1] in '0'..'9' -> '٫'
                    else -> ch
                }
            )
        }
        return sb.toString()
    }

    fun faDigits(n: Int): String = faDigits(n.toString())
    fun faDigits(n: Long): String = faDigits(n.toString())

    /**
     * Convert Jalali (year, month 1..12, day) to Gregorian as Triple(year, month 1..12, day).
     * The exact inverse of [gregorianToJalali] (same day-count algorithm family), so round-trips are
     * identity for every date — including leap-year (کبیسه) Esfand 30ths. Verified by unit test over
     * every day of 1990..2050.
     */
    fun jalaliToGregorian(jYear: Int, jMonth: Int, jDay: Int): Triple<Int, Int, Int> {
        var jy = jYear
        var gy: Int
        if (jy > 979) { gy = 1600; jy -= 979 } else { gy = 621 }
        var days = 365 * jy + (jy / 33) * 8 + ((jy % 33 + 3) / 4) + 78 + jDay +
            if (jMonth < 7) (jMonth - 1) * 31 else (jMonth - 7) * 30 + 186
        gy += 400 * (days / 146097)   // 146097 days = one 400-year Gregorian cycle
        days %= 146097
        if (days > 36524) {           // century handling (no leap on 100s unless 400s)
            days--
            gy += 100 * (days / 36524)
            days %= 36524
            if (days >= 365) days++
        }
        gy += 4 * (days / 1461)       // 1461 days = 4 years (one leap)
        days %= 1461
        if (days > 365) {
            gy += (days - 1) / 365
            days = (days - 1) % 365
        }
        var gd = days + 1
        val gLeap = (gy % 4 == 0 && gy % 100 != 0) || gy % 400 == 0
        val monthDays = intArrayOf(31, if (gLeap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        var gm = 1
        while (gm <= 12 && gd > monthDays[gm - 1]) { gd -= monthDays[gm - 1]; gm++ }
        return Triple(gy, gm, gd)
    }

    /**
     * Days in a Jalali month. 1..6 = 31; 7..11 = 30; Esfand = 29, or 30 in a leap year (کبیسه) —
     * derived from the true day count between Esfand 1 and the next Farvardin 1, so it stays
     * consistent with the converters by construction (no separate leap rule to drift out of sync).
     */
    fun jalaliMonthLength(jYear: Int, jMonth: Int): Int {
        if (jMonth in 1..6) return 31
        if (jMonth in 7..11) return 30
        val (gy1, gm1, gd1) = jalaliToGregorian(jYear, 12, 1)
        val (gy2, gm2, gd2) = jalaliToGregorian(jYear + 1, 1, 1)
        val a = Calendar.getInstance().apply { clear(); set(gy1, gm1 - 1, gd1, 12, 0, 0) }
        val b = Calendar.getInstance().apply { clear(); set(gy2, gm2 - 1, gd2, 12, 0, 0) }
        return Math.round((b.timeInMillis - a.timeInMillis) / 86400000.0).toInt()
    }

    /** The Jalali month name (1..12), e.g. monthName(1) == "فروردین". */
    fun monthName(jMonth: Int): String = MONTHS[jMonth - 1]

    /**
     * Column (0..6) of a Jalali date in a Saturday-first week grid (شنبه=0 … جمعه=6),
     * matching how Iranian calendars are laid out.
     */
    fun weekColumn(jYear: Int, jMonth: Int, jDay: Int): Int {
        val (gy, gm, gd) = jalaliToGregorian(jYear, jMonth, jDay)
        val c = Calendar.getInstance().apply { clear(); set(gy, gm - 1, gd) }
        return c.get(Calendar.DAY_OF_WEEK) % 7 // SATURDAY(7)→0, SUNDAY(1)→1, …, FRIDAY(6)→6
    }

    /**
     * A Jalali date is a RIGHT-TO-LEFT phrase that happens to START with a number ("13 شهریور 1405"
     * reads day → month → year from the RIGHT). Latin digits are weak under the Unicode Bidi
     * Algorithm, so inside an English screen — or simply concatenated after a label like "Next: " —
     * the leading "13" gets absorbed into the surrounding left-to-right run and the parts render out
     * of order ("شهریور 1405 13").
     *
     * Wrapping the whole date in RLI…PDI (RIGHT-TO-LEFT ISOLATE / POP DIRECTIONAL ISOLATE) makes it
     * a self-contained right-to-left island: it lays out correctly in an English screen, a Persian
     * screen, and in the middle of any other sentence, without affecting the text around it.
     */
    private const val RLI = '\u2067'
    private const val PDI = '\u2069'

    /** Wrap [text] so it always lays out right-to-left, whatever surrounds it. */
    fun rtlIsolate(text: String): String = if (text.isEmpty()) text else "$RLI$text$PDI"

    /** A local-time millis timestamp → e.g. "13 شهریور 1405" (right-to-left, Latin numerals). */
    fun formatDate(millis: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        val (jy, jm, jd) = gregorianToJalali(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
        return rtlIsolate("$jd ${MONTHS[jm - 1]} $jy")
    }

    /** A local-time millis timestamp → e.g. "13 شهریور 1405 ساعت 09:30". */
    fun formatDateTime(millis: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        val (jy, jm, jd) = gregorianToJalali(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
        val hh = c.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
        val mm = c.get(Calendar.MINUTE).toString().padStart(2, '0')
        return rtlIsolate("$jd ${MONTHS[jm - 1]} $jy ساعت $hh:$mm")
    }
}
