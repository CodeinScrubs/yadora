package com.example.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Once a day, in the background: refit the memory model to this learner's own history when there is
 * enough new evidence to be worth it (`MedReviewRepository.refitPersonalModel`). A fit is adopted only
 * if it predicts the learner's later reviews better than the weights in use; either way the attempt is
 * recorded. Adoption only writes the database: the scheduler switches weights at the start of the next
 * review session, so no session ever previews with one set and commits with another.
 */
class PersonalModelWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? com.example.MedReviewApplication ?: return Result.success()
        // The learner can switch the personal model off in Settings; then nothing is refitted or adopted.
        val prefs = app.getSharedPreferences("medreview_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_ENABLED, true)) return Result.success()
        // FORCE skips only the "enough NEW evidence since the last attempt" wait. The minimum review count
        // and the held-out gate still decide whether anything is adopted.
        val force = inputData.getBoolean(KEY_FORCE, false)
        runCatching { app.repository.refitPersonalModel(force = force) }.onFailure { error ->
            android.util.Log.w("Yadora", "personal model refit failed", error)
            runCatching {
                app.repository.logEvent("PERSONAL_MODEL_FAILED", detail = "${error::class.java.simpleName}: ${error.message?.take(120)}")
            }
        }
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "personal_memory_model"
        const val PREF_ENABLED = "personal_model_enabled"
        private const val KEY_FORCE = "force"
        private const val UNIQUE_NOW = "personal_memory_model_now"

        /**
         * One attempt now, for the Settings switch being turned back on: the daily refit would otherwise
         * wait for 20% more evidence or 30 days after the last attempt, which is the set just retired.
         */
        fun refitNow(context: Context) {
            runCatching {
                androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                    UNIQUE_NOW,
                    androidx.work.ExistingWorkPolicy.REPLACE,
                    androidx.work.OneTimeWorkRequestBuilder<PersonalModelWorker>()
                        .setInputData(androidx.work.workDataOf(KEY_FORCE to true))
                        .setConstraints(androidx.work.Constraints.Builder().setRequiresBatteryNotLow(true).build())
                        .build(),
                )
            }
        }
    }
}
