package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "study_units",
    // Hot query paths (added in DB v4): every due-list/count scan filters archived + nextReviewAt.
    indices = [
        Index(value = ["nextReviewAt"], name = "index_study_units_nextReviewAt"),
        Index(value = ["archived"], name = "index_study_units_archived"),
    ],
)
data class StudyUnitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val subjectId: Long? = null,
    val systemId: Long? = null,
    val studyType: String,
    val recallPrompt: String? = null,
    val notes: String? = null,
    val source: String? = null,
    val highYield: Boolean = false,
    val state: String = "New",
    val difficulty: Double = 5.0,
    val stability: Double = 1.0,
    val retrievability: Double = 1.0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val studiedAt: Long = System.currentTimeMillis(),
    val lastReviewedAt: Long? = null,
    val nextReviewAt: Long,
    val currentIntervalDays: Double = 0.0,
    val reviewCount: Int = 0,
    val lapseCount: Int = 0,
    val archived: Boolean = false,
    // Honest scheduling data (added in DB v5). nextReviewAt stays THE effective/actionable date every
    // query uses; these record WHY it is what it is:
    //  - modelDueAt: what the memory model last computed. A review sets both to the same value.
    //  - deferredUntil: set when the USER moved the date (Not today / redistribute / manual edit)
    //    without a review — the model's opinion in modelDueAt is preserved, so deferral can never
    //    masquerade as science. NULL = the schedule is purely the model's.
    // 0 = pre-v5 row whose model date was never distinguishable from its effective date.
    val modelDueAt: Long = 0,
    val deferredUntil: Long? = null,
    // Soft delete (added in DB v5): a deleted topic keeps its data for 30 days (recoverable from the
    // archive screen), then is purged on app start. NULL = not deleted. Deleted rows also have
    // archived=true so every active-list query excludes them for free.
    val deletedAt: Long? = null,
)
