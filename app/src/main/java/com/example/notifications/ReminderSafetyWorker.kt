package com.example.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.util.Calendar

/**
 * Third reliability layer, after exact alarms + boot catch-up. WorkManager survives many OEM battery
 * managers that silently kill AlarmManager. Every ~6h it checks: if today's reminder time has passed,
 * something is due, and no reminder has shown today, it fires one.
 *
 * Dedup is via `last_notif_shown_at` (written whenever a reminder is shown), so this never
 * double-notifies alongside the exact alarm — whichever layer fires first suppresses the others today.
 */
class ReminderSafetyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val sp = ctx.getSharedPreferences("medreview_settings", Context.MODE_PRIVATE)
        if (!sp.getBoolean("daily_reminder", true)) return Result.success()

        // CHAIN REPAIR, unconditionally: OEM battery managers can wipe AlarmManager alarms without a
        // reboot, and a wiped chain can't re-arm itself. WorkManager usually survives those killers,
        // so every sweep re-arms both daily alarms — the safety net doesn't just show a late
        // notification, it puts the whole reminder system back on its feet.
        runCatching { NotificationScheduler.scheduleDailyReminder(ctx) }

        val hour = sp.getInt("reminder_hour", 20)
        val minute = sp.getInt("reminder_minute", 0)
        val now = Calendar.getInstance()
        if (now.get(Calendar.HOUR_OF_DAY) !in 8..21) return Result.success()

        // "Something should have shown by now" = the EARLIER of the two daily slots (the secondary
        // 10:00/18:00 slot can precede the user's set time), so a dead morning alarm is caught the
        // same day instead of after the evening slot.
        val secHour = NotificationScheduler.secondaryReminderHour(hour)
        val primaryToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val secondaryToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, secHour); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val reminderToday = if (secondaryToday.before(primaryToday)) secondaryToday else primaryToday
        if (now.before(reminderToday)) return Result.success()

        val startOfToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        if (sp.getLong("last_notif_shown_at", 0L) >= startOfToday) return Result.success() // already shown

        val app = ctx as? com.example.MedReviewApplication ?: return Result.success()
        val endOfToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
        }.timeInMillis
        if (app.database.studyUnitDao().getDueCount(endOfToday) > 0) {
            NotificationScheduler.showReviewNotification(ctx)
        }
        return Result.success()
    }
}
