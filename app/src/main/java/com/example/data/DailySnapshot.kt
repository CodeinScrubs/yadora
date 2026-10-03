package com.example.data

import android.content.Context
import androidx.core.content.edit
import com.example.MedReviewApplication
import com.example.data.local.entity.EventLogEntity
import com.example.data.local.entity.StudyUnitEntity
import com.example.domain.srs.MedScheduler
import com.example.notifications.NotificationScheduler
import com.example.ui.today.DailyPlan
import com.example.ui.today.DayBounds
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Once a day, a content-free count of the learner's load: what is due, how much of it is overdue and for how long, what
 * the daily limit holds back, what the learner deferred. One DAILY_SNAPSHOT event a day, nothing on screen.
 *
 * Why: study is irregular (the owner, 2026-10-03: some days every review and new topics, some days none, some reviews
 * left for the next days), and whether a plan of about 2,000 topics in a year is keeping up is the first thing an analysis
 * months later must see. The logs cannot rebuild it exactly: a notification's "Not today" defers every due topic without
 * naming them, and "Spread out" records only how many it moved. tools/pilot/analyze.py reads these into its load table.
 *
 * Written by whichever runs first on a new local day, the 6-hourly safety worker or the app opening, so a day the app is
 * never opened still gets one. Best effort: it never throws, and a duplicate (two writers at once) only costs a row.
 */
object DailySnapshot {
    const val EVENT = "DAILY_SNAPSHOT"
    /** The local epoch day of the last snapshot, in the device-only prefs: a restore must not silence a new phone's. */
    const val PREF_DAY = "daily_snapshot_day"

    private val lock = Mutex()

    /** The event's detail, space-separated key=value pairs like REMINDER_FIRED's (pure, so it is testable). */
    fun detail(active: List<StudyUnitEntity>, now: Long, reviewsDoneToday: Int, dailyLimit: Int): String {
        val start = DayBounds.startOf(now)
        val end = DayBounds.endOf(now)
        val due = active.filter { it.nextReviewAt <= end }
        val overdue = due.filter { it.nextReviewAt < start }
        val plan = DailyPlan.plan(due, reviewsDoneToday, dailyLimit, now)
        val oldestOverdueDays = overdue.minOfOrNull { it.nextReviewAt }?.let { (start - it + 86_399_999L) / 86_400_000L } ?: 0L
        return listOf(
            "active=${active.size}",
            "rated=${active.count { it.reviewCount > 0 }}",
            "due=${due.size}",
            "overdue=${overdue.size}",
            "oldest_overdue_days=$oldestOverdueDays",
            "first=${due.count { DailyPlan.isFirstRating(it) }}",
            "offered=${plan.size}",
            "held=${plan.heldBack}",
            "done=$reviewsDoneToday",
            "deferred=${active.count { it.deferredUntil != null }}",
            "limit=$dailyLimit",
        ).joinToString(" ")
    }

    /** Today's snapshot, unless one was written already today. Never throws. */
    suspend fun recordOnce(context: Context, now: Long = System.currentTimeMillis()) {
        runCatching {
            val app = context.applicationContext as? MedReviewApplication ?: return
            val today = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()
            val prefs = NotificationScheduler.transientPrefs(app)
            lock.withLock {
                if (prefs.getLong(PREF_DAY, Long.MIN_VALUE) == today) return
                val limit = MedScheduler.safeDailyLimit(
                    app.getSharedPreferences("medreview_settings", Context.MODE_PRIVATE).getFloat("daily_review_limit", 50f),
                )
                val active = app.database.studyUnitDao().getAllActiveOnce()
                val done = app.database.reviewLogDao().countReviewsBetween(DayBounds.startOf(now), DayBounds.endOf(now))
                app.database.eventLogDao().insert(EventLogEntity(type = EVENT, at = now, detail = detail(active, now, done, limit)))
                prefs.edit { putLong(PREF_DAY, today) }
            }
        }.onFailure { android.util.Log.w("Yadora", "daily snapshot failed", it) }
    }
}
