package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
    var year by remember { mutableIntStateOf(init.first) }
    var month by remember { mutableIntStateOf(init.second) }
    var day by remember { mutableIntStateOf(init.third) }

    // Keep the selected day valid when navigating into a shorter month (e.g. 31 فروردین → اسفند).
    fun clampDay() { day = day.coerceAtMost(PersianDate.jalaliMonthLength(year, month)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "${PersianDate.faDigits(day)} ${PersianDate.monthName(month)} ${PersianDate.faDigits(year)}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column {
                // Year stepper.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { year--; clampDay() }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "سال قبل")
                    }
                    Text(PersianDate.faDigits(year), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { year++; clampDay() }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "سال بعد")
                    }
                }
                // Month stepper.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { if (month == 1) { month = 12; year-- } else month--; clampDay() }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "ماه قبل")
                    }
                    Text(PersianDate.monthName(month), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { if (month == 12) { month = 1; year++ } else month++; clampDay() }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "ماه بعد")
                    }
                }
                // Weekday header, Saturday-first.
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    listOf("ش", "ی", "د", "س", "چ", "پ", "ج").forEach { d ->
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
                                            PersianDate.faDigits(d),
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
            }) { Text("تأیید") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("لغو") } },
    )
}
