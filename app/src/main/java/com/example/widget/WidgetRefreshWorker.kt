package com.example.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Redraws the home-screen widget under a lifetime Android actually respects.
 *
 * [DueWidgetProvider.updateAll] is called from places that are not guaranteed to outlive the work:
 * a BroadcastReceiver (boot, time change, reminder) can have its process reclaimed the moment
 * `onReceive` returns, and a bare `Thread` started there is killed mid-flight — leaving the widget
 * showing yesterday's count with nothing to correct it until the OS's own 30-minute tick.
 * `onUpdate` has `goAsync()` for exactly this reason; every other caller needs this.
 */
class WidgetRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            val mgr = AppWidgetManager.getInstance(applicationContext)
            val ids = mgr.getAppWidgetIds(
                ComponentName(applicationContext, DueWidgetProvider::class.java)
            )
            if (ids.isNotEmpty()) DueWidgetProvider.render(applicationContext, mgr, ids)
            Result.success()
        }.getOrElse {
            // A widget refresh is never worth surfacing an error for; retry once and let it go.
            android.util.Log.w("Yadora", "widget refresh failed", it)
            if (runAttemptCount < 2) Result.retry() else Result.success()
        }
    }
}
