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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v6 → v7: the per-log calibration scale.
 *
 * Adds one column to review_logs and nothing else. Existing rows get the "not recorded" sentinel,
 * which replay reads as a scale of 1.0 — the scale those reviews were in fact given — so upgrading
 * moves no date and changes no replay. Seeds a REAL v6 database (exact SQL + identity hash from the
 * committed 6.json schema).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MigrationV6ToV7Test {

    private val dbName = "migration-v6-v7-test.db"
    private lateinit var context: Context

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getDatabasePath(dbName).also { if (it.exists()) it.delete() }
    }

    @After fun teardown() {
        context.getDatabasePath(dbName).also { if (it.exists()) it.delete() }
    }

    private fun seedV6() {
        val v6Sql = listOf(
            "CREATE TABLE IF NOT EXISTS `subjects` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `colorHex` TEXT, `createdAt` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `systems` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `colorHex` TEXT, `createdAt` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `study_units` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `subjectId` INTEGER, `systemId` INTEGER, `studyType` TEXT NOT NULL, `recallPrompt` TEXT, `notes` TEXT, `source` TEXT, `highYield` INTEGER NOT NULL, `state` TEXT NOT NULL, `difficulty` REAL NOT NULL, `stability` REAL NOT NULL, `retrievability` REAL NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `studiedAt` INTEGER NOT NULL, `lastReviewedAt` INTEGER, `nextReviewAt` INTEGER NOT NULL, `currentIntervalDays` REAL NOT NULL, `reviewCount` INTEGER NOT NULL, `lapseCount` INTEGER NOT NULL, `archived` INTEGER NOT NULL, `modelDueAt` INTEGER NOT NULL, `deferredUntil` INTEGER, `deletedAt` INTEGER, `understandingDueAt` INTEGER, `memoryModel` TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `review_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `studyUnitId` INTEGER NOT NULL, `reviewedAt` INTEGER NOT NULL, `memoryRating` TEXT NOT NULL, `understandingRating` TEXT NOT NULL, `previousIntervalDays` REAL NOT NULL, `nextIntervalDays` REAL NOT NULL, `previousState` TEXT NOT NULL, `nextState` TEXT NOT NULL, `retrievabilityAtReview` REAL NOT NULL, `elapsedDays` REAL NOT NULL, `logType` TEXT NOT NULL, `initialDifficulty` TEXT, `reviewDurationMs` INTEGER NOT NULL, `wasImportantAtReview` INTEGER NOT NULL, `desiredRetentionAtReview` REAL NOT NULL, `schedulerVersion` TEXT NOT NULL, `schedulerPolicyVersion` TEXT NOT NULL, `understandingFactorAtReview` REAL NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `event_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `at` INTEGER NOT NULL, `type` TEXT NOT NULL, `unitId` INTEGER, `detail` TEXT)",
            "CREATE INDEX IF NOT EXISTS `index_study_units_nextReviewAt` ON `study_units` (`nextReviewAt`)",
            "CREATE INDEX IF NOT EXISTS `index_study_units_archived` ON `study_units` (`archived`)",
            "CREATE INDEX IF NOT EXISTS `index_review_logs_studyUnitId` ON `review_logs` (`studyUnitId`)",
            "CREATE INDEX IF NOT EXISTS `index_review_logs_reviewedAt` ON `review_logs` (`reviewedAt`)",
            "CREATE INDEX IF NOT EXISTS `index_event_logs_type` ON `event_logs` (`type`)",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, '7cdcb75472dac94d4cd0bacc68d7a8c8')",
        )
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(6) {
                    override fun onCreate(db: SupportSQLiteDatabase) { v6Sql.forEach { db.execSQL(it) } }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        helper.writableDatabase.apply {
            // A live FSRS-6 topic with BOTH clocks set: the understanding deadline is exactly the kind
            // of thing a careless migration would reinterpret.
            execSQL(
                "INSERT INTO study_units (id,title,subjectId,systemId,studyType,recallPrompt,notes,source,highYield,state,difficulty,stability,retrievability,createdAt,updatedAt,studiedAt,lastReviewedAt,nextReviewAt,currentIntervalDays,reviewCount,lapseCount,archived,modelDueAt,deferredUntil,deletedAt,understandingDueAt,memoryModel) " +
                    "VALUES (11,'Appendicitis',NULL,NULL,'Topic','Diagnostic criteria','RLQ pain',NULL,1,'Strong',2.13,68.93,0.95,1000,2000,1000,5000,7000,110.3,3,0,0,9000,NULL,NULL,7000,'FSRS-6')"
            )
            execSQL(
                "INSERT INTO review_logs (id,studyUnitId,reviewedAt,memoryRating,understandingRating,previousIntervalDays,nextIntervalDays,previousState,nextState,retrievabilityAtReview,elapsedDays,logType,initialDifficulty,reviewDurationMs,wasImportantAtReview,desiredRetentionAtReview,schedulerVersion,schedulerPolicyVersion,understandingFactorAtReview) " +
                    "VALUES (21,11,5000,'Good','Partial',4.5,110.3,'Building','Strong',0.912,5.0,'RECALL',NULL,23054,1,0.93,'FSRS-6','YADORA-5',0.9)"
            )
            close()
        }
    }

    @Test
    fun `v6 migrates to v7 adding the scale column without moving any schedule`() = runBlocking {
        seedV6()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()

        val unit = db.studyUnitDao().getUnitById(11)!!
        assertEquals("title", "Appendicitis", unit.title)
        assertEquals("stability untouched", 68.93, unit.stability, 1e-9)
        assertEquals("difficulty untouched", 2.13, unit.difficulty, 1e-9)
        assertEquals("effective due date untouched", 7000L, unit.nextReviewAt)
        assertEquals("model due date untouched", 9000L, unit.modelDueAt)
        assertEquals("understanding deadline untouched", 7000L, unit.understandingDueAt)
        assertNull("no deferral invented", unit.deferredUntil)
        assertEquals("model identity untouched", "FSRS-6", unit.memoryModel)
        assertEquals("reviewCount", 3, unit.reviewCount)

        val logs = db.reviewLogDao().getLogsForUnit(11).first()
        assertEquals("log survived", 1, logs.size)
        assertEquals("the new column carries the not-recorded sentinel", -1.0, logs[0].calibrationScaleAtReview, 0.0)
        assertEquals("policy stamp untouched", "YADORA-5", logs[0].schedulerPolicyVersion)
        assertEquals("retention target untouched", 0.93, logs[0].desiredRetentionAtReview, 1e-9)
        assertEquals("prediction untouched", 0.912, logs[0].retrievabilityAtReview, 1e-9)

        db.close()
    }

    @Test
    fun `a freshly written v7 log round-trips its scale`() = runBlocking {
        seedV6()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()

        val id = db.reviewLogDao().insertLog(
            com.example.data.local.entity.ReviewLogEntity(
                studyUnitId = 11, reviewedAt = 8000, memoryRating = "Good", understandingRating = "Clear",
                previousIntervalDays = 110.3, nextIntervalDays = 200.0, previousState = "Strong", nextState = "Strong",
                calibrationScaleAtReview = 0.83,
            )
        )
        val fresh = db.reviewLogDao().getLogsForUnit(11).first().first { it.id == id }
        assertEquals("calibrationScaleAtReview round-trips", 0.83, fresh.calibrationScaleAtReview, 0.0)

        db.close()
    }
}
