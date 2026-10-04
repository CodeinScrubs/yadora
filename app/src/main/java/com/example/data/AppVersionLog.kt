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
 * One APP_VERSION event the first time a build runs on this phone: its code and name, the code that ran before
 * (0 = a fresh install, or the first build that records it) and when it was installed. An analysis months later must
 * tell what changed when: a shift in the logs that starts the day an update arrived points at the update, not at the
 * learner. A test build installed over another with the same version code is a new build too, told apart by its install
 * time (an outside audit, 2026-10-04).
 *
 * Recorded where [DailySnapshot] is (the app opening, the 6-hourly safety worker), not when the process starts, where a
 * thread would race every test that reads the event log. Device-local, like the reminder bookkeeping: a restore onto
 * another phone does not carry it. Best effort; it never throws.
 */
object AppVersionLog {
    const val EVENT = "APP_VERSION"
    const val PREF_LAST_VERSION_CODE = "last_version_code"
    const val PREF_LAST_INSTALLED_AT = "last_installed_at"

    private val lock = Mutex()

    /** When this package was last installed or updated (epoch ms), or 0 when Android does not say. */
    fun installedAt(context: Context): Long = runCatching {
        val pm = context.packageManager
        val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") pm.getPackageInfo(context.packageName, 0)
        }
        info.lastUpdateTime
    }.getOrDefault(0L)

    suspend fun recordIfChanged(
        context: Context,
        versionCode: Int = BuildConfig.VERSION_CODE,
        versionName: String = BuildConfig.VERSION_NAME,
        installed: Long? = null,
    ) {
        runCatching {
            val app = context.applicationContext as? MedReviewApplication ?: return
            val prefs = NotificationScheduler.transientPrefs(app)
            val installedAt = installed ?: installedAt(app)
            lock.withLock {
                val previous = prefs.getInt(PREF_LAST_VERSION_CODE, 0)
                if (previous == versionCode && prefs.getLong(PREF_LAST_INSTALLED_AT, 0L) == installedAt) return
                app.database.eventLogDao().insert(
                    EventLogEntity(
                        type = EVENT,
                        detail = "code=$versionCode name=$versionName previous=$previous installed=$installedAt",
                    )
                )
                prefs.edit {
                    putInt(PREF_LAST_VERSION_CODE, versionCode)
                    putLong(PREF_LAST_INSTALLED_AT, installedAt)
                }
            }
        }.onFailure { android.util.Log.w("Yadora", "version record failed", it) }
    }
}
