package com.example.notifications

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * When the user grants (or the system restores) the "exact alarm" permission, Android broadcasts
 * ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED. Any alarms we'd downgraded to inexact while the
 * permission was missing should be re-armed exactly — so we reschedule the daily reminder here.
 */
class ExactAlarmPermissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (intent.action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) return
        val sp = context.getSharedPreferences("medreview_settings", Context.MODE_PRIVATE)
        if (sp.getBoolean("daily_reminder", true)) {
            NotificationScheduler.scheduleDailyReminder(context.applicationContext)
        }
    }
}
