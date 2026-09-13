package com.example

import android.app.Application
import androidx.room.Room
import com.example.data.local.database.AppDatabase
import com.example.data.repository.MedReviewRepository
import com.example.notifications.NotificationScheduler

class MedReviewApplication : Application() {
    lateinit var database: AppDatabase
    lateinit var repository: MedReviewRepository

    override fun onCreate() {
        super.onCreate()

        // Local crash log for friend testing (no backend): the stack trace of any crash is appended
        // to files/crash.log and shipped inside the analytics export — otherwise a crash on a
        // friend's phone is invisible. Chains to the default handler so the app still crashes
        // normally (no zombie states).
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val f = java.io.File(filesDir, "crash.log")
                // Keep the file bounded: drop the oldest half once it grows past ~200KB.
                if (f.length() > 200_000) {
                    val tail = f.readText().takeLast(100_000)
                    f.writeText(tail)
                }
                f.appendText(
                    "=== ${java.util.Date()} · v${com.example.BuildConfig.VERSION_NAME} · " +
                        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · SDK ${android.os.Build.VERSION.SDK_INT}\n" +
                        android.util.Log.getStackTraceString(throwable) + "\n"
                )
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        database = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "medreview_db"
        )
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()

        repository = MedReviewRepository(
            database.studyUnitDao(),
            database.categoryDao(),
            database.reviewLogDao(),
            database
        )

        // No demo data is seeded: the user's database only ever contains topics they add themselves.

        // Purge topics whose 30-day soft-delete grace expired. Background + best-effort: a failed
        // purge just retries next launch; it must never delay or crash startup.
        Thread {
            runCatching { kotlinx.coroutines.runBlocking { repository.purgeExpiredDeleted() } }
        }.start()
        // The per-user interval correction (MedScheduler.calibrationScale) is NOT read here: the
        // review session and the Important toggle each refresh it from the logs right before they
        // schedule, on the same coroutine that uses it. Setting it from a startup thread raced them.

        // Initialize notification channel and schedule reminders based on saved settings
        NotificationScheduler.createNotificationChannel(this)
        val sharedPrefs = getSharedPreferences("medreview_settings", MODE_PRIVATE)
        com.example.domain.srs.MedScheduler.userRetention = sharedPrefs.getFloat("desired_retention", 0.90f).toDouble()
        if (sharedPrefs.getBoolean("daily_reminder", true)) {
            NotificationScheduler.scheduleDailyReminder(this)
        } else {
            NotificationScheduler.cancelReminder(this)
        }

        // Third reliability layer (after exact alarms + boot catch-up): a periodic WorkManager sweep
        // that fires a reminder if the alarm was killed by an OEM battery manager and today's reminder
        // never showed. Dedup via `last_notif_shown_at` means it never double-notifies.
        // Guarded: it's a redundant safety net, so if WorkManager can't initialize (e.g. a test/host
        // environment where the startup provider didn't run), app startup must not crash over it.
        runCatching {
            androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "reminder_safety",
                // UPDATE (not KEEP): if a future version changes the sweep interval/constraints, existing
                // installs must actually receive the change instead of keeping the old request forever.
                androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
                androidx.work.PeriodicWorkRequestBuilder<com.example.notifications.ReminderSafetyWorker>(
                    6, java.util.concurrent.TimeUnit.HOURS
                ).build(),
            )
        }
    }
}
