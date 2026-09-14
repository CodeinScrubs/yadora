package com.example.ui.add

import com.example.data.local.entity.StudyUnitEntity

/**
 * What Save does to a topic that already exists.
 *
 * The Edit screen fills its form ONCE, from the row as it was when the screen opened, and the screen
 * can then sit in the back stack while other paths write that same row: a reminder's "Review now"
 * opens a review on top of it, the notification's "Not today" defers it, a rating correction replays
 * it. Save used to copy the row the form was loaded from, so coming back from a review and pressing
 * Save quietly put the pre-review stability, due dates and counts back while the review's log stayed —
 * the review silently undone, the row and its history disagreeing.
 *
 * So Save applies the fields the form owns to the row as it is NOW, and changes a date only when the
 * user changed it. The form is compared with the row it was loaded from, never with the fresh row:
 * against the fresh row an untouched date looks exactly like an edited one.
 */
object TopicEdit {

    /** The form as the user leaves it. A date is null when the form never held one. */
    data class Form(
        val title: String,
        val subjectId: Long?,
        val systemId: Long?,
        val studyType: String,
        val recallPrompt: String?,
        /** Normalized key points ([com.example.domain.srs.KeyPoints.normalize]), or null for none. */
        val keyPoints: String?,
        val notes: String,
        val source: String,
        val highYield: Boolean,
        val studiedAt: Long?,
        val nextReviewAt: Long?,
    )

    data class Plan(
        /** The row to write: the fresh row with the form's own fields applied. */
        val updated: StudyUnitEntity,
        /**
         * The study date changed. It is the replay origin, so the schedule must be recomputed from it:
         * the whole history of a rated topic, and the due date itself of an unrated one (which is due
         * on its study date).
         */
        val studyDateChanged: Boolean,
        /** The user picked another next-review date: a deferral (DB v5), never the model's own date. */
        val nextDateChanged: Boolean,
        /** Important was switched on, on a topic with real reviews, without a date change. */
        val tightenForImportant: Boolean,
    )

    fun plan(loaded: StudyUnitEntity, fresh: StudyUnitEntity, form: Form, now: Long): Plan {
        val formStudiedAt = form.studiedAt
        val formNext = form.nextReviewAt
        val studyDateChanged = formStudiedAt != null && formStudiedAt != loaded.studiedAt
        val nextDateChanged = formNext != null && formNext != loaded.nextReviewAt
        val updated = fresh.copy(
            title = form.title,
            subjectId = form.subjectId,
            systemId = form.systemId,
            studyType = form.studyType,
            recallPrompt = form.recallPrompt,
            keyPoints = form.keyPoints,
            notes = form.notes,
            source = form.source,
            highYield = form.highYield,
            studiedAt = if (studyDateChanged && formStudiedAt != null) formStudiedAt else fresh.studiedAt,
            nextReviewAt = if (nextDateChanged && formNext != null) formNext else fresh.nextReviewAt,
            deferredUntil = if (nextDateChanged) formNext else fresh.deferredUntil,
            updatedAt = now,
        )
        return Plan(
            updated = updated,
            studyDateChanged = studyDateChanged,
            nextDateChanged = nextDateChanged,
            tightenForImportant = form.highYield && !fresh.highYield && fresh.reviewCount > 0 &&
                !nextDateChanged && fresh.lastReviewedAt != null,
        )
    }
}
