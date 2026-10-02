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
import androidx.core.content.edit
import androidx.core.net.toUri
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

    const val ALARM_CHANNEL_ID = "medreview_alarm_v1"
    const val NOTIFICATION_ID = 1

    const val ACTION_FIRE = "com.example.notifications.ACTION_FIRE"
    const val ACTION_SNOOZE = "com.example.notifications.ACTION_SNOOZE"
    const val ACTION_SNOOZE_FIRE = "com.example.notifications.ACTION_SNOOZE_FIRE"
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
    private const val PREF_SNOOZED_UNTIL = "reminder_snoozed_until"
    private const val PREF_NEXT_NUDGE_AT = "reminder_next_nudge_at"
    const val PREF_LAST_SHOWN_AT = "last_notif_shown_at"

    /**
     * DEVICE-LOCAL reminder bookkeeping, deliberately kept OUT of "medreview_settings".
     *
     * Android's cloud backup / device transfer copies whole SharedPreferences FILES, so anything
     * living beside the real settings rides along to a new phone. These three values describe the
     * state of *this* device's reminder chain — when it last fired, how long the user snoozed it,
     * when the next nudge is armed — and are actively harmful once restored elsewhere: a
     * same-day transfer could arrive with "already shown today" or a still-running snooze and go
     * silent on the new phone exactly when the user is checking that it works. res/xml/backup_rules
     * and data_extraction_rules exclude this file by name.
     */
    const val TRANSIENT_PREFS = "medreview_transient"

    private val TRANSIENT_KEYS = listOf(PREF_SNOOZED_UNTIL, PREF_NEXT_NUDGE_AT, PREF_LAST_SHOWN_AT)

    /**
     * The transient store, migrating any values still sitting in the old settings file on first use.
     * Idempotent: once moved, the settings file holds none of these keys and this is a no-op. Without
     * the migration, updating the app would silently forget an in-flight snooze — reintroducing, once,
     * the exact "reminder fires during a snooze" problem the snooze checks exist to prevent.
     */
    fun transientPrefs(context: Context): android.content.SharedPreferences {
        val transient = context.getSharedPreferences(TRANSIENT_PREFS, Context.MODE_PRIVATE)
        val legacy = context.getSharedPreferences("medreview_settings", Context.MODE_PRIVATE)
        if (TRANSIENT_KEYS.any { legacy.contains(it) }) {
            val t = transient.edit()
            val l = legacy.edit()
            TRANSIENT_KEYS.forEach { k ->
                if (legacy.contains(k)) {
                    t.putLong(k, legacy.getLong(k, 0L))
                    l.remove(k)
                }
            }
            t.apply()
            l.apply()
        }
        return transient
    }
    private const val COLLISION_WINDOW_MS = 5L * 60 * 1000

    /**
     * True when the CURRENT reminder channel can actually show notifications. Permission alone isn't
     * enough — a user can silence the channel (or all app notifications) in system settings, and the
     * Reminder Health screen would otherwise show green while nothing can appear.
     */
    fun isReminderChannelEnabled(context: Context): Boolean {
        // minSdk is 26, so notification channels always exist here.
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

    /** High-importance channel for the optional "ring like an alarm clock" full-screen reminder. */
    fun createAlarmChannel(context: Context) {
        val channel = NotificationChannel(ALARM_CHANNEL_ID, "Alarm Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Rings like an alarm clock when reviews are due"
            // The full-screen AlarmRingActivity owns the looping alarm tone; keep the channel itself
            // silent so the alarm sound doesn't play twice (channel + activity).
            setSound(null, null)
            // AlarmRingActivity owns vibration too; the channel must stay silent/haptic-free to
            // avoid a second overlapping pattern and to respect the user's vibration toggle.
            enableVibration(false)
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    /** True if the OS will honor exact alarms for this app (always true below Android 12). */
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    /**
     * The system screen where the user grants "Alarms & reminders" to THIS app. The package URI is
     * not decoration: without it some devices open the list of every installed app instead of
     * Yadora's own toggle, and the user has to go looking for it. One definition, used by onboarding
     * and Settings alike, so the two can't drift. Below Android 12 exact alarms need no grant and that
     * screen does not exist, so the app's own details page is returned rather than an action nothing
     * handles.
     */
    fun exactAlarmSettingsIntent(context: Context): Intent {
        val appUri = "package:${context.packageName}".toUri()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(appUri)
        } else {
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(appUri)
        }
    }

    /**
     * The guaranteed SECOND daily slot: opposite half of the day from the user's chosen time, so the
     * two fires are well separated. Evening people (>= 14:00) get a 10:00 morning nudge; morning
     * people get an 18:00 evening one. Public + pure so it's unit-testable.
     */
    fun secondaryReminderHour(primaryHour: Int): Int = if (primaryHour >= 14) 10 else 18

    /** True when two independently-planned alarms are close enough to be one user-visible reminder. */
    fun reminderSlotsCollide(firstMillis: Long, secondMillis: Long): Boolean =
        kotlin.math.abs(firstMillis - secondMillis) < COLLISION_WINDOW_MS

    /**
     * True when a real reminder was posted less than the collision window before [now], so a fire now would be the
     * same reminder again. Setting the clock forward past a slot delivers the time-change catch-up
     * ([BootReceiver]) and the now-overdue alarm within a second of each other: on the emulator that posted and
     * alerted twice and logged two NOTIF_SHOWN for one reminder. A shown time AFTER [now] (the clock was set back)
     * is not recent.
     */
    fun shownJustBefore(lastShownAt: Long, now: Long): Boolean =
        lastShownAt in (now - COLLISION_WINDOW_MS + 1)..now

    fun shownJustBefore(context: Context): Boolean =
        shownJustBefore(transientPrefs(context).getLong(PREF_LAST_SHOWN_AT, 0L), System.currentTimeMillis())

    /**
     * True when a real reminder was posted today, between [startOfToday] and [now]. A shown time after [now] means
     * the clock was set back since, and counting it as "today" silenced that day's catch-up and safety sweep.
     */
    fun shownToday(lastShownAt: Long, startOfToday: Long, now: Long): Boolean = lastShownAt in startOfToday..now

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
        val now = System.currentTimeMillis()
        val snoozedUntil = transientPrefs(context).getLong(PREF_SNOOZED_UNTIL, 0L)
        if (snoozedUntil > now) {
            // A real snooze suppresses every ordinary reminder until the chosen target. The safety
            // worker and app-open re-arm paths call this function too, so the state must live in prefs.
            cancelAlarm(context, REQ_DAILY, ACTION_FIRE)
            cancelAlarm(context, REQ_DAILY_2, ACTION_FIRE)
            armAlarm(context, snoozedUntil, REQ_SNOOZE_FIRE, ACTION_SNOOZE_FIRE)
            return
        } else if (snoozedUntil != 0L) {
            transientPrefs(context).edit { remove(PREF_SNOOZED_UNTIL) }
        }

        val primary = nextNudgeTime(context)
        val secondary = nextSecondarySlotTime(context)
        armAlarm(context, primary, REQ_DAILY, ACTION_FIRE)
        // A morning primary can produce a 3-hour repeat at exactly the 18:00 secondary slot. Two
        // different PendingIntents at the same instant create duplicate notifications/full-screen
        // launches. One fire is enough; the next receiver pass will re-arm the following slot.
        if (reminderSlotsCollide(primary, secondary)) {
            cancelAlarm(context, REQ_DAILY_2, ACTION_FIRE)
        } else {
            armAlarm(context, secondary, REQ_DAILY_2, ACTION_FIRE)
        }
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

    /**
     * Everything this app has armed, the pending TEST reminder included: for "Delete all data". Turning reminders off
     * uses [cancelReminder], which leaves a test the learner just asked for; a wipe used to leave it too, and a minute
     * later "Test reminder — it works" arrived after "all data deleted" (an outside emulator audit, 2026-09-30).
     */
    fun cancelAll(context: Context) {
        cancelReminder(context)
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(firePendingIntent(context, REQ_TEST, ACTION_TEST))
    }

    fun cancelReminder(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(firePendingIntent(context, REQ_DAILY, ACTION_FIRE))
        am.cancel(firePendingIntent(context, REQ_DAILY_2, ACTION_FIRE))
        am.cancel(firePendingIntent(context, REQ_SNOOZE_FIRE, ACTION_FIRE))
        am.cancel(firePendingIntent(context, REQ_SNOOZE_FIRE, ACTION_SNOOZE_FIRE))
        // Drop the armed-nudge bookmark too: nothing is scheduled any more, so a stale future value
        // must not be honoured the next time reminders are turned back on.
        transientPrefs(context).edit { remove(PREF_SNOOZED_UNTIL).remove(PREF_NEXT_NUDGE_AT) }
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
        val target = clampToWakingWindow(snoozeTargetMillis(context))
        transientPrefs(context).edit { putLong(PREF_SNOOZED_UNTIL, target) }
        cancelAlarm(context, REQ_DAILY, ACTION_FIRE)
        cancelAlarm(context, REQ_DAILY_2, ACTION_FIRE)
        armAlarm(context, target, REQ_SNOOZE_FIRE, ACTION_SNOOZE_FIRE)
    }

    /**
     * True while a snooze the USER chose is still in the future. The extra reliability layers (the
     * WorkManager sweep and the boot catch-up) must consult this: they exist to revive a dead
     * reminder chain, not to overrule a deliberate "not now".
     */
    fun isSnoozed(context: Context): Boolean =
        transientPrefs(context).getLong(PREF_SNOOZED_UNTIL, 0L) > System.currentTimeMillis()

    /** Consume persisted snooze state when its dedicated alarm fires. */
    fun clearSnooze(context: Context) {
        transientPrefs(context).edit { remove(PREF_SNOOZED_UNTIL) }
    }

    /**
     * Pull a trigger time into the 08:00–22:00 waking window, WITHOUT moving it to another day.
     *
     * It used to roll forward a day whenever the hour was past the window, which quietly broke the
     * snooze button's own promise: with a reminder time of 22:00 or 23:00, "Tomorrow" produced
     * tomorrow-at-23:00, got clamped forward again, and landed the DAY AFTER tomorrow at 08:00 — a
     * two-day silence where the label said one. Keeping the clamp inside the target's own day means
     * the label and the behaviour always agree; the only day-roll left is the safety check below,
     * for a clamped time that would otherwise sit in the past.
     */
    internal fun clampToWakingWindow(timeMillis: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = timeMillis }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        if (hour in WAKING_START_HOUR until WAKING_END_HOUR) return timeMillis
        cal.set(Calendar.HOUR_OF_DAY, if (hour >= WAKING_END_HOUR) WAKING_END_HOUR - 1 else WAKING_START_HOUR)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        // Never arm something in the past (a late-night target pulled back to 21:00 today).
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun cancelAlarm(context: Context, requestCode: Int, action: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(firePendingIntent(context, requestCode, action))
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

        // IDEMPOTENT RE-ARM. scheduleDailyReminder() is called from ~11 places, including
        // MedReviewApplication.onCreate on every cold process start — which a placed home-screen
        // widget triggers twice an hour. Recomputing "now + 3h" on each of those calls perpetually
        // postponed the pending nudge, so the intra-day nag chain never fired at all (confirmed in a
        // real 11-day export: 22 notifications, all from the two fixed daily slots, zero nudges).
        // Keep the instant we already armed until it actually comes due.
        val armed = transientPrefs(context).getLong(PREF_NEXT_NUDGE_AT, 0L)
        if (isLiveNudge(armed, now.timeInMillis)) {
            val armedHour = Calendar.getInstance().apply { timeInMillis = armed }.get(Calendar.HOUR_OF_DAY)
            if (armedHour in WAKING_START_HOUR until WAKING_END_HOUR) return armed
        }

        val candidate = now.timeInMillis + REPEAT_INTERVAL_MS
        val candHour = Calendar.getInstance().apply { timeInMillis = candidate }.get(Calendar.HOUR_OF_DAY)
        if (candHour in WAKING_START_HOUR until WAKING_END_HOUR) {
            transientPrefs(context).edit { putLong(PREF_NEXT_NUDGE_AT, candidate) }
            return candidate
        }

        transientPrefs(context).edit { remove(PREF_NEXT_NUDGE_AT) }
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
    }

    /**
     * True when [armed], the nudge instant saved when it was armed, is still the one to keep: in the future, and no
     * further ahead than one repeat, which is how far it was when it was saved. Anything further means the clock was
     * set back since; keeping it would silence the intra-day nudges until the old date comes round again.
     */
    fun isLiveNudge(armed: Long, now: Long): Boolean = armed > now && armed - now <= REPEAT_INTERVAL_MS

    @SuppressLint("MissingPermission")
    fun showReviewNotification(
        context: Context,
        markShown: Boolean = true,
        source: String = "alarm",
    ): Boolean {
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
        // The receiver first counts due rows, then this function fetches them again. A review can land
        // between those operations. Never post/log a real "Time to review" notification with zero due.
        if (markShown && count == 0) return false

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
            // Only a TEST fire can reach this branch: a real reminder with nothing due returns early
            // above. It used to say "You have study topics ready to review" — on a device with zero
            // topics, the very first reminder a new user tries told them something untrue (seen on a
            // real Samsung during device testing). Say what is actually happening instead.
            else -> if (isFa) "الان مبحثی برای مرور نیست — یادآورهایت این‌طور نمایش داده می‌شوند." else if (isDe) "Gerade ist nichts fällig — so sehen deine Erinnerungen aus." else "Nothing is due right now — this is how your reminders will look."
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

        // "Silence alarms" is a kill switch, NOT a second alarm setting: it suppresses the
        // full-screen ring while leaving alarm mode configured, so turning it back off restores the
        // user's setup. Ordinary reminder notifications are unaffected and still arrive.
        val alarmModeRequested = sp.getBoolean("alarm_enabled", false) &&
            !sp.getBoolean("alarm_silenced", false)
        val canUseFullScreen = Build.VERSION.SDK_INT < 34 ||
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).canUseFullScreenIntent()
        // On Android 14+, Play/system policy can revoke full-screen access. Fall back to an ordinary
        // audible reminder instead of selecting the silent alarm channel without launching the ringer.
        val alarmMode = alarmModeRequested && canUseFullScreen
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
                transientPrefs(context).edit { putLong(PREF_LAST_SHOWN_AT, System.currentTimeMillis()) }
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
                                detail = "source=$source due=$count${if (alarmMode) " alarm" else ""}"
                            )
                        )
                    }
                }
            }
            return true
        }
        return false
    }

    /**
     * (title, highYield) for every topic in today's plan (DailyPlan), in the order the session serves
     * them. Safe on any thread. The plan, not every due row: the reminder must name what "Review now"
     * actually opens, never reviews the daily limit is holding for tomorrow.
     */
    private fun fetchDueSummaries(context: Context): List<Pair<String, Boolean>> {
        val app = context.applicationContext as? com.example.MedReviewApplication ?: return emptyList()
        return runCatching {
            kotlinx.coroutines.runBlocking {
                app.todayPlan().queue.map { it.title to it.highYield }
            }
        }.getOrDefault(emptyList())
    }
}
