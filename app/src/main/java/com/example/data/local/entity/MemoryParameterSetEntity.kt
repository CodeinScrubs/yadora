package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One attempt to fit FSRS-6 to this learner's own history (DB v9, `Fsrs6Optimizer`), and — when it
 * passed the held-out comparison — the weights the scheduler used from then on.
 *
 * Written once and never edited, like a model id: a stored stability means something only together
 * with the weights that produced it, so a set that has ever scheduled a review is kept for replay and
 * only ever RETIRED. The default weights are set 0 and have no row.
 */
@Entity(tableName = "memory_parameter_sets")
data class MemoryParameterSetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    /** ACTIVE (schedules new reviews), RETIRED (used before, kept for replay), REJECTED (failed the gate, never used). */
    val status: String,
    /** The 21 weights, comma-separated in Kotlin's locale-independent Double format; empty when REJECTED. */
    val weights: String,
    /** The set in use when this attempt was judged (0 = the default weights). */
    val comparedWithSetId: Long,
    /** Loss-eligible recall reviews that existed when the attempt ran: what the next attempt waits to exceed. */
    val availableReviews: Int,
    val trainReviews: Int,
    val testReviews: Int,
    // Held-out scores of the weights then in use and of the candidate, on the same later reviews.
    // -1 = not computable (an AUC needs both outcomes to occur).
    val currentLogLoss: Double,
    val candidateLogLoss: Double,
    val currentRmseBins: Double,
    val candidateRmseBins: Double,
    val currentAuc: Double,
    val candidateAuc: Double,
    /** One-sided paired z of the candidate's per-review log-loss improvement, clamped to ±99. */
    val zScore: Double,
    val activatedAt: Long? = null,
    val retiredAt: Long? = null,
) {
    companion object {
        const val ACTIVE = "ACTIVE"
        const val RETIRED = "RETIRED"
        const val REJECTED = "REJECTED"
    }
}
