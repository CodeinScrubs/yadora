package com.example.data

import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.local.entity.EventLogEntity

/**
 * AUTOINCREMENT history belongs to this installation. A backup can retain audit references to
 * permanently purged rows, so restoring just the surviving rows on a new phone reuses their old ids.
 * That aliases growth/Undo, merge exclusions, correction ranges and prospective forecast joins.
 * Reserve all known retained references, without deleting history or changing the backup schema.
 */
internal object BackupIdentity {
    data class Floors(val topic: Long, val review: Long)

    fun floors(unitIds: Iterable<Long>, logIds: Iterable<Long>, events: List<EventLogEntity>): Floors {
        var topic = 0L
        var review = 0L
        fun reservable(id: Long): Long {
            require(id != Long.MAX_VALUE) { "Damaged backup: exhausted historical identity" }
            return id.coerceAtLeast(0L)
        }
        unitIds.forEach { topic = maxOf(topic, reservable(it)) }
        logIds.forEach { review = maxOf(review, reservable(it)) }
        for (event in events) {
            event.unitId?.let { topic = maxOf(topic, reservable(it)) }
            val detail = event.detail ?: continue
            when (event.type) {
                "STUDY_ACTION" -> detail.toLongOrNull()?.let { review = maxOf(review, reservable(it)) }
                "MERGE" -> detail.split(',').forEach { field ->
                    field.trim().toLongOrNull()?.let { topic = maxOf(topic, reservable(it)) }
                }
                "RATING_CORRECTED", "REVIEW_DATE_CORRECTED", "REVIEW_FORECAST" -> detail.split(' ').forEach { field ->
                    if (field.startsWith("log=") || (event.type != "REVIEW_FORECAST" && field.startsWith("upto="))) {
                        field.substringAfter('=').toLongOrNull()?.let { review = maxOf(review, reservable(it)) }
                    }
                }
            }
        }
        return Floors(topic, review)
    }

    /** Called in the SAME restore transaction, after inserting every restored row. Never lower this phone's marks. */
    fun preserve(db: SupportSQLiteDatabase, floors: Floors) {
        // Fixed table names, bound values: event detail and filenames never become SQL.
        for ((table, floor) in listOf("study_units" to floors.topic, "review_logs" to floors.review)) {
            if (floor <= 0L) continue
            db.execSQL("UPDATE sqlite_sequence SET seq = MAX(seq, ?) WHERE name = ?", arrayOf<Any>(floor, table))
            db.execSQL("INSERT INTO sqlite_sequence(name, seq) SELECT ?, ? " +
                "WHERE NOT EXISTS (SELECT 1 FROM sqlite_sequence WHERE name = ?)", arrayOf<Any>(table, floor, table))
        }
    }
}
