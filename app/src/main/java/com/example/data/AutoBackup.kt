package com.example.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.MedReviewApplication
import com.example.notifications.NotificationScheduler
import kotlinx.coroutines.sync.withLock
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

/**
 * Automatic backups: once a day, a full backup ([BackupManager.writeBackup]) into a folder the learner chose.
 *
 * Why it exists: the study history is the product. Years of ratings are what the schedule is computed from,
 * and they live only on this phone — the database is deliberately excluded from Android's cloud backup (see
 * CLAUDE.md). A manual export works only if the learner remembers to make one, and the loudest complaints
 * about a comparable app were exactly this: "I lost all my data", "it wiped 7 years of data" after changing
 * phones. A folder on shared storage survives uninstalling Yadora and clearing its data, and a folder that a
 * sync app mirrors (Drive, OneDrive, Syncthing...) survives losing the phone.
 *
 * Rules:
 * - never overwrite: every run writes a new, dated file (`yadora_backup_2026-09-24_213045.json`);
 * - one backup at a time: choosing a folder starts the daily job AND a "back up now", and on a phone the two ran
 *   at once and left two identical files, the second renamed "… (1).json" by the storage provider and never
 *   pruned (seen on a Samsung, 2026-09-27). Runs are serialised ([runLock]), names carry seconds, and a renamed
 *   copy is recognised, so the day's older file is pruned like any other. "Back up now" always writes: it must
 *   include everything up to the moment it is pressed;
 * - a write that fails part-way deletes its own file, so a broken backup never sits beside the good ones;
 * - old files are pruned only AFTER a successful write, and only files with exactly this name pattern:
 *   the newest backup of each of the last [KEEP_DAYS] days that have one, plus the newest of each of the
 *   last [KEEP_MONTHS] months, so a mistake noticed weeks later can still be undone;
 * - an empty library is never backed up (after "Delete all data" the old backups must not be rotated out
 *   by empty ones);
 * - the folder's address lives in the device-only transient prefs: a restore onto another phone does not
 *   carry a permission that phone never granted, and asks for a folder again.
 */
object AutoBackup {
    const val UNIQUE_NAME = "yadora-auto-backup"
    const val PREF_TREE_URI = "auto_backup_tree_uri"
    const val PREF_LAST_OK_AT = "auto_backup_last_ok_at"
    const val PREF_LAST_OK_FILE = "auto_backup_last_ok_file"
    const val PREF_LAST_ERROR_AT = "auto_backup_last_error_at"
    const val PREF_LAST_ERROR = "auto_backup_last_error"
    const val KEEP_DAYS = 7
    const val KEEP_MONTHS = 6

    /** A scheduled run within this long of the last good one does nothing: one backup a day is the plan. */
    const val MIN_GAP_MS = 20L * 60 * 60 * 1000

    /** Serialises every run: the daily job and "Back up now" must never write at the same moment. */
    private val runLock = kotlinx.coroutines.sync.Mutex()

    /**
     * Whether a run writes, decided from the last good backup alone (pure, so it is testable). A forced run ("Back
     * up now", the first backup in a new folder) always writes; the daily job writes once a day.
     */
    fun shouldWrite(force: Boolean, now: Long, lastOkAt: Long): Boolean {
        val age = now - lastOkAt
        return force || lastOkAt <= 0L || age < 0L || age >= MIN_GAP_MS // a clock set back: write, don't trust the stamp
    }

    /** When the learner last exported a backup by hand (Settings → Export full backup). */
    const val PREF_LAST_MANUAL_AT = "manual_backup_last_at"
    /** When the learner last dismissed Today's backup suggestion. */
    const val PREF_NUDGE_DISMISSED_AT = "auto_backup_nudge_dismissed_at"
    const val NUDGE_MIN_TOPICS = 20
    const val NUDGE_QUIET_MS = 30L * 24 * 60 * 60 * 1000

    /**
     * Should Today suggest automatic backup? Only once there is something worth losing ([NUDGE_MIN_TOPICS]
     * topics), only while it is off, and at most once a month: a backup made by hand or a "Not now" quiets it
     * for [NUDGE_QUIET_MS].
     */
    fun shouldNudge(topics: Int, autoOn: Boolean, now: Long, lastManualAt: Long, dismissedAt: Long): Boolean =
        !autoOn && topics >= NUDGE_MIN_TOPICS && now - maxOf(lastManualAt, dismissedAt) > NUDGE_QUIET_MS

    /**
     * A backup's name: date and time to the second. Also recognised: the older minute-only names, and a copy a
     * storage provider renamed on a clash ("… (1).json"), so both are pruned like any other backup.
     */
    private val NAME = Regex("""yadora_backup_(\d{4})-(\d{2})-(\d{2})_(\d{2})(\d{2})(\d{2})?(?: \(\d+\))?\.json""")

    fun fileName(at: LocalDateTime): String =
        "yadora_backup_%04d-%02d-%02d_%02d%02d%02d.json".format(
            java.util.Locale.ROOT, at.year, at.monthValue, at.dayOfMonth, at.hour, at.minute, at.second,
        )

    private fun timeOf(name: String): LocalDateTime? = NAME.matchEntire(name)?.groupValues?.let { g ->
        runCatching {
            LocalDateTime.of(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].ifEmpty { "0" }.toInt())
        }.getOrNull()
    }

    /** Which backups to delete from a folder holding [names]. Anything not named like a backup is never touched. */
    fun toDelete(names: Collection<String>): List<String> {
        val dated = names.mapNotNull { n -> timeOf(n)?.let { n to it } }.sortedByDescending { it.second }
        val keep = HashSet<String>()
        dated.groupBy { it.second.toLocalDate() }.entries.sortedByDescending { it.key }.take(KEEP_DAYS)
            .forEach { (_, files) -> keep += files.first().first }
        dated.groupBy { YearMonth.from(it.second) }.entries.sortedByDescending { it.key }.take(KEEP_MONTHS)
            .forEach { (_, files) -> keep += files.first().first }
        return dated.map { it.first }.filterNot { it in keep }
    }

    sealed class Outcome {
        data class Written(val name: String) : Outcome()
        data class Skipped(val reason: String) : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    /** A folder backups can be written into. The app's is the learner's chosen folder ([SafFolder]). */
    interface Folder {
        class Entry(val name: String, val handle: Any)
        fun list(): List<Entry>
        fun create(name: String): Entry?
        fun openOutput(entry: Entry): OutputStream
        fun delete(entry: Entry): Boolean
        /** Whether [entry] can be renamed; a folder that cannot gets each backup under its final name directly. */
        fun canRename(entry: Entry): Boolean
        /** [entry] under [name], or null when the rename failed. */
        fun rename(entry: Entry, name: String): Entry?
    }

    /**
     * The name a backup is written under until it is complete. It never matches a backup's name, so neither the
     * pruning nor a learner looking for a backup takes it for one: a write the system kills half-way leaves this
     * file, not a truncated file named like the day's backup that the pruning would keep in place of a good one (an
     * outside audit, 2026-10-02).
     */
    const val STAGED_PREFIX = "yadora_partial_"

    /** A staged file older than an hour was never renamed: the run that wrote it was killed, so it is incomplete. */
    fun isStaleStaged(name: String, at: LocalDateTime): Boolean =
        name.startsWith(STAGED_PREFIX) &&
            (timeOf("yadora_backup_" + name.removePrefix(STAGED_PREFIX))?.isBefore(at.minusHours(1)) ?: false)

    /**
     * One backup into [folder]: write it under a staged name ([STAGED_PREFIX]) and rename it once complete, clean up after
     * a failure, prune after a success. A folder that cannot rename gets the backup under its final name directly, as
     * every backup was written before 2026-10-03.
     */
    suspend fun writeInto(
        folder: Folder,
        at: LocalDateTime,
        topicCount: Int,
        write: suspend (OutputStream) -> Unit,
    ): Outcome {
        if (topicCount <= 0) return Outcome.Skipped("the library is empty")
        val name = fileName(at)

        suspend fun writeTo(target: Folder.Entry): String? = try {
            folder.openOutput(target).use { write(it) }
            null
        } catch (t: Throwable) {
            runCatching { folder.delete(target) }
            t.message ?: t.javaClass.simpleName
        }

        val staged = folder.create(STAGED_PREFIX + name.removePrefix("yadora_backup_"))?.let { e ->
            if (runCatching { folder.canRename(e) }.getOrDefault(false)) e else { runCatching { folder.delete(e) }; null }
        }
        val entry: Folder.Entry
        if (staged != null) {
            writeTo(staged)?.let { return Outcome.Failed(it) }
            entry = runCatching { folder.rename(staged, name) }.getOrNull() ?: run {
                // The rename failed after all: write the backup again under its real name, and drop the staged copy.
                runCatching { folder.delete(staged) }
                val direct = folder.create(name) ?: return Outcome.Failed("could not create $name")
                writeTo(direct)?.let { return Outcome.Failed(it) }
                direct
            }
        } else {
            entry = folder.create(name) ?: return Outcome.Failed("could not create $name")
            writeTo(entry)?.let { return Outcome.Failed(it) }
        }

        val existing = folder.list()
        val doomed = toDelete(existing.map { it.name }).toSet()
        // Never the file just written, whatever name the provider actually gave it.
        existing.filter { it.name in doomed && it.name != name && it.name != entry.name && it.handle != entry.handle }
            .forEach { runCatching { folder.delete(it) } }
        existing.filter { isStaleStaged(it.name, at) && it.handle != entry.handle }.forEach { runCatching { folder.delete(it) } }
        return Outcome.Written(entry.name)
    }

    fun treeUri(context: Context): Uri? =
        NotificationScheduler.transientPrefs(context).getString(PREF_TREE_URI, null)?.toUri()

    fun isOn(context: Context): Boolean = treeUri(context) != null

    /** The learner picked [uri] with the system folder picker: keep the permission and start the daily job. */
    fun enable(context: Context, uri: Uri) {
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)
        NotificationScheduler.transientPrefs(context).edit {
            putString(PREF_TREE_URI, uri.toString())
            remove(PREF_LAST_ERROR_AT)
            remove(PREF_LAST_ERROR)
            // A new folder has no backup yet: the last good one was written somewhere else.
            remove(PREF_LAST_OK_AT)
            remove(PREF_LAST_OK_FILE)
        }
        schedule(context)
    }

    /**
     * Back up into [uri] from now on, instead of the folder in use. The new folder's permission is taken FIRST: the old
     * one used to be released before it, so a folder whose provider refuses a lasting permission left automatic backup
     * off, with the working folder dropped (a production review, 2026-10-10). Throws, changing nothing, when [uri] cannot
     * be used.
     */
    fun changeFolder(context: Context, uri: Uri) {
        val old = treeUri(context)
        enable(context, uri)
        if (old != null && old != uri) runCatching {
            context.contentResolver.releasePersistableUriPermission(
                old,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    fun disable(context: Context) {
        treeUri(context)?.let { uri ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        NotificationScheduler.transientPrefs(context).edit { remove(PREF_TREE_URI) }
        runCatching { androidx.work.WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME) }
    }

    /** The daily job. KEEP: relaunching the app must not restart the day-long period. */
    fun schedule(context: Context) {
        runCatching {
            androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                androidx.work.PeriodicWorkRequestBuilder<AutoBackupWorker>(1, java.util.concurrent.TimeUnit.DAYS)
                    .setConstraints(androidx.work.Constraints.Builder().setRequiresStorageNotLow(true).build())
                    .build(),
            )
        }
    }

    /** The chosen folder's name, for Settings; null if it can no longer be read. */
    fun folderName(context: Context): String? = runCatching {
        val tree = treeUri(context) ?: return null
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /**
     * Back up now if one is due ([force]: regardless). Records the outcome for Settings. Never throws: a
     * failure is recorded, and the next day's run tries again.
     */
    suspend fun runNow(context: Context, force: Boolean): Outcome = runLock.withLock { runLocked(context, force) }

    private suspend fun runLocked(context: Context, force: Boolean): Outcome {
        val prefs = NotificationScheduler.transientPrefs(context)
        val tree = treeUri(context) ?: return Outcome.Skipped("automatic backup is off")
        val now = System.currentTimeMillis()
        if (!shouldWrite(force, now, prefs.getLong(PREF_LAST_OK_AT, 0L))) return Outcome.Skipped("already backed up today")
        val outcome = try {
            val app = context.applicationContext as MedReviewApplication
            val count = app.database.studyUnitDao().countAllOnce()
            writeInto(SafFolder(context, tree), LocalDateTime.now(ZoneId.systemDefault()), count) { out ->
                BackupManager.writeBackup(context, out)
            }
        } catch (t: Throwable) {
            // Typically a SecurityException: the folder was deleted or its permission revoked.
            Outcome.Failed(t.message ?: t.javaClass.simpleName)
        }
        prefs.edit {
            when (outcome) {
                is Outcome.Written -> {
                    putLong(PREF_LAST_OK_AT, now)
                    putString(PREF_LAST_OK_FILE, outcome.name)
                    remove(PREF_LAST_ERROR_AT)
                    remove(PREF_LAST_ERROR)
                }
                is Outcome.Failed -> {
                    putLong(PREF_LAST_ERROR_AT, now)
                    putString(PREF_LAST_ERROR, outcome.message.take(200))
                }
                is Outcome.Skipped -> Unit
            }
        }
        return outcome
    }

    /** The learner's folder, through the Storage Access Framework. */
    class SafFolder(context: Context, private val tree: Uri) : Folder {
        private val resolver = context.contentResolver
        private val treeDocId = DocumentsContract.getTreeDocumentId(tree)
        private val dir = DocumentsContract.buildDocumentUriUsingTree(tree, treeDocId)

        override fun list(): List<Folder.Entry> {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeDocId)
            val out = ArrayList<Folder.Entry>()
            resolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    out += Folder.Entry(name, DocumentsContract.buildDocumentUriUsingTree(tree, id))
                }
            }
            return out
        }

        override fun create(name: String): Folder.Entry? =
            DocumentsContract.createDocument(resolver, dir, "application/json", name)?.let { uri ->
                // The provider may have changed the name (a clash becomes "… (1).json"): report the real one.
                Folder.Entry(displayName(uri) ?: name, uri)
            }

        private fun displayName(uri: Uri): String? = runCatching {
            resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()

        override fun canRename(entry: Folder.Entry): Boolean = runCatching {
            resolver.query(entry.handle as Uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)
                ?.use { c -> c.moveToFirst() && (c.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_RENAME) != 0 } ?: false
        }.getOrDefault(false)

        override fun rename(entry: Folder.Entry, name: String): Folder.Entry? =
            runCatching { DocumentsContract.renameDocument(resolver, entry.handle as Uri, name) }.getOrNull()
                ?.let { uri -> Folder.Entry(displayName(uri) ?: name, uri) }

        override fun openOutput(entry: Folder.Entry): OutputStream =
            resolver.openOutputStream(entry.handle as Uri, "w") ?: throw java.io.IOException("cannot write ${entry.name}")

        override fun delete(entry: Folder.Entry): Boolean = DocumentsContract.deleteDocument(resolver, entry.handle as Uri)
    }
}

/** The daily automatic backup ([AutoBackup]). Always succeeds: an outcome is recorded, never retried in a loop. */
class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        AutoBackup.runNow(applicationContext, force = false)
        return Result.success()
    }
}
