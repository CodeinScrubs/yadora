package com.example.data

import android.content.Context
import androidx.core.content.edit
import com.example.BuildConfig
import com.example.MedReviewApplication
import com.example.data.local.entity.EventLogEntity
import com.example.notifications.NotificationScheduler
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One APP_VERSION event the first time a build runs on this phone: its code and name, and the code that ran before
 * (0 = a fresh install, or the first build that records it). An analysis months later must tell what changed when: a
 * shift in the logs that starts the day an update arrived points at the update, not at the learner.
 *
 * Recorded where [DailySnapshot] is (the app opening, the 6-hourly safety worker), not when the process starts, where a
 * thread would race every test that reads the event log. Device-local, like the reminder bookkeeping: a restore onto
 * another phone does not carry it. Best effort; it never throws.
 */
object AppVersionLog {
    const val EVENT = "APP_VERSION"
    const val PREF_LAST_VERSION_CODE = "last_version_code"

    private val lock = Mutex()

    suspend fun recordIfChanged(
        context: Context,
        versionCode: Int = BuildConfig.VERSION_CODE,
        versionName: String = BuildConfig.VERSION_NAME,
    ) {
        runCatching {
            val app = context.applicationContext as? MedReviewApplication ?: return
            val prefs = NotificationScheduler.transientPrefs(app)
            lock.withLock {
                val previous = prefs.getInt(PREF_LAST_VERSION_CODE, 0)
                if (previous == versionCode) return
                app.database.eventLogDao().insert(
                    EventLogEntity(type = EVENT, detail = "code=$versionCode name=$versionName previous=$previous")
                )
                prefs.edit { putInt(PREF_LAST_VERSION_CODE, versionCode) }
            }
        }.onFailure { android.util.Log.w("Yadora", "version record failed", it) }
    }
}
