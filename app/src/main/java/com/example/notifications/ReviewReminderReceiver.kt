package com.example.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.room.withTransaction
import kotlinx.coroutines.runBlocking
import java.util.Calendar

/**
 * Handles every reminder alarm fire. The DB work runs off the main thread via goAsync().
 *
 * - ACTION_FIRE: show the reminder if something is due today, then re-arm the next nudge (the ~3h
 *   repeat cycle in [NotificationScheduler]). This is what makes the reminder keep nagging.
 * - ACTION_SNOOZE ("Snooze"): dismiss and re-remind ~3h later, without changing any topic's schedule.
 * - ACTION_TEST: always show, so the pipeline can be verified.
 */
class ReviewReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // OFF means OFF: an alarm armed before the user disabled reminders can still fire once —
        // swallow it here (and disarm) instead of showing a reminder the user opted out of.
        // ACTION_TEST is exempt: the user explicitly tapped "send a test reminder".
        val sp = context.getSharedPreferences("medreview_settings", Context.MODE_PRIVATE)
        if (!sp.getBoolean("daily_reminder", true) && intent.action != NotificationScheduler.ACTION_TEST) {
            NotificationScheduler.cancelReminder(context)
            NotificationManagerCompat.from(context).cancel(NotificationScheduler.NOTIFICATION_ID)
            return
        }
        when (intent.action) {
            NotificationScheduler.ACTION_TEST -> {
                // Off the main thread: showReviewNotification now reads the DB to build a rich reminder.
                // markShown=false: a pipeline test must not suppress today's real safety-net sweep.
                val pending = goAsync()
                val appContext = context.applicationContext
                Thread {
                    try { NotificationScheduler.showReviewNotification(appContext, markShown = false) } finally { pending.finish() }
                }.start()
            }
            NotificationScheduler.ACTION_SNOOZE -> {
                // Re-remind later WITHOUT changing any topic's schedule.
                NotificationManagerCompat.from(context).cancel(NotificationScheduler.NOTIFICATION_ID)
                NotificationScheduler.scheduleSnooze(context)
                // Log it: snooze frequency is a key adherence signal in the exported data.
                val pending = goAsync()
                val appContext = context.applicationContext
                Thread {
                    try {
                        (appContext as? com.example.MedReviewApplication)?.let { app ->
                            runBlocking {
                                app.database.eventLogDao().insert(
                                    com.example.data.local.entity.EventLogEntity(type = "SNOOZE")
                                )
                            }
                        }
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
            NotificationScheduler.ACTION_NOT_TODAY -> {
                // "Not today": push everything due to tomorrow morning — the guilt-free escape hatch.
                val pending = goAsync()
                val appContext = context.applicationContext
                Thread {
                    try {
                        val app = appContext as? com.example.MedReviewApplication
                        if (app != null) {
                            val tomorrow8 = Calendar.getInstance().apply {
                                add(Calendar.DAY_OF_YEAR, 1)
                                set(Calendar.HOUR_OF_DAY, 8); set(Calendar.MINUTE, 0)
                                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                            }.timeInMillis
                            runBlocking {
                                // One transaction: the schedule push and its adherence event must land
                                // together — a crash in between would leave a push with no record of it.
                                app.database.withTransaction {
                                    app.database.studyUnitDao()
                                        .procrastinateAllDue(endOfToday(), tomorrow8, System.currentTimeMillis())
                                    app.database.eventLogDao().insert(
                                        com.example.data.local.entity.EventLogEntity(type = "PROCRASTINATE_ALL")
                                    )
                                }
                            }
                        }
                        NotificationManagerCompat.from(appContext).cancel(NotificationScheduler.NOTIFICATION_ID)
                        NotificationScheduler.scheduleNextDayReminder(appContext)
                        com.example.widget.DueWidgetProvider.updateAll(appContext) // count just went to 0
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
            else -> {
                val pending = goAsync()
                val appContext = context.applicationContext
                Thread {
                    try {
                        if (dueCountToday(appContext) > 0) {
                            NotificationScheduler.showReviewNotification(appContext)
                            // Still due: keep nagging through the day (re-arm the next ~3h nudge).
                            NotificationScheduler.scheduleDailyReminder(appContext)
                        } else {
                            // Caught up: stop waking every ~3h; arm only tomorrow's primary reminder.
                            NotificationScheduler.scheduleNextDayReminder(appContext)
                        }
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
        }
    }

    /** Count of units actually due by the END OF TODAY (not the next 24h). */
    private fun dueCountToday(context: Context): Int {
        val app = context.applicationContext as? com.example.MedReviewApplication ?: return 0
        return runBlocking { app.database.studyUnitDao().getDueCount(endOfToday()) }
    }

    private fun endOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 23)
        set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59)
        set(Calendar.MILLISECOND, 999)
    }.timeInMillis
}
