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
    val archived: Boolean = false
)
