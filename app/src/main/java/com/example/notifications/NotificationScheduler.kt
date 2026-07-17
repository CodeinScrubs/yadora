package com.example.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.Calendar

/**
 * Local, offline reminder scheduling on **AlarmManager** (not WorkManager), because reminders are
 * time-critical and a missed reminder defeats the whole app.
 *
 * Persistence model (the product requirement): the reminder NAGS until the user acts.
 * - It first fires at the user's set time, then **re-fires every ~3h through the waking window**
 *   (08:00–22:00) for as long as topics are still due — so missing it (or a silent phone) doesn't
 *   lose the nudge. Past the waking window it resumes at the next day's set time.
 * - Overdue topics carry over day to day automatically (their due date stays in the past), so the
 *   nag continues on later days too.
 * - It goes quiet only when nothing is due — i.e. the user reviewed everything OR **procrastinated**
 *   it (which moves the topic to tomorrow). [ReviewReminderReceiver] does the per-fire work.
 *
 * Notification actions: "Review now" (opens the app) and "Not today" (procrastinates all of today's
 * due topics to tomorrow). Per-topic procrastinate lives in the review screen.
 */
object NotificationScheduler {

    const val CHANNEL_ID = "medreview_daily_reminder_v2"
    const val ALARM_CHANNEL_ID = "medreview_alarm_v1"
    const val NOTIFICATION_ID = 1

    const val ACTION_FIRE = "com.example.notifications.ACTION_FIRE"
    const val ACTION_SNOOZE = "com.example.notifications.ACTION_SNOOZE"
    const val ACTION_TEST = "com.example.notifications.ACTION_TEST"
    const val ACTION_NOT_TODAY = "com.example.notifications.ACTION_NOT_TODAY"
    const val ACTION_DISMISS = "com.example.notifications.ACTION_DISMISS"

    private const val REQ_DAILY = 1001
    private const val REQ_OPEN = 1004
    private const val REQ_TEST = 1005
    private const val REQ_SNOOZE_BTN = 1006
    private const val REQ_SNOOZE_FIRE = 1007
    private const val REQ_FULLSCREEN = 1008
    private const val REQ_NOT_TODAY = 1009
    private const val REQ_DAILY_2 = 1010
    private const val REQ_DISMISS = 1011

    private const val REPEAT_INTERVAL_MS = 3L * 60 * 60 * 1000 // re-nudge every ~3h
    private const val WAKING_START_HOUR = 8
    private const val WAKING_END_HOUR = 22

    /**
     * True when the CURRENT reminder channel can actually show notifications. Permission alone isn't
     * enough — a user can silence the channel (or all app notifications) in system settings, and the
     * Reminder Health screen would otherwise show green while nothing can appear.
     */
    fun isReminderChannelEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = nm.getNotificationChannel(channelId(context)) ?: return true // not created yet
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /**
     * Channel id encodes the current sound/vibration prefs. Android notification channels are
     * immutable after creation, so re-calling createNotificationChannel with one fixed id does NOT
     * apply a toggled setting. Encoding the choice in the id means a change actually takes effect.
     */
    private fun channelId(context: Context): String {
        val sp = context.getSharedPreferences("medreview_settings", Context.MODE_PRIVATE)
        val s = if (sp.getBoolean("sound_enabled", true)) "s1" else "s0"
        val v = if (sp.getBoolean("vibration_enabled", true)) "v1" else "v0"
        return "medreview_reminder_${s}_${v}"
    }

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val sp = context.getSharedPreferences("medreview_settings", Context.MODE_PRIVATE)
            val soundEnabled = sp.getBoolean("sound_enabled", true)
            val vibrationEnabled = sp.getBoolean("vibration_enabled", true)
            val importance =
                if (soundEnabled || vibrationEnabled) NotificationManager.IMPORTANCE_DEFAULT else NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(channelId(context), "Daily Reminders", importance).apply {
                description = "Reminders for due study units"
                if (!soundEnabled) setSound(null, null)
                enableVibration(vibrationEnabled)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
            // Prune stale reminder channels left over from previous sound/vibration combos, so the
            // app's channel list in system settings doesn't accumulate one dead entry per toggle.
            val current = channelId(context)
            nm.notificationChannels
                .filter { it.id.startsWith("medreview_reminder") && it.id != current }
                .forEach { nm.deleteNotificationChannel(it.id) }
        }
    }

    /** High-importance channel for the optional "ring like an alarm clock" full-screen reminder. */
    fun createAlarmChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(ALARM_CHANNEL_ID, "Alarm Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Rings like an alarm clock when reviews are due"
                // The full-screen AlarmRingActivity owns the looping alarm tone; keep the channel itself
                // silent so the alarm sound doesn't play twice (channel + activity).
                setSound(null, null)
                enableVibration(true)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    /** True if the OS will honor exact alarms for this app (always true below Android 12). */
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    /**
     * The guaranteed SECOND daily slot: opposite half of the day from the user's chosen time, so the
     * two fires are well separated. Evening people (>= 14:00) get a 10:00 morning nudge; morning
     * people get an 18:00 evening one. Public + pure so it's unit-testable.
     */
    fun secondaryReminderHour(primaryHour: Int): Int = if (primaryHour >= 14) 10 else 18

    /**
     * Arm the next reminder "nudge" (set time, or the next ~3h repeat through the day) PLUS the
     * guaranteed second daily slot — two independent exact alarms, so one missed fire never means a
     * silent day.
     */
    fun scheduleDailyReminder(context: Context) {
        // Self-protecting: many flows (add topic, review, redistribute, restore) re-arm the reminder
        // unconditionally. OFF must mean OFF — if the user disabled reminders, every such call becomes
        // a cancel instead, so no code path can silently re-enable them.
        val sp = context.getSharedPreferences("medreview_settings", Context.MODE_PRIVATE)
        if (!sp.getBoolean("daily_reminder", true)) {
            cancelReminder(context)
            return
        }
        armAlarm(context, nextNudgeTime(context), REQ_DAILY, ACTION_FIRE)
        armAlarm(context, nextSecondarySlotTime(context), REQ_DAILY_2, ACTION_FIRE)
    }

    /** Next occurrence (today if still ahead, else tomorrow) of the second daily slot. */
    private fun nextSecondarySlotTime(context: Context): Long {
        val sp = context.getSharedPreferences("medreview_settings", Context.MODE_PRIVATE)
        val secHour = secondaryReminderHour(sp.getInt("reminder_hour", 20))
        val now = Calendar.getInstance()
        val slot = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, secHour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (!now.before(slot)) slot.add(Calendar.DAY_OF_YEAR, 1)
        return slot.timeInMillis
    }

    /** Arm only tomorrow's reminders (both slots) — used when today is cleared, to stop the ~3h nag loop. */
    fun scheduleNextDayReminder(context: Context) {
        val sp = context.getSharedPreferences("medreview_settings", Context.MODE_PRIVATE)
        if (!sp.getBoolean("daily_reminder", true)) { cancelReminder(context); return } // OFF means OFF
        val hour = sp.getInt("reminder_hour", 20)
        val minute = sp.getInt("reminder_minute", 0)
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        armAlarm(context, next, REQ_DAILY, ACTION_FIRE)
        // This API promises TOMORROW only: nextSecondarySlotTime() could still return TODAY's slot
        // (harmless — the fire checks the due count — but a pointless wakeup). Arm tomorrow explicitly.
        val nextSecondary = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, secondaryReminderHour(hour))
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        armAlarm(context, nextSecondary, REQ_DAILY_2, ACTION_FIRE)
    }

    /** Arm a one-off TEST reminder that always fires (ignores due count). For verifying the pipeline. */
    fun scheduleTest(context: Context, delayMillis: Long = 60_000L) {
        createNotificationChannel(context)
        armAlarm(context, System.currentTimeMillis() + delayMillis, REQ_TEST, ACTION_TEST)
    }

    fun cancelReminder(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(firePendingIntent(context, REQ_DAILY, ACTION_FIRE))
        am.cancel(firePendingIntent(context, REQ_DAILY_2, ACTION_FIRE))
        am.cancel(firePendingIntent(context, REQ_SNOOZE_FIRE, ACTION_FIRE))
    }

    /**
     * The snooze button is a human choice, not a vague delay: before late afternoon it means
     * "this evening" (18:00 today); after that it means "tomorrow" (the user's reminder time).
     * Returns the target millis; [snoozeIsEvening] tells the UI which label to show.
     */
    fun snoozeIsEvening(now: Calendar = Calendar.getInstance()): Boolean = now.get(Calendar.HOUR_OF_DAY) < 17

    private fun snoozeTargetMillis(context: Context): Long {
        val now = Calendar.getInstance()
        return if (snoozeIsEvening(now)) {
            Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 18); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        } else {
            val sp = context.getSharedPreferences("medreview_settings", Context.MODE_PRIVATE)
            Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, sp.getInt("reminder_hour", 20))
                set(Calendar.MINUTE, sp.getInt("reminder_minute", 0))
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis
        }
    }

    /** Re-show the reminder at the snooze target WITHOUT changing any topic's due date (a true snooze). */
    fun scheduleSnooze(context: Context) {
        // Clamp into waking hours so an edge case never rings in the middle of the night.
        armAlarm(context, clampToWakingWindow(snoozeTargetMillis(context)), REQ_SNOOZE_FIRE, ACTION_FIRE)
    }

    /** Push a trigger time into the 08:00–22:00 waking window (next 08:00 if it lands at night). */
    private fun clampToWakingWindow(timeMillis: Long): Long {
        val hour = Calendar.getInstance().apply { timeInMillis = timeMillis }.get(Calendar.HOUR_OF_DAY)
        if (hour in WAKING_START_HOUR until WAKING_END_HOUR) return timeMillis
        return Calendar.getInstance().apply {
            timeInMillis = timeMillis
            if (hour >= WAKING_END_HOUR) add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, WAKING_START_HOUR)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun armAlarm(context: Context, triggerAtMillis: Long, requestCode: Int, action: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = firePendingIntent(context, requestCode, action)
        try {
            if (canScheduleExact(context)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            } else {
                // Exact-alarm permission not granted: still fire, just within a looser window.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
    }

    private fun firePendingIntent(context: Context, requestCode: Int, action: String): PendingIntent {
        val intent = Intent(context, ReviewReminderReceiver::class.java).apply { this.action = action }
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Next fire time: today's set time if still ahead; otherwise the next ~3h repeat while it's
     * daytime; once past the waking window, tomorrow's set time.
     */
    private fun nextNudgeTime(context: Context): Long {
        val sp = context.getSharedPreferences("medreview_settings", Context.MODE_PRIVATE)
        val hour = sp.getInt("reminder_hour", 20)
        val minute = sp.getInt("reminder_minute", 0)
        val now = Calendar.getInstance()
        val setToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (now.before(setToday)) return setToday.timeInMillis

        val candidate = now.timeInMillis + REPEAT_INTERVAL_MS
        val candHour = Calendar.getInstance().apply { timeInMillis = candidate }.get(Calendar.HOUR_OF_DAY)
        if (candHour in WAKING_START_HOUR until WAKING_END_HOUR) return candidate

        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
    }

    @SuppressLint("MissingPermission")
    fun showReviewNotification(context: Context, markShown: Boolean = true) {
        createNotificationChannel(context)
        val sp = context.getSharedPreferences("medreview_settings", Context.MODE_PRIVATE)
        val soundEnabled = sp.getBoolean("sound_enabled", true)
        val vibrationEnabled = sp.getBoolean("vibration_enabled", true)
        val isFa = (sp.getString("app_language", "en") ?: "en") == "fa"
        fun n(v: Int) = if (isFa) com.example.ui.i18n.PersianDate.faDigits(v) else v.toString()

        // Pull the actual due topics so the reminder can say what's waiting (count, high-yield, titles).
        val due = fetchDueSummaries(context)
        val count = due.size
        val hy = due.count { it.second }

        val isDe = (sp.getString("app_language", "en") ?: "en") == "de"
        val title = when {
            // A test fire with nothing due should say what it is, not cry wolf.
            count <= 0 && !markShown -> if (isFa) "یادآور آزمایشی — کار می‌کند." else if (isDe) "Test-Erinnerung — funktioniert." else "Test reminder — it works."
            count <= 0 -> if (isFa) "زمان مرور فرا رسیده!" else if (isDe) "Zeit zum Wiederholen!" else "Time to review!"
            isFa -> "${n(count)} مبحث برای مرور"
            isDe -> if (count == 1) "1 Thema zur Wiederholung" else "$count Themen zur Wiederholung"
            else -> "$count ${if (count == 1) "topic" else "topics"} to review"
        }
        val text = when {
            hy > 0 -> if (isFa) "${n(hy)} مبحث مهم" else if (isDe) "$hy wichtig" else "$hy important"
            count > 0 -> if (isFa) "برای مرور آماده‌اند." else if (isDe) "Bereit, wenn du es bist." else "Ready when you are."
            else -> if (isFa) "مباحثی برای مرور آماده‌اند." else if (isDe) "Themen sind bereit zur Wiederholung." else "You have study topics ready to review."
        }
        val reviewNowLabel = if (isFa) "مرور" else if (isDe) "Jetzt wiederholen" else "Review now"
        // Human snooze: the label says WHEN it will come back (evening before ~17:00, else tomorrow).
        val snoozeLabel = if (snoozeIsEvening())
            (if (isFa) "عصر امروز" else if (isDe) "Heute Abend" else "This evening")
        else
            (if (isFa) "فردا" else if (isDe) "Morgen" else "Tomorrow")
        val notTodayLabel = if (isFa) "امروز نه" else if (isDe) "Heute nicht" else "Not today"

        val openIntent = Intent(context, com.example.MainActivity::class.java).apply {
            // SINGLE_TOP (not CLEAR_TASK): if the app is already open, deliver via onNewIntent — which
            // MainActivity implements for open_review — instead of destroying in-progress UI state.
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("open_review", true)
        }
        val openPi = PendingIntent.getActivity(
            context, REQ_OPEN, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val snoozeIntent = Intent(context, ReviewReminderReceiver::class.java).apply { action = ACTION_SNOOZE }
        val snoozePi = PendingIntent.getBroadcast(
            context, REQ_SNOOZE_BTN, snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notTodayIntent = Intent(context, ReviewReminderReceiver::class.java).apply { action = ACTION_NOT_TODAY }
        val notTodayPi = PendingIntent.getBroadcast(
            context, REQ_NOT_TODAY, notTodayIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val dismissIntent = Intent(context, ReviewReminderReceiver::class.java).apply { action = ACTION_DISMISS }
        val dismissPi = PendingIntent.getBroadcast(
            context, REQ_DISMISS, dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val alarmMode = sp.getBoolean("alarm_enabled", false)
        val channel = if (alarmMode) { createAlarmChannel(context); ALARM_CHANNEL_ID } else channelId(context)

        // Professional presentation: the sprout brand glyph as the (system-tinted) status-bar icon,
        // sage as the accent color throughout the shade, and the full-color app artwork as the
        // large icon so the expanded notification is unmistakably Yadora.
        val largeIcon = runCatching {
            android.graphics.BitmapFactory.decodeResource(context.resources, com.example.R.drawable.logo_image)
        }.getOrNull()
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(com.example.R.drawable.ic_notification)
            .setColor(0xFF4E7A5A.toInt()) // brand sage — tints the glyph, app name, and action text
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(if (alarmMode || soundEnabled || vibrationEnabled) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .setShowWhen(true)
            .addAction(0, reviewNowLabel, openPi)
        // Android shows at most 3 actions. In alarm mode the ringing MUST be dismissible from the
        // notification shade (the ring screen can be backgrounded with Home) — Dismiss replaces
        // Snooze there; in normal mode Snooze stays and swiping the notification away dismisses it.
        if (alarmMode) {
            val dismissLabel = if (isFa) "قطع هشدار" else if (isDe) "Stopp" else "Dismiss"
            builder.addAction(0, dismissLabel, dismissPi)
        } else {
            builder.addAction(0, snoozeLabel, snoozePi)
        }
        if (largeIcon != null) builder.setLargeIcon(largeIcon)
        if (count > 0) {
            // Expandable list of what's waiting + the "Not today" action (procrastinate everything to
            // tomorrow) — the guilt-free escape hatch, now wired to procrastinateAllDue in the receiver.
            val body = due.take(5).joinToString("\n") { "• ${it.first}" } + if (count > 5) "\n…" else ""
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
            builder.addAction(0, notTodayLabel, notTodayPi)
            // Lock-screen privacy: what the user studies is their business. The public (locked) version
            // shows only the count; topic titles appear after unlock.
            val publicVersion = NotificationCompat.Builder(context, channel)
                .setSmallIcon(com.example.R.drawable.ic_notification)
                .setColor(0xFF4E7A5A.toInt())
                .setContentTitle(title)
                .setContentText(if (isFa) "برای دیدن مباحث، قفل را باز کن." else if (isDe) "Entsperren, um die Themen zu sehen." else "Unlock to see your topics.")
                .build()
            builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(publicVersion)
        }
        if (alarmMode) {
            // Full-screen "alarm clock": fires AlarmRingActivity over the lock screen and rings.
            val fsIntent = Intent(context, AlarmRingActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            val fsPi = PendingIntent.getActivity(
                context, REQ_FULLSCREEN, fsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setFullScreenIntent(fsPi, true)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
            // NOTE: no setOngoing — AlarmRingActivity + MainActivity + Snooze all cancel NOTIFICATION_ID,
            // and an ongoing notification only adds "can't be swiped away" failure modes.
        }
        if (!alarmMode && !soundEnabled) builder.setSound(null)
        if (!alarmMode && !vibrationEnabled) builder.setVibrate(longArrayOf(0L))

        val canPost = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (canPost) {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
            // Dedup marker: the WorkManager safety net skips today once this is set. Test notifications
            // pass markShown=false so trying the pipeline never suppresses that evening's real safety net.
            if (markShown) {
                sp.edit().putLong("last_notif_shown_at", System.currentTimeMillis()).apply()
                // Adherence + reliability research data: WHEN each real reminder fired and how many
                // topics were waiting. Joined with STUDY_ACTION timestamps this answers "did the
                // reminder lead to a review?" and "did reminders fire at all on this device?" —
                // the two questions a field-test period must answer. Best-effort by design.
                runCatching {
                    val app = context.applicationContext as? com.example.MedReviewApplication
                    if (app != null) kotlinx.coroutines.runBlocking {
                        app.database.eventLogDao().insert(
                            com.example.data.local.entity.EventLogEntity(
                                type = "NOTIF_SHOWN",
                                detail = "due=$count${if (alarmMode) " alarm" else ""}"
                            )
                        )
                    }
                }
            }
        }
    }

    /** (title, highYield) for every unit due by end of today, high-yield first. Safe on any thread. */
    private fun fetchDueSummaries(context: Context): List<Pair<String, Boolean>> {
        val app = context.applicationContext as? com.example.MedReviewApplication ?: return emptyList()
        val endOfToday = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 23); set(java.util.Calendar.MINUTE, 59)
            set(java.util.Calendar.SECOND, 59); set(java.util.Calendar.MILLISECOND, 999)
        }.timeInMillis
        return runCatching {
            kotlinx.coroutines.runBlocking {
                app.database.studyUnitDao().getDueUnitsList(endOfToday).map { it.title to it.highYield }
            }
        }.getOrDefault(emptyList())
    }
}
