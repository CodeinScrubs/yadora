package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ui.i18n.PersianDate
import java.util.Calendar

/**
 * A Jalali (شمسی) date-picker dialog — the missing half of the app's Persian calendar support: dates
 * were *displayed* in Jalali but *picked* on a Gregorian grid. Backed by the round-trip-verified
 * [PersianDate] converters, so leap years (کبیسه) are exact: Esfand shows 30 cells in a leap year.
 *
 * Layout follows Iranian convention: Saturday-first week (ش ی د س چ پ ج). The app is already RTL when
 * this is shown, so Row order and the auto-mirrored chevrons come out correctly for free.
 *
 * @param initialMillis the date to open on (local time).
 * @param onConfirm called with the picked day pinned to LOCAL 9:00 — the same convention as the
 *        Gregorian picker path ([datePickerUtcToLocalDay]), so downstream scheduling is unaffected.
 */
@Composable
fun JalaliDatePickerDialog(
    initialMillis: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val init = remember(initialMillis) {
        val c = Calendar.getInstance().apply { timeInMillis = initialMillis }
        PersianDate.gregorianToJalali(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }
    // Saveable: turning the phone (or a font-size change) used to reset a half-chosen date to the day it opened on.
    var year by rememberSaveable(initialMillis) { mutableIntStateOf(init.first) }
    var month by rememberSaveable(initialMillis) { mutableIntStateOf(init.second) }
    var day by rememberSaveable(initialMillis) { mutableIntStateOf(init.third) }
    // Digits follow the interface language, as everywhere else (AppDate): an English or German screen on the Jalali
    // calendar keeps Latin digits, a Persian one shows Persian digits, the title included (it printed "8 مهر 1405").
    val languageCode = com.example.ui.i18n.LocalStrings.current.languageCode
    val persianDigits = languageCode == "fa"
    fun digits(n: Int): String = if (persianDigits) PersianDate.faDigits(n) else n.toString()

    // Keep the selected day valid when navigating into a shorter month (e.g. 31 فروردین → اسفند).
    fun clampDay() { day = day.coerceAtMost(PersianDate.jalaliMonthLength(year, month)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                // Calendar format is user-selectable INDEPENDENTLY of UI language, so this dialog can
                // appear on an English or German screen. rtlIsolate keeps the date reading
                // day → month → year there too, exactly like every other Jalali date in the app.
                PersianDate.rtlIsolate("${digits(day)} ${PersianDate.monthName(month)} ${digits(year)}"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            // Scrolls when the dialog is short: in landscape (360 dp tall) the grid was cut after the first row, so most
            // days could not be picked (an outside emulator audit, 2026-09-30).
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // Year stepper.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { year--; clampDay() }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = when (com.example.ui.i18n.LocalStrings.current.languageCode) { "fa" -> "سال قبل"; "de" -> "Vorheriges Jahr"; else -> "Previous year" })
                    }
                    Text(
                        digits(year),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    )
                    IconButton(onClick = { year++; clampDay() }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = when (com.example.ui.i18n.LocalStrings.current.languageCode) { "fa" -> "سال بعد"; "de" -> "Nächstes Jahr"; else -> "Next year" })
                    }
                }
                // Month stepper.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { if (month == 1) { month = 12; year-- } else month--; clampDay() }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = when (com.example.ui.i18n.LocalStrings.current.languageCode) { "fa" -> "ماه قبل"; "de" -> "Vorheriger Monat"; else -> "Previous month" })
                    }
                    Text(PersianDate.monthName(month), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { if (month == 12) { month = 1; year++ } else month++; clampDay() }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = when (com.example.ui.i18n.LocalStrings.current.languageCode) { "fa" -> "ماه بعد"; "de" -> "Nächster Monat"; else -> "Next month" })
                    }
                }
                // Weekday header, Saturday-first, in the interface language.
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    when (languageCode) {
                        "fa" -> listOf("ش", "ی", "د", "س", "چ", "پ", "ج")
                        "de" -> listOf("Sa", "So", "Mo", "Di", "Mi", "Do", "Fr")
                        else -> listOf("Sa", "Su", "Mo", "Tu", "We", "Th", "Fr")
                    }.forEach { d ->
                        Text(
                            d,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Day grid.
                val monthLen = PersianDate.jalaliMonthLength(year, month)
                val firstCol = PersianDate.weekColumn(year, month, 1)
                val cells = firstCol + monthLen
                val rows = (cells + 6) / 7
                for (r in 0 until rows) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        for (col in 0 until 7) {
                            val idx = r * 7 + col
                            val d = idx - firstCol + 1
                            Box(
                                modifier = Modifier.weight(1f).height(40.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (d in 1..monthLen) {
                                    val selected = d == day
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .background(
                                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                                                CircleShape,
                                            )
                                            .clickable { day = d },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            digits(d),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val (gy, gm, gd) = PersianDate.jalaliToGregorian(year, month, day)
                // Local 9:00 on the picked day — same convention as the Gregorian picker path.
                val millis = Calendar.getInstance().apply {
                    clear(); set(gy, gm - 1, gd, 9, 0, 0)
                }.timeInMillis
                onConfirm(millis)
            }) { Text(when (com.example.ui.i18n.LocalStrings.current.languageCode) { "fa" -> "تأیید"; "de" -> "OK"; else -> "OK" }) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(when (com.example.ui.i18n.LocalStrings.current.languageCode) { "fa" -> "لغو"; "de" -> "Abbrechen"; else -> "Cancel" }) } },
    )
}
