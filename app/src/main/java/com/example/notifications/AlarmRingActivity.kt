package com.example.notifications

import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import com.example.ui.theme.MyApplicationTheme

/**
 * Full-screen "alarm clock" that rings at the due time, shown even over the lock screen. It is
 * launched by a full-screen-intent notification from [NotificationScheduler] when the user enables
 * alarm mode. Plays the device alarm tone on a loop and vibrates until the user acts.
 */
class AlarmRingActivity : ComponentActivity() {

    companion object {
        // The ringing lives in this activity, so a notification action or the main app opening needs
        // a way to silence it from outside. Same-process only; cleared in onDestroy.
        @Volatile
        private var active: AlarmRingActivity? = null

        /** Silence + close the ringing alarm screen if one exists. Safe to call from anywhere. */
        fun dismissActive() {
            val a = active ?: return
            a.runOnUiThread { runCatching { a.stopAndFinish() } }
        }
    }

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    /** The ringer has had window focus at least once: the learner has seen it, so a stop now means they left. */
    private var seen = false
    private val autoStopHandler by lazy { android.os.Handler(mainLooper) }
    private val autoStopRunnable = Runnable {
        if (!isFinishing) {
            stopRinging()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge, like MainActivity: the bars sit on the ringer's own background, so the icon colours the theme
        // sets can be read. Without it Android 8 drew a black status bar, and a light theme's dark icons vanished in it.
        enableEdgeToEdge()
        active = this

        // Show over the lock screen and wake the display, like a real alarm clock.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        startRinging()
        // Don't ring forever — auto-stop after a few minutes like a real alarm clock.
        autoStopHandler.postDelayed(autoStopRunnable, 5 * 60 * 1000L)

        val prefs = getSharedPreferences("medreview_settings", MODE_PRIVATE)
        val lang = prefs.getString("app_language", "en") ?: "en"
        val isFa = lang == "fa"
        val isDe = lang == "de"
        // This screen is its own activity, outside MainActivity's theming, so it applies the user's
        // appearance itself. It used to take the theme defaults: it ignored an in-app light/dark choice
        // and the accent, and set Persian text in Manrope, which has no Arabic-script glyphs.
        val themeMode = prefs.getString("theme_mode", "system") ?: "system"
        val accent = (prefs.getString("accent_color", "") ?: "").takeIf { it.isNotBlank() }?.let {
            runCatching { androidx.compose.ui.graphics.Color(it.toColorInt()) }.getOrNull()
        }

        setContent {
            val dark = when (themeMode) {
                "light" -> false
                "dark" -> true
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            MyApplicationTheme(darkTheme = dark, accent = accent, languageCode = lang) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = if (isFa) "زمان مرور" else if (isDe) "Zeit zum Wiederholen" else "Time to review",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = if (isFa) "مباحثی برای مرور آماده‌اند." else if (isDe) "Themen sind bereit zur Wiederholung." else "Some topics are ready for review.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(48.dp))
                        Button(
                            onClick = { openReview() },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        ) { Text(if (isFa) "شروع مرور" else if (isDe) "Jetzt wiederholen" else "Review now", fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { stopAndFinish() },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        ) { Text(if (isFa) "بستن" else if (isDe) "Stopp" else "Dismiss") }
                    }
                }
            }
        }
    }

    private fun startRinging() {
        val sp = getSharedPreferences("medreview_settings", MODE_PRIVATE)
        // Respect the user's sound toggle: sound off + alarm mode on = a silent, vibrating alarm.
        if (sp.getBoolean("sound_enabled", true)) runCatching {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                // Play on the ALARM stream so it's audible even if media volume is down.
                runCatching {
                    audioAttributes = android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                play()
            }
        }
        runCatching {
            if (!sp.getBoolean("vibration_enabled", true)) return@runCatching
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            val effect = VibrationEffect.createWaveform(longArrayOf(0, 600, 800), 0) // minSdk 26: always available
            // An ALARM vibration, like the tone: without a usage Android files it under media, so the phone's alarm-
            // vibration setting and Do Not Disturb's alarm exception did not apply to it. (It did vibrate on the owner's
            // Samsung on silent, 2026-10-02; "scale: 0.00" in dumpsys there means unscaled, not muted.)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator?.vibrate(effect, android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(
                    effect,
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
            }
        }
    }

    private fun stopRinging() {
        runCatching { ringtone?.stop() }
        runCatching { vibrator?.cancel() }
        // Clear only OUR reminder notification (not the user's other notifications) after dismiss/review.
        runCatching { (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(NotificationScheduler.NOTIFICATION_ID) }
        ringtone = null
        vibrator = null
    }

    private fun openReview() {
        stopRinging()
        runCatching {
            (getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager).requestDismissKeyguard(this, null)
        }
        runCatching {
            startActivity(
                android.content.Intent(this, com.example.MainActivity::class.java).apply {
                    // SINGLE_TOP (not CLEAR_TASK): reuse a running MainActivity via onNewIntent
                    // instead of destroying whatever the user had open.
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("open_review", true)
                }
            )
        }
        finish()
    }

    internal fun stopAndFinish() {
        stopRinging()
        finish()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) seen = true
    }

    override fun onStop() {
        // The user left (Home button, another app): a ringing alarm with no visible Dismiss button is
        // a trap — stop the noise and close. (Skip during rotation, which also passes through onStop.)
        // Only once the ringer has been SEEN: an activity started while the device sleeps is stopped by the system
        // before the display wakes. On Android 8.0 that closed the ringer about 0.4 s after it opened whenever the
        // screen was off, and the screen never woke (an outside emulator audit, 2026-09-30; reproduced on an
        // emulator, 2026-10-02). Until it has been seen, the notification's Dismiss, opening the app and the
        // five-minute auto-stop still end it.
        if (seen && !isChangingConfigurations && !isFinishing) {
            stopRinging()
            finish()
        }
        super.onStop()
    }

    override fun onDestroy() {
        autoStopHandler.removeCallbacks(autoStopRunnable)
        stopRinging()
        if (active === this) active = null
        super.onDestroy()
    }
}
