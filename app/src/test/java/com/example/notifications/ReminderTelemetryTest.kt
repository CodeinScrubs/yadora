package com.example.notifications

import android.app.AlarmManager
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.MedReviewApplication
import com.example.data.AnalyticsExporter
import com.example.ui.i18n.EnglishStrings
import com.example.ui.i18n.LocalStrings
import com.example.ui.settings.SettingsScreen
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The pilot's view of reminder delivery (2026-10-03). Whether reminders arrive, on time, on a friend's phone over weeks
 * (Doze, Samsung and Xiaomi battery managers) is the one thing no afternoon on a test device can show, and before this
 * a reminder that never came left no trace: NOTIF_SHOWN is written only when something is posted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReminderTelemetryTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun `the detail says when it was due, how late it came and what it did`() {
        assertEquals(
            "slot=primary scheduled=1000000 late_s=93 exact=1 idle=0 saver=0 bucket=10 outcome=posted due=12",
            ReminderTelemetry.detail("primary", 1_000_000L, 1_093_500L, exact = true, deviceIdle = false, powerSave = false,
                standbyBucket = 10, outcome = "posted", due = 12),
        )
        // An alarm armed by a build without the extras is unknown, never "on time".
        assertEquals(
            "slot=? scheduled=? late_s=? exact=? idle=? saver=? bucket=? outcome=nothing_due",
            ReminderTelemetry.detail(null, null, 5L, null, null, null, null, outcome = "nothing_due"),
        )
        // Early (a clock set back) reads negative, rounded down, not as on time.
        assertTrue(ReminderTelemetry.detail("secondary", 10_000L, 8_500L, true, true, false, 40, "nothing_due").contains("late_s=-2 "))
    }

    @Test
    fun `every reminder alarm carries the time it was armed for, and its fire is logged`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MedReviewApplication>()
        NotificationScheduler.scheduleDailyReminder(app)
        NotificationScheduler.scheduleTest(app)
        val alarms = shadowOf(app.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        @Suppress("DEPRECATION") // Robolectric 4.17 deprecates the field and offers no getter for the intent
        val armed = alarms.scheduledAlarms.map { it.triggerAtMs to shadowOf(it.operation).savedIntent }
        val bySlot = armed.associate { (_, intent) -> intent.getStringExtra(NotificationScheduler.EXTRA_SLOT) to intent }
        assertEquals(setOf("primary", "secondary", "test"), bySlot.keys)
        for ((at, intent) in armed) {
            assertEquals("each alarm knows when it was due", at, intent.getLongExtra(NotificationScheduler.EXTRA_SCHEDULED_AT, -1L))
            assertTrue("and how it was armed", intent.hasExtra(NotificationScheduler.EXTRA_EXACT))
        }

        app.sendBroadcast(bySlot.getValue("primary"))
        app.sendBroadcast(bySlot.getValue("test"))
        shadowOf(Looper.getMainLooper()).idle()
        val fired = waitForEvents(app, ReminderTelemetry.EVENT, 2).map { it.orEmpty() }
        val primary = fired.single { it.contains("slot=primary") }
        assertTrue("an empty library: nothing was due, and it says so: $primary", primary.contains("outcome=nothing_due due=0"))
        assertTrue("it fired before its time in this test, so it reads early: $primary", Regex("""late_s=-\d+""").containsMatchIn(primary))
        assertTrue("the test reminder is logged too", fired.any { it.contains("slot=test") && it.contains("outcome=test") })

        // The research export carries the events and the phone's reminder health.
        val json = JSONObject(AnalyticsExporter.buildJson(app))
        val events = json.getJSONArray("eventLogs")
        assertEquals(2, (0 until events.length()).count { events.getJSONObject(it).getString("type") == ReminderTelemetry.EVENT })
        val health = json.getJSONObject("reminderHealth")
        for (key in listOf("notificationsAllowed", "reminderChannelOn", "exactAlarmsAllowed", "fullScreenAllowed",
            "batteryOptimizationIgnored", "backgroundRestricted", "standbyBucket", "powerSaveMode", "secondaryReminderHour", "lastShownAt")) {
            assertTrue("reminderHealth has $key", health.has(key))
        }
    }

    /** A day with reminders off is the learner's choice, not a phone that failed to deliver: the switch is logged. */
    @Test
    fun `turning reminders off and on in Settings is logged`() {
        val app = ApplicationProvider.getApplicationContext<MedReviewApplication>()
        compose.setContent {
            MyApplicationTheme(languageCode = "en") {
                CompositionLocalProvider(LocalStrings provides EnglishStrings) { SettingsScreen(onBack = {}) }
            }
        }
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText(EnglishStrings.settings).fetchSemanticsNodes().isNotEmpty() }
        val switch = hasContentDescription(EnglishStrings.dailyReviewReminder) and isToggleable()
        compose.onNode(hasScrollAction()).performScrollToNode(switch)
        compose.onNode(switch).performClick()
        compose.waitForIdle()
        assertEquals(listOf<String?>(null), runBlocking { waitForEvents(app, "REMINDERS_OFF", 1) })
        compose.onNode(switch).performClick()
        compose.waitForIdle()
        assertEquals(1, runBlocking { waitForEvents(app, "REMINDERS_ON", 1) }.size)
    }

    /** The receiver and the switch write from their own threads: wait for them, a few seconds at most. */
    private suspend fun waitForEvents(app: MedReviewApplication, type: String, count: Int): List<String?> {
        repeat(100) {
            val found = app.database.eventLogDao().getAll().filter { it.type == type }
            if (found.size >= count) return found.map { it.detail }
            Thread.sleep(50)
        }
        return app.database.eventLogDao().getAll().filter { it.type == type }.map { it.detail }
    }
}
