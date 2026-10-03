package com.example.notifications

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager

/**
 * What the pilot needs to see whether reminders ARRIVE, and on time, on each participant's phone over real days: the
 * one thing an afternoon of device testing cannot show (Doze, and Samsung or Xiaomi battery managers over weeks).
 *
 * Every reminder alarm carries the time it was armed for ([NotificationScheduler.EXTRA_SCHEDULED_AT]). When it fires,
 * the receiver logs one [EVENT] with how late it came, how it was armed and what the phone was doing; the research
 * export ships those events with [healthSnapshot], and tools/pilot/analyze.py turns them into a table per phone (rule
 * D11 in docs/PILOT.md). Before this, a reminder that never came left no trace at all: NOTIF_SHOWN is written only when
 * something is posted, and only a participant who pressed "report a missed reminder" left a snapshot. Content-free:
 * times, flags and today's due count, nothing about any topic.
 */
object ReminderTelemetry {

    const val EVENT = "REMINDER_FIRED"

    /**
     * The event's detail, space-separated key=value pairs like NOTIF_SHOWN's: which alarm (primary, secondary,
     * snooze, test), the time it was armed for, how many seconds after that it fired, whether it was armed as an
     * exact alarm, whether the phone was in Doze or battery saver, the app's standby bucket (10 active ... 45
     * restricted), what the receiver did, and today's due count when it counted. "?" = not known (an alarm armed by a
     * build without these extras).
     */
    fun detail(
        slot: String?,
        scheduledAt: Long?,
        firedAt: Long,
        exact: Boolean?,
        deviceIdle: Boolean?,
        powerSave: Boolean?,
        standbyBucket: Int?,
        outcome: String,
        due: Int? = null,
    ): String = buildList {
        add("slot=${slot ?: "?"}")
        add("scheduled=${scheduledAt ?: "?"}")
        add("late_s=${scheduledAt?.let { Math.floorDiv(firedAt - it, 1000L) } ?: "?"}")
        add("exact=${flag(exact)}")
        add("idle=${flag(deviceIdle)}")
        add("saver=${flag(powerSave)}")
        add("bucket=${standbyBucket ?: "?"}")
        add("outcome=$outcome")
        if (due != null) add("due=$due")
    }.joinToString(" ")

    private fun flag(b: Boolean?) = when (b) {
        true -> "1"
        false -> "0"
        null -> "?"
    }

    /**
     * Logs one fire of the alarm [intent] carried. Best effort and off the main thread (the receiver's own worker):
     * a reminder must never fail over its own bookkeeping, so this runs after the next alarm is armed and swallows
     * every error.
     */
    fun log(context: Context, intent: Intent, firedAt: Long, outcome: String, due: Int? = null) {
        runCatching {
            val app = context.applicationContext as? com.example.MedReviewApplication ?: return
            val scheduledAt = intent.getLongExtra(NotificationScheduler.EXTRA_SCHEDULED_AT, -1L).takeIf { it > 0L }
            val exact = if (intent.hasExtra(NotificationScheduler.EXTRA_EXACT)) {
                intent.getBooleanExtra(NotificationScheduler.EXTRA_EXACT, false)
            } else {
                null
            }
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val text = detail(
                slot = intent.getStringExtra(NotificationScheduler.EXTRA_SLOT),
                scheduledAt = scheduledAt,
                firedAt = firedAt,
                exact = exact,
                deviceIdle = runCatching { pm?.isDeviceIdleMode }.getOrNull(),
                powerSave = runCatching { pm?.isPowerSaveMode }.getOrNull(),
                standbyBucket = standbyBucket(context),
                outcome = outcome,
                due = due,
            )
            kotlinx.coroutines.runBlocking {
                app.database.eventLogDao().insert(
                    com.example.data.local.entity.EventLogEntity(type = EVENT, at = firedAt, detail = text)
                )
            }
        }.onFailure { android.util.Log.w("Yadora", "reminder telemetry failed", it) }
    }

    /** The app's standby bucket (Android 9+): in RARE (40) or RESTRICTED (45) the system rations its alarms and jobs. */
    private fun standbyBucket(context: Context): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                (context.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager).appStandbyBucket
            }.getOrNull()
        } else {
            null
        }

    /**
     * Everything that decides whether a reminder can reach the learner, read when the research file is exported: the
     * same checks as Settings' Reminder Health, plus the battery and standby state Android uses to ration alarms.
     * Null = could not be read.
     */
    fun healthSnapshot(context: Context): Map<String, Any?> {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val sp = context.getSharedPreferences("medreview_settings", Context.MODE_PRIVATE)
        return linkedMapOf(
            "notificationsAllowed" to runCatching {
                androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
            }.getOrNull(),
            "reminderChannelOn" to runCatching { NotificationScheduler.isReminderChannelEnabled(context) }.getOrNull(),
            "exactAlarmsAllowed" to runCatching { NotificationScheduler.canScheduleExact(context) }.getOrNull(),
            "fullScreenAllowed" to runCatching {
                if (Build.VERSION.SDK_INT >= 34) {
                    (context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).canUseFullScreenIntent()
                } else {
                    true
                }
            }.getOrNull(),
            "batteryOptimizationIgnored" to runCatching { pm?.isIgnoringBatteryOptimizations(context.packageName) }.getOrNull(),
            "backgroundRestricted" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).isBackgroundRestricted }.getOrNull()
            } else {
                false
            },
            "standbyBucket" to standbyBucket(context),
            "powerSaveMode" to runCatching { pm?.isPowerSaveMode }.getOrNull(),
            "secondaryReminderHour" to NotificationScheduler.secondaryReminderHour(sp.getInt("reminder_hour", 20)),
            "lastShownAt" to NotificationScheduler.transientPrefs(context).getLong(NotificationScheduler.PREF_LAST_SHOWN_AT, 0L),
        )
    }
}
