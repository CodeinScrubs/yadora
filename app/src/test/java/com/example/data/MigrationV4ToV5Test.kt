package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.database.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The user's entire value is their stored study history, so a migration bug is catastrophic and
 * silent. This seeds a REAL v4 database (exact SQL + identity hash from the committed 4.json schema),
 * opens it through the app's real MIGRATION_4_5, and proves that:
 *   - the migration runs without Room rejecting the schema,
 *   - existing rows survive,
 *   - modelDueAt is backfilled from nextReviewAt (the v5 honest-scheduling invariant),
 *   - the new deferredUntil/deletedAt/policy columns exist and default correctly.
 * Runs on the JVM under Robolectric — no device needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MigrationV4ToV5Test {

    private val dbName = "migration-v4-v5-test.db"
    private lateinit var context: Context

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getDatabasePath(dbName).also { if (it.exists()) it.delete() }
    }

    @After fun teardown() {
        context.getDatabasePath(dbName).also { if (it.exists()) it.delete() }
    }

    /** Build a v4-shaped database exactly as Room v4 would have left it on disk, then seed one topic + log. */
    private fun seedV4() {
        val v4Sql = listOf(
            "CREATE TABLE IF NOT EXISTS `subjects` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `colorHex` TEXT, `createdAt` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `systems` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `colorHex` TEXT, `createdAt` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `study_units` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `subjectId` INTEGER, `systemId` INTEGER, `studyType` TEXT NOT NULL, `recallPrompt` TEXT, `notes` TEXT, `source` TEXT, `highYield` INTEGER NOT NULL, `state` TEXT NOT NULL, `difficulty` REAL NOT NULL, `stability` REAL NOT NULL, `retrievability` REAL NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `studiedAt` INTEGER NOT NULL, `lastReviewedAt` INTEGER, `nextReviewAt` INTEGER NOT NULL, `currentIntervalDays` REAL NOT NULL, `reviewCount` INTEGER NOT NULL, `lapseCount` INTEGER NOT NULL, `archived` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `review_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `studyUnitId` INTEGER NOT NULL, `reviewedAt` INTEGER NOT NULL, `memoryRating` TEXT NOT NULL, `understandingRating` TEXT NOT NULL, `previousIntervalDays` REAL NOT NULL, `nextIntervalDays` REAL NOT NULL, `previousState` TEXT NOT NULL, `nextState` TEXT NOT NULL, `retrievabilityAtReview` REAL NOT NULL, `elapsedDays` REAL NOT NULL, `logType` TEXT NOT NULL, `initialDifficulty` TEXT, `reviewDurationMs` INTEGER NOT NULL, `wasImportantAtReview` INTEGER NOT NULL, `desiredRetentionAtReview` REAL NOT NULL, `schedulerVersion` TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `event_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `at` INTEGER NOT NULL, `type` TEXT NOT NULL, `unitId` INTEGER, `detail` TEXT)",
            "CREATE INDEX IF NOT EXISTS `index_study_units_nextReviewAt` ON `study_units` (`nextReviewAt`)",
            "CREATE INDEX IF NOT EXISTS `index_study_units_archived` ON `study_units` (`archived`)",
            "CREATE INDEX IF NOT EXISTS `index_review_logs_studyUnitId` ON `review_logs` (`studyUnitId`)",
            "CREATE INDEX IF NOT EXISTS `index_review_logs_reviewedAt` ON `review_logs` (`reviewedAt`)",
            "CREATE INDEX IF NOT EXISTS `index_event_logs_type` ON `event_logs` (`type`)",
            // Room's schema-identity bookkeeping, with the exact hash committed in 4.json — without this
            // Room can't confirm the pre-migration schema and would refuse to migrate.
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, '22025c73135f47a2bfa17e8ba7b0d6c3')",
        )
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: SupportSQLiteDatabase) { v4Sql.forEach { db.execSQL(it) } }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        helper.writableDatabase.apply {
            // A real, mid-life topic and its recall log — the data a v4 user would actually have.
            execSQL(
                "INSERT INTO study_units (id,title,subjectId,systemId,studyType,recallPrompt,notes,source,highYield,state,difficulty,stability,retrievability,createdAt,updatedAt,studiedAt,lastReviewedAt,nextReviewAt,currentIntervalDays,reviewCount,lapseCount,archived) " +
                    "VALUES (7,'Nephrolithiasis',NULL,NULL,'Topic',NULL,'flank pain',NULL,1,'Building',6.2,12.5,0.91,1000,2000,1000,1900,5000,9.0,3,1,0)"
            )
            execSQL(
                "INSERT INTO review_logs (id,studyUnitId,reviewedAt,memoryRating,understandingRating,previousIntervalDays,nextIntervalDays,previousState,nextState,retrievabilityAtReview,elapsedDays,logType,initialDifficulty,reviewDurationMs,wasImportantAtReview,desiredRetentionAtReview,schedulerVersion) " +
                    "VALUES (11,7,1900,'Good','Clear',4.0,9.0,'Learning','Building',0.9,4.0,'RECALL',NULL,3000,1,0.93,'FSRS-5')"
            )
            close()
        }
    }

    @Test
    fun `v4 database migrates to v5 preserving data and backfilling modelDueAt`() = runBlocking {
        seedV4()

        // Opening AppDatabase (version 5) against the v4 file forces MIGRATION_4_5 to run. If Room
        // rejects the migrated schema, .build()+first query throws and the test fails.
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()

        val unit = db.studyUnitDao().getUnitById(7)!!
        assertEquals("row survived", "Nephrolithiasis", unit.title)
        assertEquals("scientific state preserved", 12.5, unit.stability, 1e-9)
        assertEquals("reviewCount preserved", 3, unit.reviewCount)
        assertEquals("modelDueAt backfilled from nextReviewAt", 5000L, unit.modelDueAt)
        assertNull("no deferral introduced by migration", unit.deferredUntil)
        assertNull("not deleted", unit.deletedAt)

        val logs = db.reviewLogDao().getLogsForUnit(7).first()
        assertEquals("log survived", 1, logs.size)
        assertEquals("log memory rating preserved", "Good", logs[0].memoryRating)
        // v5 log columns exist with their documented pre-v5 defaults.
        assertEquals("policy version defaults empty pre-v5", "", logs[0].schedulerPolicyVersion)
        assertEquals("understanding factor defaults -1 pre-v5", -1.0, logs[0].understandingFactorAtReview, 1e-9)

        db.close()
    }
}
