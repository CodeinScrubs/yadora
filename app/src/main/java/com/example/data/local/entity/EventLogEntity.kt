package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A lightweight audit trail of non-review actions (added in DB v2). Procrastinate / "not today" /
 * redistribute / snooze were previously invisible in the exported data, so we couldn't tell whether a
 * user was actually keeping up or silently pushing everything forward. Logging them makes the
 * behavioural picture (and any future coaching) grounded in real events rather than guesswork.
 */
@Entity(
    tableName = "event_logs",
    indices = [androidx.room.Index(value = ["type"], name = "index_event_logs_type")],
)
data class EventLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long = System.currentTimeMillis(),
    // STUDY_ACTION | PROCRASTINATE | PROCRASTINATE_ALL | REDISTRIBUTE | SNOOZE | MERGE | NOTIF_SHOWN |
    // PROJECTION_FAILED | MISSED_REMINDER_REPORT | PERSONAL_MODEL | PERSONAL_MODEL_FAILED | PERSONAL_MODEL_OFF |
    // PERSONAL_MODEL_DISCARDED | PERSONAL_MODEL_RETIRED (an active set with its grades out of order, at refresh) |
    // REMINDER_FIRED (every reminder alarm, ReminderTelemetry) | REMINDERS_ON | REMINDERS_OFF (the Settings switch) |
    // APP_OPENED (from a reminder, the alarm or the widget) | RATING_CORRECTED (com.example.data.RecomputedPredictions)
    val type: String,
    val unitId: Long? = null,
    val detail: String? = null,
)
