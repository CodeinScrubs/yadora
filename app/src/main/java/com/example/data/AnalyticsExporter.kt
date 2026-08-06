package com.example.data

import android.content.Context
import androidx.room.withTransaction
import com.example.MedReviewApplication
import org.json.JSONArray
import org.json.JSONObject

/**
 * Exports a diagnostic/analytics snapshot of study units + review logs as JSON. NOT fully anonymous:
 * it includes subject/collection names, device manufacturer/model, and the crash-log tail (the
 * Settings copy discloses exactly this). Topic titles and notes are never included.
 *
 * Each unit's review history (the ordered sequence of `reviewedAt` + `memoryRating`) plus its current
 * FSRS state (stability/difficulty/reviewCount/lapseCount) is exactly what's needed to later analyze
 * how well the scheduler is performing and to optimize FSRS parameters / desired retention across
 * several users. Titles and notes are intentionally omitted; still treat it as a SENSITIVE
 * diagnostic file (it names subjects/collections, device model, crash tail), not an anonymous one.
 */
object AnalyticsExporter {

    suspend fun buildJson(context: Context): String {
        val app = context.applicationContext as MedReviewApplication
        val unitDao = app.database.studyUnitDao()
        val logDao = app.database.reviewLogDao()

        // Recently-deleted topics included: their logs are still in the DB, and an export where logs
        // reference a missing topic row would be internally inconsistent for analysis.
        // ONE transaction so every table reflects the same instant.
        lateinit var units: List<com.example.data.local.entity.StudyUnitEntity>
        lateinit var logs: List<com.example.data.local.entity.ReviewLogEntity>
        lateinit var events: List<com.example.data.local.entity.EventLogEntity>
        lateinit var subjects: List<com.example.data.local.entity.SubjectEntity>
        lateinit var systems: List<com.example.data.local.entity.SystemEntity>
        app.database.withTransaction {
            units = unitDao.getAllActiveOnce() + unitDao.getArchivedOnce() + unitDao.getRecentlyDeletedOnce()
            logs = logDao.getAllLogsOnce()
            events = app.database.eventLogDao().getAll()
            subjects = app.database.categoryDao().getAllSubjectsOnce()
            systems = app.database.categoryDao().getAllSystemsOnce()
        }
        val sp = context.getSharedPreferences("medreview_settings", Context.MODE_PRIVATE)

        val root = JSONObject()
        // v3: v5 honest-scheduling fields (modelDueAt/deferredUntil/deletedAt), per-log policy
        // snapshot, log ids (join key for STUDY_ACTION events), reminder/exam context.
        // v4: per-log adherence (scheduledForAt/daysLate) + the scheduler's own policy constants, so an
        // interval in the data can be checked against the policy that produced it without the source.
        // v5: per-topic memoryModel + understandingDueAt, and the model/parameter identity in the
        // policy block. Calibration MUST group by model — pooling FSRS-5 and FSRS-6 outcomes would
        // average two different curves and make the result meaningless.
        root.put("exportVersion", 5)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("appVersionName", com.example.BuildConfig.VERSION_NAME) // never goes stale on version bumps
        root.put("scheduler", com.example.domain.srs.MedScheduler.CURRENT_MODEL.id)
        root.put("unitCount", units.size)
        root.put("reviewLogCount", logs.size)

        // Device fingerprint: lets a missed-reminder report be correlated with OEM battery-killers
        // (Xiaomi/Huawei/Oppo/Vivo/Samsung) when several friends' exports are compared.
        root.put("device", JSONObject().apply {
            put("manufacturer", android.os.Build.MANUFACTURER)
            put("model", android.os.Build.MODEL)
            put("sdkInt", android.os.Build.VERSION.SDK_INT)
        })

        // Tail of the local crash log (written by the uncaught-exception handler) — a crash on a
        // friend's phone is otherwise invisible with no backend.
        val crashTail = runCatching {
            java.io.File(context.filesDir, "crash.log").takeIf { it.exists() }?.readText()?.takeLast(8000)
        }.getOrNull()
        root.put("lastCrashLog", crashTail ?: JSONObject.NULL)

        // Subject/system id → name maps, so exported unit ids are interpretable without the raw DB.
        root.put("subjects", JSONObject().apply { subjects.forEach { put(it.id.toString(), it.name) } })
        root.put("systems", JSONObject().apply { systems.forEach { put(it.id.toString(), it.name) } })

        root.put("settings", JSONObject().apply {
            put("language", sp.getString("app_language", "en"))
            put("dailyReviewLimit", sp.getFloat("daily_review_limit", 50f).toInt())
            put("reminderHour", sp.getInt("reminder_hour", 20))
            put("reminderMinute", sp.getInt("reminder_minute", 0))
            // The retention actually in force is the USER setting (+0.03 for important topics) —
            // the constants alone would mislead analysis. Per-review truth is in each log's
            // desiredRetentionAtReview; this is the current global setting.
            put("userDesiredRetention", sp.getFloat("desired_retention", 0.90f).toDouble())
            put("defaultBaseRetention", com.example.domain.srs.MedScheduler.BASE_RETENTION)
            put("importantRetentionBonus", 0.03)
            put("policyVersion", com.example.domain.srs.MedScheduler.POLICY_VERSION)
            // Adherence context: whether reminders were even on, and the exam horizon (a deadline
            // changes study behavior — the analysis must be able to see it).
            put("dailyReminderEnabled", sp.getBoolean("daily_reminder", true))
            put("alarmModeEnabled", sp.getBoolean("alarm_enabled", false))
            put("alarmSilenced", sp.getBoolean("alarm_silenced", false))
            // Decorative BY DESIGN: the countdown never feeds the scheduler. Exported so analysis can
            // see the deadline the user was studying against, not because it changed any interval.
            put("examDate", sp.getLong("exam_date", 0L))
        })

        // The product-layer constants in force. An interval alone can't be judged without them: a
        // 5-day first review means one thing under a 5-day cap and another without it, and reading a
        // year-old export should not require digging out the matching source revision.
        root.put("policy", JSONObject().apply {
            put("version", com.example.domain.srs.MedScheduler.POLICY_VERSION)
            // The model that schedules NEW reviews, and the exact frozen weight vector it uses. A
            // retrained vector must ship a new id, or two different sets of weights would be pooled
            // under one name and the calibration numbers would silently stop meaning anything.
            put("memoryModel", com.example.domain.srs.MedScheduler.CURRENT_MODEL.id)
            put("parameterSetId", com.example.domain.srs.Fsrs6Parameters.DEFAULT_PARAMETER_SET_ID)
            put("legacyModelRetainedForReplay", com.example.domain.srs.MedScheduler.MemoryModel.FSRS_5.id)
            // Understanding is a separate clock now, not a multiplier on the memory interval.
            put("understandingIsSeparateClock", true)
            put("firstStudyMaxDays", com.example.domain.srs.MedScheduler.FIRST_STUDY_MAX_DAYS)
            put("relearnStepDays", com.example.domain.srs.MedScheduler.RELEARN_STEP_DAYS)
            put("minIntervalDays", com.example.domain.srs.MedScheduler.MIN_INTERVAL_DAYS)
            put("understandingPartialFactor", com.example.domain.srs.MedScheduler.UNDERSTANDING_PARTIAL_FACTOR)
            put("understandingConfusedFactor", com.example.domain.srs.MedScheduler.UNDERSTANDING_CONFUSED_FACTOR)
            put("highYieldRetention", com.example.domain.srs.MedScheduler.HIGH_YIELD_RETENTION)
            put("examDateAffectsScheduling", false)
        })

        val unitsArr = JSONArray()
        for (u in units) {
            unitsArr.put(JSONObject().apply {
                put("id", u.id)
                put("studyType", u.studyType)
                put("subjectId", u.subjectId ?: JSONObject.NULL)
                put("systemId", u.systemId ?: JSONObject.NULL)
                put("highYield", u.highYield)
                put("state", u.state)
                put("stability", u.stability)
                put("difficulty", u.difficulty)
                put("currentIntervalDays", u.currentIntervalDays)
                put("reviewCount", u.reviewCount)
                put("lapseCount", u.lapseCount)
                put("createdAt", u.createdAt)
                put("studiedAt", u.studiedAt)
                put("lastReviewedAt", u.lastReviewedAt ?: JSONObject.NULL)
                put("nextReviewAt", u.nextReviewAt)
                put("archived", u.archived)
                // v5 honest scheduling: modelDueAt vs deferredUntil is what lets the analysis tell
                // "the science said X" apart from "the user moved it to Y".
                put("modelDueAt", u.modelDueAt)
                put("deferredUntil", u.deferredUntil ?: JSONObject.NULL)
                put("deletedAt", u.deletedAt ?: JSONObject.NULL)
                put("understandingDueAt", u.understandingDueAt ?: JSONObject.NULL)
                put("memoryModel", u.memoryModel)
            })
        }
        root.put("studyUnits", unitsArr)

        // ADHERENCE: when was this review actually DUE, and how late was it answered?
        // Nothing stores that directly, but it is exactly reconstructable: a review answers the date
        // set by the previous review (its reviewedAt + the interval it granted), and the first rating
        // answers the topic's study date. Without this the export cannot distinguish "the scheduler
        // chose a bad interval" from "the user answered eleven days late", which is THE question when
        // judging scheduling quality. Derived here rather than stored, so it also covers old history.
        val unitStudiedAt = units.associate { it.id to it.studiedAt }
        val scheduledForByLog = HashMap<Long, Long>(logs.size)
        for ((unitId, unitLogs) in logs.groupBy { it.studyUnitId }) {
            var dueAt = unitStudiedAt[unitId]
            for (l in unitLogs.sortedWith(compareBy({ it.reviewedAt }, { it.id }))) {
                if (dueAt != null) scheduledForByLog[l.id] = dueAt
                dueAt = l.reviewedAt + (l.nextIntervalDays * 86400000.0).toLong()
            }
        }

        val logsArr = JSONArray()
        for (l in logs) {
            logsArr.put(JSONObject().apply {
                put("id", l.id) // join key: STUDY_ACTION events carry the log id in `detail`
                val scheduledFor = scheduledForByLog[l.id]
                put("scheduledForAt", scheduledFor ?: JSONObject.NULL)
                // Negative = answered EARLY (cramming ahead), positive = answered late.
                put(
                    "daysLate",
                    if (scheduledFor == null) JSONObject.NULL
                    else (l.reviewedAt - scheduledFor) / 86400000.0
                )
                put("studyUnitId", l.studyUnitId)
                put("reviewedAt", l.reviewedAt)
                put("memoryRating", l.memoryRating)
                put("understandingRating", l.understandingRating)
                put("previousIntervalDays", l.previousIntervalDays)
                put("nextIntervalDays", l.nextIntervalDays)
                put("previousState", l.previousState)
                put("nextState", l.nextState)
                put("retrievabilityAtReview", l.retrievabilityAtReview)
                put("elapsedDays", l.elapsedDays)
                put("logType", l.logType) // FIRST_STUDY rows are difficulty answers, not recall grades
                put("initialDifficulty", l.initialDifficulty ?: JSONObject.NULL)
                put("reviewDurationMs", l.reviewDurationMs)
                put("wasImportantAtReview", l.wasImportantAtReview)
                put("desiredRetentionAtReview", l.desiredRetentionAtReview)
                put("schedulerVersion", l.schedulerVersion)
                put("schedulerPolicyVersion", l.schedulerPolicyVersion)
                put("understandingFactorAtReview", l.understandingFactorAtReview)
            })
        }
        root.put("reviewLogs", logsArr)

        val eventsArr = JSONArray()
        for (e in events) {
            eventsArr.put(JSONObject().apply {
                put("at", e.at)
                put("type", e.type)
                put("unitId", e.unitId ?: JSONObject.NULL)
                put("detail", e.detail ?: JSONObject.NULL)
            })
        }
        root.put("eventLogs", eventsArr)

        return root.toString(2)
    }
}
