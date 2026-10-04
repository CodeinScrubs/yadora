package com.example.data

import android.content.Context
import com.example.MedReviewApplication
import com.example.data.local.entity.EventLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * One SETTINGS_CHANGED event each time the learner changes a setting the plan depends on: the daily limit, the retention
 * target, the reminder time. The day's snapshot records the limit once a day and every review records its retention
 * target, but neither says WHEN a setting changed, so a limit lowered in the middle of a day, or changed and changed back,
 * could not be told apart months later (an outside audit, 2026-10-04). detail: key=<setting> old=<value> new=<value>.
 * Nothing on screen. Written on the application's own scope with the time of the change, so leaving Settings at once
 * loses nothing; best effort, it never throws.
 */
object SettingsChangeLog {
    const val EVENT = "SETTINGS_CHANGED"
    const val DAILY_LIMIT = "daily_review_limit"
    const val RETENTION = "desired_retention"
    const val REMINDER_TIME = "reminder_time"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The event's detail, or null when nothing changed (a drag that ended where it began). */
    fun detail(key: String, old: String, new: String): String? = if (old == new) null else "key=$key old=$old new=$new"

    /** The limit as the slider shows it: a whole number of reviews. */
    fun limitValue(raw: Float): String = Math.round(raw).toString()

    /** The retention target as the slider shows it: a whole percent, written as a fraction. */
    fun retentionValue(raw: Float): String = "%.2f".format(java.util.Locale.ROOT, Math.round(raw * 100) / 100.0)

    fun reminderTimeValue(hour: Int, minute: Int): String = "%02d:%02d".format(java.util.Locale.ROOT, hour, minute)

    fun record(context: Context, key: String, old: String, new: String, at: Long = System.currentTimeMillis()) {
        val text = detail(key, old, new) ?: return
        val app = context.applicationContext as? MedReviewApplication ?: return
        scope.launch {
            runCatching { app.database.eventLogDao().insert(EventLogEntity(at = at, type = EVENT, detail = text)) }
                .onFailure { android.util.Log.w("Yadora", "settings change not logged", it) }
        }
    }
}
