package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.local.dao.CategoryDao
import com.example.data.local.dao.ReviewLogDao
import com.example.data.local.dao.StudyUnitDao
import com.example.data.local.entity.REVIEW_HISTORY_ORDER
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
import kotlinx.coroutines.sync.withLock

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

    /**
     * Run [block] as ONE database transaction. For read-modify-write edits that must not interleave
     * with another writer — a review committing, a notification's "Not today" — between the read and
     * the write. Nested transactions (e.g. [updateUnitReplayingHistory] inside it) join this one.
     */
    suspend fun <R> inTransaction(block: suspend () -> R): R = database.withTransaction(block)

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
            // One-shot read inside the transaction (a Flow's query runs outside it).
            if (reviewLogDao.getLogsForUnitOnce(unit.id).isNotEmpty()) {
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
        if (isOnCurrentModel(unit)) return unit
        val merged = mergedUnitIds()
        carriedOver(unit, merged)?.let { return it }
        return projectWithHistory(unit, reviewLogDao.getLogsForUnitOnce(unit.id), merged = unit.id in merged)
    }

    /**
     * A MERGED topic already on the live model that only needs a different weight SET keeps its state: it is carried
     * over, not replayed. That state is the merge's review-count-weighted average (the settled merge rule), which no
     * replay of the combined history reproduces, and replaying it at every set change quietly turned the rule into a
     * chronological replay (a second audit, 2026-09-28: stability 97.9 -> 127.3 under a new set id with IDENTICAL
     * weights). A stability means the same under every FSRS-6 set (the day recall falls to 90%), so this is a defined
     * conversion; difficulty is carried as it is. A MODEL change (FSRS-5 to FSRS-6) still replays, because that
     * curve's numbers do not carry, and a real rating correction still replays the combined history, as its dialog
     * says. Null when the topic is not such a case.
     */
    private fun carriedOver(unit: StudyUnitEntity, mergedIds: Set<Long>): StudyUnitEntity? =
        if (!isOnCurrentModel(unit) && MedScheduler.MemoryModel.of(unit.memoryModel) == MedScheduler.CURRENT_MODEL && unit.id in mergedIds) {
            unit.copy(parameterSetId = MedScheduler.activeParameterSet.id, updatedAt = System.currentTimeMillis())
        } else null

    /** On the live model AND the active weight set: nothing to project. */
    private fun isOnCurrentModel(unit: StudyUnitEntity): Boolean =
        MedScheduler.MemoryModel.of(unit.memoryModel) == MedScheduler.CURRENT_MODEL &&
            unit.parameterSetId == MedScheduler.activeParameterSet.id

    /**
     * The pure half of [projectOntoCurrentModel], taking the history rather than fetching it, so a
     * caller already inside a transaction (merge) can project without collecting a Flow there.
     */
    private fun projectWithHistory(unit: StudyUnitEntity, history: List<ReviewLogEntity>, merged: Boolean): StudyUnitEntity {
        if (isOnCurrentModel(unit)) return unit
        // Read ONCE: a refresh landing mid-projection must not split one topic's history across two sets.
        // A personal weight set is projected onto exactly like a new model: by replaying the real history.
        val target = MedScheduler.activeParameterSet

        val logs = history.sortedWith(REVIEW_HISTORY_ORDER)
        if (logs.isEmpty()) {
            // Never rated: no evidence to replay, so only the model label changes.
            return unit.copy(memoryModel = MedScheduler.CURRENT_MODEL.id, parameterSetId = target.id, updatedAt = System.currentTimeMillis())
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
            // Projection rebuilds under the CURRENT model, so time is measured its way too: the day count the review was
            // scheduled with when it is in that model's units, never counted again in today's time zone.
            val elapsed = MedScheduler.replayElapsedDays(
                prevTime, log.reviewedAt, MedScheduler.CURRENT_MODEL, log.elapsedDays, log.schedulerVersion, log.logType, merged,
            )
            val highYield = if (log.wasImportantAtReview >= 0) log.wasImportantAtReview == 1 else unit.highYield
            state = MedScheduler.projectStep(state, elapsed, grade, highYield, target.weights)
            if (grade == MemoryRating.Forgot) lapses++
            reviews++
            lastGradedRating = log.memoryRating
            prevTime = log.reviewedAt
        }
        val projected = state ?: return unit.copy(memoryModel = MedScheduler.CURRENT_MODEL.id, parameterSetId = target.id)

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
            parameterSetId = target.id,
            updatedAt = System.currentTimeMillis(),
        )
    }

    // --- the personal memory model (DB v9) ---

    /** Every rating correction, for the Progress calibration card (which predictions they recomputed). */
    fun observeCorrectionEvents(): Flow<List<com.example.data.local.entity.EventLogEntity>> =
        database.eventLogDao().observeCorrectionEvents()

    /** Every fit attempt and adopted weight set, oldest first, for the Progress and Settings screens. */
    fun observeParameterSets(): Flow<List<com.example.data.local.entity.MemoryParameterSetEntity>> =
        database.memoryParameterSetDao().observeAll()

    /**
     * Load the weight sets into the scheduler: the ACTIVE one schedules new reviews, and every other
     * non-rejected set stays readable so a row or log still on it replays under its own weights. Called
     * right before anything schedules, the same places the calibration is refreshed. A stored vector that
     * does not decode inside the reference bounds is ignored, which leaves the published defaults active, and
     * an active set whose first-rating grades are out of order is retired (PERSONAL_MODEL_RETIRED).
     */
    suspend fun refreshMemoryModel() {
        val dao = database.memoryParameterSetDao()
        var rows = dao.getAll()
        // A set adopted before 2026-10-02, when the fit began keeping the first-rating grades in order, or restored from
        // such a file, can bring a topic rated Hard back later than one rated Medium. It is retired here, once and with a
        // record, instead of scheduling (an outside audit, 2026-10-03); it stays readable for replay, like every retired set.
        rows.lastOrNull { it.status == com.example.data.local.entity.MemoryParameterSetEntity.ACTIVE }?.let { row ->
            val w = com.example.domain.srs.Fsrs6Optimizer.decode(row.weights)
            if (w != null && !com.example.domain.srs.Fsrs6Optimizer.keepsGradeOrder(w)) {
                database.withTransaction {
                    dao.retireActive(System.currentTimeMillis())
                    database.eventLogDao().insert(
                        com.example.data.local.entity.EventLogEntity(
                            type = "PERSONAL_MODEL_RETIRED",
                            detail = "set=${row.id} reason=grade order (initial stabilities ${w.take(4).joinToString(",") { "%.3f".format(java.util.Locale.ROOT, it) }})",
                        )
                    )
                }
                rows = dao.getAll()
            }
        }
        val known = HashMap<Long, DoubleArray>()
        for (row in rows) {
            if (row.status == com.example.data.local.entity.MemoryParameterSetEntity.REJECTED) continue
            com.example.domain.srs.Fsrs6Optimizer.decode(row.weights)?.let { known[row.id] = it }
        }
        val active = rows.lastOrNull { it.status == com.example.data.local.entity.MemoryParameterSetEntity.ACTIVE }
            ?.let { row -> known[row.id]?.let { MedScheduler.ParameterSet(row.id, it, row.activatedAt ?: row.createdAt) } }
        MedScheduler.knownParameterSets = known
        MedScheduler.activeParameterSet = active ?: MedScheduler.DEFAULT_PARAMETER_SET
    }

    /**
     * Every topic's graded history, rebuilt exactly as [projectWithHistory] rebuilds it, for the optimizer.
     *
     * Merged topics are left out, as the pilot analysis leaves them out of its fit (tools/pilot/analyze.py). A
     * merged history interleaves two copies studied separately, and its state is a weighted average no replay
     * reproduces; the on-device fit used to train on it anyway (an outside audit, 2026-09-27).
     */
    suspend fun trainingHistories(): List<com.example.domain.srs.Fsrs6Optimizer.History> {
        val merged = mergedUnitIds()
        return reviewLogDao.getAllLogsOnce().groupBy { it.studyUnitId }.filterKeys { it !in merged }.values.mapNotNull { logs ->
            com.example.domain.srs.Fsrs6Optimizer.historyOf(
                logs.sortedWith(REVIEW_HISTORY_ORDER)
                    .map {
                        com.example.domain.srs.Fsrs6Optimizer.Event(
                            it.reviewedAt, it.memoryRating, it.logType,
                            // Merged topics are left out above, so every stored count runs from this topic's own reviews.
                            MedScheduler.storedModelDays(it.elapsedDays, it.schedulerVersion, it.logType, MedScheduler.MemoryModel.FSRS_6),
                            // Folds follow the order the app recorded reviews, independently of a phone clock rollback.
                            validationOrder = it.id,
                        )
                    },
            ) { from, to -> MedScheduler.modelElapsedDays(from, to, MedScheduler.MemoryModel.FSRS_6) }
        }
    }

    /**
     * What a personal-model fit learns from and is judged against, as one identity: every review up to [maxLogId]
     * (which topic, when, both ratings, its type, model and weight set) and the weight set in use. A restore, a
     * wipe, a merge or a rating correction changes it even when every time stays the same; a review added after
     * the fit began (a higher id) does not. Read inside a transaction, where the fit captures its inputs and where
     * it would adopt the result.
     */
    internal suspend fun fitIdentity(
        maxLogId: Long,
        retention: Double = MedScheduler.effectiveRetention(highYield = false),
    ): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        reviewLogDao.getAllLogsOnce().asSequence().filter { it.id <= maxLogId }.sortedBy { it.id }.forEach { l ->
            digest.update(
                "${l.id}|${l.studyUnitId}|${l.reviewedAt}|${l.memoryRating}|${l.understandingRating}|${l.logType}|${l.schedulerVersion}|${l.parameterSetId}|${l.elapsedDays}\n"
                    .toByteArray(Charsets.UTF_8)
            )
        }
        val active = database.memoryParameterSetDao().getActive()
        digest.update("active|${active?.id ?: 0L}|${active?.weights.orEmpty()}".toByteArray(Charsets.UTF_8))
        // These also change training or its adoption test without changing a log's id, rating or time:
        // merging an unrated duplicate excludes the survivor, legacy logs use the current zone, and
        // the retention target controls the candidate's interval-lengthening check.
        digest.update("\nmerged|${mergedUnitIds().sorted().joinToString(",")}".toByteArray(Charsets.UTF_8))
        digest.update("\nzone|${java.time.ZoneId.systemDefault().id}|retention|$retention".toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Every topic a merge touched, survivors and absorbed copies, read from the MERGE events as analyze.py reads them. */
    private suspend fun mergedUnitIds(): Set<Long> = database.eventLogDao().getMergeEvents().flatMapTo(HashSet()) { e ->
        listOfNotNull(e.unitId) + (e.detail ?: "").split(",").mapNotNull { it.trim().toLongOrNull() }
    }

    /** One refit at a time: the daily job and the one Settings starts must not fit and adopt side by side. */
    private val refitLock = kotlinx.coroutines.sync.Mutex()

    /**
     * Refit the memory model to this learner when there is enough NEW evidence to be worth it: at least
     * [com.example.domain.srs.Fsrs6Optimizer.MIN_REVIEWS_FOR_A_FIT] reviews, and 20% more than the last
     * attempt saw or a month since it. The fit is judged against the set in use on the learner's own
     * later reviews ([com.example.domain.srs.Fsrs6Optimizer.fitAndValidate]); the attempt is recorded
     * either way, and an accepted set becomes ACTIVE in the same transaction that retires the old one.
     * Writes the database only — the scheduler switches at the next [refreshMemoryModel]. Returns the
     * attempt's report, or null when none was due.
     */
    suspend fun refitPersonalModel(
        now: Long = System.currentTimeMillis(),
        force: Boolean = false,
        /** The Settings switch as it stands NOW; read again where a result would be adopted. */
        isEnabled: () -> Boolean = { true },
    ): com.example.domain.srs.Fsrs6Optimizer.FitReport? = refitLock.withLock { refitLocked(now, force, isEnabled) }

    private suspend fun refitLocked(
        now: Long,
        force: Boolean,
        isEnabled: () -> Boolean,
    ): com.example.domain.srs.Fsrs6Optimizer.FitReport? {
        val dao = database.memoryParameterSetDao()
        // What the fit learns from and is judged against, read as ONE snapshot (a restore between two separate
        // reads could pair one history with another's baseline), with its identity, so the result can be checked
        // against the database as it is when the fit finishes (a fit takes seconds).
        var maxLogId = 0L
        var identity = ""
        var histories: List<com.example.domain.srs.Fsrs6Optimizer.History> = emptyList()
        var latest: com.example.data.local.entity.MemoryParameterSetEntity? = null
        var activeRow: com.example.data.local.entity.MemoryParameterSetEntity? = null
        var retention = 0.9
        database.withTransaction {
            maxLogId = reviewLogDao.maxLogId()
            retention = MedScheduler.effectiveRetention(highYield = false)
            identity = fitIdentity(maxLogId, retention)
            histories = trainingHistories()
            latest = dao.getLatest()
            activeRow = dao.getActive()
        }
        val available = histories.sumOf { h -> (1 until h.size).count { h.inLoss(it) } }
        if (available < com.example.domain.srs.Fsrs6Optimizer.MIN_REVIEWS_FOR_A_FIT) return null
        val lastAttempt = latest
        if (!force && lastAttempt != null && available < lastAttempt.availableReviews * REFIT_EVIDENCE_GROWTH &&
            now - lastAttempt.createdAt < REFIT_MAX_AGE_MS
        ) return null

        val activeWeights = activeRow?.let { com.example.domain.srs.Fsrs6Optimizer.decode(it.weights) }
        val current = activeWeights ?: com.example.domain.srs.Fsrs6Parameters.DEFAULT_WEIGHTS
        val report = com.example.domain.srs.Fsrs6Optimizer.fitAndValidate(
            histories, current, retention = retention,
        )
        if (report.verdict == com.example.domain.srs.Fsrs6Optimizer.Verdict.NOT_ENOUGH_DATA) return report

        val weights = report.weights
        val accepted = report.verdict == com.example.domain.srs.Fsrs6Optimizer.Verdict.ACCEPTED && weights != null
        fun stored(x: Double?) = if (x != null && x.isFinite()) x else -1.0
        database.withTransaction {
            // Checked where the result is written, not only where the job started: the learner may have switched
            // the personal model off, wiped or restored the data while the fit ran, and a fit that finished after
            // "off" used to be adopted anyway (an outside audit, 2026-09-27). Settings writes the switch before its
            // own transaction retires the active set, so reading it inside this one leaves no gap. The identity
            // covers what each review SAID, not only when it happened: a restore with the same times but a changed
            // rating used to pass (a second audit, 2026-09-28).
            if (!isEnabled() || fitIdentity(maxLogId) != identity) {
                database.eventLogDao().insert(
                    com.example.data.local.entity.EventLogEntity(
                        type = "PERSONAL_MODEL_DISCARDED",
                        detail = if (!isEnabled()) "switched off during the fit" else "history changed during the fit",
                    )
                )
                return@withTransaction
            }
            if (accepted) dao.retireActive(now)
            dao.insert(
                com.example.data.local.entity.MemoryParameterSetEntity(
                    createdAt = now,
                    status = if (accepted) com.example.data.local.entity.MemoryParameterSetEntity.ACTIVE
                        else com.example.data.local.entity.MemoryParameterSetEntity.REJECTED,
                    weights = if (accepted && weights != null) com.example.domain.srs.Fsrs6Optimizer.encode(weights) else "",
                    comparedWithSetId = if (activeWeights != null) activeRow?.id ?: 0L else 0L,
                    availableReviews = available,
                    trainReviews = report.trainReviews,
                    testReviews = report.testReviews,
                    currentLogLoss = stored(report.current?.logLoss),
                    candidateLogLoss = stored(report.candidate?.logLoss),
                    currentRmseBins = stored(report.current?.rmseBins),
                    candidateRmseBins = stored(report.candidate?.rmseBins),
                    currentAuc = stored(report.current?.auc),
                    candidateAuc = stored(report.candidate?.auc),
                    zScore = if (report.zScore.isNaN()) 0.0 else report.zScore.coerceIn(-99.0, 99.0),
                    activatedAt = if (accepted) now else null,
                )
            )
            database.eventLogDao().insert(
                com.example.data.local.entity.EventLogEntity(
                    type = "PERSONAL_MODEL",
                    detail = "${report.verdict} train=${report.trainReviews} test=${report.testReviews} " +
                        "z=${"%.2f".format(java.util.Locale.ROOT, report.zScore)}" +
                        // Why a set that predicted better was still refused: it would lengthen, or reorder the grades.
                        (if (report.lengthening.isNaN()) "" else " lengthening=${"%.3f".format(java.util.Locale.ROOT, report.lengthening)}"),
                )
            )
        }
        return report
    }

    /**
     * Stop using a personal model: the active set is retired, the published defaults schedule new
     * reviews, and every topic crosses back by projection at its next review, like any model change.
     */
    suspend fun useDefaultMemoryModel(now: Long = System.currentTimeMillis()) {
        database.withTransaction {
            database.memoryParameterSetDao().retireActive(now)
            database.eventLogDao().insert(com.example.data.local.entity.EventLogEntity(type = "PERSONAL_MODEL_OFF"))
        }
        refreshMemoryModel()
    }

    companion object {
        /** A refit waits for this much more evidence than the last attempt saw... */
        const val REFIT_EVIDENCE_GROWTH = 1.2
        /** ...or for this long, whichever comes first. */
        const val REFIT_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
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
        // Every copy is projected onto the live weight set below, so the registry must be current.
        runCatching { refreshMemoryModel() }
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
            val survivorLogs = reviewLogDao.getLogsForUnitOnce(survivorRow.id)
            val absorbedLogs = absorbedRows.map { reviewLogDao.getLogsForUnitOnce(it.id) }
            // A copy that is itself the survivor of an earlier merge keeps its averaged state (carriedOver).
            val mergedBefore = mergedUnitIds()
            val survivor = carriedOver(survivorRow, mergedBefore)
                ?: projectWithHistory(survivorRow, survivorLogs, merged = survivorRow.id in mergedBefore)
            val absorbed = absorbedRows.mapIndexed { i, row ->
                carriedOver(row, mergedBefore) ?: projectWithHistory(row, absorbedLogs[i], merged = row.id in mergedBefore)
            }

            val all = listOf(survivor) + absorbed
            // Weight by evidence: an unrated copy counts once, a well-drilled copy counts per review.
            val weights = all.map { maxOf(it.reviewCount, 1).toDouble() }
            val totalWeight = weights.sum()
            fun weighted(pick: (StudyUnitEntity) -> Double): Double =
                all.indices.sumOf { pick(all[it]) * weights[it] } / totalWeight

            val mergedStability = weighted { it.stability }
            val mergedDifficulty = weighted { it.difficulty }
            val earliestDue = all.minOf { it.nextReviewAt }
            val mergedModelDue = all.minOf { it.modelDueAt }
            val mergedUnderstandingDue = all.mapNotNull { it.understandingDueAt }.minOrNull()

            // Each copy counted its own first log as a graded review. In the COMBINED history only the earliest
            // of those is still one: every other copy's FIRST_STUDY becomes a re-encoding exposure, which the
            // replay (editReviewRating, projection) and the export's self-check never count. Summing the copies'
            // counts left the survivor one review ahead of its own history per absorbed first study (seen on the
            // Samsung: "row says 8, history has 7"), so a later correction silently changed the count and with it
            // the fuzz seed. Only those demoted seeds come off the sum; every real review still counts.
            val copyFirstLogs = (listOf(survivorLogs) + absorbedLogs).mapNotNull { it.minWithOrNull(REVIEW_HISTORY_ORDER) }
            val combinedFirstId = copyFirstLogs.minWithOrNull(REVIEW_HISTORY_ORDER)?.id
            val demotedSeeds = copyFirstLogs.filter { it.id != combinedFirstId && it.logType == "FIRST_STUDY" }

            // The merged topic comes back on the earliest date any copy had. When that date is later than both
            // merged clocks, a copy's DEFERRAL put it there (without deferrals the earliest date IS the earlier
            // clock), so the deferral is kept, not cleared: nextReviewAt must equal the earlier clock unless the
            // user moved it, and clearing it left a topic due on a date nothing on the row explained. The same
            // holds for a copy the user moved EARLIER by hand. A merge still creates no deferral of its own.
            val clocksDue = listOfNotNull(mergedModelDue.takeIf { it > 0L }, mergedUnderstandingDue).minOrNull()
            val keptDeferral = if (clocksDue == null || earliestDue == clocksDue) null else earliestDue

            val merged = survivor.copy(
                stability = mergedStability,
                difficulty = mergedDifficulty,
                currentIntervalDays = weighted { it.currentIntervalDays },
                reviewCount = (all.sumOf { it.reviewCount } - demotedSeeds.size).coerceAtLeast(0),
                lapseCount = (all.sumOf { it.lapseCount } - demotedSeeds.count { it.memoryRating == "Forgot" }).coerceAtLeast(0),
                highYield = all.any { it.highYield }, // importance is a union: if either mattered, it matters
                // The recall prompt defines what "remembering" this topic means, so a merge must not drop
                // one: keep the survivor's if it has one, otherwise the first absorbed copy's.
                recallPrompt = all.firstNotNullOfOrNull { it.recallPrompt?.takeIf { p -> p.isNotBlank() } },
                // Key points are the scoring standard for this material, so they follow the prompt's
                // rule: the survivor's own, otherwise the first absorbed copy's. Never a union — two
                // copies in two languages would list every point twice and halve what each tick means.
                keyPoints = all.firstNotNullOfOrNull { it.keyPoints?.takeIf { p -> p.isNotBlank() } },
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
                modelDueAt = mergedModelDue,
                // The understanding clock follows the same never-push-further-away rule: if ANY copy
                // still owed a comprehension repair, the merged topic still owes it. Taking the
                // earliest also keeps nextReviewAt explainable — it must equal the earlier of the two
                // clocks, and dropping this would leave a topic due earlier than either of them.
                understandingDueAt = mergedUnderstandingDue,
                // Every copy was just projected, so the merged state is expressed in one model.
                memoryModel = MedScheduler.CURRENT_MODEL.id,
                parameterSetId = survivor.parameterSetId,
                deferredUntil = keptDeferral,
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
                        parameterSetId = 0L,
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

    /**
     * Hard-delete topics whose 30-day grace expired, HISTORY FIRST so a crash can't orphan logs. Choosing the
     * topics and deleting them is one transaction: chosen outside it, a topic restored in between was deleted
     * anyway, with its history (an outside audit reproduced it, 2026-09-27).
     */
    suspend fun purgeExpiredDeleted(graceMillis: Long = 30L * 24 * 60 * 60 * 1000) {
        val cutoff = System.currentTimeMillis() - graceMillis
        database.withTransaction {
            val ids = studyUnitDao.getPurgeCandidateIds(cutoff)
            if (ids.isEmpty()) return@withTransaction
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
        val set = MedScheduler.activeParameterSet
        val logs = calibrationEvidence(set)
        return com.example.domain.srs.RecallCalibration.scale(
            predicted = logs.map { it.retrievabilityAtReview }.toDoubleArray(),
            recalled = logs.map { it.memoryRating != MemoryRating.Forgot.name }.toBooleanArray(),
            p = com.example.domain.srs.Fsrs6Parameters(weights = set.weights),
        )
    }

    /**
     * The reviews the calibration of [set] learns from: pooled within ONE weight set (predictions made by different
     * weights are predictions of different models), passing [com.example.domain.srs.RecallCalibration.isEvidence], from
     * when the set began scheduling, and without the predictions a rating correction recomputed
     * ([com.example.data.RecomputedPredictions], since 2026-10-03). Newest first, the newest
     * [com.example.domain.srs.RecallCalibration.WINDOW] of them: the recomputed rows are left out BEFORE the window is
     * taken, as the Progress card takes it (`calibrationStatsOf`), so the two count the same reviews.
     */
    internal suspend fun calibrationEvidence(set: MedScheduler.ParameterSet): List<ReviewLogEntity> {
        val corrections = database.eventLogDao().getCorrectionEvents()
        // Every prediction a correction recomputed, read from the corrected topics' own logs (corrections are rare).
        val recomputed = if (corrections.isEmpty()) emptySet() else com.example.data.RecomputedPredictions.ids(
            corrections.mapNotNull { it.unitId }.distinct().flatMap { reviewLogDao.getLogsForUnitOnce(it) },
            corrections,
        )
        val window = com.example.domain.srs.RecallCalibration.WINDOW
        return reviewLogDao.getRecentRecallLogsOnce(
            MedScheduler.CURRENT_MODEL.id,
            set.id,
            set.activatedAt,
            com.example.domain.srs.RecallCalibration.MIN_ELAPSED_DAYS,
            com.example.domain.srs.RecallCalibration.EARLY_REVIEW_FRACTION,
            window + recomputed.size, // at most that many of them are left out below
        ).filter { it.retrievabilityAtReview in 0.0..1.0 && it.id !in recomputed } // the query already excludes the -1 sentinel
            .take(window)
    }

    /**
     * Today's plan ([com.example.ui.today.DailyPlan]): every never-rated topic due today, plus the due
     * reviews that fit in what is left of the daily limit. The one definition the review session, the
     * Today screen, the reminders and the widget all count from, so none of them can disagree.
     */
    suspend fun todayPlan(
        dailyLimit: Int,
        now: Long = System.currentTimeMillis(),
        ignoreLimit: Boolean = false,
    ): com.example.ui.today.DailyPlan.Plan {
        val due = studyUnitDao.getDueUnitsList(com.example.ui.today.DayBounds.endOf(now))
        val done = reviewLogDao.countReviewsBetween(com.example.ui.today.DayBounds.startOf(now), com.example.ui.today.DayBounds.endOf(now))
        return com.example.ui.today.DailyPlan.plan(due, done, dailyLimit, now, ignoreLimit)
    }

    /**
     * "Review ahead" ([com.example.ui.today.ReviewAhead]): rated topics not due today, the lowest current
     * recall first, read on each topic's OWN model and weight set. A topic whose recall cannot be computed
     * is left out rather than guessed at. Reads no exam date.
     */
    suspend fun reviewAheadQueue(
        now: Long = System.currentTimeMillis(),
        limit: Int = com.example.ui.today.ReviewAhead.SESSION_SIZE,
    ): List<StudyUnitEntity> = com.example.ui.today.ReviewAhead.order(
        active = studyUnitDao.getAllActiveOnce(),
        now = now,
        recall = { u ->
            runCatching {
                val model = MedScheduler.MemoryModel.of(u.memoryModel)
                val elapsed = MedScheduler.modelElapsedDays(u.lastReviewedAt ?: u.studiedAt, now, model)
                MedScheduler.retrievability(elapsed, u.stability, model, u.parameterSetId)
            }.getOrNull()
        },
        limit = limit,
    )

    /** Reviews (first ratings excluded) whose time falls in one local day, [since] to [until], live. */
    fun observeReviewsBetween(since: Long, until: Long): Flow<Int> = reviewLogDao.observeReviewsBetween(since, until)

    /**
     * How many answers in a row, counting back from the topic's latest log, left understanding
     * unrepaired — the input the repair-clock backoff needs (`MedScheduler.unrepairedStreak`). Read
     * from the logs, the single source of truth, so the preview, the commit and the replay agree.
     */
    suspend fun unrepairedStreak(unitId: Long): Int = MedScheduler.unrepairedStreak(
        reviewLogDao.getLogsForUnitOnce(unitId).sortedWith(REVIEW_HISTORY_ORDER).map { it.memoryRating to it.understandingRating },
    )

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
            // "Not today" means LATER, never sooner. The Library's review-now opens a topic weeks
            // before its date, and writing [until] unconditionally pulled a topic due in a month
            // forward to tomorrow — and recorded that as the user's own choice. A topic not due
            // before [until] is left exactly as it is, with no deferral and no event.
            if (unit.nextReviewAt >= until) return@withTransaction
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

    /** What one committed rating wrote, for the screen that asked for it. */
    data class RatedReview(
        /**
         * The row as it was STORED when the rating began: the snapshot Undo restores. A topic still on an older model
         * is scheduled from a copy projected onto the current one, but a projection is persisted only by a commit, so
         * Undo must put back the stored row. Putting back the projected copy labelled the row FSRS-6 over a history
         * FSRS-5 wrote, which the export's self-check reports as MODEL_OWNERSHIP (seen on the Samsung, 2026-09-28).
         */
        val before: StudyUnitEntity,
        val after: StudyUnitEntity,
        val logId: Long,
        /** 0 = this was the topic's first rating (review #0), whatever its date. */
        val reviewNumber: Int,
        /** The memory interval written to the row, fuzz included. */
        val memoryIntervalDays: Double,
        /** The date the topic actually returns on: the earlier of the memory and repair clocks. */
        val effectiveDueAt: Long,
        val repairPending: Boolean,
    )

    /**
     * THE commit path for one rating: the review screen calls it, and so does every test that pins what
     * a rating writes (replay == live, the pilot fixture, the two-year soak), so the code the tests verify
     * is the code the app runs. It used to live inline in the review screen, and three tests kept copies
     * of it that had to be updated by hand whenever it changed.
     *
     * It reloads the row (so an edit made on the Edit screen is not clobbered by a stale copy), carries it
     * onto the current memory model BEFORE anything schedules from it, schedules with the same
     * [MedScheduler.review] the rating buttons preview with, and writes the row and its log in one
     * transaction ([commitReview]). [now] is a parameter only so a test can place a history in time; the
     * screen passes the wall clock. Returns null if the topic no longer exists.
     *
     * The read, the projection, the scheduling and the write are ONE transaction, not only the write: a write
     * landing between this read and [commitReview] (an Edit-screen save, a second rating of the same topic, a
     * notification's "Not today") used to be overwritten by the row this rating had read, or counted once
     * for two reviews (an outside audit reproduced both with a paused read, 2026-09-27).
     */
    suspend fun rateUnit(
        unitId: Long,
        now: Long,
        memoryRating: MemoryRating,
        understandingRating: UnderstandingRating,
        understandingAsked: Boolean = true,
        methods: Set<com.example.domain.model.ReviewMethod> = emptySet(),
        questionsCorrect: Int? = null,
        questionsTotal: Int? = null,
        sessionKind: com.example.domain.model.SessionKind,
        reviewDurationMs: Long,
    ): RatedReview? = database.withTransaction { rateUnitLocked(unitId, now, memoryRating, understandingRating, understandingAsked, methods, questionsCorrect, questionsTotal, sessionKind, reviewDurationMs) }

    private suspend fun rateUnitLocked(
        unitId: Long,
        now: Long,
        memoryRating: MemoryRating,
        understandingRating: UnderstandingRating,
        understandingAsked: Boolean,
        methods: Set<com.example.domain.model.ReviewMethod>,
        questionsCorrect: Int?,
        questionsTotal: Int?,
        sessionKind: com.example.domain.model.SessionKind,
        reviewDurationMs: Long,
    ): RatedReview? {
        val loaded = getUnitById(unitId) ?: return null
        // An FSRS-5 stability is not an FSRS-6 stability, so the state is rebuilt by replaying this
        // topic's real rating history on the current model. No-op once it is already there, and it
        // never touches the dates: only the latent state moves.
        val unit = projectOntoCurrentModel(loaded)

        // Measured as the CURRENT model counts time (whole local calendar days for FSRS-6), so a topic
        // offered by today's queue is credited with the day the learner actually waited rather than the
        // clock difference from whatever hour they last reviewed at. Clamped at 0: a future-dated topic
        // reviewed early would otherwise log negative elapsed days.
        val elapsedDays = MedScheduler.modelElapsedDays(unit.lastReviewedAt ?: unit.studiedAt, now, MedScheduler.CURRENT_MODEL)

        // The first graded rating is always review #0 (seeded from the rating, capped by the first-study
        // window) however late it happens. Same rule as the replay path.
        val reviewNumber = MedScheduler.effectiveReviewNumber(unit.reviewCount)

        val outcome = MedScheduler.review(
            stability = unit.stability,
            difficulty = unit.difficulty,
            elapsedDays = elapsedDays,
            memoryRating = memoryRating,
            understanding = understandingRating,
            highYield = unit.highYield,
            reviewNumber = reviewNumber,
            model = MedScheduler.CURRENT_MODEL,
            // From the logs, the same source the preview read it from.
            unrepairedStreak = unrepairedStreak(unit.id),
            // The set the projection just put this topic on, stated rather than re-read.
            parameterSetId = unit.parameterSetId,
        )

        // Deterministic ±5% fuzz (seeded by unit + prior review count): the value the preview showed and
        // the value the history replay recomputes.
        val nextInterval = MedScheduler.fuzzedInterval(
            outcome.intervalDays, outcome.baseIntervalDays, unit.id, unit.reviewCount,
            isFirstStudy = reviewNumber == 0,
        )
        val nextState = MedScheduler.masteryState(
            stability = outcome.state.stability,
            justForgot = memoryRating == MemoryRating.Forgot,
        )

        // TWO CLOCKS (DB v6). The memory model's date is kept exactly in modelDueAt; a weak understanding
        // adds a SHORT repair deadline instead of scaling that prediction down, and the topic returns on
        // whichever comes first.
        val memoryDueAt = now + (nextInterval * 86400000).toLong()
        // Kept only when it beats the memory date as scheduled, fuzz included (MedScheduler.repairDays, YADORA-7).
        val repairDays = MedScheduler.repairDays(outcome, memoryRating, nextInterval, MedScheduler.POLICY_VERSION)
        val understandingDueAt = repairDays?.let { now + (it * 86400000).toLong() }
        val effectiveDueAt = listOfNotNull(memoryDueAt, understandingDueAt).min()

        val updatedUnit = unit.copy(
            lastReviewedAt = now,
            nextReviewAt = effectiveDueAt,
            // A real review resets the honest-scheduling pair (DB v5): the model's date IS the effective
            // date again, and any earlier deferral is spent.
            modelDueAt = memoryDueAt,
            understandingDueAt = understandingDueAt,
            memoryModel = MedScheduler.CURRENT_MODEL.id,
            deferredUntil = null,
            currentIntervalDays = nextInterval,
            reviewCount = unit.reviewCount + 1,
            lapseCount = if (memoryRating == MemoryRating.Forgot) unit.lapseCount + 1 else unit.lapseCount,
            state = nextState.name,
            difficulty = outcome.state.difficulty,
            stability = outcome.state.stability,
            retrievability = outcome.retrievabilityAtReview,
            updatedAt = now,
        )

        val score = if (reviewNumber == 0) -1 to -1
            else com.example.domain.model.QuestionScore.normalized(questionsCorrect, questionsTotal)
        val log = ReviewLogEntity(
            studyUnitId = unit.id,
            reviewedAt = now,
            memoryRating = memoryRating.name,
            // When the understanding question was skipped (the Forgot fast-commit), record that it was
            // never asked instead of an answer the learner never gave.
            understandingRating = if (understandingAsked) understandingRating.name else "NotAsked",
            previousIntervalDays = unit.currentIntervalDays,
            nextIntervalDays = nextInterval,
            previousState = unit.state,
            nextState = nextState.name,
            retrievabilityAtReview = outcome.retrievabilityAtReview,
            elapsedDays = elapsedDays,
            // FIRST_STUDY rows carry a first rating, not a recall: tagged so exports and calibration never
            // mix the two signals.
            logType = if (reviewNumber == 0) "FIRST_STUDY" else "RECALL",
            initialDifficulty = if (reviewNumber == 0) MedScheduler.difficultyLabelFor(memoryRating) else null,
            reviewDurationMs = reviewDurationMs,
            wasImportantAtReview = if (unit.highYield) 1 else 0,
            desiredRetentionAtReview = MedScheduler.effectiveRetention(unit.highYield),
            schedulerVersion = MedScheduler.SCHEDULER_VERSION,
            // Which policy bundle and which understanding factor shaped this interval, so later policy
            // changes replay history faithfully. -1.0 = "not recorded": the fast Forgot path passes Partial
            // as a placeholder, and storing its factor would claim an answer the learner never gave.
            schedulerPolicyVersion = MedScheduler.POLICY_VERSION,
            understandingFactorAtReview =
                if (understandingAsked) MedScheduler.understandingFactor(understandingRating) else -1.0,
            // The per-user interval correction this review was scheduled with.
            calibrationScaleAtReview = MedScheduler.calibrationScale,
            // Not scored since the key-point rating cap was retired.
            keyPointsTotal = -1,
            keyPointsRecalled = -1,
            // The weight set this prediction and interval came from.
            parameterSetId = unit.parameterSetId,
            // Research data. A first study is not a review, so it records no method or score.
            reviewMethods = if (reviewNumber == 0) null else com.example.domain.model.ReviewMethod.encode(methods),
            questionsCorrect = score.first,
            questionsTotal = score.second,
            sessionKind = sessionKind.name,
        )
        val logId = commitReview(updatedUnit, log)
        return RatedReview(
            before = loaded, after = updatedUnit, logId = logId, reviewNumber = reviewNumber,
            memoryIntervalDays = nextInterval, effectiveDueAt = effectiveDueAt,
            repairPending = repairDays != null,
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
            // Undo takes back the REVIEW, not what the learner changed since: the snapshot's scheduling fields go
            // back, while title, notes, source, scope, subject, Important, study date and archive state stay as
            // they are now. Writing the whole snapshot reverted an edit made between the rating and the undo.
            val current = studyUnitDao.getUnitById(previousUnit.id)
            studyUnitDao.updateUnit(current?.let { withSchedulingOf(it, previousUnit) } ?: previousUnit)
            reviewLogDao.deleteLogById(logId)
            database.eventLogDao().deleteStudyActionForLog(logId.toString())
        }
    }

    /**
     * [current] with every field a review writes taken from [snapshot]; everything the learner edits kept. When
     * nothing else changed since the rating, that is the snapshot itself, exactly.
     */
    internal fun withSchedulingOf(current: StudyUnitEntity, snapshot: StudyUnitEntity): StudyUnitEntity {
        val merged = current.copy(
            state = snapshot.state,
            difficulty = snapshot.difficulty,
            stability = snapshot.stability,
            retrievability = snapshot.retrievability,
            lastReviewedAt = snapshot.lastReviewedAt,
            nextReviewAt = snapshot.nextReviewAt,
            currentIntervalDays = snapshot.currentIntervalDays,
            reviewCount = snapshot.reviewCount,
            lapseCount = snapshot.lapseCount,
            modelDueAt = snapshot.modelDueAt,
            deferredUntil = snapshot.deferredUntil,
            understandingDueAt = snapshot.understandingDueAt,
            memoryModel = snapshot.memoryModel,
            parameterSetId = snapshot.parameterSetId,
            updatedAt = snapshot.updatedAt,
        )
        return if (merged == snapshot) snapshot else merged.copy(updatedAt = System.currentTimeMillis())
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
     *
     * Saving a rating UNCHANGED does nothing. It used to replay the whole history, which is not always the
     * identity: a merged topic's weighted average became a chronological replay, a topic that had crossed to
     * a personal weight set recomputed its older rows on that set, and after a time-zone move the calendar
     * days between old reviews could shift (an outside audit reproduced all three, 2026-09-27). A real
     * correction still recomputes everything, which is what the dialog says it does.
     *
     * The read, the replay and the write are one transaction, so nothing written in between is overwritten.
     */
    suspend fun editReviewRating(
        unitId: Long,
        logId: Long,
        newMemory: MemoryRating,
        newUnderstanding: UnderstandingRating?,
    ) = database.withTransaction { editReviewRatingLocked(unitId, logId, newMemory, newUnderstanding) }

    private suspend fun editReviewRatingLocked(
        unitId: Long,
        logId: Long,
        newMemory: MemoryRating,
        newUnderstanding: UnderstandingRating?,
    ) {
        val unit = studyUnitDao.getUnitById(unitId) ?: return
        // The order the reviews happened in (REVIEW_HISTORY_ORDER: saved order, not the clock, which can go back).
        // One-shot read: a Flow's query would run outside this transaction.
        val logs = reviewLogDao.getLogsForUnitOnce(unitId)
            .sortedWith(REVIEW_HISTORY_ORDER)
        if (logs.isEmpty()) return
        if (logId != -1L) {
            // A correction of a log that is gone (undone meanwhile) corrects nothing.
            val target = logs.firstOrNull { it.id == logId } ?: return
            val sameMemory = target.memoryRating == newMemory.name
            val sameUnderstanding = newUnderstanding == null || target.understandingRating == newUnderstanding.name
            if (sameMemory && sameUnderstanding) return
        }

        // A topic on a personal weight set replays under that set, so the registry must hold it even in
        // a process where no review session has run yet.
        refreshMemoryModel()
        // A merged history interleaves two copies' reviews, so its stored day counts run from the wrong reviews here.
        val merged = unitId in mergedUnitIds()

        // A correction is a NEW scheduling decision, so it uses the per-user calibration as it stands
        // now, read from the logs like every other path that schedules. It used to use whatever
        // MedScheduler.calibrationScale held — 1.0 until a review session had run in this process — so
        // a correction made straight after launching the app ignored the learned correction. A pure
        // replay (logId = -1) decides nothing new: every row keeps the scale it recorded.
        if (logId != -1L) {
            runCatching { MedScheduler.calibrationScale = recallCalibrationScale() }
        }

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
        // ...and under the weight set it is on, for the same reason: replay reproduces the history this
        // topic actually had. The next review projects it onto the active set like any model change.
        val replaySetId = if (replayModel == MedScheduler.MemoryModel.FSRS_6) unit.parameterSetId else 0L
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
            // must keep replaying on fractional milliseconds. An FSRS-6 recall replays with the day count it was
            // scheduled with, not one counted again in today's time zone (MedScheduler.storedModelDays).
            val elapsed = MedScheduler.replayElapsedDays(
                prevTime, log.reviewedAt, replayModel, log.elapsedDays, log.schedulerVersion, log.logType, merged,
            )

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
                        retrievabilityAtReview = MedScheduler.retrievability(elapsed, stability, replayModel, replaySetId),
                        elapsedDays = elapsed,
                        // logType is deliberately PRESERVED. Rewriting it to RECALL would launder a
                        // study exposure into retrieval history and destroy the distinction forever.
                        // The numbers above were just computed on this model and set: the row says so.
                        schedulerVersion = replayModel.id,
                        parameterSetId = replaySetId,
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
                parameterSetId = replaySetId,
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
                    // FSRS-5 is frozen and never scaled, so a corrected row on it records "no correction".
                    calibrationScaleAtReview = if (log.id != logId) log.calibrationScaleAtReview
                        else if (replayModel == MedScheduler.MemoryModel.FSRS_6) MedScheduler.calibrationScale
                        else 1.0,
                    // The prediction and interval above were computed on THIS model and weight set, so the row
                    // is stamped with them. It used to keep its original stamp: a topic that had crossed from
                    // the defaults to a personal set, then had a rating corrected, carried rows labelled
                    // "defaults" holding the personal set's numbers, which the calibration (after a switch back
                    // to the defaults) and the pilot's exact replay both trust (an outside audit, 2026-09-27).
                    schedulerVersion = replayModel.id,
                    parameterSetId = replaySetId,
                )
            )

            unrepairedStreak = if (MedScheduler.continuesUnrepairedStreak(mem.name, undStored)) unrepairedStreak + 1 else 0
            // The repair rule of the policy this row was stamped with (the edited row: today's), against its final interval.
            lastRemediationDays = MedScheduler.repairDays(outcome, mem, interval, policyForThisLog)
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
            // The answer the learner first gave survives its correction: the replay rewrites the log in place, and
            // without this the research export could not tell a corrected rating from an original one, nor which
            // later predictions a correction recomputed (an outside audit, 2026-10-02). `upto` is the topic's last log
            // this replay rewrote, read in this transaction: saved order, not the clock, tells which later logs it
            // recomputed (RecomputedPredictions; the clock can have gone back since those reviews).
            if (logId != -1L) logs.firstOrNull { it.id == logId }?.let { before ->
                database.eventLogDao().insert(
                    com.example.data.local.entity.EventLogEntity(
                        type = "RATING_CORRECTED",
                        unitId = unitId,
                        detail = "log=$logId upto=${logs.maxOf { it.id }} memory=${before.memoryRating}>${newMemory.name} " +
                            "understanding=${before.understandingRating}>${newUnderstanding?.name ?: before.understandingRating}",
                    )
                )
            }
        }
    }
}
