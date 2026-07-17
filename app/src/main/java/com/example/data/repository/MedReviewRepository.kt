package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.local.dao.CategoryDao
import com.example.data.local.dao.ReviewLogDao
import com.example.data.local.dao.StudyUnitDao
import com.example.data.local.entity.ReviewLogEntity
import com.example.data.local.entity.StudyUnitEntity
import com.example.data.local.entity.SubjectEntity
import com.example.data.local.entity.SystemEntity
import com.example.domain.model.MemoryRating
import com.example.domain.model.UnderstandingRating
import com.example.domain.srs.MedScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class MedReviewRepository(
    private val studyUnitDao: StudyUnitDao,
    private val categoryDao: CategoryDao,
    private val reviewLogDao: ReviewLogDao,
    private val database: com.example.data.local.database.AppDatabase
) {
    // Categories
    val allSubjects: Flow<List<SubjectEntity>> = categoryDao.getAllSubjects()
    val allSystems: Flow<List<SystemEntity>> = categoryDao.getAllSystems()

    suspend fun insertSubject(name: String, colorHex: String? = null): Long {
        return categoryDao.insertSubject(SubjectEntity(name = name, colorHex = colorHex))
    }

    suspend fun insertSystem(name: String): Long {
        return categoryDao.insertSystem(SystemEntity(name = name))
    }
    
    // Study Units
    val activeUnits: Flow<List<StudyUnitEntity>> = studyUnitDao.getAllActiveUnits()
    val archivedUnits: Flow<List<StudyUnitEntity>> = studyUnitDao.getArchivedUnits()
    
    fun getDueUnits(cutoffTime: Long): Flow<List<StudyUnitEntity>> {
        return studyUnitDao.getDueUnits(cutoffTime)
    }

    suspend fun getUnitById(id: Long): StudyUnitEntity? {
        return studyUnitDao.getUnitById(id)
    }

    /**
     * True when an active topic with the same title AND the same distinguishing details already
     * exists (so it's a real duplicate). Same title but a different subject/notes/source is allowed —
     * the user is deliberately keeping two related-but-distinct topics.
     */
    suspend fun isDuplicate(title: String, subjectId: Long?, notes: String?, source: String?, excludeId: Long = 0L): Boolean {
        fun norm(s: String?) = (s ?: "").trim().lowercase()
        return studyUnitDao.findActiveByTitle(title).any { u ->
            u.id != excludeId &&
                u.subjectId == subjectId &&
                norm(u.notes) == norm(notes) &&
                norm(u.source) == norm(source)
        }
    }

    suspend fun insertUnit(unit: StudyUnitEntity): Long {
        return studyUnitDao.insertUnit(unit)
    }

    suspend fun updateUnit(unit: StudyUnitEntity) {
        studyUnitDao.updateUnit(unit)
    }

    /** Update a batch atomically — a crash mid-way must not leave a half-applied recovery plan. */
    suspend fun updateUnitsAtomic(units: List<StudyUnitEntity>) {
        database.withTransaction {
            units.forEach { studyUnitDao.updateUnit(it) }
        }
    }

    /**
     * Update a unit AND replay its review history from the (possibly moved) study date, atomically.
     * Used when editing a topic changes its studiedAt: the study date is the replay origin, so the
     * whole schedule must be recomputed from it — this is what makes the edit screen's "recalculates
     * this topic's review history" caption true. If the replay aborts (corrupt log), the studiedAt
     * change rolls back with it, so history and schedule never disagree.
     */
    suspend fun updateUnitReplayingHistory(unit: StudyUnitEntity) {
        database.withTransaction {
            studyUnitDao.updateUnit(unit)
            // logId = -1 matches no log → pure replay, no rating substituted (args unused).
            editReviewRating(unit.id, -1L, MemoryRating.Good, UnderstandingRating.Clear)
        }
    }

    suspend fun archiveUnit(id: Long) {
        studyUnitDao.archiveUnit(id, System.currentTimeMillis())
    }

    suspend fun unarchiveUnit(id: Long) {
        studyUnitDao.unarchiveUnit(id, System.currentTimeMillis())
    }

    // --- 30-day recoverable soft delete (DB v5) ---

    val recentlyDeleted: Flow<List<StudyUnitEntity>> = studyUnitDao.getRecentlyDeleted()

    suspend fun softDeleteUnit(id: Long) {
        studyUnitDao.softDeleteUnit(id, System.currentTimeMillis())
    }

    suspend fun restoreDeletedUnit(id: Long) {
        studyUnitDao.restoreDeletedUnit(id, System.currentTimeMillis())
    }

    /** Hard-delete topics whose 30-day grace expired, HISTORY FIRST so a crash can't orphan logs. */
    suspend fun purgeExpiredDeleted(graceMillis: Long = 30L * 24 * 60 * 60 * 1000) {
        val cutoff = System.currentTimeMillis() - graceMillis
        val ids = studyUnitDao.getPurgeCandidateIds(cutoff)
        if (ids.isEmpty()) return
        database.withTransaction {
            reviewLogDao.deleteLogsForUnits(ids)
            studyUnitDao.hardDeleteUnits(ids)
        }
    }

    /**
     * An ARCHIVED (non-deleted) topic with this exact normalized title, if one exists — so re-adding
     * a topic you archived offers "restore it, with its whole history" instead of a duplicate.
     */
    suspend fun findArchivedDuplicate(title: String): StudyUnitEntity? {
        fun norm(s: String) = s.trim().lowercase()
        return studyUnitDao.findByTitleAnyState(title)
            .firstOrNull { it.archived && it.deletedAt == null && norm(it.title) == norm(title) }
    }
    
    // Progress / Stats
    val totalActiveCount: Flow<Int> = studyUnitDao.getTotalActiveUnitsCount()
    
    fun getCountByState(state: String): Flow<Int> {
        return studyUnitDao.getCountByState(state)
    }
    
    fun getReviewsCountSince(time: Long): Flow<Int> {
        return reviewLogDao.getReviewsCountSince(time)
    }
    
    // Logs
    suspend fun insertReviewLog(log: ReviewLogEntity) {
        reviewLogDao.insertLog(log)
    }

    fun getLogsForUnit(unitId: Long): Flow<List<ReviewLogEntity>> {
        return reviewLogDao.getLogsForUnit(unitId)
    }

    fun getLogsSince(sinceTime: Long): Flow<List<ReviewLogEntity>> {
        return reviewLogDao.getLogsSince(sinceTime)
    }

    suspend fun deleteLastLogForUnit(unitId: Long) {
        reviewLogDao.deleteLastLogForUnit(unitId)
    }

    suspend fun deleteLogById(logId: Long) {
        reviewLogDao.deleteLogById(logId)
    }

    /** Every committed study action's timestamp — feeds the growth visual (survives topic deletion). */
    fun studyActionTimes(): Flow<List<Long>> = database.eventLogDao().observeStudyActionTimes()

    /**
     * Per-topic "Not today": push ONE unit to [until] as a USER DEFERRAL — v5 semantics, exactly like
     * the bulk paths: nextReviewAt + deferredUntil move together, modelDueAt is untouched, and the
     * schedule change + its audit event land in ONE transaction (a crash can't record one without
     * the other). The FSRS memory state is never touched — this is not a review.
     */
    suspend fun procrastinateUnit(id: Long, until: Long) {
        database.withTransaction {
            val unit = studyUnitDao.getUnitById(id) ?: return@withTransaction
            studyUnitDao.updateUnit(
                unit.copy(nextReviewAt = until, deferredUntil = until, updatedAt = System.currentTimeMillis())
            )
            database.eventLogDao().insert(
                com.example.data.local.entity.EventLogEntity(
                    type = "PROCRASTINATE", unitId = id, detail = "${unit.nextReviewAt}->$until"
                )
            )
        }
    }

    /** Record a non-review action (procrastinate / redistribute / snooze) for the behavioural log. */
    suspend fun logEvent(type: String, unitId: Long? = null, detail: String? = null) {
        database.eventLogDao().insert(
            com.example.data.local.entity.EventLogEntity(type = type, unitId = unitId, detail = detail)
        )
    }

    /**
     * Persist a review atomically: the unit's schedule, its log, AND its growth event land in one
     * transaction (keyed by the log id, so undo can remove exactly this event). Returns the log id.
     */
    suspend fun commitReview(updatedUnit: StudyUnitEntity, log: ReviewLogEntity): Long {
        var logId = 0L
        database.withTransaction {
            studyUnitDao.updateUnit(updatedUnit)
            logId = reviewLogDao.insertLog(log)
            database.eventLogDao().insert(
                com.example.data.local.entity.EventLogEntity(
                    type = "STUDY_ACTION", unitId = updatedUnit.id, detail = logId.toString()
                )
            )
        }
        return logId
    }

    /**
     * Undo a review atomically — the mirror of [commitReview]. Restoring the unit, deleting the log,
     * and removing the growth event must be one transaction; a crash between them would leave the
     * schedule, the history, and the growth visual disagreeing with each other.
     */
    suspend fun undoReview(previousUnit: StudyUnitEntity, logId: Long) {
        database.withTransaction {
            studyUnitDao.updateUnit(previousUnit)
            reviewLogDao.deleteLogById(logId)
            database.eventLogDao().deleteStudyActionForLog(logId.toString())
        }
    }

    /**
     * Correct a past review's difficulty/understanding (e.g. you later realize a topic wasn't as easy
     * as you first thought). Rather than try to "un-apply" one review, this REPLAYS the whole review
     * history for the unit with the edited rating substituted in, so every downstream interval and the
     * unit's current memory state + next due date are recomputed consistently. No schema change needed —
     * the logs already store every rating and timestamp.
     *
     * Pass [logId] = -1 for a PURE replay (no rating substituted): used after the topic's study date
     * changes, so the schedule is recomputed from the new origin instead of silently diverging from it.
     */
    suspend fun editReviewRating(
        unitId: Long,
        logId: Long,
        newMemory: MemoryRating,
        newUnderstanding: UnderstandingRating,
    ) {
        val unit = studyUnitDao.getUnitById(unitId) ?: return
        // Deterministic ordering: two logs can share a millisecond (restored/synthetic data) — break
        // ties by insertion id so replay order can never silently differ between runs.
        val logs = reviewLogDao.getLogsForUnit(unitId).first()
            .sortedWith(compareBy({ it.reviewedAt }, { it.id }))
        if (logs.isEmpty()) return

        // Seed the replay from the SAME state AddUnit wrote (firstStudy Partial), so a back-dated
        // topic's first review (which builds on this seed via nextState) replays exactly like the live
        // path did — otherwise editing any old rating would silently shift every downstream interval.
        val replaySeed = MedScheduler.firstStudy(UnderstandingRating.Partial, unit.highYield).state
        var stability = replaySeed.stability
        var difficulty = replaySeed.difficulty
        var prevTime = unit.studiedAt
        var prevStateName = "New"
        var prevInterval = 0.0
        var reviewCount = 0
        var lapseCount = 0
        var lastReviewedAt = unit.studiedAt
        var lastInterval = 0.0
        var lastStability = stability
        var lastDifficulty = difficulty
        var lastStateName = unit.state
        val updatedLogs = ArrayList<ReviewLogEntity>(logs.size)

        for (log in logs) {
            // Strict: a corrupt rating must ABORT the correction (callers catch and leave the topic
            // untouched), never be silently replayed as fake "Good/Clear" history.
            val mem = if (log.id == logId) newMemory
                else runCatching { MemoryRating.valueOf(log.memoryRating) }
                    .getOrElse { throw IllegalStateException("Review log ${log.id} has an invalid memory rating '${log.memoryRating}'") }
            // "NotAsked" = the understanding question was skipped (Forgot fast-commit). Math-neutral:
            // Forgot's interval ignores understanding entirely, so Partial stands in for computation
            // while the stored string stays honestly NotAsked (undStored below).
            val und = when {
                log.id == logId -> newUnderstanding
                log.understandingRating == "NotAsked" -> UnderstandingRating.Partial
                else -> runCatching { UnderstandingRating.valueOf(log.understandingRating) }
                    .getOrElse { throw IllegalStateException("Review log ${log.id} has an invalid understanding rating '${log.understandingRating}'") }
            }
            val undStored = if (log.id == logId) newUnderstanding.name
                else if (log.understandingRating == "NotAsked") "NotAsked" else und.name
            val elapsed = ((log.reviewedAt - prevTime) / 86400000.0).coerceAtLeast(0.0)

            // Timezone-stable classification: trust the logType RECORDED at review time. Recomputing
            // it from timestamps here would use the DEVICE'S CURRENT timezone — a user who travels
            // could silently flip an old first-study into a recall (or vice versa) just by editing a
            // rating. Only legacy UNKNOWN rows fall back to timestamp reconstruction.
            val reviewNumber = when (log.logType) {
                "FIRST_STUDY" -> 0
                "RECALL" -> maxOf(reviewCount, 1)
                else -> MedScheduler.effectiveReviewNumber(unit.studiedAt, log.reviewedAt, reviewCount)
            }
            // Replay each review under its HISTORICAL conditions (retention target + importance at the
            // time, stored per-log since v4) — editing one old rating must not silently rewrite the
            // whole history under today's settings. Pre-v4 rows (-1 sentinels) fall back to current.
            val histRetention = log.desiredRetentionAtReview.takeIf { it in 0.70..0.99 }
            val histImportant = if (log.wasImportantAtReview >= 0) log.wasImportantAtReview == 1 else unit.highYield
            // Policy snapshot (v5): an UNTOUCHED log replays under the understanding factor originally
            // applied; the log being EDITED gets the current policy's factor (it's a new decision).
            val histFactor = if (log.id != logId) log.understandingFactorAtReview.takeIf { it > 0.0 } else null
            val outcome = MedScheduler.review(
                stability = stability,
                difficulty = difficulty,
                elapsedDays = elapsed,
                memoryRating = mem,
                understanding = und,
                highYield = histImportant,
                reviewNumber = reviewNumber,
                desiredRetentionOverride = histRetention,
                understandingFactorOverride = histFactor,
            )
            // Same deterministic fuzz as the live commit (seeded by unit + prior review count, which
            // is exactly what this loop counter holds at this step) — replay==live.
            val interval = MedScheduler.fuzzedInterval(outcome.intervalDays, outcome.baseIntervalDays, unit.id, reviewCount)
            val nextStateName = MedScheduler.masteryState(outcome.state.stability, mem == MemoryRating.Forgot).name

            // Stage the corrected log; everything is written atomically in one transaction below.
            updatedLogs.add(
                log.copy(
                    memoryRating = mem.name,
                    understandingRating = undStored,
                    previousIntervalDays = prevInterval,
                    nextIntervalDays = interval,
                    previousState = prevStateName,
                    nextState = nextStateName,
                    retrievabilityAtReview = outcome.retrievabilityAtReview,
                    elapsedDays = elapsed,
                    logType = if (reviewNumber == 0) "FIRST_STUDY" else "RECALL",
                    // Derived the same way live does; backfills pre-v4 rows as a side effect. All
                    // other v4 context fields (duration, retention-at-review…) are preserved by copy().
                    initialDifficulty = if (reviewNumber == 0) MedScheduler.difficultyLabelFor(mem) else null,
                    // v5 policy snapshot: record the factor actually used this replay (the stored one
                    // for untouched rows, the current policy's for the edited row / pre-v5 backfill).
                    schedulerPolicyVersion = log.schedulerPolicyVersion.ifEmpty { MedScheduler.POLICY_VERSION },
                    understandingFactorAtReview = histFactor ?: MedScheduler.understandingFactor(und),
                )
            )

            stability = outcome.state.stability
            difficulty = outcome.state.difficulty
            if (mem == MemoryRating.Forgot) lapseCount++
            reviewCount++
            prevTime = log.reviewedAt
            prevStateName = nextStateName
            prevInterval = interval
            lastReviewedAt = log.reviewedAt
            lastInterval = interval
            lastStability = outcome.state.stability
            lastDifficulty = outcome.state.difficulty
            lastStateName = nextStateName
        }

        // Re-anchor the next due date to the last (corrected) review time + recomputed interval, then write
        // the corrected logs + unit ATOMICALLY so a crash mid-replay can't desync history and schedule.
        val finalUnit = unit.copy(
            stability = lastStability,
            difficulty = lastDifficulty,
            state = lastStateName,
            reviewCount = reviewCount,
            lapseCount = lapseCount,
            currentIntervalDays = lastInterval,
            lastReviewedAt = lastReviewedAt,
            nextReviewAt = lastReviewedAt + (lastInterval * 86400000).toLong(),
            // A replay recomputes the MODEL's truth, and any prior user deferral is superseded by it.
            modelDueAt = lastReviewedAt + (lastInterval * 86400000).toLong(),
            deferredUntil = null,
            updatedAt = System.currentTimeMillis(),
        )
        database.withTransaction {
            updatedLogs.forEach { reviewLogDao.insertLog(it) }
            studyUnitDao.updateUnit(finalUnit)
        }
    }
}
