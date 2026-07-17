package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.example.ui.MedReviewApp
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
  // Bumped when the app is opened from a "Review now" notification/alarm, so Compose jumps to review.
  private val openReviewSignal = androidx.compose.runtime.mutableStateOf(0)

  override fun onNewIntent(intent: android.content.Intent) {
    super.onNewIntent(intent)
    // Opening the app always silences a ringing alarm — the app itself is the answer to it.
    runCatching { com.example.notifications.AlarmRingActivity.dismissActive() }
    if (intent.getBooleanExtra("open_review", false)) {
      openReviewSignal.value++
      androidx.core.app.NotificationManagerCompat.from(this).cancel(com.example.notifications.NotificationScheduler.NOTIFICATION_ID)
    }
    intent.removeExtra("open_review") // consume it, so a later recreate doesn't re-navigate
    setIntent(intent)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val app = application as MedReviewApplication
    runCatching { com.example.notifications.AlarmRingActivity.dismissActive() } // opening the app silences a ringing alarm
    com.example.widget.DueWidgetProvider.updateAll(this) // keep the home-screen count fresh on open
    if (intent?.getBooleanExtra("open_review", false) == true) {
      openReviewSignal.value++
      intent?.removeExtra("open_review") // consume it, so rotation/recreate doesn't re-navigate
      // Opening review from the reminder/alarm dismisses the (possibly ongoing) reminder notification.
      androidx.core.app.NotificationManagerCompat.from(this).cancel(com.example.notifications.NotificationScheduler.NOTIFICATION_ID)
    }
    setContent {
      val sharedPrefs = androidx.compose.runtime.remember { getSharedPreferences("medreview_settings", android.content.Context.MODE_PRIVATE) }
      val initialLanguage = sharedPrefs.getString("app_language", "en") ?: "en"
      val initialLanguageSelected = sharedPrefs.getBoolean("language_selected", false)

      var currentLanguage by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(initialLanguage) }
      var languageSelected by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(initialLanguageSelected) }
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
          runCatching { androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(it)) }.getOrNull()
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
                    sharedPrefs.edit()
                        .putString("app_language", lang)
                        .putBoolean("language_selected", true)
                        .apply()
                    currentLanguage = lang
                    languageSelected = true
                })
            } else {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    // pre-existing permission logic
                    val permissionState = androidx.compose.runtime.remember {
                      androidx.compose.runtime.mutableStateOf(
                        androidx.core.content.ContextCompat.checkSelfPermission(
                          this, 
                          android.Manifest.permission.POST_NOTIFICATIONS
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                      )
                    }
                    val requestPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                      androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
                    ) { isGranted ->
                      permissionState.value = isGranted
                    }
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                      if (!permissionState.value) {
                        requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                      }
                    }
                }
                MedReviewApp(
                    repository = app.repository,
                    onLanguageChange = { lang ->
                        sharedPrefs.edit().putString("app_language", lang).apply()
                        currentLanguage = lang
                    },
                    onThemeChange = { mode, hex ->
                        sharedPrefs.edit().putString("theme_mode", mode).putString("accent_color", hex).apply()
                        themeMode = mode
                        accentHex = hex
                    },
                    openReviewSignal = openReviewSignal.value
                )
            }
        }
      }
    }
  }
}
