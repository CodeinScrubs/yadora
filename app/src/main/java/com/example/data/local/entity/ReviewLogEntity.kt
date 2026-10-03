package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "review_logs",
    // Hot paths (added in DB v4): per-unit history loads and time-windowed stats.
    indices = [
        Index(value = ["studyUnitId"], name = "index_review_logs_studyUnitId"),
        Index(value = ["reviewedAt"], name = "index_review_logs_reviewedAt"),
    ],
)
data class ReviewLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val studyUnitId: Long,
    val reviewedAt: Long = System.currentTimeMillis(),
    val memoryRating: String,
    val understandingRating: String,
    val previousIntervalDays: Double,
    val nextIntervalDays: Double,
    val previousState: String,
    val nextState: String,
    // Calibration data (added in DB v2): the model's predicted recall probability at review time and
    // the actual elapsed days. Lets exported logs answer "was the scheduler well-calibrated?" — does
    // predicted R match the observed Forgot-rate. -1.0 for rows written before v2.
    val retrievabilityAtReview: Double = -1.0,
    val elapsedDays: Double = -1.0,
    // What kind of event this row is (added in DB v3). A FIRST_STUDY row's memoryRating is really a
    // "how difficult did this topic feel?" answer, NOT a delayed-recall grade — without this tag the
    // two signals are indistinguishable in exports and would pollute recall analytics. The v3
    // migration leaves pre-existing rows UNKNOWN (classifying them needs the per-unit replay logic,
    // not SQL); an edit-rating replay recomputes the classification from timestamps — the same rule
    // the live path uses — and upgrades those rows to their true type as a side effect.
    val logType: String = "UNKNOWN", // FIRST_STUDY | RECALL | UNKNOWN
    // Per-review context (added in DB v4) — the fields future analysis/weight-tuning cannot backfill:
    // the difficulty label the user actually chose on a first study (memoryRating holds only its FSRS
    // mapping), how long the card was open before rating, and the scheduler settings in force at the
    // moment of the review. -1/'' = recorded before v4.
    val initialDifficulty: String? = null,          // Easy | Medium | Hard (FIRST_STUDY rows only)
    val reviewDurationMs: Long = -1,
    val wasImportantAtReview: Int = -1,             // 1/0; -1 = unknown (pre-v4)
    val desiredRetentionAtReview: Double = -1.0,
    val schedulerVersion: String = "",
    // Policy snapshot (added in DB v5) — the foundation for ever changing a product-layer number
    // safely. schedulerVersion above names the MEMORY MODEL (FSRS-5); this names the Yadora policy
    // bundle around it, and understandingFactorAtReview freezes the multiplier actually applied, so
    // a future factor change replays old reviews under their ORIGINAL factor instead of silently
    // rewriting history. -1/'' = recorded before v5 (replay falls back to current constants).
    val schedulerPolicyVersion: String = "",
    val understandingFactorAtReview: Double = -1.0,
    // The per-user calibration scale the memory interval was multiplied by (added in DB v7; see
    // RecallCalibration). Stored for the same reason as the retention target: the scale moves as
    // evidence accumulates, and a replay must reproduce the interval this review was actually given,
    // not re-decide it under today's estimate. -1.0 = recorded before v7 (replay treats it as 1.0).
    val calibrationScaleAtReview: Double = -1.0,
    // Key-point scoring (added in DB v8): how many key points the topic showed at this review and how
    // many the learner ticked as recalled. -1 = no key points were scored (none defined, a first
    // check-in, or a row written before v8).
    val keyPointsTotal: Int = -1,
    val keyPointsRecalled: Int = -1,
    // Which FSRS-6 weight set produced this review's prediction and interval (added in DB v9): 0 = the
    // published defaults, as for every row written before personal sets existed. The calibration and
    // the Progress card only pool rows of the set they describe.
    val parameterSetId: Long = 0,
    // v10 — pilot research data. None of it feeds the scheduler.
    /** How the learner reviewed: comma-separated [com.example.domain.model.ReviewMethod] names; null = not said. */
    val reviewMethods: String? = null,
    /** Questions answered right / answered in this review, when the learner entered a score; -1 = not recorded. */
    val questionsCorrect: Int = -1,
    val questionsTotal: Int = -1,
    /** The session that produced this log: PLAN, EXTRA, TOPIC or AHEAD ([com.example.domain.model.SessionKind]); null before v10. */
    val sessionKind: String? = null,
)

/**
 * The order a topic's reviews happened in: the order they were saved. The id is autoincrement and a restore keeps
 * it, so it records the true sequence; the time stamp does not. A phone clock set back between two reviews gives the
 * later review the earlier time, and sorting a history by time replayed it in the wrong order, so a correction or a
 * model change rebuilt a state the topic never had (an outside audit, 2026-10-02). The live path clamps such a gap to
 * zero; replaying in this order reproduces it. Every replay, the repair-clock streak and the export walk histories in
 * this order (tools/pilot/analyze.py too). Time breaks a tie only between logs not yet saved (id 0).
 */
val REVIEW_HISTORY_ORDER: Comparator<ReviewLogEntity> = compareBy({ it.id }, { it.reviewedAt })
