package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import com.example.ui.MedReviewApp
import kotlinx.coroutines.launch
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
  companion object {
    /** Which tap opened the app: "notification", "alarm", "widget", "topic" or "topics" ([logOpen]). */
    const val EXTRA_OPENED_FROM = "opened_from"
    /** The topic whose own notification was tapped ([com.example.notifications.TopicNotifications]): it opens to rate. */
    const val EXTRA_OPEN_TOPIC = "open_topic"
  }

  // Bumped when the app is opened from a "Review now" notification/alarm, so Compose jumps to review.
  private val openReviewSignal = androidx.compose.runtime.mutableStateOf(0)

  /** A topic notification's tap: the topic, and a count so the same topic tapped twice opens twice. */
  private val openTopicSignal = androidx.compose.runtime.mutableStateOf<com.example.ui.OpenTopic?>(null)

  /** Consumed, like open_review, so a recreated activity does not open the topic again. */
  private fun takeOpenTopic(intent: android.content.Intent?) {
    val id = intent?.getLongExtra(EXTRA_OPEN_TOPIC, -1L) ?: -1L
    intent?.removeExtra(EXTRA_OPEN_TOPIC)
    if (id > 0) openTopicSignal.value = com.example.ui.OpenTopic(id, (openTopicSignal.value?.seq ?: 0) + 1)
  }

  /**
   * One APP_OPENED event per tap on a reminder, the alarm or the widget. With REMINDER_FIRED and NOTIF_SHOWN it
   * lets the pilot follow a reminder to the review it led to, or did not (an outside audit, 2026-10-02). Consumed,
   * so a recreated activity does not count the same tap twice.
   */
  private fun logOpen(intent: android.content.Intent?) {
    val from = intent?.getStringExtra(EXTRA_OPENED_FROM) ?: return
    intent.removeExtra(EXTRA_OPENED_FROM)
    val app = application as MedReviewApplication
    lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
      runCatching {
        app.database.eventLogDao().insert(com.example.data.local.entity.EventLogEntity(type = "APP_OPENED", detail = "from=$from"))
      }
    }
  }

  override fun onResume() {
    super.onResume()
    // A trip with the app in the background changes the time zone without a new onCreate: record it on coming back,
    // before any review is made there (TimeZoneLog; a prefs read when nothing changed).
    val app = application as MedReviewApplication
    lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { com.example.data.TimeZoneLog.recordIfChanged(app) }
  }

  override fun onNewIntent(intent: android.content.Intent) {
    super.onNewIntent(intent)
    logOpen(intent)
    // Opening the app always silences a ringing alarm — the app itself is the answer to it.
    runCatching { com.example.notifications.AlarmRingActivity.dismissActive() }
    if (intent.getBooleanExtra("open_review", false)) {
      openReviewSignal.value++
      androidx.core.app.NotificationManagerCompat.from(this).cancel(com.example.notifications.NotificationScheduler.NOTIFICATION_ID)
    }
    intent.removeExtra("open_review") // consume it, so a later recreate doesn't re-navigate
    takeOpenTopic(intent)
    setIntent(intent)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val app = application as MedReviewApplication
    if (savedInstanceState == null) logOpen(intent)
    // For the analysis: the first run of a new build, a changed time zone, and the day's load if the safety worker has
    // not written it yet today (AppVersionLog, TimeZoneLog, DailySnapshot). All best effort; none throws.
    lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
      com.example.data.AppVersionLog.recordIfChanged(app)
      com.example.data.TimeZoneLog.recordIfChanged(app)
      com.example.data.DailySnapshot.recordOnce(app, source = com.example.data.DailySnapshot.SOURCE_APP)
    }
    runCatching { com.example.notifications.AlarmRingActivity.dismissActive() } // opening the app silences a ringing alarm
    com.example.notifications.TodayRefresh.afterChange(this) // keep the home-screen count and the topic notifications fresh
    if (intent?.getBooleanExtra("open_review", false) == true) {
      openReviewSignal.value++
      intent?.removeExtra("open_review") // consume it, so rotation/recreate doesn't re-navigate
      // Opening review from the reminder/alarm dismisses the (possibly ongoing) reminder notification.
      androidx.core.app.NotificationManagerCompat.from(this).cancel(com.example.notifications.NotificationScheduler.NOTIFICATION_ID)
    }
    if (savedInstanceState == null) takeOpenTopic(intent)
    setContent {
      val sharedPrefs = androidx.compose.runtime.remember { getSharedPreferences("medreview_settings", android.content.Context.MODE_PRIVATE) }
      val initialLanguage = sharedPrefs.getString("app_language", "en") ?: "en"
      val initialLanguageSelected = sharedPrefs.getBoolean("language_selected", false)

      var currentLanguage by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(initialLanguage) }
      var languageSelected by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(initialLanguageSelected) }
      // Device-local, backup-excluded prefs: permissions don't travel to a new phone, so neither may the
      // "reminders step done" flag (see RemindersSetupPolicy).
      val remindersTransientPrefs = androidx.compose.runtime.remember { com.example.notifications.NotificationScheduler.transientPrefs(this) }
      var remindersSetupDone by androidx.compose.runtime.remember {
          androidx.compose.runtime.mutableStateOf(remindersTransientPrefs.getBoolean(com.example.ui.onboarding.RemindersSetupPolicy.PREF_DONE, false))
      }
      var themeMode by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(sharedPrefs.getString("theme_mode", "system") ?: "system") }
      var accentHex by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(sharedPrefs.getString("accent_color", "") ?: "") }
      // Calendar preference, independent of UI language. "auto" follows language (fa→Jalali).
      // A prefs listener makes a change in Settings take effect immediately, no restart needed.
      var calendarFormat by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(sharedPrefs.getString("calendar_format", "auto") ?: "auto") }
      androidx.compose.runtime.DisposableEffect(Unit) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, k ->
          if (k == "calendar_format") calendarFormat = p.getString("calendar_format", "auto") ?: "auto"
        }
        sharedPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { sharedPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
      }

      val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
      val darkTheme = when (themeMode) { "light" -> false; "dark" -> true; else -> systemDark }
      val accent = accentHex.takeIf { it.isNotBlank() }?.let {
          runCatching { androidx.compose.ui.graphics.Color(it.toColorInt()) }.getOrNull()
      }

      MyApplicationTheme(darkTheme = darkTheme, accent = accent, languageCode = currentLanguage) {
        val layoutDirection = if (currentLanguage == "fa") androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr
        val appStrings = when (currentLanguage) {
            "fa" -> com.example.ui.i18n.PersianStrings
            "de" -> com.example.ui.i18n.GermanStrings
            else -> com.example.ui.i18n.EnglishStrings
        }
        
        val useJalali = when (calendarFormat) { "jalali" -> true; "gregorian" -> false; else -> currentLanguage == "fa" }
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalLayoutDirection provides layoutDirection,
            com.example.ui.i18n.LocalStrings provides appStrings,
            com.example.ui.i18n.LocalUseJalali provides useJalali
        ) {
            if (!languageSelected) {
                com.example.ui.language.LanguageSelectionScreen(onLanguageSelected = { lang ->
                    sharedPrefs.edit {
                        putString("app_language", lang)
                        putBoolean("language_selected", true)
                    }
                    currentLanguage = lang
                    languageSelected = true
                })
            } else {
                val showRemindersSetup = androidx.compose.runtime.remember(remindersSetupDone) {
                    com.example.ui.onboarding.RemindersSetupPolicy.shouldShow(
                        setupDone = remindersSetupDone,
                        notificationsEnabled = androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled(),
                        exactAlarmsAllowed = com.example.notifications.NotificationScheduler.canScheduleExact(this),
                    )
                }
                if (showRemindersSetup) {
                    // Step 2 of first-run onboarding. It replaces a bare POST_NOTIFICATIONS request that
                    // fired the instant the language was picked, with no explanation — while the
                    // exact-alarm permission Android 14+ withholds from new installs was never mentioned
                    // at all, so reminders quietly arrived up to an hour late.
                    com.example.ui.onboarding.RemindersSetupScreen(onDone = {
                        remindersTransientPrefs.edit {
                            putBoolean(com.example.ui.onboarding.RemindersSetupPolicy.PREF_DONE, true)
                        }
                        remindersSetupDone = true
                        // Whatever was just granted takes effect now, not at the next re-arm.
                        runCatching { com.example.notifications.NotificationScheduler.scheduleDailyReminder(this) }
                    })
                } else {
                MedReviewApp(
                    repository = app.repository,
                    onLanguageChange = { lang ->
                        sharedPrefs.edit { putString("app_language", lang) }
                        currentLanguage = lang
                    },
                    onThemeChange = { mode, hex ->
                        sharedPrefs.edit { putString("theme_mode", mode).putString("accent_color", hex) }
                        themeMode = mode
                        accentHex = hex
                    },
                    openReviewSignal = openReviewSignal.value,
                    openTopic = openTopicSignal.value,
                )
                }
            }
        }
      }
    }
  }
}
