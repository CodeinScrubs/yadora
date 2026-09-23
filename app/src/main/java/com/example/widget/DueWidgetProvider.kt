package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Home-screen widget: how many reviews are due today, one tap to open the app. A standing visual cue
 * on the launcher is one of the strongest habit loops on Android — the pile is visible before the
 * user even thinks about studying.
 *
 * Updates: the OS 30-min periodic tick (onUpdate) + explicit pushes via [updateAll] from the app
 * (open, review committed, "Not today", boot), so the number is fresh at the moments it changes.
 */
class DueWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // DB read off the main thread; goAsync keeps the receiver alive until we finish.
        val pending = goAsync()
        Thread {
            try {
                render(context, appWidgetManager, appWidgetIds)
            } catch (t: Throwable) {
                // Best-effort background work: an exception here would reach the thread's uncaught
                // handler, which chains to the app's global handler and takes the whole app down —
                // over what is only a reminder refresh. Swallow and log instead.
                android.util.Log.w("Yadora", "background work failed", t)
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        /** Push a fresh count to every placed widget. Safe to call from any thread; no-op if none. */
        fun updateAll(context: Context) {
            runCatching {
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(ComponentName(context, DueWidgetProvider::class.java))
                if (ids.isEmpty()) return
                // WorkManager, not a bare Thread. Called from a BroadcastReceiver the process can
                // be torn down the moment onReceive returns, so an unmanaged thread may never finish
                // and the widget silently keeps a stale count.
                // UNIQUE work, REPLACE: a review session fires this on every rating, and only the
                // last count is worth drawing. Plain enqueue() would leave a 50-request queue behind
                // after a 50-topic session, each one re-rendering the same widget.
                androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                    "yadora-widget-refresh",
                    androidx.work.ExistingWorkPolicy.REPLACE,
                    androidx.work.OneTimeWorkRequestBuilder<WidgetRefreshWorker>().build(),
                )
            }
        }

        internal fun render(context: Context, mgr: AppWidgetManager, ids: IntArray) {
            val app = context.applicationContext as? com.example.MedReviewApplication ?: return
            // Today's plan (DailyPlan): the count "Start review" loads, not every due row.
            val plan = kotlinx.coroutines.runBlocking { app.todayPlan() }
            val due = plan.size

            val sp = context.getSharedPreferences("medreview_settings", Context.MODE_PRIVATE)
            val lang = sp.getString("app_language", "en") ?: "en"
            val isFa = lang == "fa"
            val countText = if (isFa) com.example.ui.i18n.PersianDate.faDigits(due) else due.toString()
            val label = when {
                // The day's limit is done but reviews are still waiting: "caught up" would be untrue.
                due == 0 && plan.limitReached && isFa -> "برای امروز کافی است"
                due == 0 && plan.limitReached && lang == "de" -> "für heute fertig"
                due == 0 && plan.limitReached -> "done for today"
                due == 0 && isFa -> "مروری نمانده"
                due == 0 && lang == "de" -> "alles erledigt"
                due == 0 -> "all caught up"
                isFa -> "مرور امروز"
                lang == "de" && due == 1 -> "Thema fällig"
                lang == "de" -> "Themen fällig"
                due == 1 -> "review due"
                else -> "reviews due"
            }

            val openIntent = Intent(context, com.example.MainActivity::class.java).apply {
                // SINGLE_TOP (not CLEAR_TASK): reuse a running MainActivity via onNewIntent
                // instead of destroying whatever the user had open.
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                // One-tap studying: when reviews are waiting, the widget goes straight into the
                // review session instead of just opening the app.
                if (due > 0) putExtra("open_review", true)
            }
            val pi = PendingIntent.getActivity(
                context, 2001, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            for (id in ids) {
                val views = RemoteViews(context.packageName, com.example.R.layout.widget_due).apply {
                    setTextViewText(com.example.R.id.widget_count, countText)
                    setTextViewText(com.example.R.id.widget_label, label)
                    setOnClickPendingIntent(com.example.R.id.widget_root, pi)
                }
                mgr.updateAppWidget(id, views)
            }
        }
    }
}
