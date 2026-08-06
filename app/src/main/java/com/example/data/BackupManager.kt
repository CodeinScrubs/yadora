package com.example.data

import android.content.Context
import androidx.room.withTransaction
import com.example.MedReviewApplication
import com.example.data.local.entity.ReviewLogEntity
import com.example.data.local.entity.StudyUnitEntity
import com.example.data.local.entity.SubjectEntity
import com.example.data.local.entity.SystemEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * FULL, restorable backup: every field of every subject, system, study unit (including titles, notes,
 * recall prompts, and sources) and review log, as one JSON document the user saves to a folder and can
 * later import. Unlike [AnalyticsExporter] (which strips titles/notes for safe sharing), this is a
 * complete snapshot.
 *
 * Restore REPLACES the current database with the backup, preserving ids so every
 * subject/system/unit/log relationship stays intact, all inside one transaction (all-or-nothing).
 */
object BackupManager {
    // v3: adds review_logs.logType and the user's settings (reminder time, retention, language, exam…)
    // so a restore on a NEW phone recreates the whole study setup, not just the data. Restore stays
    // tolerant of v1/v2 files (missing fields fall back to defaults; missing settings are skipped).
    // v5: honest-scheduling fields (modelDueAt/deferredUntil), soft delete (deletedAt), per-log
    // policy snapshot (schedulerPolicyVersion/understandingFactorAtReview).
    const val BACKUP_VERSION = 5

    // The user-preference keys worth carrying across devices (deliberately excludes transient state
    // like last_notif_shown_at).
    private val SETTINGS_STRING_KEYS = listOf("app_language", "theme_mode", "accent_color", "calendar_format", "exam_name")
    private val SETTINGS_BOOL_KEYS = listOf("language_selected", "daily_reminder", "sound_enabled", "vibration_enabled", "alarm_enabled", "alarm_silenced")
    private val SETTINGS_INT_KEYS = listOf("reminder_hour", "reminder_minute")
    private val SETTINGS_FLOAT_KEYS = listOf("daily_review_limit", "desired_retention")
    private val SETTINGS_LONG_KEYS = listOf("exam_date")

    suspend fun buildBackupJson(context: Context): String {
        val db = (context.applicationContext as MedReviewApplication).database
        // ONE transaction = one moment in time: reading each table separately could interleave with
        // a concurrent write (receiver, purge) and produce an internally inconsistent backup.
        // Recently-deleted units are exported too: their logs are still in the DB, and a backup
        // whose logs reference a missing topic would (correctly) fail restore preflight.
        lateinit var subjects: List<SubjectEntity>
        lateinit var systems: List<SystemEntity>
        lateinit var units: List<StudyUnitEntity>
        lateinit var logs: List<ReviewLogEntity>
        lateinit var events: List<com.example.data.local.entity.EventLogEntity>
        db.withTransaction {
            subjects = db.categoryDao().getAllSubjectsOnce()
            systems = db.categoryDao().getAllSystemsOnce()
            units = db.studyUnitDao().getAllActiveOnce() +
                db.studyUnitDao().getArchivedOnce() +
                db.studyUnitDao().getRecentlyDeletedOnce()
            logs = db.reviewLogDao().getAllLogsOnce()
            events = db.eventLogDao().getAll()
        }

        val root = JSONObject()
        root.put("backupVersion", BACKUP_VERSION)
        root.put("exportedAt", System.currentTimeMillis())

        root.put("subjects", JSONArray().apply {
            for (s in subjects) put(JSONObject().apply {
                put("id", s.id); put("name", s.name)
                put("colorHex", s.colorHex ?: JSONObject.NULL); put("createdAt", s.createdAt)
            })
        })
        root.put("systems", JSONArray().apply {
            for (s in systems) put(JSONObject().apply {
                put("id", s.id); put("name", s.name)
                put("colorHex", s.colorHex ?: JSONObject.NULL); put("createdAt", s.createdAt)
            })
        })
        root.put("studyUnits", JSONArray().apply {
            for (u in units) put(JSONObject().apply {
                put("id", u.id); put("title", u.title)
                put("subjectId", u.subjectId ?: JSONObject.NULL); put("systemId", u.systemId ?: JSONObject.NULL)
                put("studyType", u.studyType); put("recallPrompt", u.recallPrompt ?: JSONObject.NULL)
                put("notes", u.notes ?: JSONObject.NULL); put("source", u.source ?: JSONObject.NULL)
                put("highYield", u.highYield); put("state", u.state)
                put("difficulty", u.difficulty); put("stability", u.stability); put("retrievability", u.retrievability)
                put("createdAt", u.createdAt); put("updatedAt", u.updatedAt); put("studiedAt", u.studiedAt)
                put("lastReviewedAt", u.lastReviewedAt ?: JSONObject.NULL); put("nextReviewAt", u.nextReviewAt)
                put("currentIntervalDays", u.currentIntervalDays); put("reviewCount", u.reviewCount)
                put("lapseCount", u.lapseCount); put("archived", u.archived)
                put("modelDueAt", u.modelDueAt); put("deferredUntil", u.deferredUntil ?: JSONObject.NULL)
                put("deletedAt", u.deletedAt ?: JSONObject.NULL)
            })
        })
        root.put("reviewLogs", JSONArray().apply {
            for (l in logs) put(JSONObject().apply {
                put("id", l.id); put("studyUnitId", l.studyUnitId); put("reviewedAt", l.reviewedAt)
                put("memoryRating", l.memoryRating); put("understandingRating", l.understandingRating)
                put("previousIntervalDays", l.previousIntervalDays); put("nextIntervalDays", l.nextIntervalDays)
                put("previousState", l.previousState); put("nextState", l.nextState)
                put("retrievabilityAtReview", l.retrievabilityAtReview); put("elapsedDays", l.elapsedDays)
                put("logType", l.logType)
                put("initialDifficulty", l.initialDifficulty ?: JSONObject.NULL)
                put("reviewDurationMs", l.reviewDurationMs); put("wasImportantAtReview", l.wasImportantAtReview)
                put("desiredRetentionAtReview", l.desiredRetentionAtReview); put("schedulerVersion", l.schedulerVersion)
                put("schedulerPolicyVersion", l.schedulerPolicyVersion)
                put("understandingFactorAtReview", l.understandingFactorAtReview)
            })
        })
        root.put("eventLogs", JSONArray().apply {
            for (e in events) put(JSONObject().apply {
                put("id", e.id); put("at", e.at); put("type", e.type)
                put("unitId", e.unitId ?: JSONObject.NULL); put("detail", e.detail ?: JSONObject.NULL)
            })
        })

        // The user's study setup, so a restore on a new phone brings back reminder time, language,
        // retention target, exam countdown, etc. — not just the topics.
        val sp = context.getSharedPreferences("medreview_settings", Context.MODE_PRIVATE)
        root.put("settings", JSONObject().apply {
            SETTINGS_STRING_KEYS.forEach { k -> sp.getString(k, null)?.let { put(k, it) } }
            SETTINGS_BOOL_KEYS.forEach { k -> if (sp.contains(k)) put(k, sp.getBoolean(k, false)) }
            SETTINGS_INT_KEYS.forEach { k -> if (sp.contains(k)) put(k, sp.getInt(k, 0)) }
            SETTINGS_FLOAT_KEYS.forEach { k -> if (sp.contains(k)) put(k, sp.getFloat(k, 0f).toDouble()) }
            SETTINGS_LONG_KEYS.forEach { k -> if (sp.contains(k)) put(k, sp.getLong(k, 0L)) }
        })
        return root.toString(2)
    }

    /** Replace ALL data with the backup's contents. Returns the number of study units restored. */
    suspend fun restoreFromJson(context: Context, json: String): Int {
        val db = (context.applicationContext as MedReviewApplication).database
        val root = JSONObject(json)
        require(root.has("studyUnits")) { "This file is not a Yadora backup." }

        // Safety net: restore is all-or-nothing, so before touching anything, snapshot the CURRENT
        // data to a private file. If the user imports the wrong backup, their real data is still
        // recoverable from files/last_before_restore_backup.json.
        // ABORT restore if the safety copy can't be created (e.g. storage full): destroying the
        // only copy of the user's data without a recovery net is never acceptable. Temp + rename so
        // a crash mid-write can't leave a truncated safety file that LOOKS valid.
        run {
            val emergency = buildBackupJson(context)
            val tmp = context.filesDir.resolve("last_before_restore_backup.json.tmp")
            tmp.writeText(emergency)
            check(tmp.length() > 0L) { "Safety copy could not be written" }
            val dest = context.filesDir.resolve("last_before_restore_backup.json")
            if (dest.exists()) dest.delete()
            check(tmp.renameTo(dest)) { "Safety copy could not be finalized" }
        }

        val subjectsArr = root.optJSONArray("subjects") ?: JSONArray()
        val systemsArr = root.optJSONArray("systems") ?: JSONArray()
        val unitsArr = root.optJSONArray("studyUnits") ?: JSONArray()
        val logsArr = root.optJSONArray("reviewLogs") ?: JSONArray()

        val subjects = (0 until subjectsArr.length()).map { i ->
            val o = subjectsArr.getJSONObject(i)
            SubjectEntity(
                id = o.getLong("id"), name = o.getString("name"),
                colorHex = o.strOrNull("colorHex"), createdAt = o.optLong("createdAt", System.currentTimeMillis())
            )
        }
        val systems = (0 until systemsArr.length()).map { i ->
            val o = systemsArr.getJSONObject(i)
            SystemEntity(
                id = o.getLong("id"), name = o.getString("name"),
                colorHex = o.strOrNull("colorHex"), createdAt = o.optLong("createdAt", System.currentTimeMillis())
            )
        }
        val units = (0 until unitsArr.length()).map { i ->
            val o = unitsArr.getJSONObject(i)
            StudyUnitEntity(
                id = o.getLong("id"), title = o.optString("title", ""),
                subjectId = o.longOrNull("subjectId"), systemId = o.longOrNull("systemId"),
                studyType = o.optString("studyType", "Other"), recallPrompt = o.strOrNull("recallPrompt"),
                notes = o.strOrNull("notes"), source = o.strOrNull("source"),
                highYield = o.optBoolean("highYield", false), state = o.optString("state", "New"),
                difficulty = o.optDouble("difficulty", 5.0), stability = o.optDouble("stability", 1.0),
                retrievability = o.optDouble("retrievability", 1.0),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                studiedAt = o.optLong("studiedAt", System.currentTimeMillis()),
                lastReviewedAt = o.longOrNull("lastReviewedAt"),
                nextReviewAt = o.optLong("nextReviewAt", System.currentTimeMillis()),
                currentIntervalDays = o.optDouble("currentIntervalDays", 0.0),
                reviewCount = o.optInt("reviewCount", 0), lapseCount = o.optInt("lapseCount", 0),
                archived = o.optBoolean("archived", false),
                // Pre-v5 backups: the effective date was the only date — same backfill the migration uses.
                modelDueAt = o.optLong("modelDueAt", o.optLong("nextReviewAt", System.currentTimeMillis())),
                deferredUntil = o.longOrNull("deferredUntil"),
                deletedAt = o.longOrNull("deletedAt"),
            )
        }
        // Whole-file preflight: reject structurally corrupt topics BEFORE any current data is deleted.
        val fileVersion = root.optInt("backupVersion", 1)
        require(fileVersion in 1..BACKUP_VERSION) { "This backup was made by a NEWER Yadora version ($fileVersion) — update the app first." }
        require(units.map { it.id }.toSet().size == units.size) { "Damaged backup: duplicate topic ids" }
        require(subjects.map { it.id }.toSet().size == subjects.size) { "Damaged backup: duplicate subject ids" }
        require(systems.map { it.id }.toSet().size == systems.size) { "Damaged backup: duplicate collection ids" }
        // Positive ids only: id 0 would be silently REASSIGNED by Room's autoGenerate on insert,
        // orphaning every review log that still references the exported id.
        require(units.all { it.id > 0 }) { "Damaged backup: topic with invalid id" }
        require(subjects.all { it.id > 0 }) { "Damaged backup: subject with invalid id" }
        require(systems.all { it.id > 0 }) { "Damaged backup: collection with invalid id" }
        // Category references must resolve (null = "no subject" is fine; a dangling id is corruption).
        val subjectIds = subjects.mapTo(HashSet()) { it.id }
        val systemIds = systems.mapTo(HashSet()) { it.id }
        units.forEachIndexed { i, u ->
            require(u.subjectId == null || u.subjectId in subjectIds) { "Damaged backup: topic ${i + 1} references missing subject" }
            require(u.systemId == null || u.systemId in systemIds) { "Damaged backup: topic ${i + 1} references missing collection" }
        }
        units.forEachIndexed { i, u ->
            // Must clear the MODEL's floor, not merely be positive: a sub-floor stability is a value
            // the scheduler can never produce, and it used to make the first Forgot rating on that
            // topic throw out of the FSRS lapse branch.
            require(u.stability >= com.example.domain.srs.Fsrs.S_MIN && u.stability.isFinite()) {
                "Damaged backup: invalid stability (topic ${i + 1})"
            }
            require(u.difficulty in 1.0..10.0) { "Damaged backup: invalid difficulty (topic ${i + 1})" }
            require(u.currentIntervalDays >= 0.0 && u.currentIntervalDays.isFinite()) { "Damaged backup: invalid interval (topic ${i + 1})" }
            require(u.reviewCount >= 0 && u.lapseCount >= 0) { "Damaged backup: invalid counts (topic ${i + 1})" }
            require(u.nextReviewAt > 0L && u.studiedAt > 0L) { "Damaged backup: invalid dates (topic ${i + 1})" }
        }

        // Strict validation: a damaged backup must ABORT the restore, not be silently coerced into
        // fake-plausible history (e.g. every corrupt rating becoming "Good" would poison FSRS replay
        // and analytics). The thrown message surfaces as "Restore failed — invalid backup".
        val validMemory = setOf("Forgot", "Hard", "Good", "Easy")
        val validUnderstanding = setOf("Confused", "Partial", "Clear", "NotAsked") // NotAsked = skipped question (Forgot fast-commit)
        val unitIds = units.mapTo(HashSet()) { it.id }
        val logs = (0 until logsArr.length()).map { i ->
            val o = logsArr.getJSONObject(i)
            val memory = o.optString("memoryRating", "")
            val understanding = o.optString("understandingRating", "")
            val unitRef = o.optLong("studyUnitId", -1L)
            require(memory in validMemory) { "Damaged backup: invalid memory rating '$memory' (log ${i + 1})" }
            require(understanding in validUnderstanding) { "Damaged backup: invalid understanding rating '$understanding' (log ${i + 1})" }
            require(unitRef in unitIds) { "Damaged backup: review log ${i + 1} references missing topic $unitRef" }
            ReviewLogEntity(
                id = o.optLong("id", 0L), studyUnitId = unitRef,
                reviewedAt = o.optLong("reviewedAt", System.currentTimeMillis()),
                memoryRating = memory,
                understandingRating = understanding,
                previousIntervalDays = o.optDouble("previousIntervalDays", 0.0),
                nextIntervalDays = o.optDouble("nextIntervalDays", 0.0),
                previousState = o.optString("previousState", "New"),
                nextState = o.optString("nextState", "New"),
                // Absent in older backups → the same "unknown" sentinels pre-v4 rows use.
                retrievabilityAtReview = o.optDouble("retrievabilityAtReview", -1.0),
                elapsedDays = o.optDouble("elapsedDays", -1.0),
                logType = o.optString("logType", "UNKNOWN"),
                initialDifficulty = o.strOrNull("initialDifficulty"),
                reviewDurationMs = o.optLong("reviewDurationMs", -1L),
                wasImportantAtReview = o.optInt("wasImportantAtReview", -1),
                desiredRetentionAtReview = o.optDouble("desiredRetentionAtReview", -1.0),
                schedulerVersion = o.optString("schedulerVersion", ""),
                schedulerPolicyVersion = o.optString("schedulerPolicyVersion", ""),
                understandingFactorAtReview = o.optDouble("understandingFactorAtReview", -1.0),
            )
        }
        // REPLACE-by-id semantics in the restore rely on log ids being unique within the file.
        require(logs.map { it.id }.toSet().size == logs.size) { "Damaged backup: duplicate review-log ids" }
        require(logs.all { it.id > 0 }) { "Damaged backup: review log with invalid id" }

        // v1 backups have no eventLogs array; that's fine — restore just clears the table.
        val eventsArr = root.optJSONArray("eventLogs") ?: JSONArray()
        val events = (0 until eventsArr.length()).map { i ->
            val o = eventsArr.getJSONObject(i)
            com.example.data.local.entity.EventLogEntity(
                id = o.optLong("id", 0L),
                at = o.optLong("at", System.currentTimeMillis()),
                type = o.optString("type", "UNKNOWN"),
                unitId = o.longOrNull("unitId"),
                detail = o.strOrNull("detail")
            )
        }

        db.withTransaction {
            db.eventLogDao().deleteAll() // always: "replace ALL data" must not leave stale events behind
            db.reviewLogDao().deleteAllLogs()
            db.studyUnitDao().deleteAllUnits()
            db.categoryDao().deleteAllSubjects()
            db.categoryDao().deleteAllSystems()
            subjects.forEach { db.categoryDao().insertSubject(it) }
            systems.forEach { db.categoryDao().insertSystem(it) }
            units.forEach { db.studyUnitDao().insertUnit(it) }
            logs.forEach { db.reviewLogDao().insertLog(it) }
            events.forEach { db.eventLogDao().insert(it) }
        }

        // Restore the study setup too (v3+ backups; older files simply have no settings object).
        // Language/theme apply fully on the next app start; the caller already re-arms the reminder.
        root.optJSONObject("settings")?.let { s ->
            val sp = context.getSharedPreferences("medreview_settings", Context.MODE_PRIVATE)
            val e = sp.edit()
            SETTINGS_STRING_KEYS.forEach { k -> if (s.has(k)) e.putString(k, s.optString(k)) }
            SETTINGS_BOOL_KEYS.forEach { k -> if (s.has(k)) e.putBoolean(k, s.optBoolean(k)) }
            // Settings get the same scepticism as topic rows. A backup is an EDITABLE file from
            // outside the app, so a value here can be anything — and desired_retention in particular
            // is load-bearing: out of range it used to make FSRS throw on every review, forever.
            // Clamp rather than reject, so one silly number can't cost the user their whole restore.
            SETTINGS_FLOAT_KEYS.forEach { k ->
                if (s.has(k)) {
                    val raw = s.optDouble(k)
                    val safe = when {
                        !raw.isFinite() -> null // drop it; the app default applies
                        k == "desired_retention" -> raw.coerceIn(
                            com.example.domain.srs.MedScheduler.MIN_RETENTION,
                            com.example.domain.srs.MedScheduler.MAX_RETENTION,
                        )
                        k == "daily_review_limit" -> raw.coerceIn(1.0, 500.0)
                        else -> raw
                    }
                    if (safe != null) e.putFloat(k, safe.toFloat())
                }
            }
            SETTINGS_INT_KEYS.forEach { k ->
                if (s.has(k)) when (k) {
                    "reminder_hour" -> e.putInt(k, s.optInt(k).coerceIn(0, 23))
                    "reminder_minute" -> e.putInt(k, s.optInt(k).coerceIn(0, 59))
                    else -> e.putInt(k, s.optInt(k))
                }
            }
            SETTINGS_LONG_KEYS.forEach { k -> if (s.has(k)) e.putLong(k, s.optLong(k)) }
            // commit() (not apply()): restore success is reported after this returns, so the
            // settings must actually be on disk by then.
            @Suppress("ApplySharedPref")
            e.commit()
            runCatching {
                com.example.domain.srs.MedScheduler.userRetention =
                    sp.getFloat("desired_retention", 0.90f).toDouble()
            }
        }
        return units.size
    }

    /**
     * Irreversibly erases ALL user data — every subject, system, topic (including soft-deleted ones),
     * review log, and event — plus the on-disk crash log and the pre-restore safety copy. Study
     * settings (reminder time, retention, exam) are cleared; the chosen LANGUAGE is deliberately kept
     * so the app doesn't jump back to English after a wipe. Reminders/alarms are cancelled.
     *
     * This is what the privacy policy's "you can delete all your data at any time" promise refers to.
     * All table deletes run in ONE transaction: either everything is gone or nothing is.
     */
    suspend fun deleteAllData(context: Context) {
        val db = (context.applicationContext as MedReviewApplication).database
        db.withTransaction {
            db.eventLogDao().deleteAll()
            db.reviewLogDao().deleteAllLogs()
            db.studyUnitDao().deleteAllUnits()
            db.categoryDao().deleteAllSubjects()
            db.categoryDao().deleteAllSystems()
        }
        // Stop every scheduled reminder/alarm — there is nothing left to review.
        runCatching { com.example.notifications.NotificationScheduler.cancelReminder(context) }
        // Also take down any reminder ALREADY in the shade: its body lists real topic titles, so
        // leaving it there after "all data deleted" both contradicts the message and keeps the very
        // content the user just erased visible on their lock screen.
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(context)
                .cancel(com.example.notifications.NotificationScheduler.NOTIFICATION_ID)
        }
        runCatching { com.example.notifications.AlarmRingActivity.dismissActive() }
        // Clear settings but PRESERVE the language choice (a wipe shouldn't reset the UI to English).
        val sp = context.getSharedPreferences("medreview_settings", Context.MODE_PRIVATE)
        val keptLanguage = sp.getString("app_language", "en")
        @Suppress("ApplySharedPref")
        sp.edit().clear().putString("app_language", keptLanguage).commit()
        // Device-local reminder bookkeeping lives in its own file (kept out of cloud backup), so
        // clearing settings alone would leave a stale "already shown today" / snooze behind.
        runCatching {
            com.example.notifications.NotificationScheduler.transientPrefs(context)
                .edit().clear().apply()
        }
        runCatching {
            com.example.domain.srs.MedScheduler.userRetention =
                sp.getFloat("desired_retention", 0.90f).toDouble()
        }
        // Remove local diagnostic/safety files so nothing personal lingers on disk.
        runCatching { context.filesDir.resolve("crash.log").delete() }
        runCatching { context.filesDir.resolve("last_before_restore_backup.json").delete() }
        runCatching { context.filesDir.resolve("last_before_restore_backup.json.tmp").delete() }
        runCatching { com.example.widget.DueWidgetProvider.updateAll(context) }
    }

    private fun JSONObject.strOrNull(key: String): String? = if (isNull(key)) null else optString(key)
    private fun JSONObject.longOrNull(key: String): Long? = if (isNull(key)) null else optLong(key)
}
