package com.example.notifications

import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
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
    private val autoStopHandler by lazy { android.os.Handler(mainLooper) }
    private val autoStopRunnable = Runnable {
        if (!isFinishing) {
            stopRinging()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        val isFa = (getSharedPreferences("medreview_settings", MODE_PRIVATE)
            .getString("app_language", "en") ?: "en") == "fa"

        setContent {
            MyApplicationTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = if (isFa) "زمان مرور" else "Time to review",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = if (isFa) "مباحثی برای مرور آماده‌اند." else "You have study topics ready to review.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(48.dp))
                        Button(
                            onClick = { openReview() },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        ) { Text(if (isFa) "شروع مرور" else "Review now", fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { stopAndFinish() },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        ) { Text(if (isFa) "بستن" else "Dismiss") }
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
            val pattern = longArrayOf(0, 600, 800)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION") vibrator?.vibrate(pattern, 0)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                (getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager).requestDismissKeyguard(this, null)
            }
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

    override fun onStop() {
        // The user left (Home button, another app): a ringing alarm with no visible Dismiss button is
        // a trap — stop the noise and close. (Skip during rotation, which also passes through onStop.)
        if (!isChangingConfigurations && !isFinishing) {
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
