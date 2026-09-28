package com.example.data

import android.content.Context
import androidx.core.content.edit
import androidx.room.withTransaction
import com.example.MedReviewApplication
import com.example.data.local.entity.ReviewLogEntity
import com.example.data.local.entity.StudyUnitEntity
import com.example.data.local.entity.SubjectEntity
import com.example.data.local.entity.SystemEntity
import com.example.data.JsonStreams.field
import com.example.data.JsonStreams.forEachRecord
import com.example.data.JsonStreams.readValue
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
    // v6: study_units.understandingDueAt + memoryModel. Without these a restore would reset every
    // topic to FSRS-5 and drop its pending understanding repair — silently rewriting the schedule.
    // v7: key points (study_units.keyPoints) and how each review scored against them
    // (review_logs.keyPointsTotal/keyPointsRecalled).
    // v8: the personal memory model — every fitted weight set (memoryParameterSets) and which set each
    // topic and review belongs to (parameterSetId). Without them a restore would put every personal-set
    // topic on weights the file no longer holds.
    // v9: what each review consisted of (review_logs.reviewMethods / questionsCorrect / questionsTotal /
    // sessionKind) and the pseudonymous research id, so a pilot participant who restores onto a new phone
    // keeps one identity and loses none of the pilot data.
    const val BACKUP_VERSION = 9

    // The user-preference keys worth carrying across devices (deliberately excludes transient state
    // like last_notif_shown_at).
    private val SETTINGS_STRING_KEYS = listOf("app_language", "theme_mode", "accent_color", "calendar_format", "exam_name", ResearchId.PREF_KEY)
    private val SETTINGS_BOOL_KEYS = listOf("language_selected", "daily_reminder", "sound_enabled", "vibration_enabled", "alarm_enabled", "alarm_silenced", PersonalModelWorker.PREF_ENABLED)
    private val SETTINGS_INT_KEYS = listOf("reminder_hour", "reminder_minute")
    private val SETTINGS_FLOAT_KEYS = listOf("daily_review_limit", "desired_retention")
    private val SETTINGS_LONG_KEYS = listOf("exam_date")

    /**
     * The backup, written straight to [out] as compact JSON, one record at a time ([JsonStreams]): memory stays
     * the size of the entities however long the history. Same fields as ever, so every older build's restore
     * reads it. The caller owns and closes [out].
     */
    suspend fun writeBackup(context: Context, out: java.io.OutputStream) {
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
        lateinit var parameterSets: List<com.example.data.local.entity.MemoryParameterSetEntity>
        db.withTransaction {
            parameterSets = db.memoryParameterSetDao().getAll()
            subjects = db.categoryDao().getAllSubjectsOnce()
            systems = db.categoryDao().getAllSystemsOnce()
            units = db.studyUnitDao().getAllActiveOnce() +
                db.studyUnitDao().getArchivedOnce() +
                db.studyUnitDao().getRecentlyDeletedOnce()
            logs = db.reviewLogDao().getAllLogsOnce()
            events = db.eventLogDao().getAll()
        }

        val w = android.util.JsonWriter(java.io.OutputStreamWriter(java.io.BufferedOutputStream(out), Charsets.UTF_8))
        w.beginObject()
        w.field("backupVersion", BACKUP_VERSION)
        w.field("exportedAt", System.currentTimeMillis())

        w.name("subjects").beginArray()
        for (s in subjects) {
            w.beginObject()
            w.field("id", s.id).field("name", s.name).field("colorHex", s.colorHex).field("createdAt", s.createdAt)
            w.endObject()
        }
        w.endArray()
        w.name("systems").beginArray()
        for (s in systems) {
            w.beginObject()
            w.field("id", s.id).field("name", s.name).field("colorHex", s.colorHex).field("createdAt", s.createdAt)
            w.endObject()
        }
        w.endArray()
        w.name("studyUnits").beginArray()
        for (u in units) {
            w.beginObject()
            w.field("id", u.id).field("title", u.title)
            w.field("subjectId", u.subjectId).field("systemId", u.systemId)
            w.field("studyType", u.studyType).field("recallPrompt", u.recallPrompt)
            w.field("notes", u.notes).field("source", u.source)
            w.field("highYield", u.highYield).field("state", u.state)
            w.field("difficulty", u.difficulty).field("stability", u.stability).field("retrievability", u.retrievability)
            w.field("createdAt", u.createdAt).field("updatedAt", u.updatedAt).field("studiedAt", u.studiedAt)
            w.field("lastReviewedAt", u.lastReviewedAt).field("nextReviewAt", u.nextReviewAt)
            w.field("currentIntervalDays", u.currentIntervalDays).field("reviewCount", u.reviewCount)
            w.field("lapseCount", u.lapseCount).field("archived", u.archived)
            w.field("modelDueAt", u.modelDueAt).field("deferredUntil", u.deferredUntil)
            w.field("deletedAt", u.deletedAt)
            w.field("understandingDueAt", u.understandingDueAt)
            w.field("memoryModel", u.memoryModel)
            w.field("keyPoints", u.keyPoints)
            w.field("parameterSetId", u.parameterSetId)
            w.endObject()
        }
        w.endArray()
        w.name("reviewLogs").beginArray()
        for (l in logs) {
            w.beginObject()
            w.field("id", l.id).field("studyUnitId", l.studyUnitId).field("reviewedAt", l.reviewedAt)
            w.field("memoryRating", l.memoryRating).field("understandingRating", l.understandingRating)
            w.field("previousIntervalDays", l.previousIntervalDays).field("nextIntervalDays", l.nextIntervalDays)
            w.field("previousState", l.previousState).field("nextState", l.nextState)
            w.field("retrievabilityAtReview", l.retrievabilityAtReview).field("elapsedDays", l.elapsedDays)
            w.field("logType", l.logType)
            w.field("initialDifficulty", l.initialDifficulty)
            w.field("reviewDurationMs", l.reviewDurationMs).field("wasImportantAtReview", l.wasImportantAtReview)
            w.field("desiredRetentionAtReview", l.desiredRetentionAtReview).field("schedulerVersion", l.schedulerVersion)
            w.field("schedulerPolicyVersion", l.schedulerPolicyVersion)
            w.field("understandingFactorAtReview", l.understandingFactorAtReview)
            w.field("calibrationScaleAtReview", l.calibrationScaleAtReview)
            w.field("keyPointsTotal", l.keyPointsTotal).field("keyPointsRecalled", l.keyPointsRecalled)
            w.field("parameterSetId", l.parameterSetId)
            w.field("reviewMethods", l.reviewMethods)
            w.field("questionsCorrect", l.questionsCorrect).field("questionsTotal", l.questionsTotal)
            w.field("sessionKind", l.sessionKind)
            w.endObject()
        }
        w.endArray()
        w.name("memoryParameterSets").beginArray()
        for (s in parameterSets) {
            w.beginObject()
            w.field("id", s.id).field("createdAt", s.createdAt).field("status", s.status).field("weights", s.weights)
            w.field("comparedWithSetId", s.comparedWithSetId).field("availableReviews", s.availableReviews)
            w.field("trainReviews", s.trainReviews).field("testReviews", s.testReviews)
            w.field("currentLogLoss", s.currentLogLoss).field("candidateLogLoss", s.candidateLogLoss)
            w.field("currentRmseBins", s.currentRmseBins).field("candidateRmseBins", s.candidateRmseBins)
            w.field("currentAuc", s.currentAuc).field("candidateAuc", s.candidateAuc).field("zScore", s.zScore)
            w.field("activatedAt", s.activatedAt).field("retiredAt", s.retiredAt)
            w.endObject()
        }
        w.endArray()
        w.name("eventLogs").beginArray()
        for (e in events) {
            w.beginObject()
            w.field("id", e.id).field("at", e.at).field("type", e.type)
            w.field("unitId", e.unitId).field("detail", e.detail)
            w.endObject()
        }
        w.endArray()

        // The user's study setup, so a restore on a new phone brings back reminder time, language,
        // retention target, exam countdown, etc. — not just the topics.
        val sp = context.getSharedPreferences("medreview_settings", Context.MODE_PRIVATE)
        w.name("settings").beginObject()
        SETTINGS_STRING_KEYS.forEach { k -> sp.getString(k, null)?.let { w.field(k, it) } }
        SETTINGS_BOOL_KEYS.forEach { k -> if (sp.contains(k)) w.field(k, sp.getBoolean(k, false)) }
        SETTINGS_INT_KEYS.forEach { k -> if (sp.contains(k)) w.field(k, sp.getInt(k, 0)) }
        SETTINGS_FLOAT_KEYS.forEach { k -> if (sp.contains(k)) w.field(k, sp.getFloat(k, 0f).toDouble()) }
        SETTINGS_LONG_KEYS.forEach { k -> if (sp.contains(k)) w.field(k, sp.getLong(k, 0L)) }
        w.endObject()

        w.endObject()
        w.flush()
    }

    /** [writeBackup] into a String, for tests and other small callers. */
    suspend fun buildBackupJson(context: Context): String =
        java.io.ByteArrayOutputStream().also { writeBackup(context, it) }.toString(Charsets.UTF_8.name())

    /** Far above any backup the app can write (years of heavy use are ~15 MB), still bounded. */
    const val MAX_BACKUP_BYTES = 512L * 1024 * 1024

    /** [restoreFromStream] from a String, for tests and other small callers. */
    suspend fun restoreFromJson(context: Context, json: String): Int =
        restoreFromStream(context, json.byteInputStream(Charsets.UTF_8))

    /**
     * Replace ALL data with the backup read from [input]. Returns the number of study units restored.
     *
     * The file is read record by record straight into entities ([JsonStreams]) and validated in full BEFORE
     * anything current is touched. Only then is a safety copy of the current data written, and the
     * database replaced in one transaction. The caller owns and closes [input].
     */
    suspend fun restoreFromStream(context: Context, input: java.io.InputStream): Int {
        val db = (context.applicationContext as MedReviewApplication).database

        // Strict validation: a damaged backup must ABORT the restore, not be silently coerced into
        // fake-plausible history (e.g. every corrupt rating becoming "Good" would poison FSRS replay
        // and analytics). The thrown message surfaces as "Restore failed — invalid backup".
        val validMemory = setOf("Forgot", "Hard", "Good", "Easy")
        val validUnderstanding = setOf("Confused", "Partial", "Clear", "NotAsked") // NotAsked = skipped question (Forgot fast-commit)

        var sawUnits = false
        var sawLogs = false
        var fileVersion = 1
        val subjects = ArrayList<SubjectEntity>()
        val systems = ArrayList<SystemEntity>()
        val units = ArrayList<StudyUnitEntity>()
        val parameterSets = ArrayList<com.example.data.local.entity.MemoryParameterSetEntity>()
        val logs = ArrayList<ReviewLogEntity>()
        val events = ArrayList<com.example.data.local.entity.EventLogEntity>()
        var settings: JSONObject? = null

        val text = java.io.PushbackReader(
            java.io.InputStreamReader(java.io.BufferedInputStream(JsonStreams.Bounded(input, MAX_BACKUP_BYTES)), Charsets.UTF_8),
        )
        // A byte-order mark (a file re-saved by a Windows text editor) is not JSON; skip it rather than fail.
        text.read().let { first -> if (first >= 0 && first != 0xFEFF) text.unread(first) }
        val reader = android.util.JsonReader(text)
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "backupVersion" -> fileVersion = (reader.readValue() as? Number)?.toInt() ?: 1
                "subjects" -> reader.forEachRecord { _, o ->
                    subjects += SubjectEntity(
                        id = o.getLong("id"), name = o.getString("name"),
                        colorHex = o.strOrNull("colorHex"), createdAt = o.optLong("createdAt", System.currentTimeMillis())
                    )
                }
                "systems" -> reader.forEachRecord { _, o ->
                    systems += SystemEntity(
                        id = o.getLong("id"), name = o.getString("name"),
                        colorHex = o.strOrNull("colorHex"), createdAt = o.optLong("createdAt", System.currentTimeMillis())
                    )
                }
                "studyUnits" -> {
                    // REQUIRED, and a LIST. The reader treats JSON null as "no records", so `"studyUnits": null`
                    // used to pass as an empty library and replace every topic with nothing (found by an outside
                    // audit, 2026-09-27; the safety copy still held the data). A real empty library is [].
                    requireList(reader, "studyUnits")
                    sawUnits = true
                    reader.forEachRecord { _, o ->
                        units += StudyUnitEntity(
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
                            // Soft-deleted implies archived: every active query reads `archived = 0` alone. An
                            // edited file saying deleted-but-active would put a topic in Today that the purge then
                            // removes, so the deletion the file records wins (restorable from Recently deleted).
                            archived = o.optBoolean("archived", false) || o.longOrNull("deletedAt") != null,
                            // Pre-v5 backups: the effective date was the only date — same backfill the migration uses.
                            modelDueAt = o.optLong("modelDueAt", o.optLong("nextReviewAt", System.currentTimeMillis())),
                            deferredUntil = o.longOrNull("deferredUntil"),
                            deletedAt = o.longOrNull("deletedAt"),
                            understandingDueAt = o.longOrNull("understandingDueAt"),
                            // Pre-v6 files predate the model split, and everything in them was FSRS-5 by
                            // definition. Falling back to the entity default would claim the same thing, but
                            // saying it explicitly keeps the intent obvious at the restore site.
                            // An EXPLICIT model this build does not know (a backup from a newer Yadora) is refused
                            // below instead of being read as FSRS-5: a stability means nothing on the wrong curve.
                            memoryModel = o.optString("memoryModel", "FSRS-5").ifBlank { "FSRS-5" },
                            // Pre-v7 files have no key points; absent reads as none, the same as the migration.
                            keyPoints = o.strOrNull("keyPoints"),
                            // Pre-v8 files: everything was computed by the published defaults, set 0.
                            parameterSetId = o.optLong("parameterSetId", 0L),
                        )
                    }
                }
                // The personal weight sets, validated like everything else: a set that ever scheduled must
                // decode to 21 weights inside the reference bounds, or replaying the topics on it would be
                // impossible.
                "memoryParameterSets" -> reader.forEachRecord { _, o ->
                    parameterSets += com.example.data.local.entity.MemoryParameterSetEntity(
                        id = o.optLong("id", 0L), createdAt = o.optLong("createdAt", 0L), status = o.optString("status", ""),
                        weights = o.optString("weights", ""), comparedWithSetId = o.optLong("comparedWithSetId", 0L),
                        availableReviews = o.optInt("availableReviews", 0), trainReviews = o.optInt("trainReviews", 0),
                        testReviews = o.optInt("testReviews", 0),
                        currentLogLoss = o.optDouble("currentLogLoss", -1.0), candidateLogLoss = o.optDouble("candidateLogLoss", -1.0),
                        currentRmseBins = o.optDouble("currentRmseBins", -1.0), candidateRmseBins = o.optDouble("candidateRmseBins", -1.0),
                        currentAuc = o.optDouble("currentAuc", -1.0), candidateAuc = o.optDouble("candidateAuc", -1.0),
                        zScore = o.optDouble("zScore", 0.0),
                        activatedAt = o.longOrNull("activatedAt"), retiredAt = o.longOrNull("retiredAt"),
                    )
                }
                "reviewLogs" -> reader.also { requireList(it, "reviewLogs"); sawLogs = true }.forEachRecord { i, o ->
                    val memory = o.optString("memoryRating", "")
                    val understanding = o.optString("understandingRating", "")
                    val unitRef = o.optLong("studyUnitId", -1L)
                    require(memory in validMemory) { "Damaged backup: invalid memory rating '$memory' (log ${i + 1})" }
                    require(understanding in validUnderstanding) { "Damaged backup: invalid understanding rating '$understanding' (log ${i + 1})" }
                    // A score is either absent (-1/-1) or a real count of ticked points out of at least one shown.
                    val kpTotal = o.optInt("keyPointsTotal", -1)
                    val kpRecalled = o.optInt("keyPointsRecalled", -1)
                    require((kpTotal == -1 && kpRecalled == -1) || (kpTotal >= 1 && kpRecalled in 0..kpTotal)) {
                        "Damaged backup: invalid key-point score $kpRecalled/$kpTotal (log ${i + 1})"
                    }
                    val logSetId = o.optLong("parameterSetId", 0L)
                    // v9 research fields. Absent in older files = not recorded. A question score is either absent or
                    // a real count; methods and session kind are re-encoded so only known names are stored.
                    val qCorrect = o.optInt("questionsCorrect", -1)
                    val qTotal = o.optInt("questionsTotal", -1)
                    require((qCorrect == -1 && qTotal == -1) || com.example.domain.model.QuestionScore.isValid(qCorrect, qTotal)) {
                        "Damaged backup: invalid question score $qCorrect/$qTotal (log ${i + 1})"
                    }
                    val methods = com.example.domain.model.ReviewMethod.encode(
                        com.example.domain.model.ReviewMethod.decode(o.strOrNull("reviewMethods"))
                    )
                    val sessionKind = o.strOrNull("sessionKind")?.takeIf { k ->
                        com.example.domain.model.SessionKind.entries.any { it.name == k }
                    }
                    logs += ReviewLogEntity(
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
                        calibrationScaleAtReview = o.optDouble("calibrationScaleAtReview", -1.0),
                        keyPointsTotal = kpTotal,
                        keyPointsRecalled = kpRecalled,
                        parameterSetId = logSetId,
                        reviewMethods = methods,
                        questionsCorrect = qCorrect,
                        questionsTotal = qTotal,
                        sessionKind = sessionKind,
                    )
                }
                // v1 backups have no eventLogs array; that's fine — restore just clears the table.
                "eventLogs" -> reader.forEachRecord { _, o ->
                    events += com.example.data.local.entity.EventLogEntity(
                        id = o.optLong("id", 0L),
                        at = o.optLong("at", System.currentTimeMillis()),
                        type = o.optString("type", "UNKNOWN"),
                        unitId = o.longOrNull("unitId"),
                        detail = o.strOrNull("detail")
                    )
                }
                "settings" -> settings = reader.readValue() as? JSONObject
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        require(sawUnits) { "This file is not a Yadora backup." }
        // Every backup Yadora has written, from v1 on, carries its review history. Topics that were rated in a
        // file whose history is missing or empty mean a damaged file, and restoring it would erase the learner's
        // history while the topics still claimed their reviews (an outside audit reproduced it, 2026-09-28).
        if (units.any { it.reviewCount > 0 }) {
            require(sawLogs && logs.isNotEmpty()) { "Damaged backup: its topics were reviewed, but the file has no review history" }
        }
        val knownModels = com.example.domain.srs.MedScheduler.MemoryModel.entries.mapTo(HashSet()) { it.id }
        units.forEachIndexed { i, u ->
            require(u.memoryModel in knownModels) {
                "Topic ${i + 1} uses a memory model this version of Yadora does not know (${u.memoryModel}) — update the app first."
            }
        }

        require(parameterSets.all { it.id > 0 }) { "Damaged backup: memory model with invalid id" }
        require(parameterSets.map { it.id }.toSet().size == parameterSets.size) { "Damaged backup: duplicate memory model ids" }
        val statuses = setOf(
            com.example.data.local.entity.MemoryParameterSetEntity.ACTIVE,
            com.example.data.local.entity.MemoryParameterSetEntity.RETIRED,
            com.example.data.local.entity.MemoryParameterSetEntity.REJECTED,
        )
        require(parameterSets.all { it.status in statuses }) { "Damaged backup: memory model with invalid status" }
        require(parameterSets.count { it.status == com.example.data.local.entity.MemoryParameterSetEntity.ACTIVE } <= 1) {
            "Damaged backup: more than one active memory model"
        }
        val usableSetIds = parameterSets
            .filter { it.status != com.example.data.local.entity.MemoryParameterSetEntity.REJECTED }
            .onEach { s ->
                require(com.example.domain.srs.Fsrs6Optimizer.decode(s.weights) != null) { "Damaged backup: memory model ${s.id} has invalid weights" }
            }
            .mapTo(HashSet()) { it.id } + 0L
        units.forEachIndexed { i, u ->
            require(u.parameterSetId in usableSetIds) { "Damaged backup: topic ${i + 1} references a missing memory model" }
        }
        // Whole-file preflight: reject structurally corrupt topics BEFORE any current data is deleted.
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
            // The loosest legitimate floor across models (FSRS-6 allows 0.001, FSRS-5 0.01) -- this
            // check exists to reject zero/negative/NaN corruption, not to re-impose a model bound.
            require(u.stability >= com.example.domain.srs.Fsrs6.S_MIN && u.stability.isFinite()) {
                "Damaged backup: invalid stability (topic ${i + 1})"
            }
            require(u.difficulty in 1.0..10.0) { "Damaged backup: invalid difficulty (topic ${i + 1})" }
            require(u.currentIntervalDays >= 0.0 && u.currentIntervalDays.isFinite()) { "Damaged backup: invalid interval (topic ${i + 1})" }
            require(u.reviewCount >= 0 && u.lapseCount >= 0) { "Damaged backup: invalid counts (topic ${i + 1})" }
            require(u.nextReviewAt > 0L && u.studiedAt > 0L) { "Damaged backup: invalid dates (topic ${i + 1})" }
        }

        // Every log must resolve to a topic and to a weight set in this same file. Checked after the whole
        // file is read: the arrays can come in any order.
        val unitIds = units.mapTo(HashSet()) { it.id }
        logs.forEachIndexed { i, l ->
            require(l.studyUnitId in unitIds) { "Damaged backup: review log ${i + 1} references missing topic ${l.studyUnitId}" }
            require(l.parameterSetId in usableSetIds) { "Damaged backup: review log ${i + 1} references a missing memory model" }
        }
        // REPLACE-by-id semantics in the restore rely on log ids being unique within the file.
        require(logs.map { it.id }.toSet().size == logs.size) { "Damaged backup: duplicate review-log ids" }
        require(logs.all { it.id > 0 }) { "Damaged backup: review log with invalid id" }

        // Safety net: restore is all-or-nothing, so before touching anything, snapshot the CURRENT
        // data to a private file. If the user imports the wrong backup, their real data is still
        // recoverable from files/last_before_restore_backup.json.
        // ABORT restore if the safety copy can't be created (e.g. storage full): destroying the
        // only copy of the user's data without a recovery net is never acceptable. Temp + rename so
        // a crash mid-write can't leave a truncated safety file that LOOKS valid. Streamed to disk,
        // like every backup, so a long history cannot run the restore out of memory here.
        run {
            val tmp = context.filesDir.resolve("last_before_restore_backup.json.tmp")
            tmp.outputStream().use { writeBackup(context, it) }
            check(tmp.length() > 0L) { "Safety copy could not be written" }
            val dest = context.filesDir.resolve("last_before_restore_backup.json")
            if (dest.exists()) dest.delete()
            check(tmp.renameTo(dest)) { "Safety copy could not be finalized" }
        }

        db.withTransaction {
            db.eventLogDao().deleteAll() // always: "replace ALL data" must not leave stale events behind
            db.reviewLogDao().deleteAllLogs()
            db.studyUnitDao().deleteAllUnits()
            db.categoryDao().deleteAllSubjects()
            db.categoryDao().deleteAllSystems()
            db.memoryParameterSetDao().deleteAll()
            parameterSets.forEach { db.memoryParameterSetDao().insert(it) }
            subjects.forEach { db.categoryDao().insertSubject(it) }
            systems.forEach { db.categoryDao().insertSystem(it) }
            units.forEach { db.studyUnitDao().insertUnit(it) }
            logs.forEach { db.reviewLogDao().insertLog(it) }
            events.forEach { db.eventLogDao().insert(it) }
        }
        // The scheduler must see the restored weight sets before anything schedules again.
        runCatching { (context.applicationContext as MedReviewApplication).repository.refreshMemoryModel() }

        // Restore the study setup too (v3+ backups; older files simply have no settings object).
        // Language/theme apply fully on the next app start; the caller already re-arms the reminder.
        settings?.let { s ->
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
            // Checked but NOT thrown. The database transaction above has already committed, so
            // raising here would surface as "Restore failed -- invalid backup" for a valid backup
            // whose topics are sitting restored on disk. Settings are the recoverable half: the user
            // can see and reset them. Losing the truth about their topics is not recoverable.
            if (!e.commit()) android.util.Log.w("Yadora", "topics restored, but settings could not be written")
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
            // A personal memory model is fitted from the user's reviews: it is their data too.
            db.memoryParameterSetDao().deleteAll()
        }
        com.example.domain.srs.MedScheduler.knownParameterSets = emptyMap()
        com.example.domain.srs.MedScheduler.activeParameterSet = com.example.domain.srs.MedScheduler.DEFAULT_PARAMETER_SET
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
        // language_selected must survive with it. Keeping the language but dropping the flag sent the
        // user back through language onboarding after a wipe -- the opposite of the stated intent.
        val keptLanguageSelected = sp.getBoolean("language_selected", false)
        @Suppress("ApplySharedPref")
        val prefsCleared = sp.edit().clear()
            .putString("app_language", keptLanguage)
            .putBoolean("language_selected", keptLanguageSelected)
            .commit()
        // NOT a check(): the database was already wiped several lines above, irreversibly. Throwing
        // here would make the caller report "your data is still on this device" about data that is
        // provably gone -- a worse lie than the one this failure path exists to prevent. Leftover
        // settings are cosmetic; the destructive part succeeded, so the success message stands.
        if (!prefsCleared) android.util.Log.w("Yadora", "data deleted, but settings could not be cleared")
        // Device-local reminder bookkeeping lives in its own file (kept out of cloud backup), so
        // clearing settings alone would leave a stale "already shown today" / snooze behind.
        runCatching {
            com.example.notifications.NotificationScheduler.transientPrefs(context)
                .edit { clear() }
        }
        runCatching {
            com.example.domain.srs.MedScheduler.userRetention =
                sp.getFloat("desired_retention", 0.90f).toDouble()
        }
        // Remove local diagnostic/safety files so nothing personal lingers on disk.
        runCatching { context.filesDir.resolve("crash.log").delete() }
        runCatching { context.filesDir.resolve("last_before_restore_backup.json").delete() }
        runCatching { context.filesDir.resolve("last_before_restore_backup.json.tmp").delete() }
        // The last research export written for sharing (subject names, device model, every review's timing).
        runCatching { context.cacheDir.resolve("exports").deleteRecursively() }
        runCatching { com.example.widget.DueWidgetProvider.updateAll(context) }
    }

    /** A section that holds records must be a list: null or anything else is a damaged file, never "none". */
    private fun requireList(reader: android.util.JsonReader, name: String) =
        require(reader.peek() == android.util.JsonToken.BEGIN_ARRAY) { "Damaged backup: \"$name\" is not a list" }

    private fun JSONObject.strOrNull(key: String): String? = if (isNull(key)) null else optString(key)
    private fun JSONObject.longOrNull(key: String): Long? = if (isNull(key)) null else optLong(key)
}
