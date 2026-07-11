package com.example.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar

/**
 * Re-arms the daily reminder after the device reboots, the app is updated, or the timezone changes —
 * AlarmManager alarms are cleared by all of these events, so without this the reminder would silently
 * stop. (Note: this only runs once the user has opened the app at least once after install.)
 *
 * It also CATCHES UP: if the phone was off past today's reminder time and reviews are still due, it
 * notifies immediately instead of waiting for the next slot — so a missed reminder isn't lost.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // This receiver is exported (BOOT_COMPLETED requires it) — validate the action so a foreign
        // app can't poke it into running DB work / firing a catch-up notification at will.
        val allowed = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
        )
        if (intent.action !in allowed) return

        val sp = context.getSharedPreferences("medreview_settings", Context.MODE_PRIVATE)
        if (!sp.getBoolean("daily_reminder", true)) return
        NotificationScheduler.createNotificationChannel(context)
        NotificationScheduler.scheduleDailyReminder(context)

        val pending = goAsync()
        val appContext = context.applicationContext
        Thread {
            try {
                val app = appContext as? com.example.MedReviewApplication ?: return@Thread
                val hour = sp.getInt("reminder_hour", 20)
                val minute = sp.getInt("reminder_minute", 0)
                val now = Calendar.getInstance()
                val reminderToday = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                val endOfToday = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
                    set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
                }.timeInMillis
                val due = kotlinx.coroutines.runBlocking { app.database.studyUnitDao().getDueCount(endOfToday) }
                // Only fire the catch-up during waking hours and only if today's time already passed.
                if (due > 0 && now.after(reminderToday) && now.get(Calendar.HOUR_OF_DAY) in 8..21) {
                    NotificationScheduler.showReviewNotification(appContext)
                }
                com.example.widget.DueWidgetProvider.updateAll(appContext) // refresh count after reboot
            } finally {
                pending.finish()
            }
        }.start()
    }
}
