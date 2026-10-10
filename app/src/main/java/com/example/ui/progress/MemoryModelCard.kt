package com.example.ui.progress

import com.example.data.local.entity.MemoryParameterSetEntity
import com.example.data.local.entity.ReviewLogEntity
import com.example.domain.srs.Fsrs6Optimizer
import com.example.domain.srs.MedScheduler

/**
 * The weight set scheduling new reviews, read from the database rows. Screens never read the scheduler's
 * in-memory set: a process that has not run a review session yet has not loaded it.
 */
internal fun activeSetOf(sets: List<MemoryParameterSetEntity>): MedScheduler.ParameterSet =
    sets.lastOrNull { it.status == MemoryParameterSetEntity.ACTIVE }
        ?.let { row -> Fsrs6Optimizer.decode(row.weights)?.let { MedScheduler.ParameterSet(row.id, it, row.activatedAt ?: row.createdAt) } }
        ?: MedScheduler.DEFAULT_PARAMETER_SET

/** What the Progress screen states about the memory model. */
data class MemoryModelStatus(
    /** The personal set in use, or null when the published defaults schedule. */
    val active: MemoryParameterSetEntity?,
    /** The most recent fit attempt of any outcome, or null when none has run. */
    val latestAttempt: MemoryParameterSetEntity?,
    /** Recall reviews at least a day after the previous one: about what a fit can learn from. */
    val recallReviews: Int,
    /**
     * The latest attempt also retired the set that was in use: at a due refit the active set is checked again and
     * retired when it no longer meets the conditions it was adopted under, also when the new fit is refused
     * (`MedReviewRepository.refitPersonalModel`, in the same transaction, at the same time). The card used to say
     * "nothing changed" then (a production review, 2026-10-10).
     */
    val latestRetiredActive: Boolean = false,
)

/**
 * [events]: the MERGE events among them say which topics' histories a merge combined. The fit leaves those out
 * (`MedReviewRepository.trainingHistories`), so "N so far" does too: it counted them, and said a fit was due that the
 * fit's own count did not reach (a production review, 2026-10-10).
 */
internal fun memoryModelStatusOf(
    logs: List<ReviewLogEntity>,
    sets: List<MemoryParameterSetEntity>,
    events: List<com.example.data.local.entity.EventLogEntity> = emptyList(),
): MemoryModelStatus {
    val latest = sets.lastOrNull()
    val merged = events.filter { it.type == com.example.data.RecomputedPredictions.MERGE_EVENT }.mapNotNullTo(HashSet()) { it.unitId }
    return MemoryModelStatus(
        active = sets.lastOrNull { it.status == MemoryParameterSetEntity.ACTIVE && Fsrs6Optimizer.decode(it.weights) != null },
        latestAttempt = latest,
        recallReviews = logs.count { it.logType == "RECALL" && it.elapsedDays >= 1.0 && it.studyUnitId !in merged },
        latestRetiredActive = latest?.status == MemoryParameterSetEntity.REJECTED &&
            sets.any { it.status == MemoryParameterSetEntity.RETIRED && it.retiredAt == latest.createdAt },
    )
}
