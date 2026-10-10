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
 * - ACTION_FIRE: at a chosen reminder time, show the reminder (it sounds once) and one silent notification per topic of
 *   today's share ([TopicNotifications]); at a ~3h repeat of the chain, only keep what is showing in step, silently.
 *   Then re-arm the next alarm (the repeat cycle in [NotificationScheduler]).
 * - [TopicNotifications.ACTION_HIDE]: a topic's notification was swiped away; it stays away until the next chosen time.
 * - ACTION_SNOOZE ("This evening" / "Tomorrow"): dismiss, then re-remind at 18:00 today (when snoozed
 *   before 17:00) or at the reminder time tomorrow, without changing any topic's schedule.
 * - ACTION_TEST: always show, so the pipeline can be verified.
 */
class ReviewReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Read first: how late the alarm came is measured from the moment it reached the app (ReminderTelemetry).
        val firedAt = System.currentTimeMillis()
        // OFF means OFF: an alarm armed before the user disabled reminders can still fire once —
        // swallow it here (and disarm) instead of showing a reminder the user opted out of.
        // ACTION_TEST is exempt: the user explicitly tapped "send a test reminder".
        val sp = context.getSharedPreferences("medreview_settings", Context.MODE_PRIVATE)
        if (!sp.getBoolean("daily_reminder", true) && intent.action != NotificationScheduler.ACTION_TEST &&
            intent.action != TopicNotifications.ACTION_HIDE
        ) {
            NotificationScheduler.remindersOff(context)
            return
        }
        // A dedicated snooze fire consumes the persisted suppression state before normal handling,
        // so the next daily chain can be planned from the actual current time.
        if (intent.action == NotificationScheduler.ACTION_SNOOZE_FIRE) {
            NotificationScheduler.clearSnooze(context)
        }
        when (intent.action) {
            NotificationScheduler.ACTION_TEST -> {
                // Off the main thread: showReviewNotification now reads the DB to build a rich reminder.
                // markShown=false: a pipeline test must not suppress today's real safety-net sweep.
                val pending = goAsync()
                val appContext = context.applicationContext
                Thread {
                    try {
                        NotificationScheduler.showReviewNotification(appContext, markShown = false, source = "test")
                        // A test shows what a reminder looks like: the topics of today's share as well.
                        runBlocking { TopicNotifications.postAll(appContext) }
                    } catch (t: Throwable) {
                        android.util.Log.w("Yadora", "test reminder failed", t)
                    } finally {
                        // Logged too (slot=test), so a test reminder also shows the delivery log works on this phone.
                        ReminderTelemetry.log(appContext, intent, firedAt, outcome = "test")
                        pending.finish()
                    }
                }.start()
            }
            NotificationScheduler.ACTION_DISMISS -> {
                // "Dismiss" (alarm mode): silence the ringing screen + clear the notification.
                // No schedule change, no snooze — the daily chain stays armed for the next slot. The silent topic
                // notifications stay: they are the list to work from, not the alarm.
                AlarmRingActivity.dismissActive()
                NotificationManagerCompat.from(context).cancel(NotificationScheduler.NOTIFICATION_ID)
            }
            TopicNotifications.ACTION_HIDE -> {
                // Swiped away: not a review and not a deferral. It comes back at the next chosen reminder time if it is still
                // in today's share; until then a sync leaves it away, and an empty list takes its summary away.
                val unitId = intent.getLongExtra(TopicNotifications.EXTRA_UNIT_ID, -1L)
                if (unitId > 0) {
                    TopicNotifications.hide(context, unitId)
                    val pending = goAsync()
                    val appContext = context.applicationContext
                    Thread {
                        try {
                            runBlocking {
                                TopicNotifications.sync(appContext)
                                (appContext as? com.example.MedReviewApplication)?.database?.eventLogDao()?.insert(
                                    com.example.data.local.entity.EventLogEntity(type = "TOPIC_NOTIFICATION_HIDDEN", unitId = unitId)
                                )
                            }
                        } catch (t: Throwable) {
                            android.util.Log.w("Yadora", "background work failed", t)
                        } finally {
                            pending.finish()
                        }
                    }.start()
                }
            }
            NotificationScheduler.ACTION_SNOOZE -> {
                // Re-remind later WITHOUT changing any topic's schedule. The topics go too, and come back with it.
                NotificationManagerCompat.from(context).cancel(NotificationScheduler.NOTIFICATION_ID)
                TopicNotifications.cancelAll(context)
                NotificationScheduler.scheduleSnooze(
                    context,
                    labelSaidEvening = if (intent.hasExtra(NotificationScheduler.EXTRA_SNOOZE_EVENING))
                        intent.getBooleanExtra(NotificationScheduler.EXTRA_SNOOZE_EVENING, true) else null,
                )
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
                    } catch (t: Throwable) {
                        // Best-effort background work: an exception here would reach the thread's uncaught
                        // handler, which chains to the app's global handler and takes the whole app down —
                        // over what is only a reminder refresh. Swallow and log instead.
                        android.util.Log.w("Yadora", "background work failed", t)
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
                        // Only first ratings are left today: the deferred reviews' notifications go, theirs stay.
                        runBlocking { TopicNotifications.sync(appContext) }
                        com.example.widget.DueWidgetProvider.updateAll(appContext)
                    } catch (t: Throwable) {
                        // Best-effort background work: an exception here would reach the thread's uncaught
                        // handler, which chains to the app's global handler and takes the whole app down —
                        // over what is only a reminder refresh. Swallow and log instead.
                        android.util.Log.w("Yadora", "background work failed", t)
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
                        var outcome = "error"
                        var due: Int? = null
                        try {
                            if (NotificationScheduler.shownJustBefore(appContext)) {
                                // The reminder that is already showing, posted moments ago by another path (a
                                // clock set forward fires the catch-up and this alarm together): keep the chain,
                                // do not post and alert a second time.
                                outcome = "just_shown"
                                NotificationScheduler.scheduleDailyReminder(appContext)
                            } else if (!NotificationScheduler.isChosenSlot(appContext, intent)) {
                                // A ~3h repeat of the chain: silent (the owner, 2026-10-09: sound and vibration at most
                                // once per chosen reminder time). It keeps what is showing in step with today's share,
                                // brings back nothing the learner put away, and keeps the chain going while topics are due.
                                val count = dueCountToday(appContext)
                                due = count
                                runBlocking { TopicNotifications.sync(appContext) }
                                NotificationScheduler.refreshIfShowing(appContext)
                                if (count > 0) {
                                    outcome = "repeat"
                                    NotificationScheduler.scheduleDailyReminder(appContext)
                                } else {
                                    outcome = "nothing_due"
                                    NotificationScheduler.scheduleNextDayReminder(appContext)
                                }
                            } else {
                                val count = dueCountToday(appContext)
                                due = count
                                if (count > 0) {
                                    val source = if (intent.action == NotificationScheduler.ACTION_SNOOZE_FIRE) "snooze" else "alarm"
                                    val posted = NotificationScheduler.showReviewNotification(appContext, source = source)
                                    if (posted) {
                                        // Still due: the topics of today's share, each its own silent notification, and
                                        // the chain's next alarm.
                                        outcome = "posted"
                                        runCatching { runBlocking { TopicNotifications.postAll(appContext) } }
                                        NotificationScheduler.scheduleDailyReminder(appContext)
                                    } else {
                                        // Due count changed between the count and the richer fetch, or the OS
                                        // cannot post. Do not manufacture a zero-due reminder or duplicate chain.
                                        outcome = "not_posted"
                                        NotificationScheduler.scheduleNextDayReminder(appContext)
                                    }
                                } else {
                                    // Caught up: stop waking every ~3h; arm only tomorrow's reminders.
                                    outcome = "nothing_due"
                                    NotificationScheduler.scheduleNextDayReminder(appContext)
                                }
                            }
                        } catch (t: Throwable) {
                            // The chain is self-perpetuating: each fire arms the next. If ANYTHING above
                            // throws (DB hiccup, notification failure), the re-arm must still happen —
                            // a broken chain means silent days until the next boot or app open.
                            runCatching { NotificationScheduler.scheduleDailyReminder(appContext) }
                        }
                        // After the re-arm, so the bookkeeping can never cost the chain its next alarm.
                        ReminderTelemetry.log(appContext, intent, firedAt, outcome, due)
                    } catch (t: Throwable) {
                        // Best-effort background work: an exception here would reach the thread's uncaught
                        // handler, which chains to the app's global handler and takes the whole app down —
                        // over what is only a reminder refresh. Swallow and log instead.
                        android.util.Log.w("Yadora", "background work failed", t)
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
        }
    }

    /**
     * How many topics today's plan still offers (DailyPlan): every first rating, plus the reviews left
     * under the daily limit. Not the raw due count — once the day's limit is done the reminder stops
     * nagging about reviews Today itself says are held for tomorrow.
     */
    private fun dueCountToday(context: Context): Int {
        val app = context.applicationContext as? com.example.MedReviewApplication ?: return 0
        return runBlocking { app.todayPlan().size }
    }

    private fun endOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 23)
        set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59)
        set(Calendar.MILLISECOND, 999)
    }.timeInMillis
}
