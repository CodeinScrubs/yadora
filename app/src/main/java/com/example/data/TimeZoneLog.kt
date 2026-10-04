package com.example.data

import android.content.Context
import androidx.core.content.edit
import com.example.MedReviewApplication
import com.example.data.local.entity.EventLogEntity
import com.example.notifications.NotificationScheduler
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId

/**
 * One TIME_ZONE event when the phone's time zone is seen to differ from the last one recorded: the first time (a
 * baseline, previous=none), then after a trip, a new phone or a changed setting. FSRS-6 counts elapsed time in LOCAL
 * calendar days, so a review counts its days in the zone the phone is in then; with this the analysis can tell a review
 * counted in another zone from a bug (tools/pilot/analyze.py lists each phone's zones). Checked where [AppVersionLog] is
 * (the app opening, the 6-hourly safety worker), so a change shows within hours. detail: zone=<id> offset=<+hh:mm>
 * previous=<id>. Device-local, like the reminder bookkeeping; a restore clears the mark, since it replaces the event log.
 * Best effort; it never throws.
 */
object TimeZoneLog {
    const val EVENT = "TIME_ZONE"
    const val PREF_LAST_ZONE = "last_time_zone"

    private val lock = Mutex()

    fun detail(zone: ZoneId, previous: String?, now: Long): String {
        val offset = zone.rules.getOffset(java.time.Instant.ofEpochMilli(now)).id.let { if (it == "Z") "+00:00" else it }
        return "zone=${zone.id} offset=$offset previous=${previous ?: "none"}"
    }

    suspend fun recordIfChanged(context: Context, zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis()) {
        runCatching {
            val app = context.applicationContext as? MedReviewApplication ?: return
            val prefs = NotificationScheduler.transientPrefs(app)
            lock.withLock {
                val previous = prefs.getString(PREF_LAST_ZONE, null)
                if (previous == zone.id) return
                app.database.eventLogDao().insert(EventLogEntity(at = now, type = EVENT, detail = detail(zone, previous, now)))
                prefs.edit { putString(PREF_LAST_ZONE, zone.id) }
            }
        }.onFailure { android.util.Log.w("Yadora", "time zone record failed", it) }
    }
}
