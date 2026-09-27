package com.example.ui.i18n

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

/**
 * The Jalali converter must be EXACT — especially leap years (سال کبیسه). These tests brute-force the
 * round-trip over every single day of 1990..2050 and pin the known Nowruz/leap anchors, so any drift
 * in either direction of the conversion fails loudly.
 */
class PersianDateTest {

    @Test
    fun `round trip is identity for every day 1990-2050`() {
        val c = Calendar.getInstance().apply { clear(); set(1990, 0, 1, 12, 0, 0) }
        val end = Calendar.getInstance().apply { clear(); set(2050, 11, 31, 12, 0, 0) }
        var checked = 0
        while (!c.after(end)) {
            val gy = c.get(Calendar.YEAR); val gm = c.get(Calendar.MONTH) + 1; val gd = c.get(Calendar.DAY_OF_MONTH)
            val (jy, jm, jd) = PersianDate.gregorianToJalali(gy, gm, gd)
            // Jalali day must be structurally valid (month lengths + leap Esfand).
            assert(jm in 1..12) { "bad month $jy/$jm/$jd from $gy-$gm-$gd" }
            assert(jd in 1..PersianDate.jalaliMonthLength(jy, jm)) { "day out of range: $jy/$jm/$jd from $gy-$gm-$gd" }
            // And converting back must give exactly the original Gregorian date.
            val (gy2, gm2, gd2) = PersianDate.jalaliToGregorian(jy, jm, jd)
            assertEquals("$gy-$gm-$gd → $jy/$jm/$jd → back (year)", gy, gy2)
            assertEquals("$gy-$gm-$gd → $jy/$jm/$jd → back (month)", gm, gm2)
            assertEquals("$gy-$gm-$gd → $jy/$jm/$jd → back (day)", gd, gd2)
            c.add(Calendar.DAY_OF_YEAR, 1)
            checked++
        }
        assert(checked > 22000) { "expected ~22k days, checked $checked" }
    }

    @Test
    fun `nowruz and leap-year anchors are exact`() {
        // Nowruz boundaries.
        assertEquals(Triple(1403, 1, 1), PersianDate.gregorianToJalali(2024, 3, 20))
        assertEquals(Triple(1404, 1, 1), PersianDate.gregorianToJalali(2025, 3, 21))
        // 1403 is کبیسه: Esfand has 30 days and 1403/12/30 exists (= 2025-03-20).
        assertEquals(30, PersianDate.jalaliMonthLength(1403, 12))
        assertEquals(Triple(1403, 12, 30), PersianDate.gregorianToJalali(2025, 3, 20))
        assertEquals(Triple(2025, 3, 20), PersianDate.jalaliToGregorian(1403, 12, 30))
        // 1404 is common: Esfand has 29 days.
        assertEquals(29, PersianDate.jalaliMonthLength(1404, 12))
        // 1399 was کبیسه too (previous cycle).
        assertEquals(30, PersianDate.jalaliMonthLength(1399, 12))
        // Ordinary month lengths.
        assertEquals(31, PersianDate.jalaliMonthLength(1404, 1))   // فروردین
        assertEquals(31, PersianDate.jalaliMonthLength(1404, 6))   // شهریور
        assertEquals(30, PersianDate.jalaliMonthLength(1404, 7))   // مهر
        assertEquals(30, PersianDate.jalaliMonthLength(1404, 11))  // بهمن
    }

    @Test
    fun `week column is saturday-first`() {
        // 2026-07-04 is a Saturday → column 0 (شنبه).
        val (jy, jm, jd) = PersianDate.gregorianToJalali(2026, 7, 4)
        assertEquals(0, PersianDate.weekColumn(jy, jm, jd))
        // 2026-07-10 is a Friday → column 6 (جمعه).
        val (fy, fm, fd) = PersianDate.gregorianToJalali(2026, 7, 10)
        assertEquals(6, PersianDate.weekColumn(fy, fm, fd))
    }

    @Test
    fun `fa digits conversion`() {
        assertEquals("۱۴۰۳", PersianDate.faDigits(1403))
        assertEquals("۰۹:۳۰", PersianDate.faDigits("09:30"))
        assertEquals("abc", PersianDate.faDigits("abc"))
        assertEquals("a decimal point becomes the Persian separator", "۱۱٫۲ روز", PersianDate.faDigits("11.2 روز"))
        assertEquals("×۰٫۸۱", PersianDate.faDigits("×0.81"))
        assertEquals("a full stop is not a decimal point", "مرور ۳.", PersianDate.faDigits("مرور 3."))
        assertEquals("۱۴۰۵/۰۷/۰۵ ۰۹:۳۰", PersianDate.faDigits("1405/07/05 09:30"))
    }
}
