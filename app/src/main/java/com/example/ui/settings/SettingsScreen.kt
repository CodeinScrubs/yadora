package com.example.ui.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.notifications.NotificationScheduler
import com.example.domain.srs.MedScheduler
import kotlinx.coroutines.launch
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel

/** One requirement row: green check when satisfied, red cross + a fix button when not. */
@Composable
private fun PermissionStatusRow(label: String, granted: Boolean, actionLabel: String, onAction: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.Cancel,
            contentDescription = null,
            tint = if (granted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (!granted) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onLanguageChange: (String) -> Unit = {}, onOpenThemeSettings: () -> Unit = {}) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("medreview_settings", Context.MODE_PRIVATE) }
    
    var dailyReminder by remember { 
        mutableStateOf(sharedPrefs.getBoolean("daily_reminder", true)) 
    }
    var limit by remember { 
        mutableStateOf(sharedPrefs.getFloat("daily_review_limit", 50f)) 
    }
    var language by remember {
        mutableStateOf(sharedPrefs.getString("app_language", "en") ?: "en")
    }
    var reminderHour by remember { mutableStateOf(sharedPrefs.getInt("reminder_hour", 20)) }
    var reminderMinute by remember { mutableStateOf(sharedPrefs.getInt("reminder_minute", 0)) }
    var examName by remember { mutableStateOf(sharedPrefs.getString("exam_name", "") ?: "") }
    var examDate by remember { mutableStateOf(sharedPrefs.getLong("exam_date", 0L)) }
    var showExamDatePicker by remember { mutableStateOf(false) }
    var retention by remember { mutableStateOf(sharedPrefs.getFloat("desired_retention", 0.90f)) }

    val exportScope = rememberCoroutineScope()
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            exportScope.launch {
                val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val json = com.example.data.AnalyticsExporter.buildJson(context)
                        (context.contentResolver.openOutputStream(uri)
                            ?: throw java.io.IOException("Could not open the chosen file for writing"))
                            .use { it.write(json.toByteArray()) }
                    }.isSuccess
                }
                android.widget.Toast.makeText(context, if (ok) "Exported" else "Export failed", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    val strings = com.example.ui.i18n.LocalStrings.current
    val useJalali = com.example.ui.i18n.LocalUseJalali.current

    // Bumped on every ON_RESUME so the permission-status rows recompute after the user returns from
    // system settings (previously they stayed red until the screen was fully reopened).
    var permissionRefresh by remember { mutableStateOf(0) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) permissionRefresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // --- Full backup / restore: a complete JSON snapshot the user can save to a folder and re-import ---
    var pendingImportJson by remember { mutableStateOf<String?>(null) }
    val backupExportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            exportScope.launch {
                val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val json = com.example.data.BackupManager.buildBackupJson(context)
                        (context.contentResolver.openOutputStream(uri)
                            ?: throw java.io.IOException("Could not open the chosen file for writing"))
                            .use { it.write(json.toByteArray()) }
                    }.isSuccess
                }
                android.widget.Toast.makeText(context, if (ok) (if (language == "fa") "پشتیبان ذخیره شد" else "Backup saved") else (if (language == "fa") "ذخیرهٔ پشتیبان ناموفق بود" else "Backup failed"), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    val backupImportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            exportScope.launch {
                val json = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            // Bounded WHILE reading: abort as soon as the cap is crossed, so a huge
                            // file picked by mistake never gets fully loaded into memory first.
                            val cap = 20_000_000
                            val out = java.io.ByteArrayOutputStream()
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = stream.read(buf)
                                if (n < 0) break
                                require(out.size() + n <= cap) { "File too large to be a Yadora backup" }
                                out.write(buf, 0, n)
                            }
                            out.toByteArray().decodeToString()
                        }
                    }.getOrNull()
                }
                if (json.isNullOrBlank()) {
                    android.widget.Toast.makeText(context, if (language == "fa") "خواندن فایل ناموفق بود" else "Couldn't read that file", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    pendingImportJson = json
                }
            }
        }
    }
    if (pendingImportJson != null) {
        AlertDialog(
            onDismissRequest = { pendingImportJson = null },
            title = { Text(if (language == "fa") "بازیابی پشتیبان؟" else "Restore backup?") },
            text = { Text(if (language == "fa") "همهٔ داده‌های فعلی با محتوای این فایل جایگزین می‌شود. این کار قابل بازگشت نیست." else "This replaces ALL your current data with the contents of this file. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    val json = pendingImportJson!!
                    pendingImportJson = null
                    exportScope.launch {
                        val count = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching { com.example.data.BackupManager.restoreFromJson(context, json) }.getOrNull()
                        }
                        if (count != null) {
                            runCatching { com.example.notifications.NotificationScheduler.scheduleDailyReminder(context) }
                            com.example.widget.DueWidgetProvider.updateAll(context)
                            android.widget.Toast.makeText(context, if (language == "fa") "بازیابی شد: $count مبحث" else "Restored $count topics", android.widget.Toast.LENGTH_LONG).show()
                        } else {
                            android.widget.Toast.makeText(context, if (language == "fa") "بازیابی ناموفق بود — فایل نامعتبر" else "Restore failed — invalid backup", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text(if (language == "fa") "بازیابی" else "Restore") }
            },
            dismissButton = { TextButton(onClick = { pendingImportJson = null }) { Text(strings.cancel) } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.settings, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item {
                Text(strings.notifications, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        fun formatTime(hour: Int, minute: Int): String {
                            val m = minute.toString().padStart(2, '0')
                            // Persian convention: 24-hour clock with Persian digits (no AM/PM).
                            if (language == "fa") return com.example.ui.i18n.PersianDate.faDigits("${hour.toString().padStart(2, '0')}:$m")
                            val h = if (hour == 0 || hour == 12) 12 else hour % 12
                            val amPm = if (hour < 12) "AM" else "PM"
                            return "$h:$m $amPm"
                        }
                        Text(strings.dailyReviewReminder, style = MaterialTheme.typography.bodyLarge)
                        Text(if (language == "fa") "ساعت یادآوری: ${formatTime(reminderHour, reminderMinute)}" else "Time: ${formatTime(reminderHour, reminderMinute)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    var expandedTime by remember { mutableStateOf(false) }
                    var expandedMinute by remember { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (dailyReminder) {
                            Box {
                                TextButton(onClick = { expandedTime = true }) {
                                    Text(if (language == "fa") "ساعت" else "Hour")
                                }
                                DropdownMenu(expanded = expandedTime, onDismissRequest = { expandedTime = false }) {
                                    (0..23).forEach { h ->
                                        DropdownMenuItem(text = {
                                            if (language == "fa") {
                                                Text(com.example.ui.i18n.PersianDate.faDigits("${h.toString().padStart(2, '0')}:00"))
                                            } else {
                                                val formatted = if (h == 0 || h == 12) 12 else h % 12
                                                val amPm = if (h < 12) "AM" else "PM"
                                                Text("$formatted:00 $amPm")
                                            }
                                        }, onClick = {
                                            reminderHour = h
                                            sharedPrefs.edit().putInt("reminder_hour", h).apply()
                                            NotificationScheduler.scheduleDailyReminder(context)
                                            expandedTime = false
                                        })
                                    }
                                }
                            }
                            Box {
                                TextButton(onClick = { expandedMinute = true }) {
                                    Text(if (language == "fa") "دقیقه" else "Min")
                                }
                                DropdownMenu(expanded = expandedMinute, onDismissRequest = { expandedMinute = false }) {
                                    (0..55 step 5).forEach { m ->
                                        DropdownMenuItem(text = { Text(m.toString().padStart(2, '0').let { if (language == "fa") com.example.ui.i18n.PersianDate.faDigits(it) else it }) }, onClick = {
                                            reminderMinute = m
                                            sharedPrefs.edit().putInt("reminder_minute", m).apply()
                                            NotificationScheduler.scheduleDailyReminder(context)
                                            expandedMinute = false
                                        })
                                    }
                                }
                            }
                        }
                        Switch(
                            checked = dailyReminder,
                            onCheckedChange = { isChecked ->
                                dailyReminder = isChecked
                                sharedPrefs.edit().putBoolean("daily_reminder", isChecked).apply()
                                if (isChecked) {
                                    NotificationScheduler.scheduleDailyReminder(context)
                                } else {
                                    NotificationScheduler.cancelReminder(context)
                                }
                            }
                        )
                    }
                }

                if (dailyReminder) {
                    // Honest disclosure of the guaranteed second daily slot (10:00 for evening
                    // reminder times, 18:00 for morning ones) — it fires only while topics are due.
                    val secHour = NotificationScheduler.secondaryReminderHour(reminderHour)
                    val secText = "${secHour.toString().padStart(2, '0')}:00"
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        when (strings.languageCode) {
                            "fa" -> "یادآور دومی هم ساعت ${com.example.ui.i18n.PersianDate.faDigits(secText)} می‌آید — فقط وقتی مبحثی منتظر مرور است."
                            "de" -> "Eine zweite Erinnerung kommt um $secText — nur solange Themen zur Wiederholung anstehen."
                            else -> "A second reminder arrives at $secText — only while topics are waiting."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { NotificationScheduler.scheduleTest(context) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (language == "fa") "ارسال یادآوری آزمایشی (حدود ۱ دقیقه)" else "Send a test reminder (~1 min)")
                }
            }

            item {
                Text(
                    if (language == "fa") "مجوزها و راه‌اندازی" else "Permissions & setup",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    if (language == "fa") "برای مطمئن‌ترین یادآوری‌ها، این موارد را فعال نگه دار." else "For the most reliable reminders, keep these enabled.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Recomputed on every ON_RESUME (via permissionRefresh), so granting a permission in
                // system settings shows green the moment the user comes back.
                val notifGranted = remember(permissionRefresh) {
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                }
                // App-level toggle + channel-level toggle: either can silently mute reminders even
                // with the permission granted, so health must check all three layers.
                val appNotifsEnabled = remember(permissionRefresh) {
                    androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
                }
                val channelEnabled = remember(permissionRefresh) { NotificationScheduler.isReminderChannelEnabled(context) }
                val exactGranted = remember(permissionRefresh) { NotificationScheduler.canScheduleExact(context) }
                val batteryOk = remember(permissionRefresh) {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    pm.isIgnoringBatteryOptimizations(context.packageName)
                }
                val fullScreenOk = remember(permissionRefresh) {
                    if (Build.VERSION.SDK_INT >= 34) {
                        (context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).canUseFullScreenIntent()
                    } else true
                }

                PermissionStatusRow(
                    label = if (language == "fa") "نوتیفیکیشن" else "Notifications",
                    granted = notifGranted,
                    actionLabel = if (language == "fa") "فعال‌سازی" else "Enable"
                ) {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                        )
                    }
                }
                PermissionStatusRow(
                    label = if (language == "fa") "نوتیفیکیشن برنامه فعال" else "App notifications on",
                    granted = appNotifsEnabled,
                    actionLabel = if (language == "fa") "تنظیم" else "Fix"
                ) {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                        )
                    }
                }
                PermissionStatusRow(
                    label = if (language == "fa") "کانال یادآوری فعال" else "Reminder channel on",
                    granted = channelEnabled,
                    actionLabel = if (language == "fa") "تنظیم" else "Fix"
                ) {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                        )
                    }
                }
                PermissionStatusRow(
                    label = if (language == "fa") "هشدار دقیق" else "Exact alarms",
                    granted = exactGranted,
                    actionLabel = if (language == "fa") "فعال‌سازی" else "Enable"
                ) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)) }
                    }
                }
                PermissionStatusRow(
                    label = if (language == "fa") "نادیده‌گرفتن بهینه‌سازی باتری" else "Ignore battery optimization",
                    granted = batteryOk,
                    actionLabel = if (language == "fa") "تنظیم" else "Fix"
                ) {
                    runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                }
                PermissionStatusRow(
                    label = if (language == "fa") "زنگ تمام‌صفحه" else "Full-screen alarm",
                    granted = fullScreenOk,
                    actionLabel = if (language == "fa") "فعال‌سازی" else "Enable"
                ) {
                    if (Build.VERSION.SDK_INT >= 34) {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                    android.net.Uri.parse("package:" + context.packageName)
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                // Field-debugging for OEM battery killers: one tap snapshots the entire reminder
                // pipeline state into the event log (shipped inside the analytics export). A silent
                // missed reminder is otherwise indistinguishable from "nothing was due" in the data.
                OutlinedButton(
                    onClick = {
                        exportScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching {
                                val app = context.applicationContext as com.example.MedReviewApplication
                                val snapshot = listOf(
                                    "manufacturer=${android.os.Build.MANUFACTURER}",
                                    "model=${android.os.Build.MODEL}",
                                    "sdk=${Build.VERSION.SDK_INT}",
                                    "notif=$notifGranted",
                                    "exact=$exactGranted",
                                    "batteryIgnored=$batteryOk",
                                    "fullScreen=$fullScreenOk",
                                    "alarmMode=${sharedPrefs.getBoolean("alarm_enabled", false)}",
                                    "reminder=${reminderHour}:${reminderMinute}",
                                    "lastShownAt=${sharedPrefs.getLong("last_notif_shown_at", 0L)}",
                                ).joinToString(" ")
                                app.database.eventLogDao().insert(
                                    com.example.data.local.entity.EventLogEntity(type = "MISSED_REMINDER_REPORT", detail = snapshot)
                                )
                            }
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                android.widget.Toast.makeText(
                                    context,
                                    if (language == "fa") "ثبت شد — همراه خروجی تحلیلی ارسال می‌شود." else "Recorded — it ships with the analytics export.",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (language == "fa") "گزارش یادآوریِ ازدست‌رفته" else "Report a missed reminder")
                }
            }

            item {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                    !NotificationScheduler.canScheduleExact(context)
                ) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = if (language == "fa") "برای یادآوری دقیق و سر وقت، اجازه «هشدارها و یادآوری‌ها» را بدهید." else "For reliable, on-time reminders, allow exact alarms.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(onClick = {
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                            .setData(android.net.Uri.parse("package:" + context.packageName))
                                    )
                                }
                            }) {
                                Text(if (language == "fa") "فعال‌سازی هشدار دقیق" else "Enable exact alarms")
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = if (language == "fa") "قابلیت اطمینان یادآوری" else "Reminder reliability",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (language == "fa") "برخی گوشی‌ها (شیائومی، هواوی، اوپو، ویوو، سامسونگ) برنامه‌های پس‌زمینه را به‌شدت متوقف می‌کنند و ممکن است یادآوری‌ها قطع شوند. برای اطمینان: بهینه‌سازی باتری را برای این برنامه خاموش کنید و در صورت وجود، Autostart را روشن کنید." else "Some phones (Xiaomi, Huawei, Oppo, Vivo, Samsung) aggressively stop background apps, which can silence reminders. To be safe: turn OFF battery optimization for Yadora, and enable Autostart if your phone has it.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            }
                        }) {
                            Text(if (language == "fa") "تنظیمات باتری" else "Battery settings")
                        }
                    }
                }
            }

            item {
                HorizontalDivider()
                Spacer(modifier = Modifier.height(24.dp))
                Text(strings.appearanceRegion, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(strings.language, style = MaterialTheme.typography.bodyLarge)
                    var langExpanded by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { langExpanded = true }) {
                            Text(when (language) { "fa" -> strings.persianLanguage; "de" -> strings.germanLanguage; else -> strings.englishLanguage })
                        }
                        DropdownMenu(expanded = langExpanded, onDismissRequest = { langExpanded = false }) {
                            DropdownMenuItem(text = { Text(strings.englishLanguage) }, onClick = {
                                language = "en"
                                sharedPrefs.edit().putString("app_language", "en").apply()
                                langExpanded = false
                                onLanguageChange(language)
                            })
                            DropdownMenuItem(text = { Text(strings.persianLanguage) }, onClick = {
                                language = "fa"
                                sharedPrefs.edit().putString("app_language", "fa").apply()
                                langExpanded = false
                                onLanguageChange(language)
                            })
                            DropdownMenuItem(text = { Text(strings.germanLanguage) }, onClick = {
                                language = "de"
                                sharedPrefs.edit().putString("app_language", "de").apply()
                                langExpanded = false
                                onLanguageChange(language)
                            })
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (language == "fa") "تم و رنگ‌ها" else "Theme & colors", style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { onOpenThemeSettings() }) {
                        Text(if (language == "fa") "ویرایش" else "Customize")
                    }
                }

                // Calendar format — independent of language, so a user can keep English text with
                // Jalali dates (or Persian text with Gregorian). "Auto" follows the language.
                var calendarFormat by remember { mutableStateOf(sharedPrefs.getString("calendar_format", "auto") ?: "auto") }
                var calExpanded by remember { mutableStateOf(false) }
                fun calLabel(v: String) = when (v) {
                    "jalali" -> if (language == "fa") "شمسی (جلالی)" else "Jalali (Solar)"
                    "gregorian" -> if (language == "fa") "میلادی" else "Gregorian"
                    else -> if (language == "fa") "خودکار (بر اساس زبان)" else "Auto (follow language)"
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (language == "fa") "تقویم" else "Calendar", style = MaterialTheme.typography.bodyLarge)
                    Box {
                        TextButton(onClick = { calExpanded = true }) { Text(calLabel(calendarFormat)) }
                        DropdownMenu(expanded = calExpanded, onDismissRequest = { calExpanded = false }) {
                            listOf("auto", "jalali", "gregorian").forEach { v ->
                                DropdownMenuItem(text = { Text(calLabel(v)) }, onClick = {
                                    calendarFormat = v
                                    sharedPrefs.edit().putString("calendar_format", v).apply()
                                    calExpanded = false
                                })
                            }
                        }
                    }
                }
            }
            
            item {
                HorizontalDivider()
                Spacer(modifier = Modifier.height(24.dp))
                Text(strings.soundVibration, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(16.dp))
                
                var soundEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("sound_enabled", true)) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(strings.reminderSound, style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = soundEnabled,
                        onCheckedChange = { 
                            soundEnabled = it
                            sharedPrefs.edit().putBoolean("sound_enabled", it).apply()
                            // Re-create notification channel if needed
                            NotificationScheduler.createNotificationChannel(context)
                        }
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                var vibrationEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("vibration_enabled", true)) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(strings.vibration, style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = vibrationEnabled,
                        onCheckedChange = {
                            vibrationEnabled = it
                            sharedPrefs.edit().putBoolean("vibration_enabled", it).apply()
                            NotificationScheduler.createNotificationChannel(context)
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                var alarmEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("alarm_enabled", false)) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(if (language == "fa") "زنگ مثل ساعت زنگ‌دار" else "Ring like an alarm clock", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (language == "fa") "هنگام مرور، تمام‌صفحه و با صدای آلارم زنگ می‌زند (نیازمند مجوز «زنگ تمام‌صفحه»)." else "Rings full-screen with an alarm tone at review time (needs the full-screen alarm permission above).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = alarmEnabled,
                        onCheckedChange = {
                            alarmEnabled = it
                            sharedPrefs.edit().putBoolean("alarm_enabled", it).apply()
                            if (it) NotificationScheduler.createAlarmChannel(context)
                        }
                    )
                }
            }
            
            item {
                HorizontalDivider()
                Spacer(modifier = Modifier.height(24.dp))
                Text(strings.algorithmControl, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(16.dp))
                
                // Read-only indicator (no fake toggle): the app uses one validated model.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(strings.spacedRepAlgorithm, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "FSRS-5",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    strings.algorithmDesc.format("FSRS-5"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            item {
                HorizontalDivider()
                Spacer(modifier = Modifier.height(24.dp))
                Text(strings.limitsConstraints, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(strings.dailyReviewLimit, style = MaterialTheme.typography.bodyLarge)
                    Text(if (language == "fa") com.example.ui.i18n.PersianDate.faDigits(limit.toInt()) else "${limit.toInt()}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = limit,
                    onValueChange = { value -> limit = value },
                    onValueChangeFinished = {
                        // Persist once when the drag settles, not on every pixel frame.
                        sharedPrefs.edit().putFloat("daily_review_limit", limit).apply()
                    },
                    valueRange = 10f..200f,
                    steps = 18,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            
            item {
                HorizontalDivider()
                Spacer(modifier = Modifier.height(24.dp))
                
                var expandedGuide by remember { mutableStateOf(true) }
                val isFarsi = language == "fa"
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)),
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Info, 
                                    contentDescription = "Spaced Repetiton Guide",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isFarsi) "راهنمای متد تکرار با فاصله" else "Spaced Repetition Guide",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            TextButton(onClick = { expandedGuide = !expandedGuide }) {
                                Text(if (expandedGuide) (if (isFarsi) "بستن" else "Hide") else (if (isFarsi) "مشاهده" else "Show"))
                            }
                        }
                        
                        if (expandedGuide) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = if (isFarsi) {
                                    "تکرار با فاصله زمان هر مرور را طوری تخمین می‌زند که یادآوری‌ات نزدیک هدف انتخابی‌ات بماند — با مطالعهٔ کمتر، ماندگاری بیشتر. چند نکته:"
                                } else {
                                    "Spaced repetition estimates review times to keep your recall near your chosen target - more retention for less total study. A few tips:"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            // Tip 1: Active Recall First
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = if (isFarsi) "۱. ابتدا یادآوری فعال کنید" else "1. Active Recall First",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (isFarsi) {
                                            "قبل از کلیک روی 'نمایش یادداشت‌ها'، سعی کنید پاسخ را از ذهن خود بیرون بکشید. تقلا برای بازیابی اطلاعات، ارتباطات سیناپسی مغز را تقویت می‌کند."
                                        } else {
                                            "Force yourself to retrieve the answer from memory before tapping 'Show Notes'. The active struggle of retrieval is what forms robust memories."
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            
                            // Tip 2: Honest Ratings
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = if (isFarsi) "۲. درجه‌بندی صادقانه" else "2. Match Ratings Honestly",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (isFarsi) {
                                            "• فراموشی: مبحث فوراً فردا یا زودتر تکرار خواهد شد.\n• سخت: مکرراً و با فواصل کوتاه‌تر مرور می‌شود.\n• خوب: حالت ایده‌آل؛ فواصل افزایش می‌یابند.\n• آسان: فواصل بسیار طولانی خواهند شد."
                                        } else {
                                            "• Forgot (Fail): Reschedules unit immediately for tomorrow to relearn.\n• Hard: Limits interval growth because retrieval required high effort.\n• Good: The perfect baseline; increases intervals optimally.\n• Easy: Extends interval significantly into the future."
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            // Tip 3: Burnout Backlog Relief
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = if (isFarsi) "۳. مقابله با خستگی مباحث عقب‌افتاده" else "3. Relieve Burnout & Backlogs",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (isFarsi) {
                                            "یادگیری جدی یک ماراتن است، نه دو سرعت. اگر انبوهی از مرورهای عقب‌افتاده دارید، از دکمه 'توزیع مجدد مباحث' در صفحه امروز استفاده کنید تا مرورها را در ۳ روز آینده پخش کند."
                                        } else {
                                            "Serious learning is a marathon, not a sprint. If you fall behind, use the 'Spread Out Overdue Topics' feature on the Today screen to redistribute your backlog evenly over 3 days."
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
            
            item {
                HorizontalDivider()
                Spacer(modifier = Modifier.height(24.dp))
                Text(if (language == "fa") "هدف به‌خاطرسپاری" else "Retention target", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (language == "fa") "احتمال به‌خاطرسپاری هدف" else "Target recall", style = MaterialTheme.typography.bodyLarge)
                    Text(if (language == "fa") "٪${com.example.ui.i18n.PersianDate.faDigits((retention * 100).toInt())}" else "${(retention * 100).toInt()}%", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = retention,
                    onValueChange = {
                        retention = it
                        MedScheduler.userRetention = it.toDouble()
                    },
                    onValueChangeFinished = {
                        sharedPrefs.edit().putFloat("desired_retention", retention).apply()
                    },
                    valueRange = 0.85f..0.95f,
                    steps = 9,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = if (language == "fa") "بالاتر = مرور بیشتر و فراموشی کمتر. پایین‌تر = کار کمتر. روی مرورهای آینده اثر می‌گذارد؛ برنامهٔ فعلی مباحث تغییر نمی‌کند." else "Higher = more frequent reviews, less forgetting. Lower = less workload. Applies to future reviews — already-scheduled dates don't move.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                HorizontalDivider()
                Spacer(modifier = Modifier.height(24.dp))
                Text(if (language == "fa") "شمارش معکوس آزمون" else "Exam countdown", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(4.dp))
                // Honest scope: countdown only. Exam-aware schedule compression is a future feature —
                // the UI must not imply it exists.
                Text(
                    if (language == "fa") "روی صفحهٔ امروز نمایش داده می‌شود؛ فعلاً برنامهٔ مرورها را تغییر نمی‌دهد." else "Shown on the Today screen — it doesn't change your review schedule yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = examName,
                    onValueChange = { examName = it; sharedPrefs.edit().putString("exam_name", it).apply() },
                    label = { Text(if (language == "fa") "نام آزمون" else "Exam name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (examDate <= 0L) (if (language == "fa") "بدون تاریخ" else "No date set")
                               else com.example.ui.i18n.AppDate.date(useJalali, examDate),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { showExamDatePicker = true }) { Text(if (language == "fa") "تنظیم تاریخ" else "Set date") }
                        if (examDate > 0L) {
                            TextButton(onClick = {
                                examName = ""; examDate = 0L
                                sharedPrefs.edit().remove("exam_name").remove("exam_date").apply()
                            }) { Text(if (language == "fa") "پاک کردن" else "Clear") }
                        }
                    }
                }
            }

            item {
                HorizontalDivider()
                Spacer(modifier = Modifier.height(24.dp))
                Text(if (language == "fa") "داده‌ها" else "Data", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (language == "fa") "یک فایل پشتیبان کامل (همراه عنوان‌ها و یادداشت‌ها) بساز و جایی امن ذخیره کن. هر زمان می‌توانی آن را بازیابی کنی." else "Make a full backup (including titles & notes) and save it somewhere safe. You can restore it any time.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { backupExportLauncher.launch("yadora_backup.json") }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (language == "fa") "ساخت فایل پشتیبان" else "Export full backup")
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = { backupImportLauncher.launch(arrayOf("application/json")) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (language == "fa") "بازیابی از فایل پشتیبان" else "Import backup")
                }
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = if (language == "fa") "خروجی تحلیلی برای بهبود الگوریتم (نه پشتیبان‌گیری): بدون عنوان و یادداشت مباحث، اما شامل نام درس‌ها/مجموعه‌ها و مشخصات دستگاه." else "Analytics export for tuning the algorithm (not a backup): no topic titles or notes, but it does include your subject/collection names and device model.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = { exportLauncher.launch("yadora_analytics.json") }, modifier = Modifier.fillMaxWidth()) {
                    Text(when (language) { "fa" -> "استخراج تحلیل‌ها / لاگ‌ها"; "de" -> "Analysen / Logs extrahieren"; else -> "Extract analytics / logs" })
                }
            }

            item {
                HorizontalDivider()
                Spacer(modifier = Modifier.height(24.dp))

                val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(strings.appName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (language == "fa") "ساخته‌شده برای یادگیرندگان جدی" else "Built for serious learners",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            (if (language == "fa") "نسخهٔ " else "Version ") + com.example.BuildConfig.VERSION_NAME,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            if (language == "fa") "ساخته‌شده توسط دکتر شایان صالحی‌راد" else "Built by Dr. Shayan Salehirad",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        // Opens the Telegram app if installed (it claims telegram.me links), otherwise
                        // the browser — so it works for everyone.
                        Text(
                            text = if (language == "fa") "ارتباط با ما در تلگرام: @shayan_salehirad" else "Contact us on Telegram: @shayan_salehirad",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .clickable { runCatching { uriHandler.openUri("https://telegram.me/shayan_salehirad") } }
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        // Email: opens the user's mail app with our address prefilled.
                        val emailContext = androidx.compose.ui.platform.LocalContext.current
                        Text(
                            text = when (language) { "fa" -> "ارتباط با ما از طریق ایمیل"; "de" -> "Kontaktiere uns per E-Mail"; else -> "Contact us via email" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .clickable {
                                    runCatching {
                                        emailContext.startActivity(
                                            android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                                                data = android.net.Uri.parse("mailto:shayanay80@gmail.com")
                                                putExtra(android.content.Intent.EXTRA_SUBJECT, "Yadora")
                                            }
                                        )
                                    }
                                }
                        )

                        Spacer(modifier = Modifier.height(14.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.15f))
                        Spacer(modifier = Modifier.height(12.dp))
                        // Motivational close.
                        Text(
                            text = if (language == "fa")
                                "یک ساعت فکر کن تا ده دقیقه کار کنی؛ نه اینکه ده دقیقه فکر کنی تا یک ساعت کار کنی."
                            else
                                "Think for an hour to work for ten minutes. Don't think for ten minutes to work for an hour.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        if (showExamDatePicker && useJalali) {
            // Jalali-calendar users pick the exam date on a real Jalali (شمسی) grid.
            com.example.ui.components.JalaliDatePickerDialog(
                initialMillis = if (examDate > 0L) examDate else System.currentTimeMillis(),
                onDismiss = { showExamDatePicker = false },
                onConfirm = { millis ->
                    examDate = millis
                    sharedPrefs.edit().putLong("exam_date", millis).apply()
                    showExamDatePicker = false
                },
            )
        } else if (showExamDatePicker) {
            val examPickerState = rememberDatePickerState(initialSelectedDateMillis = if (examDate > 0L) examDate else System.currentTimeMillis())
            DatePickerDialog(
                onDismissRequest = { showExamDatePicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        examPickerState.selectedDateMillis?.let { utc ->
                            // DatePicker reports UTC midnight; pin it to local 9am on that calendar day so
                            // the countdown (computed in local time) doesn't drift by a day.
                            val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = utc }
                            val local = java.util.Calendar.getInstance().apply {
                                set(java.util.Calendar.YEAR, c.get(java.util.Calendar.YEAR))
                                set(java.util.Calendar.MONTH, c.get(java.util.Calendar.MONTH))
                                set(java.util.Calendar.DAY_OF_MONTH, c.get(java.util.Calendar.DAY_OF_MONTH))
                                set(java.util.Calendar.HOUR_OF_DAY, 9)
                                set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                            }.timeInMillis
                            examDate = local
                            sharedPrefs.edit().putLong("exam_date", local).apply()
                        }
                        showExamDatePicker = false
                    }) { Text(if (language == "fa") "تأیید" else "OK") }
                },
                dismissButton = { TextButton(onClick = { showExamDatePicker = false }) { Text(if (language == "fa") "لغو" else "Cancel") } }
            ) { DatePicker(state = examPickerState) }
        }
    }
}
