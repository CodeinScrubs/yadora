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
        ?.let { row -> Fsrs6Optimizer.decode(row.weights)?.let { MedScheduler.ParameterSet(row.id, it) } }
        ?: MedScheduler.DEFAULT_PARAMETER_SET

/** What the Progress screen states about the memory model. */
data class MemoryModelStatus(
    /** The personal set in use, or null when the published defaults schedule. */
    val active: MemoryParameterSetEntity?,
    /** The most recent fit attempt of any outcome, or null when none has run. */
    val latestAttempt: MemoryParameterSetEntity?,
    /** Recall reviews at least a day after the previous one: about what a fit can learn from. */
    val recallReviews: Int,
)

internal fun memoryModelStatusOf(logs: List<ReviewLogEntity>, sets: List<MemoryParameterSetEntity>): MemoryModelStatus =
    MemoryModelStatus(
        active = sets.lastOrNull { it.status == MemoryParameterSetEntity.ACTIVE && Fsrs6Optimizer.decode(it.weights) != null },
        latestAttempt = sets.lastOrNull(),
        recallReviews = logs.count { it.logType == "RECALL" && it.elapsedDays >= 1.0 },
    )
