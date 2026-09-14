package com.example.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.local.dao.CategoryDao
import com.example.data.local.dao.EventLogDao
import com.example.data.local.dao.MemoryParameterSetDao
import com.example.data.local.dao.ReviewLogDao
import com.example.data.local.dao.StudyUnitDao
import com.example.data.local.entity.EventLogEntity
import com.example.data.local.entity.MemoryParameterSetEntity
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
        MemoryParameterSetEntity::class,
    ],
    version = 9,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun studyUnitDao(): StudyUnitDao
    abstract fun reviewLogDao(): ReviewLogDao
    abstract fun eventLogDao(): EventLogDao
    abstract fun memoryParameterSetDao(): MemoryParameterSetDao

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

        /**
         * v5 → v6 (additive, like every migration here): the second clock, and model identity.
         *
         *  - study_units.understandingDueAt: the UNDERSTANDING remediation deadline, separate from
         *    the memory prediction. Backfilled NULL — no existing topic has a pending repair, and
         *    inventing one would drag every topic forward on upgrade day.
         *  - study_units.memoryModel: which model produced this row's stability/difficulty. Every
         *    existing row is FSRS-5 by definition, so that is the backfill. The first review after
         *    the upgrade projects the topic's real history into FSRS-6 and flips this.
         *
         * Nothing is rewritten: no schedule moves purely because the app was updated.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE study_units ADD COLUMN understandingDueAt INTEGER")
                db.execSQL("ALTER TABLE study_units ADD COLUMN memoryModel TEXT NOT NULL DEFAULT 'FSRS-5'")
            }
        }

        /**
         * v6 → v7 (additive, like every migration here): review_logs.calibrationScaleAtReview, the
         * per-user interval correction in force at each review, so replay reproduces the interval a
         * review was given rather than re-deciding it under a later estimate. Existing rows get the
         * "not recorded" sentinel (-1.0), which replay reads as 1.0 — exactly what they were scheduled
         * with. No schedule moves.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE review_logs ADD COLUMN calibrationScaleAtReview REAL NOT NULL DEFAULT -1.0")
            }
        }

        /**
         * v7 → v8 (additive, like every migration here): key points. study_units.keyPoints holds a
         * topic's optional key points (NULL for every existing topic, so nothing about its review
         * changes), and review_logs.keyPointsTotal/keyPointsRecalled record how a review scored against
         * them, with the "not scored" sentinel (-1) for every existing row. No schedule moves.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE study_units ADD COLUMN keyPoints TEXT")
                db.execSQL("ALTER TABLE review_logs ADD COLUMN keyPointsTotal INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE review_logs ADD COLUMN keyPointsRecalled INTEGER NOT NULL DEFAULT -1")
            }
        }

        /**
         * v8 → v9 (additive, like every migration here): the personal memory model. A new table records
         * every attempt to fit FSRS-6 to the learner's own history and the sets that passed; study_units
         * and review_logs gain parameterSetId, which is 0 — the published default weights — for every
         * existing row, because that is exactly what computed them. No schedule moves.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `memory_parameter_sets` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`status` TEXT NOT NULL, `weights` TEXT NOT NULL, `comparedWithSetId` INTEGER NOT NULL, " +
                        "`availableReviews` INTEGER NOT NULL, `trainReviews` INTEGER NOT NULL, `testReviews` INTEGER NOT NULL, " +
                        "`currentLogLoss` REAL NOT NULL, `candidateLogLoss` REAL NOT NULL, " +
                        "`currentRmseBins` REAL NOT NULL, `candidateRmseBins` REAL NOT NULL, " +
                        "`currentAuc` REAL NOT NULL, `candidateAuc` REAL NOT NULL, `zScore` REAL NOT NULL, " +
                        "`activatedAt` INTEGER, `retiredAt` INTEGER)"
                )
                db.execSQL("ALTER TABLE study_units ADD COLUMN parameterSetId INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE review_logs ADD COLUMN parameterSetId INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Every migration, in order — one list so no builder can forget the newest one. */
        val ALL_MIGRATIONS: Array<Migration>
            get() = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
    }
}
