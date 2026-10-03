package com.example.ui.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.net.toUri
import com.example.notifications.NotificationScheduler
import com.example.domain.srs.MedScheduler
import kotlinx.coroutines.launch
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Warning

/**
 * Writes a file the user just created with the system picker, and deletes it if the write fails part-way: a
 * truncated backup under the name the user chose looks like a good one until the day it is needed. Not
 * cancellable, so leaving Settings mid-write can neither cut the file short nor skip the cleanup.
 */
private suspend fun writePickedDocument(
    context: Context,
    uri: android.net.Uri,
    write: suspend (java.io.OutputStream) -> Unit,
): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
    val ok = runCatching {
        (context.contentResolver.openOutputStream(uri)
            ?: throw java.io.IOException("Could not open the chosen file for writing"))
            .use { write(it) }
    }.onFailure { android.util.Log.w("Yadora", "writing the chosen file failed", it) }.isSuccess
    if (!ok) runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
    ok
}

/**
 * Automatic backup ([com.example.data.AutoBackup]): a daily full backup into a folder the learner chose.
 * [refresh] changes on every ON_RESUME, so the status is re-read after a trip to another app.
 */
@Composable
private fun AutoBackupCard(language: String, useJalali: Boolean, refresh: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val prefs = remember { NotificationScheduler.transientPrefs(context) }
    val on = remember(tick, refresh) { com.example.data.AutoBackup.isOn(context) }
    val folder = remember(tick, refresh) { if (on) com.example.data.AutoBackup.folderName(context) else null }
    val lastOkAt = remember(tick, refresh) { prefs.getLong(com.example.data.AutoBackup.PREF_LAST_OK_AT, 0L) }
    val lastErrorAt = remember(tick, refresh) { prefs.getLong(com.example.data.AutoBackup.PREF_LAST_ERROR_AT, 0L) }
    fun t(fa: String, de: String, en: String) = when (language) { "fa" -> fa; "de" -> de; else -> en }

    fun backUpNow() {
        busy = true
        scope.launch {
            try {
                val outcome = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                    com.example.data.AutoBackup.runNow(context, force = true)
                }
                val msg = when (outcome) {
                    is com.example.data.AutoBackup.Outcome.Written -> t("پشتیبان ذخیره شد", "Sicherung gespeichert", "Backup saved")
                    is com.example.data.AutoBackup.Outcome.Skipped -> t("چیزی برای پشتیبان‌گیری نیست", "Nichts zu sichern", "Nothing to back up yet")
                    is com.example.data.AutoBackup.Outcome.Failed -> t("پشتیبان‌گیری ناموفق بود", "Sicherung fehlgeschlagen", "Backup failed")
                }
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            } finally {
                busy = false
                tick++
            }
        }
    }

    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val ok = runCatching {
                // A new folder replaces the old one: release the old permission first.
                if (com.example.data.AutoBackup.isOn(context)) com.example.data.AutoBackup.disable(context)
                com.example.data.AutoBackup.enable(context, uri)
            }.isSuccess
            tick++
            if (ok) backUpNow() else android.widget.Toast.makeText(
                context, t("این پوشه قابل استفاده نیست", "Dieser Ordner lässt sich nicht verwenden", "That folder can't be used"),
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(t("پشتیبان‌گیری خودکار", "Automatische Sicherung", "Automatic backup"),
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(6.dp))
            if (!on) {
                Text(
                    t(
                        "تاریخچهٔ مطالعه‌ات فقط روی همین گوشی است. یک پوشه انتخاب کن تا یادورا هر روز یک پشتیبان کامل آنجا ذخیره کند. پشتیبان‌های هفتهٔ اخیر و یکی از هر ماه نگه داشته می‌شوند. اگر پوشه‌ای را انتخاب کنی که یک برنامهٔ ابری (مثل گوگل‌درایو) همگامش می‌کند، با گم شدن گوشی هم چیزی از دست نمی‌رود.",
                        "Dein Lernverlauf liegt nur auf diesem Handy. Wähle einen Ordner, und Yadora legt dort jeden Tag eine vollständige Sicherung ab. Die Sicherungen der letzten Woche und je eine pro Monat bleiben erhalten. Ein Ordner, den eine Cloud-App synchronisiert (z. B. Google Drive), schützt dich auch, wenn das Handy verloren geht.",
                        "Your study history lives only on this phone. Choose a folder and Yadora saves a full backup there every day, keeping the last week's backups and one from each month. A folder a cloud app syncs (Google Drive, for example) also protects you if the phone is lost.",
                    ),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(onClick = { runCatching { picker.launch(null) } }, modifier = Modifier.fillMaxWidth()) {
                    Text(t("انتخاب پوشه", "Ordner wählen", "Choose a folder"))
                }
            } else {
                val where = folder ?: "…"
                Text(
                    t("روشن: هر روز در پوشهٔ «$where».", "An: täglich in „$where“.", "On: every day, in “$where”."),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    if (lastOkAt > 0L) t("آخرین پشتیبان: ", "Letzte Sicherung: ", "Last backup: ") +
                        com.example.ui.i18n.AppDate.dateTime(useJalali, lastOkAt, language == "fa")
                    else t("هنوز پشتیبانی ساخته نشده.", "Noch keine Sicherung.", "No backup yet."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Only a real failed run warns: some storage providers cannot report a folder's name even
                // when writing into it works.
                if (lastErrorAt > lastOkAt) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        t(
                            "آخرین پشتیبان‌گیری ناموفق بود. اگر پوشه جابه‌جا یا پاک شده، دوباره انتخابش کن.",
                            "Die letzte Sicherung ist fehlgeschlagen. Wähle den Ordner erneut, falls er verschoben oder gelöscht wurde.",
                            "The last backup failed. If the folder was moved or deleted, choose it again.",
                        ),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { backUpNow() }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text(t("همین حالا", "Jetzt sichern", "Back up now"), maxLines = 1)
                    }
                    OutlinedButton(onClick = { runCatching { picker.launch(null) } }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text(t("تغییر پوشه", "Ordner ändern", "Change folder"), maxLines = 1)
                    }
                }
                TextButton(onClick = { com.example.data.AutoBackup.disable(context); tick++ }, enabled = !busy) {
                    Text(t("خاموش کردن پشتیبان‌گیری خودکار", "Automatische Sicherung ausschalten", "Turn off automatic backup"))
                }
            }
        }
    }
}

/**
 * A settings row that toggles as a whole: the label and the switch are one control, so TalkBack reads the label with
 * the state and the whole row is the touch target. The switches used to be bare, unlabelled nodes ("NAF" on the
 * owner's Samsung, 2026-10-02): a screen reader announced "switch, on" with no name (an outside audit, 2026-09-30).
 */
@Composable
private fun SwitchRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onCheckedChange),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
        Switch(checked = checked, onCheckedChange = null)
    }
}

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

/**
 * What the restore picker offers. The content decides whether a file is a backup (restore validates all of it), so the
 * label a file provider happens to give it must not: on Android 8 a backup copied from a computer came up as
 * application/octet-stream and could not be selected at all (an outside emulator audit, 2026-09-30).
 */
internal val BACKUP_PICKER_TYPES = arrayOf("application/json", "application/octet-stream", "text/*")

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
    var showExamDatePicker by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var retention by remember { mutableStateOf(sharedPrefs.getFloat("desired_retention", 0.90f)) }

    val exportScope = rememberCoroutineScope()
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            exportScope.launch {
                val ok = writePickedDocument(context, uri) { com.example.data.AnalyticsExporter.writeJson(context, it) }
                android.widget.Toast.makeText(context, if (ok) (when (language) { "fa" -> "خروجی ذخیره شد"; "de" -> "Exportiert"; else -> "Exported" }) else (when (language) { "fa" -> "خروجی ناموفق بود"; "de" -> "Export fehlgeschlagen"; else -> "Export failed" }), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    val strings = com.example.ui.i18n.LocalStrings.current
    val useJalali = com.example.ui.i18n.LocalUseJalali.current

    // Bumped on every ON_RESUME so the permission-status rows recompute after the user returns from
    // system settings (previously they stayed red until the screen was fully reopened).
    var permissionRefresh by remember { mutableStateOf(0) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) permissionRefresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // --- Full backup / restore: a complete JSON snapshot the user can save to a folder and re-import ---
    // The picked file, restored only after the user confirms. Read by streaming at that point: holding a
    // multi-year backup as one String here (and again while parsing it) is what ran restores out of memory.
    // Saveable: turning the phone used to drop the chosen file and close "Delete all data?" (2026-09-30 audit).
    var pendingImportUri by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<android.net.Uri?>(null) }
    var showDeleteAll by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val backupExportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            exportScope.launch {
                val ok = writePickedDocument(context, uri) { com.example.data.BackupManager.writeBackup(context, it) }
                // A backup made by hand quiets Today's automatic-backup suggestion for a month.
                if (ok) NotificationScheduler.transientPrefs(context).edit {
                    putLong(com.example.data.AutoBackup.PREF_LAST_MANUAL_AT, System.currentTimeMillis())
                }
                android.widget.Toast.makeText(context, if (ok) (if (language == "fa") "پشتیبان ذخیره شد" else if (language == "de") "Sicherung gespeichert" else "Backup saved") else (if (language == "fa") "ذخیرهٔ پشتیبان ناموفق بود" else if (language == "de") "Sicherung fehlgeschlagen" else "Backup failed"), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    val backupImportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) pendingImportUri = uri
    }
    if (pendingImportUri != null) {
        AlertDialog(
            onDismissRequest = { pendingImportUri = null },
            title = { Text(if (language == "fa") "بازیابی پشتیبان؟" else if (language == "de") "Sicherung wiederherstellen?" else "Restore backup?") },
            text = { Text(if (language == "fa") "همهٔ داده‌های فعلی با محتوای این فایل جایگزین می‌شود. این کار قابل بازگشت نیست." else if (language == "de") "Das ersetzt ALLE aktuellen Daten durch den Inhalt dieser Datei. Das lässt sich nicht rückgängig machen." else "This replaces ALL your current data with the contents of this file. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    val uri = pendingImportUri ?: return@TextButton
                    pendingImportUri = null
                    exportScope.launch {
                        // Streamed from the file, validated in full before anything is replaced, and not
                        // cancellable half-way: a restore either completes or never touches the data. The
                        // reminder re-arm and the widget refresh belong to the restore, so they run inside the
                        // same block: leaving Settings mid-restore cancels this scope, and a cancelled
                        // withContext throws on return, which used to skip them and leave the reminders armed
                        // for the old data and the widget showing it.
                        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                            val restored = runCatching {
                                com.example.data.BackupManager.openPicked(context, uri)
                                    .use { com.example.data.BackupManager.restoreFromStream(context, it) }
                            }
                            if (restored.isSuccess) {
                                runCatching { com.example.notifications.NotificationScheduler.scheduleDailyReminder(context) }
                                runCatching { com.example.widget.DueWidgetProvider.updateAll(context) }
                            }
                            restored
                        }
                        val count = result.getOrNull()
                        if (count != null) {
                            android.widget.Toast.makeText(context, if (language == "fa") "بازیابی شد: ${com.example.ui.i18n.PersianDate.faDigits(count)} مبحث" else if (language == "de") "$count Themen wiederhergestellt" else "Restored $count topics", android.widget.Toast.LENGTH_LONG).show()
                            // The file can carry another language, theme and settings. They are in the preferences now, but
                            // the screens read them when they open, so the app kept its old look until it was restarted
                            // (an outside emulator audit, 2026-09-30). Rebuilding the activity applies them at once.
                            context.findActivity()?.recreate()
                        } else {
                            // A file that could not be OPENED (a revoked permission, a provider that is gone) is not the same
                            // failure as a file that is not a valid backup. Only the open is classed as unreadable: the reader
                            // reports a corrupt or truncated file as an IOException too, and that one is "invalid backup".
                            val unreadable = result.exceptionOrNull() is com.example.data.BackupManager.UnreadableFile
                            android.util.Log.w("Yadora", "restore failed", result.exceptionOrNull())
                            android.widget.Toast.makeText(
                                context,
                                if (unreadable) when (language) {
                                    "fa" -> "فایل باز نشد — دوباره انتخابش کن یا اول آن را در حافظهٔ گوشی ذخیره کن."
                                    "de" -> "Die Datei ließ sich nicht öffnen — wähle sie erneut oder speichere sie zuerst auf dem Telefon."
                                    else -> "Couldn't open that file — pick it again, or save it to the phone first."
                                } else if (language == "fa") "بازیابی ناموفق بود — فایل نامعتبر" else if (language == "de") "Wiederherstellung fehlgeschlagen — ungültige Sicherung" else "Restore failed — invalid backup",
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }) { Text(if (language == "fa") "بازیابی" else if (language == "de") "Wiederherstellen" else "Restore") }
            },
            dismissButton = { TextButton(onClick = { pendingImportUri = null }) { Text(strings.cancel) } }
        )
    }

    if (showDeleteAll) {
        AlertDialog(
            onDismissRequest = { showDeleteAll = false },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(when (language) { "fa" -> "حذف کامل داده‌ها؟"; "de" -> "Alle Daten löschen?"; else -> "Delete all data?" }) },
            text = {
                Text(when (language) {
                    "fa" -> "همهٔ درس‌ها، مباحث، تاریخچهٔ مرور و تنظیمات برای همیشه پاک می‌شوند. این کار قابل بازگشت نیست. اگر پشتیبان نگرفته‌ای، اکنون لغو کن و اول پشتیبان بگیر."
                    "de" -> "Alle Fächer, Themen, der Verlauf und die Einstellungen werden dauerhaft gelöscht. Das lässt sich nicht rückgängig machen. Ohne Sicherung: jetzt abbrechen und zuerst sichern."
                    else -> "Every subject, topic, review history, and setting will be permanently erased. This can't be undone. If you haven't made a backup, cancel now and back up first."
                })
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteAll = false
                        exportScope.launch {
                            // The result decides the message. Announcing "all data deleted" after a
                            // failed wipe is the worst possible lie this screen can tell: the user
                            // believes their data is gone -- possibly hands the phone on -- when it
                            // is still there, and they never retry.
                            val wiped = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                runCatching { com.example.data.BackupManager.deleteAllData(context) }
                            }
                            android.widget.Toast.makeText(
                                context,
                                if (wiped.isSuccess)
                                    when (language) { "fa" -> "همهٔ داده‌ها حذف شد"; "de" -> "Alle Daten gelöscht"; else -> "All data deleted" }
                                else
                                    when (language) { "fa" -> "حذف کامل نشد — داده‌ها هنوز روی دستگاه هستند. دوباره تلاش کن."; "de" -> "Löschen fehlgeschlagen — die Daten sind noch auf dem Gerät. Bitte erneut versuchen."; else -> "Delete failed — your data is still on this device. Please try again." },
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            if (wiped.isSuccess) {
                                onBack()
                                // The settings were reset with the data; rebuild so the look follows at once, not after a restart.
                                context.findActivity()?.recreate()
                            }
                        }
                    }
                ) { Text(when (language) { "fa" -> "حذف همه"; "de" -> "Alles löschen"; else -> "Delete everything" }, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteAll = false }) { Text(strings.cancel) } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.settings, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = when (language) { "fa" -> "بازگشت"; "de" -> "Zurück"; else -> "Back" })
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Edge-to-edge: keep the exam-name field above the keyboard (see AddUnitScreen).
                .consumeWindowInsets(padding)
                .imePadding(),
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
                    // Weighted: at 320 dp the unweighted title pushed the switch past the edge and the minute button
                    // under its neighbour (an outside emulator audit, 2026-09-30).
                    Column(modifier = Modifier.weight(1f)) {
                        fun formatTime(hour: Int, minute: Int): String {
                            val m = minute.toString().padStart(2, '0')
                            // Persian convention: 24-hour clock with Persian digits (no AM/PM).
                            if (language == "fa") return com.example.ui.i18n.PersianDate.faDigits("${hour.toString().padStart(2, '0')}:$m")
                            val h = if (hour == 0 || hour == 12) 12 else hour % 12
                            val amPm = if (hour < 12) "AM" else "PM"
                            return "$h:$m $amPm"
                        }
                        Text(strings.dailyReviewReminder, style = MaterialTheme.typography.bodyLarge)
                        Text(if (language == "fa") "ساعت یادآوری: ${formatTime(reminderHour, reminderMinute)}" else if (language == "de") "Uhrzeit: ${formatTime(reminderHour, reminderMinute)}" else "Time: ${formatTime(reminderHour, reminderMinute)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    var expandedTime by remember { mutableStateOf(false) }
                    var expandedMinute by remember { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (dailyReminder) {
                            Box {
                                TextButton(onClick = { expandedTime = true }) {
                                    Text(if (language == "fa") "ساعت" else if (language == "de") "Stunde" else "Hour")
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
                                            sharedPrefs.edit { putInt("reminder_hour", h) }
                                            NotificationScheduler.scheduleDailyReminder(context)
                                            expandedTime = false
                                        })
                                    }
                                }
                            }
                            Box {
                                TextButton(onClick = { expandedMinute = true }) {
                                    Text(if (language == "fa") "دقیقه" else if (language == "de") "Min." else "Min")
                                }
                                DropdownMenu(expanded = expandedMinute, onDismissRequest = { expandedMinute = false }) {
                                    (0..55 step 5).forEach { m ->
                                        DropdownMenuItem(text = { Text(m.toString().padStart(2, '0').let { if (language == "fa") com.example.ui.i18n.PersianDate.faDigits(it) else it }) }, onClick = {
                                            reminderMinute = m
                                            sharedPrefs.edit { putInt("reminder_minute", m) }
                                            NotificationScheduler.scheduleDailyReminder(context)
                                            expandedMinute = false
                                        })
                                    }
                                }
                            }
                        }
                        Switch(
                            modifier = Modifier.semantics { contentDescription = strings.dailyReviewReminder },
                            checked = dailyReminder,
                            onCheckedChange = { isChecked ->
                                dailyReminder = isChecked
                                sharedPrefs.edit { putBoolean("daily_reminder", isChecked) }
                                if (isChecked) {
                                    NotificationScheduler.scheduleDailyReminder(context)
                                } else {
                                    NotificationScheduler.cancelReminder(context)
                                }
                                // The pilot reads days without a reminder as a phone problem unless it can see they were off.
                                exportScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    runCatching {
                                        (context.applicationContext as com.example.MedReviewApplication).database.eventLogDao().insert(
                                            com.example.data.local.entity.EventLogEntity(type = if (isChecked) "REMINDERS_ON" else "REMINDERS_OFF")
                                        )
                                    }
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
                    Text(if (language == "fa") "ارسال یادآوری آزمایشی (حدود ۱ دقیقه)" else if (language == "de") "Test-Erinnerung senden (~1 Min.)" else "Send a test reminder (~1 min)")
                }
            }

            item {
                Text(
                    if (language == "fa") "مجوزها و راه‌اندازی" else if (language == "de") "Berechtigungen & Einrichtung" else "Permissions & setup",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    if (language == "fa") "برای مطمئن‌ترین یادآوری‌ها، این موارد را فعال نگه دار." else if (language == "de") "Für zuverlässige Erinnerungen lass diese aktiviert." else "For the most reliable reminders, keep these enabled.",
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
                    label = if (language == "fa") "نوتیفیکیشن" else if (language == "de") "Benachrichtigungen" else "Notifications",
                    granted = notifGranted,
                    actionLabel = if (language == "fa") "فعال‌سازی" else if (language == "de") "Aktivieren" else "Enable"
                ) {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                        )
                    }
                }
                PermissionStatusRow(
                    label = if (language == "fa") "نوتیفیکیشن برنامه فعال" else if (language == "de") "App-Benachrichtigungen an" else "App notifications on",
                    granted = appNotifsEnabled,
                    actionLabel = if (language == "fa") "تنظیم" else if (language == "de") "Beheben" else "Fix"
                ) {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                        )
                    }
                }
                PermissionStatusRow(
                    label = if (language == "fa") "کانال یادآوری فعال" else if (language == "de") "Erinnerungskanal an" else "Reminder channel on",
                    granted = channelEnabled,
                    actionLabel = if (language == "fa") "تنظیم" else if (language == "de") "Beheben" else "Fix"
                ) {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                        )
                    }
                }
                PermissionStatusRow(
                    label = if (language == "fa") "هشدار دقیق" else if (language == "de") "Exakte Alarme" else "Exact alarms",
                    granted = exactGranted,
                    actionLabel = if (language == "fa") "فعال‌سازی" else if (language == "de") "Aktivieren" else "Enable"
                ) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        // Shared helper: this call used to omit the package URI, so some devices opened
                        // the list of every app instead of Yadora's own toggle.
                        runCatching { context.startActivity(NotificationScheduler.exactAlarmSettingsIntent(context)) }
                    }
                }
                PermissionStatusRow(
                    label = if (language == "fa") "نادیده‌گرفتن بهینه‌سازی باتری" else if (language == "de") "Akku-Optimierung ignorieren" else "Ignore battery optimization",
                    granted = batteryOk,
                    actionLabel = if (language == "fa") "تنظیم" else if (language == "de") "Beheben" else "Fix"
                ) {
                    runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                }
                PermissionStatusRow(
                    label = if (language == "fa") "زنگ تمام‌صفحه" else if (language == "de") "Vollbild-Alarm" else "Full-screen alarm",
                    granted = fullScreenOk,
                    actionLabel = if (language == "fa") "فعال‌سازی" else if (language == "de") "Aktivieren" else "Enable"
                ) {
                    if (Build.VERSION.SDK_INT >= 34) {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                    "package:${context.packageName}".toUri()
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
                                    "lastShownAt=${NotificationScheduler.transientPrefs(context).getLong(NotificationScheduler.PREF_LAST_SHOWN_AT, 0L)}",
                                ).joinToString(" ")
                                app.database.eventLogDao().insert(
                                    com.example.data.local.entity.EventLogEntity(type = "MISSED_REMINDER_REPORT", detail = snapshot)
                                )
                            }
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                android.widget.Toast.makeText(
                                    context,
                                    if (language == "fa") "ثبت شد — همراه خروجی تحلیلی ارسال می‌شود." else if (language == "de") "Erfasst — es wird mit dem Analyse-Export mitgeliefert." else "Recorded — it ships with the analytics export.",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (language == "fa") "گزارش یادآوریِ ازدست‌رفته" else if (language == "de") "Verpasste Erinnerung melden" else "Report a missed reminder")
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
                                text = if (language == "fa") "برای یادآوری دقیق و سر وقت، اجازه «هشدارها و یادآوری‌ها» را بدهید." else if (language == "de") "Für pünktliche Erinnerungen erlaube exakte Alarme." else "For reliable, on-time reminders, allow exact alarms.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(onClick = {
                                runCatching { context.startActivity(NotificationScheduler.exactAlarmSettingsIntent(context)) }
                            }) {
                                Text(if (language == "fa") "فعال‌سازی هشدار دقیق" else if (language == "de") "Exakte Alarme aktivieren" else "Enable exact alarms")
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
                            text = if (language == "fa") "قابلیت اطمینان یادآوری" else if (language == "de") "Zuverlässigkeit der Erinnerungen" else "Reminder reliability",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (language == "fa") "برخی گوشی‌ها (شیائومی، هواوی، اوپو، ویوو، سامسونگ) برنامه‌های پس‌زمینه را به‌شدت متوقف می‌کنند و ممکن است یادآوری‌ها قطع شوند. برای اطمینان، بهینه‌سازی باتری را برای یادورا خاموش کن و اگر گوشی‌ات Autostart دارد، روشنش کن." else if (language == "de") "Manche Handys (Xiaomi, Huawei, Oppo, Vivo, Samsung) stoppen Hintergrund-Apps aggressiv, was Erinnerungen stummschalten kann. Zur Sicherheit: Schalte die Akku-Optimierung für Yadora AUS und aktiviere Autostart, falls vorhanden." else "Some phones (Xiaomi, Huawei, Oppo, Vivo, Samsung) aggressively stop background apps, which can silence reminders. To be safe: turn OFF battery optimization for Yadora, and enable Autostart if your phone has it.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            }
                        }) {
                            Text(if (language == "fa") "تنظیمات باتری" else if (language == "de") "Akku-Einstellungen" else "Battery settings")
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
                                sharedPrefs.edit { putString("app_language", "en") }
                                langExpanded = false
                                onLanguageChange(language)
                            })
                            DropdownMenuItem(text = { Text(strings.persianLanguage) }, onClick = {
                                language = "fa"
                                sharedPrefs.edit { putString("app_language", "fa") }
                                langExpanded = false
                                onLanguageChange(language)
                            })
                            DropdownMenuItem(text = { Text(strings.germanLanguage) }, onClick = {
                                language = "de"
                                sharedPrefs.edit { putString("app_language", "de") }
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
                    Text(if (language == "fa") "تم و رنگ‌ها" else if (language == "de") "Design & Farben" else "Theme & colors", style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { onOpenThemeSettings() }) {
                        Text(if (language == "fa") "ویرایش" else if (language == "de") "Anpassen" else "Customize")
                    }
                }

                // Calendar format — independent of language, so a user can keep English text with
                // Jalali dates (or Persian text with Gregorian). "Auto" follows the language.
                var calendarFormat by remember { mutableStateOf(sharedPrefs.getString("calendar_format", "auto") ?: "auto") }
                var calExpanded by remember { mutableStateOf(false) }
                fun calLabel(v: String) = when (v) {
                    "jalali" -> if (language == "fa") "شمسی (جلالی)" else if (language == "de") "Dschalali (Sonnenkalender)" else "Jalali (Solar)"
                    "gregorian" -> if (language == "fa") "میلادی" else if (language == "de") "Gregorianisch" else "Gregorian"
                    else -> if (language == "fa") "خودکار (بر اساس زبان)" else if (language == "de") "Automatisch (nach Sprache)" else "Auto (follow language)"
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (language == "fa") "تقویم" else if (language == "de") "Kalender" else "Calendar", style = MaterialTheme.typography.bodyLarge)
                    Box {
                        TextButton(onClick = { calExpanded = true }) { Text(calLabel(calendarFormat)) }
                        DropdownMenu(expanded = calExpanded, onDismissRequest = { calExpanded = false }) {
                            listOf("auto", "jalali", "gregorian").forEach { v ->
                                DropdownMenuItem(text = { Text(calLabel(v)) }, onClick = {
                                    calendarFormat = v
                                    sharedPrefs.edit { putString("calendar_format", v) }
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
                SwitchRow(checked = soundEnabled, onCheckedChange = {
                    soundEnabled = it
                    sharedPrefs.edit { putBoolean("sound_enabled", it) }
                    // Re-create notification channel if needed
                    NotificationScheduler.createNotificationChannel(context)
                }) {
                    Text(strings.reminderSound, style = MaterialTheme.typography.bodyLarge)
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                var vibrationEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("vibration_enabled", true)) }
                SwitchRow(checked = vibrationEnabled, onCheckedChange = {
                    vibrationEnabled = it
                    sharedPrefs.edit { putBoolean("vibration_enabled", it) }
                    NotificationScheduler.createNotificationChannel(context)
                }) {
                    Text(strings.vibration, style = MaterialTheme.typography.bodyLarge)
                }

                Spacer(modifier = Modifier.height(8.dp))
                var alarmEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("alarm_enabled", false)) }
                SwitchRow(checked = alarmEnabled, onCheckedChange = {
                    alarmEnabled = it
                    sharedPrefs.edit { putBoolean("alarm_enabled", it) }
                    if (it) NotificationScheduler.createAlarmChannel(context)
                }) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(if (language == "fa") "زنگ مثل ساعت زنگ‌دار" else if (language == "de") "Wie ein Wecker klingeln" else "Ring like an alarm clock", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (language == "fa") "هنگام مرور، تمام‌صفحه و با صدای آلارم زنگ می‌زند (نیازمند مجوز «زنگ تمام‌صفحه»)." else if (language == "de") "Klingelt im Vollbild mit Weckerton zur Wiederholungszeit (braucht die Vollbild-Berechtigung oben)." else "Rings full-screen with an alarm tone at review time (needs the full-screen alarm permission above).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Kill switch for the ringing itself. Deliberately separate from the toggle above so
                // silencing alarms for a while (a lecture, a night shift, a sick day) doesn't make the
                // user rebuild their alarm setup afterwards — and never silences the notifications,
                // which are what actually protect the review streak.
                if (alarmEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    var alarmSilenced by remember { mutableStateOf(sharedPrefs.getBoolean("alarm_silenced", false)) }
                    SwitchRow(checked = alarmSilenced, onCheckedChange = {
                        alarmSilenced = it
                        sharedPrefs.edit { putBoolean("alarm_silenced", it) }
                        // Stop anything ringing right now, so the switch takes effect instantly.
                        if (it) runCatching { com.example.notifications.AlarmRingActivity.dismissActive() }
                    }) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                when (language) {
                                    "fa" -> "بی‌صدا کردن زنگ‌ها"
                                    "de" -> "Alarme stummschalten"
                                    else -> "Silence alarms"
                                },
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                when (language) {
                                    "fa" -> "هیچ زنگ تمام‌صفحه‌ای پخش نمی‌شود. اعلان‌های یادآوری مثل همیشه می‌آیند و تنظیمات زنگ حفظ می‌شود."
                                    "de" -> "Kein Vollbild-Alarm klingelt mehr. Die Erinnerungs-Benachrichtigungen kommen weiterhin, und deine Alarm-Einstellungen bleiben erhalten."
                                    else -> "No full-screen alarm will ring. Reminder notifications still arrive as usual, and your alarm setup is kept."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
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
                        // Read from the scheduler, never hardcoded -- this line told every user the
                        // app was running FSRS-5 for as long as FSRS-6 had been live.
                        com.example.domain.srs.MedScheduler.CURRENT_MODEL.id,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    strings.algorithmDesc.format(com.example.domain.srs.MedScheduler.CURRENT_MODEL.id),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))
                // The personal memory model (PersonalModelWorker). On by default. Switching it off retires the
                // fitted weight set at once; nothing is refitted or adopted until it is switched on again.
                var personalModel by remember {
                    mutableStateOf(sharedPrefs.getBoolean(com.example.data.PersonalModelWorker.PREF_ENABLED, true))
                }
                SwitchRow(checked = personalModel, onCheckedChange = { on ->
                    personalModel = on
                    sharedPrefs.edit { putBoolean(com.example.data.PersonalModelWorker.PREF_ENABLED, on) }
                    if (!on) exportScope.launch {
                        runCatching { (context.applicationContext as com.example.MedReviewApplication).repository.useDefaultMemoryModel() }
                    } else {
                        // Back on: try a fit now. The daily refit waits for 20% more evidence or 30
                        // days after the last attempt — which was the set just retired — so without
                        // this a learner who toggled it off and on waited up to a month for nothing.
                        com.example.data.PersonalModelWorker.refitNow(context)
                    }
                }) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            when (language) { "fa" -> "تطبیق مدل با مرورهای من"; "de" -> "Modell an meine Wiederholungen anpassen"; else -> "Fit the model to my reviews" },
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            when (language) {
                                "fa" -> "وقتی مرورهای کافی جمع شود، FSRS-6 بر مرورهای خودت برازش می‌شود و فقط اگر مرورهای بعدی‌ات را بهتر پیش‌بینی کند و فاصله‌هایت را طولانی‌تر نکند به کار می‌رود."
                                "de" -> "Sobald genug Wiederholungen vorliegen, wird FSRS-6 an deine eigenen angepasst und nur verwendet, wenn es deine späteren Wiederholungen besser vorhersagt und deine Abstände nicht verlängert."
                                else -> "Once enough reviews exist, FSRS-6 is fitted to your own and used only if it predicts your later reviews better and does not lengthen your intervals."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            item {
                HorizontalDivider()
                Spacer(modifier = Modifier.height(24.dp))
                Text(strings.limitsConstraints, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(strings.dailyReviewLimit, style = MaterialTheme.typography.bodyLarge)
                    Text(if (language == "fa") com.example.ui.i18n.PersianDate.faDigits(Math.round(limit)) else "${Math.round(limit)}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = limit,
                    // Stored as the whole number shown. The slider interpolates in Float, so the 70 and
                    // 130 stops arrive as 69.99999 and 129.99998, which truncation turned into 69 and 129.
                    onValueChange = { value -> limit = Math.round(value).toFloat() },
                    onValueChangeFinished = {
                        // Persist once when the drag settles, not on every pixel frame.
                        sharedPrefs.edit { putFloat("daily_review_limit", limit) }
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
                                    contentDescription = when (language) { "fa" -> "راهنمای تکرار با فاصله"; "de" -> "Anleitung zur verteilten Wiederholung"; else -> "Spaced Repetition Guide" },
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = when (language) { "fa" -> "راهنمای متد تکرار با فاصله"; "de" -> "Leitfaden: Verteiltes Lernen"; else -> "Spaced Repetition Guide" },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            TextButton(onClick = { expandedGuide = !expandedGuide }) {
                                Text(if (expandedGuide) when (language) { "fa" -> "بستن"; "de" -> "Ausblenden"; else -> "Hide" } else when (language) { "fa" -> "مشاهده"; "de" -> "Anzeigen"; else -> "Show" })
                            }
                        }
                        
                        if (expandedGuide) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = when (language) {
                                    "fa" -> "تکرار با فاصله زمان هر مرور را طوری تخمین می‌زند که یادآوری‌ات نزدیک هدف انتخابی‌ات بماند — با مطالعهٔ کمتر، ماندگاری بیشتر. چند نکته:"
                                    "de" -> "Verteiltes Lernen schätzt den Zeitpunkt jeder Wiederholung so, dass dein Abruf nahe an deinem gewählten Ziel bleibt — mehr Behalten bei weniger Lernzeit. Ein paar Tipps:"
                                    else -> "Spaced repetition estimates review times to keep your recall near your chosen target - more retention for less total study. A few tips:"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            // Tip 1: Review your way. A review is whatever the learner chooses; the tip says which
                            // methods tend to stick, without making the app a flashcard tool.
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = when (language) { "fa" -> "۱. به روش خودت مرور کن"; "de" -> "1. Wiederhole auf deine Art"; else -> "1. Review Your Way" },
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = when (language) {
                                            "fa" -> "هر روشی حساب است: دوباره‌خواندن، تست، کلاس یا ویدیو. تست زدن یا توضیح دادن مطلب از حفظ معمولاً بیشتر از فقط دوباره‌خواندن در ذهن می‌ماند؛ اگر وقت کم است، اول تست بزن."
                                            "de" -> "Jede Methode zählt: nachlesen, Fragen, Vorlesung oder Video. Fragen beantworten oder den Stoff aus dem Kopf erklären bleibt meist besser hängen als bloßes Nachlesen — bei wenig Zeit zuerst Fragen."
                                            else -> "Any method counts: rereading, questions, a lecture or a video. Doing questions or explaining it from memory usually sticks better than rereading alone — when time is short, do questions first."
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
                                        text = when (language) { "fa" -> "۲. درجه‌بندی صادقانه"; "de" -> "2. Ehrlich bewerten"; else -> "2. Match Ratings Honestly" },
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = when (language) {
                                            // Behaviourally anchored: these describe what the ANSWER
                                            // was like, not how the app reacts. Calling Good "the
                                            // perfect baseline" biased the very signal FSRS consumes
                                            // — the rating is the measurement, so the help text must
                                            // not tell the user which button is the good one.
                                            "fa" -> "بگو وقتی دوباره سراغ مبحث آمدی — پیش از دوباره‌خواندن یا دیدن جواب‌ها — چقدر از آن یادت بود؛ نه اینکه جلسهٔ مرور چقدر سخت گذشت. این وصف حافظهٔ توست، نه خودت. سخت، خوب و آسان یعنی هنوز یادت بود؛ فقط فراموشی یعنی از دست رفته بود.\n• فراموشی: بیشترش را فراموش کرده بودم؛ مثل از نو خواندن بود.\n• سخت: اصلش یادم بود، ولی با جاهای خالی جدی یا زحمت زیاد.\n• خوب: بیشترش یادم بود، فقط خلأهای جزئی.\n• آسان: کامل و مسلط بودم؛ چیز تازه‌ای نبود.\nاگر تست زدی، نتیجهٔ تست‌ها بهترین راهنمای توست."
                                            "de" -> "Bewerte, wie viel du noch wusstest, als du wieder damit angefangen hast — bevor du nachgelesen oder Lösungen angesehen hast —, nicht wie mühsam die Sitzung war. Das beschreibt dein Gedächtnis, nicht dich. Schwer, Gut und Leicht heißen: noch da; nur Vergessen heißt: weg.\n• Vergessen: Das meiste war weg; es war wie neu lernen.\n• Schwer: Der Kern war da, aber mit echten Lücken oder viel Mühe.\n• Gut: Das meiste wusste ich noch, nur kleine Lücken.\n• Leicht: Sicher und vollständig; nichts war neu.\nWenn du Fragen beantwortet hast, ist dein Ergebnis der beste Anhaltspunkt."
                                            else -> "Rate how much you still knew when you came back to the topic — before rereading or checking answers — not how hard the session felt. It describes your memory, not you. Hard, Good and Easy all mean it was still there; only Forgot means it was gone.\n• Forgot: most of it was gone; it felt like learning it again.\n• Hard: the core was there, but with real gaps or a lot of effort.\n• Good: I remembered most of it, with only small gaps.\n• Easy: I knew it thoroughly; nothing felt new.\nIf you did questions, your score is the best guide."
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
                                        text = when (language) { "fa" -> "۳. مقابله با خستگی مباحث عقب‌افتاده"; "de" -> "3. Rückstand abbauen, ohne auszubrennen"; else -> "3. Relieve Burnout & Backlogs" },
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = when (language) {
                                            "fa" -> "یادگیری جدی یک ماراتن است، نه دو سرعت. اگر عقب‌افتاده‌ها از سقف روزانه‌ات بیشتر شوند، صفحهٔ امروز دکمهٔ «توزیع مجدد و پخش مباحث عقب‌افتاده» را نشان می‌دهد: مهم‌ترین‌ها در جای خالی امروز می‌مانند و بقیه بر اساس سقف روزانه‌ات در روزهای بعد پخش می‌شوند، حداکثر تا دو هفته. اگر عقب‌افتادگی از دو هفته بیشتر باشد، هر روز بیش از سقفت می‌شود؛ آن‌وقت سقف را بالاتر ببر یا کمتر مبحث تازه اضافه کن."
                                            "de" -> "Ernsthaftes Lernen ist ein Marathon, kein Sprint. Ist mehr überfällig als dein Tageslimit, bietet die Heute-Seite „Überfällige Themen verteilen“ an: Die wichtigsten bleiben im heute noch freien Platz, der Rest wird nach deinem Tageslimit auf die folgenden Tage verteilt, höchstens auf zwei Wochen. Ist der Rückstand größer, liegt jeder dieser Tage über deinem Limit; dann hilft ein höheres Limit oder weniger neue Themen."
                                            else -> "Serious learning is a marathon, not a sprint. When more is overdue than your daily limit, Today offers 'Spread Out Overdue Topics': the most important stay in what is left of today, and the rest are spread over the following days at your daily limit, for at most two weeks. A backlog bigger than that puts more than your limit on each of those days; then raise the limit or add fewer new topics for a while."
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
                Text(if (language == "fa") "هدف به‌خاطرسپاری" else if (language == "de") "Behaltensziel" else "Retention target", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (language == "fa") "احتمال به‌خاطرسپاری هدف" else if (language == "de") "Ziel-Erinnerungsquote" else "Target recall", style = MaterialTheme.typography.bodyLarge)
                    Text(if (language == "fa") "٪${com.example.ui.i18n.PersianDate.faDigits(Math.round(retention * 100))}" else "${Math.round(retention * 100)}%", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = retention,
                    onValueChange = {
                        // Snapped to the whole percent the label shows: the slider interpolates in Float,
                        // so a stop can land a hair below its value (under the old range 92% arrived as
                        // 0.9199999 and was shown as 91%).
                        retention = Math.round(it * 100) / 100f
                        MedScheduler.userRetention = retention.toDouble()
                    },
                    onValueChangeFinished = {
                        sharedPrefs.edit { putFloat("desired_retention", retention) }
                    },
                    // 0.97 is the ceiling the Important bump already uses; past it the workload roughly
                    // doubles again for a point of recall, which no longer buys readiness.
                    valueRange = 0.85f..0.97f,
                    steps = 11,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = if (language == "fa") "بالاتر = مرور بیشتر و فراموشی کمتر. پایین‌تر = کار کمتر. روی مرورهای آینده اثر می‌گذارد؛ برنامهٔ فعلی مباحث تغییر نمی‌کند." else if (language == "de") "Höher = häufigere Wiederholungen, weniger Vergessen. Niedriger = weniger Aufwand. Gilt für künftige Wiederholungen — bereits geplante Termine verschieben sich nicht." else "Higher = more frequent reviews, less forgetting. Lower = less workload. Applies to future reviews — already-scheduled dates don't move.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                HorizontalDivider()
                Spacer(modifier = Modifier.height(24.dp))
                Text(if (language == "fa") "شمارش معکوس آزمون" else if (language == "de") "Prüfungs-Countdown" else "Exam countdown", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(4.dp))
                // Honest scope: countdown only. The exam date is decorative BY DESIGN (settled decision):
                // it never compresses intervals, so the copy must not promise that it will one day.
                Text(
                    if (language == "fa") "فقط شمارش معکوس روی صفحه‌ها نشان داده می‌شود؛ برنامهٔ مرورها را تغییر نمی‌دهد. برای آمادگی امتحان: هدف به‌خاطرسپاری را روی ۹۰٪ نگه دار و در چهار هفتهٔ آخر، بعد از مرورهای هر روز، «مرور جلوتر از برنامه» را در صفحهٔ امروز بزن (ضعیف‌ترین مباحث اول). در شبیه‌سازی دوساله، این کار تقریباً همهٔ مباحث را روز امتحان به ۹۰٪ یا بیشتر رساند. بالا بردن هدف به‌جای آن، مرور بیشتری خواست و نتیجهٔ کمتری داشت." else if (language == "de") "Nur ein Countdown auf den Bildschirmen — er ändert deinen Wiederholungsplan nicht. Für die Prüfung: Lass das Behaltensziel bei 90 % und nutze in den letzten vier Wochen nach den fälligen Wiederholungen auf „Heute“ „Vorausarbeiten“ (die schwächsten Themen zuerst). In einer Zwei-Jahres-Simulation brachte das fast jedes Thema am Prüfungstag auf 90 % oder mehr. Ein höheres Ziel stattdessen kostete mehr Wiederholungen und brachte weniger." else "Only a countdown on the screens — it doesn't change your review schedule. For the exam: keep the retention target at 90% and, in the last four weeks, after each day's reviews, use Review ahead on Today (weakest topics first). In a two-year simulation that brought nearly every topic to 90% or more on exam day. Raising the target instead cost more reviews and did less.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = examName,
                    onValueChange = { examName = it; sharedPrefs.edit { putString("exam_name", it) } },
                    label = { Text(if (language == "fa") "نام آزمون" else if (language == "de") "Name der Prüfung" else "Exam name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (examDate <= 0L) (if (language == "fa") "بدون تاریخ" else if (language == "de") "Kein Datum gesetzt" else "No date set")
                               else com.example.ui.i18n.AppDate.date(useJalali, examDate, language == "fa"),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { showExamDatePicker = true }) { Text(if (language == "fa") "تنظیم تاریخ" else if (language == "de") "Datum wählen" else "Set date") }
                        if (examDate > 0L) {
                            TextButton(onClick = {
                                examName = ""; examDate = 0L
                                sharedPrefs.edit { remove("exam_name").remove("exam_date") }
                            }) { Text(if (language == "fa") "پاک کردن" else if (language == "de") "Löschen" else "Clear") }
                        }
                    }
                }
            }

            item {
                HorizontalDivider()
                Spacer(modifier = Modifier.height(24.dp))
                Text(if (language == "fa") "داده‌ها" else if (language == "de") "Daten" else "Data", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))
                AutoBackupCard(language = language, useJalali = useJalali, refresh = permissionRefresh)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = if (language == "fa") "یک فایل پشتیبان کامل (همراه عنوان‌ها و یادداشت‌ها) بساز و جایی امن ذخیره کن. فایل رمزگذاری نشده است. هر زمان می‌توانی آن را بازیابی کنی." else if (language == "de") "Erstelle eine vollständige Sicherung (mit Titeln & Notizen). Die Datei ist unverschlüsselt — bewahre sie sicher auf. Wiederherstellen jederzeit möglich." else "Make a full backup (including titles & notes). The file is unencrypted — save it somewhere safe. You can restore it any time.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { backupExportLauncher.launch("yadora_backup.json") }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (language == "fa") "ساخت فایل پشتیبان" else if (language == "de") "Vollständige Sicherung exportieren" else "Export full backup")
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = { backupImportLauncher.launch(BACKUP_PICKER_TYPES) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (language == "fa") "بازیابی از فایل پشتیبان" else if (language == "de") "Sicherung importieren" else "Import backup")
                }
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = if (language == "fa") "دادهٔ پژوهشی برای بهبود الگوریتم (نه پشتیبان‌گیری): بدون عنوان و یادداشت مباحث، اما شامل نام درس‌ها/مجموعه‌ها و مشخصات دستگاه." else if (language == "de") "Forschungsdaten zur Verbesserung des Algorithmus (keine Sicherung): ohne Thementitel oder Notizen, aber mit deinen Fachnamen und dem Gerätemodell." else "Research data for tuning the algorithm (not a backup): no topic titles or notes, but it does include your subject/collection names and device model.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // The pilot: a pseudonymous id (no name, no account) so several people's files can be pooled,
                // and a one-tap share to whatever app the file should go through (a chat, e-mail, a drive).
                val researchId = remember { com.example.data.ResearchId.get(context) }
                // One export at a time: a second tap while the first is still writing used to race on the
                // same file, and the app receiving the share could read a file being rewritten under it.
                var sharing by remember { mutableStateOf(false) }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = when (language) {
                        "fa" -> "شناسهٔ پژوهشی تو: $researchId — تصادفی است و به اسم یا گوشی‌ات ربطی ندارد."
                        "de" -> "Deine Forschungs-ID: $researchId — zufällig, ohne Bezug zu deinem Namen oder Gerät."
                        else -> "Your research ID: $researchId — random, not linked to your name or phone."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    enabled = !sharing,
                    onClick = {
                        sharing = true
                        exportScope.launch {
                          try {
                            val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                runCatching { com.example.data.AnalyticsExporter.writeShareableFile(context) }.getOrNull()
                            }
                            val shared = file != null && runCatching {
                                val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "application/json"
                                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                    putExtra(android.content.Intent.EXTRA_SUBJECT, "Yadora research data $researchId")
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(
                                    android.content.Intent.createChooser(
                                        send,
                                        when (language) { "fa" -> "ارسال دادهٔ پژوهشی"; "de" -> "Forschungsdaten senden"; else -> "Send research data" },
                                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }.isSuccess
                            if (!shared) {
                                android.widget.Toast.makeText(
                                    context,
                                    when (language) { "fa" -> "ارسال ناموفق بود"; "de" -> "Senden fehlgeschlagen"; else -> "Couldn't share the file" },
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                          } finally {
                            sharing = false
                          }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(when (language) { "fa" -> "ارسال دادهٔ پژوهشی"; "de" -> "Forschungsdaten senden"; else -> "Share research data" })
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = { exportLauncher.launch(com.example.data.AnalyticsExporter.fileName(context)) }, modifier = Modifier.fillMaxWidth()) {
                    Text(when (language) { "fa" -> "ذخیرهٔ دادهٔ پژوهشی در فایل"; "de" -> "Forschungsdaten als Datei speichern"; else -> "Save research data to a file" })
                }

                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = when (language) {
                        "fa" -> "حذف کامل داده‌ها: همهٔ درس‌ها، مباحث، تاریخچه و تنظیمات را برای همیشه پاک می‌کند. اگر می‌خواهی چیزی بماند، اول پشتیبان بگیر."
                        "de" -> "Alle Daten löschen: entfernt dauerhaft alle Fächer, Themen, den Verlauf und die Einstellungen. Erstelle zuerst eine Sicherung, wenn du etwas behalten möchtest."
                        else -> "Delete all data: permanently removes every subject, topic, your history, and settings. Make a backup first if you want to keep anything."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showDeleteAll = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                ) {
                    Text(when (language) { "fa" -> "حذف کامل داده‌ها"; "de" -> "Alle Daten löschen"; else -> "Delete all data" })
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
                            if (language == "fa") "ساخته‌شده برای یادگیرندگان جدی" else if (language == "de") "Für ernsthafte Lernende" else "Built for serious learners",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            if (language == "fa") "نسخهٔ " + com.example.ui.i18n.PersianDate.faDigits(com.example.BuildConfig.VERSION_NAME)
                            else "Version " + com.example.BuildConfig.VERSION_NAME,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            if (language == "fa") "ساخته‌شده توسط دکتر شایان صالحی‌راد" else if (language == "de") "Entwickelt von Dr. Shayan Salehirad" else "Built by Dr. Shayan Salehirad",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        // Opens the Telegram app if installed (it claims telegram.me links), otherwise
                        // the browser — so it works for everyone.
                        Text(
                            text = if (language == "fa") "ارتباط با ما در تلگرام: @shayan_salehirad" else if (language == "de") "Kontakt über Telegram: @shayan_salehirad" else "Contact us on Telegram: @shayan_salehirad",
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
                                                data = "mailto:shayanay80@gmail.com".toUri()
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
                            else if (language == "de")
                                "Denke eine Stunde nach, um zehn Minuten zu arbeiten — nicht zehn Minuten, um eine Stunde zu arbeiten."
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
                    sharedPrefs.edit { putLong("exam_date", millis) }
                    showExamDatePicker = false
                },
            )
        } else if (showExamDatePicker) {
            val examPickerState = rememberDatePickerState(initialSelectedDateMillis = com.example.ui.i18n.AppDate.pickerSelection(if (examDate > 0L) examDate else System.currentTimeMillis()))
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
                            sharedPrefs.edit { putLong("exam_date", local) }
                        }
                        showExamDatePicker = false
                    }) { Text(if (language == "fa") "تأیید" else "OK") }
                },
                dismissButton = { TextButton(onClick = { showExamDatePicker = false }) { Text(if (language == "fa") "لغو" else if (language == "de") "Abbrechen" else "Cancel") } }
            ) { DatePicker(state = examPickerState) }
        }
    }
}

private tailrec fun Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
