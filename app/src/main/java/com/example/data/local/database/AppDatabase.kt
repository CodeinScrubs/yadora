package com.example.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.local.dao.CategoryDao
import com.example.data.local.dao.EventLogDao
import com.example.data.local.dao.ReviewLogDao
import com.example.data.local.dao.StudyUnitDao
import com.example.data.local.entity.EventLogEntity
import com.example.data.local.entity.ReviewLogEntity
import com.example.data.local.entity.StudyUnitEntity
import com.example.data.local.entity.SubjectEntity
import com.example.data.local.entity.SystemEntity

@Database(
    entities = [
        SubjectEntity::class,
        SystemEntity::class,
        StudyUnitEntity::class,
        ReviewLogEntity::class,
        EventLogEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun studyUnitDao(): StudyUnitDao
    abstract fun reviewLogDao(): ReviewLogDao
    abstract fun eventLogDao(): EventLogDao

    companion object {
        /**
         * v1 → v2: add the two calibration columns to review_logs and create the event_logs table.
         * Additive only, so existing rows are preserved (never fallbackToDestructiveMigration — that
         * would wipe a user's entire study history on a schema change).
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE review_logs ADD COLUMN retrievabilityAtReview REAL NOT NULL DEFAULT -1.0")
                db.execSQL("ALTER TABLE review_logs ADD COLUMN elapsedDays REAL NOT NULL DEFAULT -1.0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS event_logs (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "at INTEGER NOT NULL, " +
                        "type TEXT NOT NULL, " +
                        "unitId INTEGER, " +
                        "detail TEXT)"
                )
            }
        }

        /**
         * v2 → v3: tag every review log with what it IS — a first-study assessment or a real recall
         * review. Old rows default to UNKNOWN (they can't be reliably classified retroactively; a
         * unit's first log may be either kind depending on back-dating).
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE review_logs ADD COLUMN logType TEXT NOT NULL DEFAULT 'UNKNOWN'")
            }
        }

        /**
         * v3 → v4: per-review context columns (the data future weight-tuning can't backfill) and
         * indices on the hot query paths. Index names must match the @Index declarations exactly,
         * or Room's schema validation rejects migrated databases.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE review_logs ADD COLUMN initialDifficulty TEXT")
                db.execSQL("ALTER TABLE review_logs ADD COLUMN reviewDurationMs INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE review_logs ADD COLUMN wasImportantAtReview INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE review_logs ADD COLUMN desiredRetentionAtReview REAL NOT NULL DEFAULT -1.0")
                db.execSQL("ALTER TABLE review_logs ADD COLUMN schedulerVersion TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_study_units_nextReviewAt` ON `study_units` (`nextReviewAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_study_units_archived` ON `study_units` (`archived`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_review_logs_studyUnitId` ON `review_logs` (`studyUnitId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_review_logs_reviewedAt` ON `review_logs` (`reviewedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_event_logs_type` ON `event_logs` (`type`)")
            }
        }

        /**
         * v4 → v5 (additive, like every migration here):
         *  - study_units.modelDueAt/deferredUntil: separate what the memory model computed from what
         *    the user chose (deferrals stop overwriting the model's due date). Existing rows backfill
         *    modelDueAt from nextReviewAt — the best available truth for pre-v5 data.
         *  - study_units.deletedAt: 30-day recoverable soft delete.
         *  - review_logs.schedulerPolicyVersion/understandingFactorAtReview: per-log policy snapshot
         *    so future product-layer changes can't silently rewrite replayed history.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE study_units ADD COLUMN modelDueAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE study_units SET modelDueAt = nextReviewAt")
                db.execSQL("ALTER TABLE study_units ADD COLUMN deferredUntil INTEGER")
                db.execSQL("ALTER TABLE study_units ADD COLUMN deletedAt INTEGER")
                db.execSQL("ALTER TABLE review_logs ADD COLUMN schedulerPolicyVersion TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE review_logs ADD COLUMN understandingFactorAtReview REAL NOT NULL DEFAULT -1.0")
            }
        }
    }
}
