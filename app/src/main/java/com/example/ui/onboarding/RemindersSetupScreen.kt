package com.example.ui.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.notifications.NotificationScheduler
import com.example.ui.i18n.LocalStrings

/**
 * Onboarding step 2: explain the two permissions that make reminders dependable, and let the user
 * grant them in context. Both are optional — Continue is always available — and both stay reachable
 * from Settings. See [RemindersSetupPolicy] for when this is shown and why.
 */
@Composable
fun RemindersSetupScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val strings = LocalStrings.current
    fun t(en: String, fa: String, de: String) = when (strings.languageCode) { "fa" -> fa; "de" -> de; else -> en }

    // areNotificationsEnabled covers every Android version: below 13 there is no runtime permission,
    // but the user can still have switched Yadora's notifications off in system settings.
    fun notificationsOn() = NotificationManagerCompat.from(context).areNotificationsEnabled()

    var notificationsGranted by remember { mutableStateOf(notificationsOn()) }
    var exactGranted by remember { mutableStateOf(NotificationScheduler.canScheduleExact(context)) }
    // Android only shows the notification dialog a limited number of times. After the user has been
    // asked once, the button opens system settings instead of silently doing nothing.
    var askedForNotifications by rememberSaveable { mutableStateOf(false) }

    // The exact-alarm grant happens on a separate system screen; re-read both when the user returns.
    LifecycleResumeEffect(Unit) {
        notificationsGranted = notificationsOn()
        exactGranted = NotificationScheduler.canScheduleExact(context)
        onPauseOrDispose { }
    }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        askedForNotifications = true
        notificationsGranted = notificationsOn()
    }

    // Onboarding runs outside the app's Scaffolds, so the screen paints its own background. The XML
    // window theme is dark: without this, a phone in light mode showed light cards and grey text on a
    // dark window.
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                t("Reminders", "یادآورها", "Erinnerungen"),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                t(
                    "Yadora tells you when a topic is due for review. Two permissions keep those reminders dependable. Both are optional, and you can change them later in Settings.",
                    "یادورا به تو خبر می‌دهد کِی وقت مرور یک مبحث است. دو اجازه این یادآورها را قابل‌اعتماد می‌کنند. هر دو اختیاری‌اند و بعداً هم از تنظیمات قابل تغییرند.",
                    "Yadora sagt dir, wann ein Thema zur Wiederholung fällig ist. Zwei Berechtigungen machen diese Erinnerungen verlässlich. Beide sind optional und später in den Einstellungen änderbar.",
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(28.dp))

            PermissionItem(
                title = t("Notifications", "نوتیفیکیشن‌ها", "Benachrichtigungen"),
                body = t(
                    "So a reminder can appear at all.",
                    "تا یادآور اصلاً بتواند نمایش داده شود.",
                    "Damit eine Erinnerung überhaupt erscheinen kann.",
                ),
                granted = notificationsGranted,
                grantedLabel = t("Allowed", "مجاز است", "Erlaubt"),
                actionLabel = t("Allow", "اجازه بده", "Erlauben"),
                onAction = {
                    val canAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !askedForNotifications &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    if (canAsk) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                            )
                        }
                    }
                },
            )
            Spacer(modifier = Modifier.height(12.dp))
            PermissionItem(
                title = t("On-time reminders", "یادآوری سر وقت", "Pünktliche Erinnerungen"),
                body = t(
                    "Without this, Android may deliver a reminder up to an hour late.",
                    "بدون این، اندروید ممکن است یادآور را تا یک ساعت دیرتر نشان دهد.",
                    "Ohne diese Berechtigung kann Android eine Erinnerung bis zu einer Stunde später zustellen.",
                ),
                granted = exactGranted,
                grantedLabel = t("Allowed", "مجاز است", "Erlaubt"),
                actionLabel = t("Allow", "اجازه بده", "Erlauben"),
                onAction = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        runCatching { context.startActivity(NotificationScheduler.exactAlarmSettingsIntent(context)) }
                    }
                },
            )

            Spacer(modifier = Modifier.height(36.dp))
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                // Not strings.continueBtn: that label is bilingual on purpose, for the language screen,
                // where no language has been chosen yet. By this step one has.
                Text(t("Continue", "ادامه", "Weiter"))
            }
        }
    }
}

@Composable
private fun PermissionItem(
    title: String,
    body: String,
    granted: Boolean,
    grantedLabel: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.width(12.dp))
            if (granted) {
                Text(grantedLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            } else {
                FilledTonalButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}
