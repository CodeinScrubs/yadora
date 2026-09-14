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
    // --- DB v6: the second clock, and which memory model owns this row's state ---
    /**
     * UNDERSTANDING remediation deadline, independent of the memory model. NULL = nothing to repair.
     *
     * Understanding used to be folded into the memory interval as a multiplier (Confused = ×0.8),
     * which is incoherent at long intervals: a topic the user says they do NOT understand would
     * still vanish for 80 days after a 100-day memory prediction. The two are different questions,
     * so they get different clocks — [nextReviewAt] is the EARLIER of this and [modelDueAt], and
     * this value never touches stability or difficulty.
     */
    val understandingDueAt: Long? = null,
    /**
     * The memory model that produced this row's current stability/difficulty.
     *
     * A stored FSRS state is only meaningful together with the model that computed it, so an
     * FSRS-5 state must never be fed to FSRS-6 as though the latent variables meant the same thing.
     * Existing rows migrate as "FSRS-5"; the first review after the upgrade PROJECTS the topic's
     * real history under FSRS-6 and flips this to "FSRS-6". Old review logs keep replaying under
     * the model each of them recorded.
     */
    val memoryModel: String = "FSRS-5",
    // --- DB v8 ---
    /**
     * Optional KEY POINTS: the answer to this topic split into the few ideas a complete recall must
     * contain, one per line ([com.example.domain.srs.KeyPoints]). At a review the learner ticks the
     * ones they actually produced, and the ticks cap the memory rating. Null = none, and the rating is
     * the learner's own judgement, exactly as before.
     */
    val keyPoints: String? = null,
    // --- DB v9 ---
    /**
     * Which FSRS-6 weight set produced this row's stability/difficulty (meaningful when [memoryModel] is
     * FSRS-6): 0 = the published defaults, otherwise a set fitted to this learner
     * (`memory_parameter_sets`). The same rule as the model itself: a state is only meaningful with the
     * weights that computed it, so a row still on an older set is PROJECTED onto the active one at its
     * next review, never relabelled.
     */
    val parameterSetId: Long = 0,
)
