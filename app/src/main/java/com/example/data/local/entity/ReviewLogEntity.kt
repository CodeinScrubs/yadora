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
    val schedulerVersion: String = ""
)
