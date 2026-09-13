package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.local.dao.CategoryDao
import com.example.data.local.dao.ReviewLogDao
import com.example.data.local.dao.StudyUnitDao
import com.example.data.local.entity.ReviewLogEntity
import com.example.data.local.entity.StudyUnitEntity
import com.example.data.text.TopicTitle
import com.example.data.local.entity.SubjectEntity
import com.example.data.local.entity.SystemEntity
import com.example.domain.model.MemoryRating
import com.example.domain.model.StudyState
import com.example.domain.model.UnderstandingRating
import com.example.domain.srs.MedScheduler
import com.example.domain.srs.MemoryState
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
        fun norm(s: String?) = TopicTitle.normalize(s ?: "")
        // Scanned in Kotlin rather than matched in SQL: SQLite compares bytes with an ASCII-only
        // lower(), which cannot see that Persian text typed on two different keyboards is the same
        // word (see TopicTitle). One in-memory pass over active topics on save is cheap; missing the
        // duplicate warning for the exact case this app was built around is not.
        return studyUnitDao.getAllActiveOnce().any { u ->
            TopicTitle.sameTopic(u.title, title) &&
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
            if (reviewLogDao.getLogsForUnit(unit.id).first().isNotEmpty()) {
                // logId = -1 matches no log → pure replay, no rating substituted (args unused).
                editReviewRating(unit.id, -1L, MemoryRating.Good, UnderstandingRating.Clear)
            } else if (unit.deferredUntil == null) {
                // NEVER RATED: there is no history to replay, but the product rule is "a topic is due on
                // its study date", so moving that date must move the due date with it. Without this, a
                // topic back-dated after creation kept its original due date and could sit due BEFORE
                // the day the user says they studied it. A user deferral is left alone — they picked
                // that date deliberately.
                studyUnitDao.updateUnit(unit.copy(nextReviewAt = unit.studiedAt, modelDueAt = unit.studiedAt))
            }
        }
    }

    /**
     * Bring a topic onto the CURRENT memory model before its next review.
     *
     * An FSRS-5 stability cannot simply be relabelled as an FSRS-6 stability: the two models fit
     * different curves, so the same number means different things and handing one to the other would
     * silently corrupt the schedule. What IS portable is the user's actual history — the ratings and
     * the times they happened — so the state is REBUILT by replaying that history through FSRS-6.
     *
     * Deliberately lazy: this runs at the moment of the next review rather than during migration, so
     * upgrading the app moves nothing, and a topic the user never touches again is never rewritten.
     * Idempotent — once `memoryModel` is FSRS-6 it returns the row unchanged.
     *
     * Re-encoding exposures (a later FIRST_STUDY row, only possible after a merge) are skipped, for
     * the same reason the FSRS-5 replay skips them: a re-study is not a retrieval and must not earn
     * recall credit. An unrated topic has nothing to project, so it just adopts the new model id.
     */
    suspend fun projectOntoCurrentModel(unit: StudyUnitEntity): StudyUnitEntity {
        if (MedScheduler.MemoryModel.of(unit.memoryModel) == MedScheduler.CURRENT_MODEL) return unit
        return projectWithHistory(unit, reviewLogDao.getLogsForUnitOnce(unit.id))
    }

    /**
     * The pure half of [projectOntoCurrentModel], taking the history rather than fetching it, so a
     * caller already inside a transaction (merge) can project without collecting a Flow there.
     */
    private fun projectWithHistory(unit: StudyUnitEntity, history: List<ReviewLogEntity>): StudyUnitEntity {
        if (MedScheduler.MemoryModel.of(unit.memoryModel) == MedScheduler.CURRENT_MODEL) return unit

        val logs = history.sortedWith(compareBy({ it.reviewedAt }, { it.id }))
        if (logs.isEmpty()) {
            // Never rated: no evidence to replay, so only the model label changes.
            return unit.copy(memoryModel = MedScheduler.CURRENT_MODEL.id, updatedAt = System.currentTimeMillis())
        }

        var state: MemoryState? = null
        var prevTime = unit.studiedAt
        var lapses = 0
        var reviews = 0
        var lastGradedRating: String? = null
        for ((index, log) in logs.withIndex()) {
            // A LATER first-study row is a re-encoding exposure (only reachable after a merge). It
            // earns no stability and no review credit, but it DOES re-anchor the elapsed-time clock:
            // the next retrieval is measured from the day the material was last actually studied.
            //
            // This must match editReviewRating's replay exactly. It did not: this loop used to drop
            // exposures from the list entirely, so prevTime was never advanced and the following
            // recall was credited with the time since the previous GRADED review instead. For a
            // history of study/recall/re-study/recall that is 20 elapsed days here against 10 in the
            // replay -- the same evidence reconstructed two different ways, so a topic's state
            // depended on whether it arrived via migration or via a rating correction.
            if (index > 0 && log.logType == "FIRST_STUDY") {
                prevTime = log.reviewedAt
                continue
            }
            val grade = runCatching { MemoryRating.valueOf(log.memoryRating) }.getOrNull() ?: continue
            // Projection rebuilds under the CURRENT model, so time is measured its way too.
            val elapsed = MedScheduler.modelElapsedDays(prevTime, log.reviewedAt, MedScheduler.CURRENT_MODEL)
            val highYield = if (log.wasImportantAtReview >= 0) log.wasImportantAtReview == 1 else unit.highYield
            state = MedScheduler.projectStep(state, elapsed, grade, highYield)
            if (grade == MemoryRating.Forgot) lapses++
            reviews++
            lastGradedRating = log.memoryRating
            prevTime = log.reviewedAt
        }
        val projected = state ?: return unit.copy(memoryModel = MedScheduler.CURRENT_MODEL.id)

        // The SCHEDULE is not recomputed here. The user was promised a date by the old model and that
        // promise is kept; only the latent state moves onto the new model, and the next real review
        // schedules from it. Counts are re-derived from the same evidence so they cannot drift.
        return unit.copy(
            stability = projected.stability,
            difficulty = projected.difficulty,
            reviewCount = reviews,
            lapseCount = lapses,
            state = MedScheduler.masteryState(
                projected.stability,
                justForgot = lastGradedRating == MemoryRating.Forgot.name,
            ).name,
            memoryModel = MedScheduler.CURRENT_MODEL.id,
            updatedAt = System.currentTimeMillis(),
        )
    }

    suspend fun archiveUnit(id: Long) {
        studyUnitDao.archiveUnit(id, System.currentTimeMillis())
    }

    suspend fun unarchiveUnit(id: Long) {
        studyUnitDao.unarchiveUnit(id, System.currentTimeMillis())
    }

    /** Batch archive/unarchive is one transaction so selection-mode actions are all-or-nothing. */
    suspend fun archiveUnits(ids: Collection<Long>) {
        val stamp = System.currentTimeMillis()
        database.withTransaction { ids.forEach { studyUnitDao.archiveUnit(it, stamp) } }
    }

    suspend fun unarchiveUnits(ids: Collection<Long>) {
        val stamp = System.currentTimeMillis()
        database.withTransaction { ids.forEach { studyUnitDao.unarchiveUnit(it, stamp) } }
    }

    /**
     * Merge duplicate topics (the same material added twice — often in two languages, e.g.
     * "Appendicitis" and "آپاندیسیت") into one, keeping [keepId] as the surviving topic.
     *
     * The whole point is that work already done on EITHER copy still counts, so:
     *  - every review log is RE-POINTED at the survivor, never deleted;
     *  - reviewCount / lapseCount are summed, because they really were that many reviews;
     *  - stability and difficulty are a weighted average, weighted by how many reviews each copy
     *    actually had, so the result sits nearer the copy you worked on most (a 12-review topic
     *    dominates a 1-review one). An unrated copy still carries weight 1 rather than 0, so merging
     *    into a brand-new topic can't silently erase its own starting state;
     *  - the next due date is the EARLIEST of the copies. Merging must never push material further
     *    away than the schedule you already had for it;
     *  - the merged-away copies are SOFT-deleted, so a mistaken merge is recoverable for 30 days.
     *
     * All of it is one transaction: a crash mid-merge must not strand history on a deleted topic.
     * No schema change — this is a re-pointing of existing rows.
     */
    suspend fun mergeUnits(keepId: Long, mergeIds: Collection<Long>): StudyUnitEntity? {
        val requestedIds = mergeIds.filter { it != keepId }.distinct()
        if (requestedIds.isEmpty()) return null
        return database.withTransaction {
            // Never merge a topic that is already in the recycle bin. getUnitById deliberately does
            // NOT filter soft-deleted rows (restore/purge need them), so without this guard an
            // ALREADY-ABSORBED copy could be merged a second time and its stale counts folded in
            // again — permanently inflating the survivor's reviewCount and skewing its memory state.
            val survivorRow = studyUnitDao.getUnitById(keepId)?.takeIf { it.deletedAt == null }
                ?: return@withTransaction null
            val absorbedRows = requestedIds
                .mapNotNull { studyUnitDao.getUnitById(it) }
                .filter { it.deletedAt == null }
            if (absorbedRows.isEmpty()) return@withTransaction null
            val absorbIds = absorbedRows.map { it.id }

            // Bring EVERY copy onto the current memory model before averaging anything. Two copies of
            // the same material can sit on different models — one reviewed since the upgrade, one not
            // — and an FSRS-5 stability is not measured in the same units as an FSRS-6 one. Averaging
            // across them would produce a number belonging to neither model and silently mis-time the
            // topic from then on. Projection replays each copy's real history, so nothing is lost.
            val survivor = projectWithHistory(survivorRow, reviewLogDao.getLogsForUnitOnce(survivorRow.id))
            val absorbed = absorbedRows.map { projectWithHistory(it, reviewLogDao.getLogsForUnitOnce(it.id)) }

            val all = listOf(survivor) + absorbed
            // Weight by evidence: an unrated copy counts once, a well-drilled copy counts per review.
            val weights = all.map { maxOf(it.reviewCount, 1).toDouble() }
            val totalWeight = weights.sum()
            fun weighted(pick: (StudyUnitEntity) -> Double): Double =
                all.indices.sumOf { pick(all[it]) * weights[it] } / totalWeight

            val mergedStability = weighted { it.stability }
            val mergedDifficulty = weighted { it.difficulty }
            val earliestDue = all.minOf { it.nextReviewAt }

            val merged = survivor.copy(
                stability = mergedStability,
                difficulty = mergedDifficulty,
                currentIntervalDays = weighted { it.currentIntervalDays },
                reviewCount = all.sumOf { it.reviewCount },
                lapseCount = all.sumOf { it.lapseCount },
                highYield = all.any { it.highYield }, // importance is a union: if either mattered, it matters
                // The recall prompt defines what "remembering" this topic means, so a merge must not drop
                // one: keep the survivor's if it has one, otherwise the first absorbed copy's.
                recallPrompt = all.firstNotNullOfOrNull { it.recallPrompt?.takeIf { p -> p.isNotBlank() } },
                // A relearn in progress is a union too: if ANY copy was just forgotten, the merged
                // topic is still relearning. masteryState() can only ever return NeedsRelearn when
                // told a lapse just happened, so deriving state purely from stability would quietly
                // strip the queue priority that a just-lapsed topic depends on.
                state = if (all.any { it.state == StudyState.NeedsRelearn.name }) StudyState.NeedsRelearn.name
                    else MedScheduler.masteryState(mergedStability, justForgot = false).name,
                lastReviewedAt = all.mapNotNull { it.lastReviewedAt }.maxOrNull(),
                nextReviewAt = earliestDue,
                // modelDueAt takes the earliest MODEL date, never `earliestDue`: nextReviewAt may be a
                // date the user deferred to, and the v5 honest-scheduling rule is that a deferral must
                // never be laundered into the memory model's own opinion.
                modelDueAt = all.minOf { it.modelDueAt },
                // The understanding clock follows the same never-push-further-away rule: if ANY copy
                // still owed a comprehension repair, the merged topic still owes it. Taking the
                // earliest also keeps nextReviewAt explainable — it must equal the earlier of the two
                // clocks, and dropping this would leave a topic due earlier than either of them.
                understandingDueAt = all.mapNotNull { it.understandingDueAt }.minOrNull(),
                // Every copy was just projected, so the merged state is expressed in one model.
                memoryModel = MedScheduler.CURRENT_MODEL.id,
                deferredUntil = null, // the merged topic is a fresh, un-deferred schedule
                updatedAt = System.currentTimeMillis(),
            )

            reviewLogDao.reassignLogs(absorbIds, keepId)
            studyUnitDao.updateUnit(merged)

            val stamp = System.currentTimeMillis()
            absorbed.forEach { copy ->
                // The absorbed copy's HISTORY now belongs to the survivor, so its own counters and
                // memory state are no longer backed by anything. Reset them to an unrated baseline
                // before soft-deleting: otherwise restoring it from "Recently deleted" would hand the
                // user a topic claiming reviews it doesn't own, with zero logs to justify them.
                val seed = MedScheduler.firstStudy(UnderstandingRating.Partial, copy.highYield).state
                studyUnitDao.updateUnit(
                    copy.copy(
                        stability = seed.stability,
                        difficulty = seed.difficulty,
                        currentIntervalDays = 0.0,
                        reviewCount = 0,
                        lapseCount = 0,
                        state = StudyState.New.name,
                        // Reset to the same shape a brand-new topic has, model label included: the
                        // seed above comes from the FSRS-5 first-study prior, and labelling the row
                        // FSRS-6 would claim a state expressed in units it was not computed in. With
                        // no history left it re-seeds at its first rating either way.
                        memoryModel = MedScheduler.MemoryModel.FSRS_5.id,
                        lastReviewedAt = null,
                        nextReviewAt = copy.studiedAt,
                        modelDueAt = copy.studiedAt,
                        // No history left to owe a repair for; a stale deadline would otherwise make
                        // a restored copy due on a date nothing in its (now empty) record justifies.
                        understandingDueAt = null,
                        deferredUntil = null,
                        updatedAt = stamp,
                    )
                )
                studyUnitDao.softDeleteUnit(copy.id, stamp)
            }
            database.eventLogDao().insert(
                com.example.data.local.entity.EventLogEntity(
                    type = "MERGE", unitId = keepId, detail = absorbIds.joinToString(","),
                )
            )
            merged
        }
    }

    // --- 30-day recoverable soft delete (DB v5) ---

    val recentlyDeleted: Flow<List<StudyUnitEntity>> = studyUnitDao.getRecentlyDeleted()

    suspend fun softDeleteUnit(id: Long) {
        studyUnitDao.softDeleteUnit(id, System.currentTimeMillis())
    }

    suspend fun softDeleteUnits(ids: Collection<Long>) {
        val stamp = System.currentTimeMillis()
        database.withTransaction { ids.forEach { studyUnitDao.softDeleteUnit(it, stamp) } }
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
    suspend fun findArchivedDuplicate(title: String): StudyUnitEntity? =
        studyUnitDao.getArchivedOnce()
            .firstOrNull { it.deletedAt == null && TopicTitle.sameTopic(it.title, title) }
    
    // Progress / Stats
    val totalActiveCount: Flow<Int> = studyUnitDao.getTotalActiveUnitsCount()
    
    fun getCountByState(state: String): Flow<Int> {
        return studyUnitDao.getCountByState(state)
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

    /**
     * The per-user interval correction ([com.example.domain.srs.RecallCalibration]) from this user's
     * most recent real recall reviews under the live model. 1.0 with no evidence. Read at app start
     * and at the start of every review session into `MedScheduler.calibrationScale`.
     */
    suspend fun recallCalibrationScale(): Double {
        val logs = reviewLogDao.getRecentRecallLogsOnce(
            MedScheduler.CURRENT_MODEL.id,
            com.example.domain.srs.RecallCalibration.MIN_ELAPSED_DAYS,
            com.example.domain.srs.RecallCalibration.WINDOW,
        ).filter { it.retrievabilityAtReview in 0.0..1.0 } // the query already excludes the -1 sentinel; belt and braces
        return com.example.domain.srs.RecallCalibration.scale(
            predicted = logs.map { it.retrievabilityAtReview }.toDoubleArray(),
            recalled = logs.map { it.memoryRating != MemoryRating.Forgot.name }.toBooleanArray(),
        )
    }

    /**
     * How many answers in a row, counting back from the topic's latest log, left understanding
     * unrepaired — the input the repair-clock backoff needs (`MedScheduler.unrepairedStreak`). Read
     * from the logs, the single source of truth, so the preview, the commit and the replay agree.
     */
    suspend fun unrepairedStreak(unitId: Long): Int = MedScheduler.unrepairedStreak(
        reviewLogDao.getLogsForUnitOnce(unitId).map { it.memoryRating to it.understandingRating },
    )

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
        newUnderstanding: UnderstandingRating?,
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
        val replayModel = MedScheduler.MemoryModel.of(unit.memoryModel)
        var lastRemediationDays: Double? = null
        // The unrepaired-understanding streak in force before each log, rebuilt from the same rule
        // the live path applies to the same rows (MedScheduler.continuesUnrepairedStreak).
        var unrepairedStreak = 0
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
            val editingThis = log.id == logId
            val und = when {
                editingThis && newUnderstanding != null -> newUnderstanding
                log.understandingRating == "NotAsked" -> UnderstandingRating.Partial
                else -> runCatching { UnderstandingRating.valueOf(log.understandingRating) }
                    .getOrElse { throw IllegalStateException("Review log ${log.id} has an invalid understanding rating '${log.understandingRating}'") }
            }
            val undStored = when {
                editingThis && newUnderstanding != null -> newUnderstanding.name
                editingThis -> log.understandingRating // preserve honest NotAsked when only recall is edited
                log.understandingRating == "NotAsked" -> "NotAsked"
                else -> und.name
            }
            // The topic's OWN model decides how elapsed time is counted; a frozen FSRS-5 history
            // must keep replaying on fractional milliseconds.
            val elapsed = MedScheduler.modelElapsedDays(prevTime, log.reviewedAt, replayModel)

            // Timezone-stable classification: trust the logType RECORDED at review time. Recomputing
            // it from timestamps here would use the DEVICE'S CURRENT timezone — a user who travels
            // could silently flip an old first-study into a recall (or vice versa) just by editing a
            // rating. Only legacy UNKNOWN rows fall back to timestamp reconstruction.
            //
            // ONE seed per history, always. reviewNumber 0 makes MedScheduler.review() re-seed from
            // Fsrs.initialState, THROWING AWAY every bit of stability accumulated so far. A normal
            // topic has exactly one FIRST_STUDY log so that is correct — but a MERGED topic carries
            // one per absorbed copy (merging re-points their logs onto the survivor). Without this
            // guard, correcting any old rating — or merely editing the merged topic's studied date,
            // which replays through here too — silently discarded the merge's weighted-average
            // memory state. Only the chronologically FIRST log can be a seed.
            val isFirstLogOfHistory = log.id == logs.first().id

            // A LATER first-study row is a RE-ENCODING EXPOSURE, not a retrieval.
            //
            // It can only exist after a merge: it is the absorbed copy's own post-study self-rating,
            // recorded when the user studied the same material again under a different title. Feeding
            // it through the recall path would grant it the stability growth a successful delayed
            // recall earns — i.e. it would reward RE-READING as if it were REMEMBERING, and inflate
            // the merged topic's schedule. It would also contradict the reason the first rating is
            // damped at all (POLICY YADORA-3: an immediate post-study judgement measures fluency, not
            // durable memory), since the same unreliable signal would be trusted completely here.
            //
            // An exposure therefore leaves stability, difficulty and the graded review count ALONE,
            // and only re-anchors the clock: the next real retrieval's elapsed time is measured from
            // the day the material was last actually studied, which is the honest baseline.
            if (log.logType == "FIRST_STUDY" && !isFirstLogOfHistory) {
                updatedLogs.add(
                    log.copy(
                        previousIntervalDays = prevInterval,
                        nextIntervalDays = prevInterval,
                        previousState = prevStateName,
                        nextState = prevStateName,
                        retrievabilityAtReview = MedScheduler.retrievability(elapsed, stability, replayModel),
                        elapsedDays = elapsed,
                        // logType is deliberately PRESERVED. Rewriting it to RECALL would launder a
                        // study exposure into retrieval history and destroy the distinction forever.
                    )
                )
                prevTime = log.reviewedAt
                lastReviewedAt = log.reviewedAt
                // An exposure carries an understanding answer like any other row, and the live path
                // counts it from the logs, so the replay must count it identically.
                unrepairedStreak = if (MedScheduler.continuesUnrepairedStreak(mem.name, undStored)) unrepairedStreak + 1 else 0
                continue
            }

            val reviewNumber = when {
                log.logType == "FIRST_STUDY" -> 0 // first-of-history, guaranteed by the branch above
                log.logType == "RECALL" -> maxOf(reviewCount, 1)
                else -> MedScheduler.effectiveReviewNumber(reviewCount)
            }
            // Replay each review under its HISTORICAL conditions (retention target + importance at the
            // time, stored per-log since v4) — editing one old rating must not silently rewrite the
            // whole history under today's settings. Pre-v4 rows (-1 sentinels) fall back to current.
            val histRetention = log.desiredRetentionAtReview.takeIf { it in 0.70..0.99 }
            val histImportant = if (log.wasImportantAtReview >= 0) log.wasImportantAtReview == 1 else unit.highYield
            // Policy snapshot (v5): an UNTOUCHED log replays under the understanding factor originally
            // applied; the log being EDITED gets the current policy's factor (it's a new decision).
            val histFactor = if (log.id != logId || newUnderstanding == null)
                log.understandingFactorAtReview.takeIf { it > 0.0 }
            else null
            // The policy this row replays under: the EDITED row is a new decision made today, every
            // untouched row keeps the one it was stamped with. Same rule the stamping below uses.
            val policyForThisLog = if (log.id == logId) MedScheduler.POLICY_VERSION
                else log.schedulerPolicyVersion.ifEmpty { MedScheduler.POLICY_VERSION }
            // The calibration scale this row was scheduled with (v7). An untouched row keeps it; the
            // edited row is a new decision under today's estimate; a pre-v7 row was scaled by nothing.
            val histScale = if (log.id != logId) (log.calibrationScaleAtReview.takeIf { it > 0.0 } ?: 1.0) else null
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
                // YADORA-3 damps the first-study seed; older logs must replay undamped so a rating
                // correction reproduces the schedule the user actually had.
                dampFirstStudyPrior = MedScheduler.dampsFirstStudyPrior(policyForThisLog),
                // Replay the WHOLE history under the model this topic currently sits on, not
                // per-log. Mixing models mid-stream would produce a state belonging to neither, and
                // this keeps the replay consistent with projectOntoCurrentModel, which rebuilds the
                // same history the same way. A topic still on FSRS-5 replays under FSRS-5, so a
                // rating correction there still reproduces the schedule the user actually had.
                model = replayModel,
                unrepairedStreak = unrepairedStreak,
                calibrationScaleOverride = histScale,
                // YADORA-6 backs off the repair clock; a row stamped with an older policy keeps the
                // flat deadline it was actually given.
                backOffRepairClock = MedScheduler.backsOffRepairClock(policyForThisLog),
            )
            // Same deterministic fuzz as the live commit (seeded by unit + prior review count, which
            // is exactly what this loop counter holds at this step) — replay==live.
            val interval = MedScheduler.fuzzedInterval(
                outcome.intervalDays, outcome.baseIntervalDays, unit.id, reviewCount,
                isFirstStudy = reviewNumber == 0,
            )
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
                    // The EDITED row is a new decision under the CURRENT policy — its version must say so.
                    schedulerPolicyVersion = if (log.id == logId) MedScheduler.POLICY_VERSION
                        else log.schedulerPolicyVersion.ifEmpty { MedScheduler.POLICY_VERSION },
                    // A row whose understanding was never ASKED keeps the "not recorded" sentinel.
                    // 'und' maps NotAsked to Partial so the math has something to work with, but
                    // writing Partial's factor here would put a judgement in the research export
                    // that the user never made -- and would silently undo the same fix on the commit
                    // path the first time any rating on this topic was corrected.
                    understandingFactorAtReview =
                        if (undStored == "NotAsked") -1.0
                        else histFactor ?: MedScheduler.understandingFactor(und),
                    // The edited row records today's scale, the one it was just scheduled with; an
                    // untouched row keeps whatever it recorded (copy() preserves the sentinel too).
                    calibrationScaleAtReview = if (log.id == logId) MedScheduler.calibrationScale else log.calibrationScaleAtReview,
                )
            )

            unrepairedStreak = if (MedScheduler.continuesUnrepairedStreak(mem.name, undStored)) unrepairedStreak + 1 else 0
            lastRemediationDays = outcome.remediationDays
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
            // Two clocks (DB v6): the replay recomputes BOTH, and the topic surfaces on whichever
            // is earlier. Under FSRS-5 remediation is always null, so this collapses to the memory
            // date and legacy replay is unchanged.
            nextReviewAt = listOfNotNull(
                lastReviewedAt + (lastInterval * 86400000).toLong(),
                lastRemediationDays?.let { lastReviewedAt + (it * 86400000).toLong() },
            ).min(),
            // A replay recomputes the MODEL's truth, and any prior user deferral is superseded by it.
            modelDueAt = lastReviewedAt + (lastInterval * 86400000).toLong(),
            understandingDueAt = lastRemediationDays?.let { lastReviewedAt + (it * 86400000).toLong() },
            deferredUntil = null,
            updatedAt = System.currentTimeMillis(),
        )
        database.withTransaction {
            updatedLogs.forEach { reviewLogDao.insertLog(it) }
            studyUnitDao.updateUnit(finalUnit)
        }
    }
}
