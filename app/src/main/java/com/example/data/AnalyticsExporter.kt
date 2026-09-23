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
        lateinit var parameterSets: List<com.example.data.local.entity.MemoryParameterSetEntity>
        app.database.withTransaction {
            parameterSets = app.database.memoryParameterSetDao().getAll()
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
        // v6: the device TIME ZONE (without it nothing in this file can be recomputed — see below),
        // a self-check block that makes the export say where it disagrees with itself, and honest
        // provenance on the reconstructed adherence fields.
        // v7: per-topic hasRecallPrompt — whether a topic defines what recall means. Only the flag:
        // the prompt text is the user's own content and stays out, exactly like titles and notes. It is
        // what lets calibration later be compared between topics that do and don't define "remembered".
        // v8: per-log calibrationScaleAtReview — the per-user interval correction each review was
        // scheduled with, so an interval in the data can still be recomputed from its inputs.
        // v9: key points. Per topic only their COUNT (the points themselves are the user's own content,
        // like titles and notes); per log how the review scored against them, which is what lets the
        // ratings of scored and unscored topics be compared.
        // v10: the personal memory model — every fit attempt with its held-out scores and weights, and the
        // weight set each topic and review belongs to. Calibration MUST group by (model, set).
        // v11: reviews are done by any method (questions, notes, a lecture, a video) and rated by how much
        // the learner still had when they came back to it. The key-point rating cap is retired, so every
        // new log records keyPointsTotal/keyPointsRecalled = -1 (older logs keep their scores), and the
        // daily limit counts the reviews already done today (dailyLimitIsPerDay).
        root.put("exportVersion", 11)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("appVersionName", com.example.BuildConfig.VERSION_NAME) // never goes stale on version bumps
        root.put("appVersionCode", com.example.BuildConfig.VERSION_CODE)
        root.put("scheduler", com.example.domain.srs.MedScheduler.CURRENT_MODEL.id)
        root.put("unitCount", units.size)
        root.put("reviewLogCount", logs.size)

        // THE TIME ZONE IS NOT OPTIONAL METADATA. FSRS-6 is fed elapsed time in whole LOCAL calendar
        // days, so `elapsedDays` on every log is a difference of local dates — it cannot be derived
        // from the UTC millisecond timestamps in this file without knowing which zone produced it.
        // An export analysed in the wrong zone will silently disagree with the app by up to a day on
        // every review. Due dates, by contrast, are elapsed-millisecond arithmetic; the two
        // conventions are deliberate and are documented in the policy block.
        val zone = java.util.TimeZone.getDefault()
        root.put("environment", JSONObject().apply {
            put("timeZoneId", zone.id)
            put("utcOffsetMinutesAtExport", zone.getOffset(System.currentTimeMillis()) / 60000)
            put("observesDaylightSaving", zone.useDaylightTime())
            put("localDateAtExport", java.time.LocalDate.now().toString())
            put("locale", java.util.Locale.getDefault().toLanguageTag())
            put("elapsedDaysConvention", "whole local calendar days (FSRS-6); fractional ms (FSRS-5, frozen)")
            put("dueDateConvention", "reviewedAt + intervalDays * 86400000 (elapsed ms, not calendar addition)")
        })

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
            put("dailyReviewLimit", com.example.domain.srs.MedScheduler.safeDailyLimit(sp.getFloat("daily_review_limit", 50f)))
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
            // The value ACTUALLY in force, not the legacy HIGH_YIELD_RETENTION constant: the real
            // target is the user's own setting + 0.03 capped at 0.97, so exporting a fixed 0.93
            // would describe a policy no review was ever scheduled under.
            put("baseRetentionInForce", com.example.domain.srs.MedScheduler.effectiveRetention(false))
            put("highYieldRetentionInForce", com.example.domain.srs.MedScheduler.effectiveRetention(true))
            put("highYieldRetentionBonus", 0.03)
            put("examDateAffectsScheduling", false)
            // The key-point cap applied to logs that carry a score (keyPointsTotal >= 1), all written before
            // v11. Retired since: new reviews are never capped.
            put("keyPointRatingCeiling", "retired 2026-09-23; before that, all recalled: any rating; at least half: up to Hard; fewer: Forgot")
            put("dailyLimitIsPerDay", true)
            // The weight set scheduling NEW reviews: 0 = the published defaults named by parameterSetId above.
            put(
                "activeParameterSetId",
                parameterSets.lastOrNull { it.status == com.example.data.local.entity.MemoryParameterSetEntity.ACTIVE }?.id ?: 0L,
            )
            // What Fsrs6Optimizer.fitAndValidate actually does: the history cut in time into FOLDS + 1 chunks,
            // each of the last FOLDS predicted by a fit on the reviews before it. It used to say "held-out
            // later 20%", which described an earlier single-split gate.
            put(
                "personalModelGate",
                "${com.example.domain.srs.Fsrs6Optimizer.FOLDS} time-series folds (each later chunk predicted by a fit on earlier reviews), " +
                    "paired per-review log loss, one-sided z >= ${com.example.domain.srs.Fsrs6Optimizer.ACCEPT_Z}",
            )
        })

        // Every attempt to fit the memory model to this learner, adopted or not, with the held-out evidence
        // it was judged on. Weights are model parameters, not user content.
        root.put("memoryParameterSets", JSONArray().apply {
            for (s in parameterSets) put(JSONObject().apply {
                put("id", s.id); put("createdAt", s.createdAt); put("status", s.status)
                put("weights", JSONArray().apply { com.example.domain.srs.Fsrs6Optimizer.decode(s.weights)?.forEach { put(it) } })
                put("comparedWithSetId", s.comparedWithSetId); put("availableReviews", s.availableReviews)
                put("trainReviews", s.trainReviews); put("testReviews", s.testReviews)
                put("currentLogLoss", s.currentLogLoss); put("candidateLogLoss", s.candidateLogLoss)
                put("currentRmseBins", s.currentRmseBins); put("candidateRmseBins", s.candidateRmseBins)
                put("currentAuc", s.currentAuc); put("candidateAuc", s.candidateAuc); put("zScore", s.zScore)
                put("activatedAt", s.activatedAt ?: JSONObject.NULL); put("retiredAt", s.retiredAt ?: JSONObject.NULL)
            })
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
                put("hasRecallPrompt", !u.recallPrompt.isNullOrBlank())
                put("keyPointCount", com.example.domain.srs.KeyPoints.parse(u.keyPoints).size)
                put("parameterSetId", u.parameterSetId)
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

        // How many times the user moved this topic's date between the previous review and this one.
        // The event log already records every such action; joining them here means the analysis does
        // not have to reimplement the join (and get it subtly wrong).
        val deferralTypes = setOf("PROCRASTINATE", "PROCRASTINATE_ALL", "REDISTRIBUTE")
        val deferralsByLog = HashMap<Long, Int>(logs.size)
        for ((unitId, unitLogs) in logs.groupBy { it.studyUnitId }) {
            val ordered = unitLogs.sortedWith(compareBy({ it.reviewedAt }, { it.id }))
            var windowStart = unitStudiedAt[unitId] ?: 0L
            for (l in ordered) {
                deferralsByLog[l.id] = events.count { e ->
                    e.type in deferralTypes && e.at in windowStart..l.reviewedAt &&
                        // PROCRASTINATE_ALL / REDISTRIBUTE are bulk actions with no unit id: they
                        // moved every due topic, so they count for whatever was due at the time.
                        (e.unitId == null || e.unitId == unitId)
                }
                windowStart = l.reviewedAt
            }
        }

        val logsArr = JSONArray()
        for (l in logs) {
            logsArr.put(JSONObject().apply {
                put("id", l.id) // join key: STUDY_ACTION events carry the log id in `detail`
                val scheduledFor = scheduledForByLog[l.id]
                put("scheduledForAt", scheduledFor ?: JSONObject.NULL)
                // HONESTY ABOUT PROVENANCE. scheduledForAt is RECONSTRUCTED (previous review +
                // the interval it granted), not recorded at the time. That reconstruction is exact
                // only when nothing moved the date in between. If the user deferred, redistributed
                // the backlog, or the understanding clock pulled the topic in early, the real
                // scheduled date was different — and daysLate would then blame the learner (or the
                // model) for a date neither of them chose. This counts the user-initiated moves that
                // landed in the window, so an analysis can discard or down-weight those rows instead
                // of trusting a number that looks precise.
                put("scheduledForAtSource", "reconstructed")
                put("deferralsBeforeThisReview", deferralsByLog[l.id] ?: 0)
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
                put("calibrationScaleAtReview", l.calibrationScaleAtReview)
                put("keyPointsTotal", l.keyPointsTotal)
                put("keyPointsRecalled", l.keyPointsRecalled)
                put("parameterSetId", l.parameterSetId)
            })
        }
        root.put("reviewLogs", logsArr)

        // SELF-CHECK. The export states where the database disagrees with itself, so a problem is
        // visible in the file rather than having to be suspected and hunted for. Every one of these
        // is an invariant the app is supposed to maintain; a non-empty list is a bug report, not a
        // statistic. Deliberately descriptive — it never repairs anything, because a silent repair
        // would destroy the evidence of whatever caused it.
        val issues = JSONArray()
        fun flag(kind: String, unitId: Long?, detail: String) {
            issues.put(JSONObject().apply {
                put("kind", kind); put("unitId", unitId ?: JSONObject.NULL); put("detail", detail)
            })
        }
        val logsByUnit = logs.groupBy { it.studyUnitId }
        val unitIdSet = units.map { it.id }.toSet()
        val usableSetIds = parameterSets
            .filter { it.status != com.example.data.local.entity.MemoryParameterSetEntity.REJECTED }
            .mapTo(HashSet()) { it.id } + 0L
        for (l in logs) {
            if (l.studyUnitId !in unitIdSet) flag("ORPHAN_LOG", l.studyUnitId, "log ${l.id} references a topic not in this export")
            if (l.parameterSetId !in usableSetIds) flag("UNKNOWN_PARAMETER_SET", l.studyUnitId, "log ${l.id} names weight set ${l.parameterSetId}")
        }
        for (u in units) {
            if (u.parameterSetId !in usableSetIds) flag("UNKNOWN_PARAMETER_SET", u.id, "topic names weight set ${u.parameterSetId}")
        }
        for (u in units) {
            val mine = logsByUnit[u.id].orEmpty().sortedWith(compareBy({ it.reviewedAt }, { it.id }))
            // A graded review is the first log plus every non-FIRST_STUDY log; later FIRST_STUDY rows
            // are re-encoding exposures (post-merge) and must not be counted as retrievals.
            val graded = mine.filterIndexed { i, log -> i == 0 || log.logType != "FIRST_STUDY" }
            if (graded.isNotEmpty() && u.reviewCount != graded.size) {
                flag("REVIEW_COUNT_MISMATCH", u.id, "row says ${u.reviewCount}, history has ${graded.size}")
            }
            val lapses = graded.count { it.memoryRating == "Forgot" }
            if (graded.isNotEmpty() && u.lapseCount != lapses) {
                flag("LAPSE_COUNT_MISMATCH", u.id, "row says ${u.lapseCount}, history has $lapses")
            }
            if (!u.stability.isFinite() || u.stability <= 0.0) flag("BAD_STABILITY", u.id, "stability=${u.stability}")
            if (!u.difficulty.isFinite() || u.difficulty !in 1.0..10.0) flag("BAD_DIFFICULTY", u.id, "difficulty=${u.difficulty}")
            // nextReviewAt must be the earlier of the two clocks unless the USER moved it.
            val expectedEffective = listOfNotNull(u.modelDueAt.takeIf { it > 0L }, u.understandingDueAt).minOrNull()
            if (u.deferredUntil == null && expectedEffective != null && u.nextReviewAt != expectedEffective) {
                flag("CLOCK_DISAGREEMENT", u.id, "nextReviewAt=${u.nextReviewAt} but min(model,understanding)=$expectedEffective")
            }
            if (u.deferredUntil != null && u.nextReviewAt != u.deferredUntil) {
                flag("DEFERRAL_DISAGREEMENT", u.id, "deferredUntil=${u.deferredUntil} but nextReviewAt=${u.nextReviewAt}")
            }
            // A row's model must own its newest log, or a stability is being read on the wrong curve.
            val newestModel = mine.lastOrNull()?.schedulerVersion?.takeIf { it.isNotBlank() }
            if (newestModel != null && u.memoryModel == "FSRS-6" && newestModel == "FSRS-5") {
                flag("MODEL_OWNERSHIP", u.id, "row is FSRS-6 but its newest log was written by FSRS-5")
            }
            if (mine.count { it.logType == "FIRST_STUDY" } > 1 && mine.firstOrNull()?.logType != "FIRST_STUDY") {
                flag("SEED_ORDERING", u.id, "multiple FIRST_STUDY rows and the earliest log is not one of them")
            }
        }
        root.put("consistency", JSONObject().apply {
            put("checkedUnits", units.size)
            put("checkedLogs", logs.size)
            put("issueCount", issues.length())
            put("issues", issues)
            put(
                "note",
                "Each entry is a violated invariant, i.e. a bug. Empty is the expected result. " +
                    "Counts are compared against replayable history, never repaired here.",
            )
        })

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
