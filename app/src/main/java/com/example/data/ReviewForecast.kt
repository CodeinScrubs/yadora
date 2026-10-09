package com.example.data

import com.example.data.local.entity.ReviewLogEntity
import com.example.data.local.entity.StudyUnitEntity
import java.time.Instant
import java.time.ZoneId

/**
 * A prospective observation, separate from the mutable history used to rebuild schedules. Rating corrections
 * overwrite that history's predictions and model stamps. This event never changes: it records the state and raw
 * forecast actually used when a review was saved, plus the original subjective answer. It is NOT a recall test.
 *
 * Stored in the existing event log, atomically with the review. Backups and research exports already preserve
 * these events. No content, names, study-time estimate or exam date is included. The log id is the join key;
 * a merge moves review ownership but leaves that key intact. Undo removes its forecast along with its log.
 */
object ReviewForecast {
    const val EVENT = "REVIEW_FORECAST"
    const val VERSION = 1

    /** Versioned, locale-independent key=value fields. `log=<id> ` is prepended after Room assigns the id. */
    fun detail(before: StudyUnitEntity, log: ReviewLogEntity, zone: ZoneId): String = buildString {
        append("v=$VERSION at=${log.reviewedAt} type=${log.logType} model=${log.schedulerVersion} set=${log.parameterSetId}")
        append(" policy=${log.schedulerPolicyVersion} zone=${zone.id}")
        append(" p=${log.retrievabilityAtReview} elapsed=${log.elapsedDays}")
        append(" stability=${before.stability} difficulty=${before.difficulty}")
        append(" previousInterval=${before.currentIntervalDays} previousReview=${before.lastReviewedAt ?: before.studiedAt}")
        append(" memoryDue=${before.modelDueAt} repairDue=${before.understandingDueAt ?: -1L}")
        append(" effectiveDue=${before.nextReviewAt} deferredUntil=${before.deferredUntil ?: -1L}")
        append(" dueContext=${dueContext(before, log, zone)}")
        append(" retention=${log.desiredRetentionAtReview} scale=${log.calibrationScaleAtReview}")
        append(" memory=${log.memoryRating} understanding=${log.understandingRating} session=${log.sessionKind ?: "UNKNOWN"}")
        append(" outcome=SUBJECTIVE_POST_STUDY")
    }

    /** Status against the same end-of-local-day boundary as Today; describes availability, not why the user chose it. */
    private fun dueContext(before: StudyUnitEntity, log: ReviewLogEntity, zone: ZoneId): String {
        if (log.logType == "FIRST_STUDY") return "FIRST_STUDY"
        val cutoff = Instant.ofEpochMilli(log.reviewedAt).atZone(zone).toLocalDate()
            .plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L
        if (before.nextReviewAt > cutoff) return "AHEAD"
        if (before.deferredUntil != null) return "USER_MOVED"
        val memory = before.modelDueAt > 0 && before.modelDueAt <= cutoff
        val repair = before.understandingDueAt?.let { it <= cutoff } == true
        return when {
            memory && repair -> "BOTH_DUE"
            repair -> "UNDERSTANDING_DUE"
            memory -> "MEMORY_DUE"
            else -> "UNKNOWN"
        }
    }
}
