package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
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
 * v9 → v10: what a review consisted of (pilot research data).
 *
 * Adds reviewMethods / questionsCorrect / questionsTotal / sessionKind to review_logs, as "not recorded"
 * for every existing row, because nobody was asked. Seeds a REAL v9 database (exact SQL + identity hash
 * from the committed 9.json schema) and checks that nothing else moves.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MigrationV9ToV10Test {

    private val dbName = "migration-v9-v10-test.db"
    private lateinit var context: Context

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getDatabasePath(dbName).also { if (it.exists()) it.delete() }
    }

    @After fun teardown() {
        context.getDatabasePath(dbName).also { if (it.exists()) it.delete() }
    }

    private fun seedV9() {
        val v9Sql = listOf(
            "CREATE TABLE IF NOT EXISTS `subjects` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `colorHex` TEXT, `createdAt` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `systems` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `colorHex` TEXT, `createdAt` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `study_units` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `subjectId` INTEGER, `systemId` INTEGER, `studyType` TEXT NOT NULL, `recallPrompt` TEXT, `notes` TEXT, `source` TEXT, `highYield` INTEGER NOT NULL, `state` TEXT NOT NULL, `difficulty` REAL NOT NULL, `stability` REAL NOT NULL, `retrievability` REAL NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `studiedAt` INTEGER NOT NULL, `lastReviewedAt` INTEGER, `nextReviewAt` INTEGER NOT NULL, `currentIntervalDays` REAL NOT NULL, `reviewCount` INTEGER NOT NULL, `lapseCount` INTEGER NOT NULL, `archived` INTEGER NOT NULL, `modelDueAt` INTEGER NOT NULL, `deferredUntil` INTEGER, `deletedAt` INTEGER, `understandingDueAt` INTEGER, `memoryModel` TEXT NOT NULL, `keyPoints` TEXT, `parameterSetId` INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_study_units_nextReviewAt` ON `study_units` (`nextReviewAt`)",
            "CREATE INDEX IF NOT EXISTS `index_study_units_archived` ON `study_units` (`archived`)",
            "CREATE TABLE IF NOT EXISTS `review_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `studyUnitId` INTEGER NOT NULL, `reviewedAt` INTEGER NOT NULL, `memoryRating` TEXT NOT NULL, `understandingRating` TEXT NOT NULL, `previousIntervalDays` REAL NOT NULL, `nextIntervalDays` REAL NOT NULL, `previousState` TEXT NOT NULL, `nextState` TEXT NOT NULL, `retrievabilityAtReview` REAL NOT NULL, `elapsedDays` REAL NOT NULL, `logType` TEXT NOT NULL, `initialDifficulty` TEXT, `reviewDurationMs` INTEGER NOT NULL, `wasImportantAtReview` INTEGER NOT NULL, `desiredRetentionAtReview` REAL NOT NULL, `schedulerVersion` TEXT NOT NULL, `schedulerPolicyVersion` TEXT NOT NULL, `understandingFactorAtReview` REAL NOT NULL, `calibrationScaleAtReview` REAL NOT NULL, `keyPointsTotal` INTEGER NOT NULL, `keyPointsRecalled` INTEGER NOT NULL, `parameterSetId` INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_review_logs_studyUnitId` ON `review_logs` (`studyUnitId`)",
            "CREATE INDEX IF NOT EXISTS `index_review_logs_reviewedAt` ON `review_logs` (`reviewedAt`)",
            "CREATE TABLE IF NOT EXISTS `event_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `at` INTEGER NOT NULL, `type` TEXT NOT NULL, `unitId` INTEGER, `detail` TEXT)",
            "CREATE INDEX IF NOT EXISTS `index_event_logs_type` ON `event_logs` (`type`)",
            "CREATE TABLE IF NOT EXISTS `memory_parameter_sets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `createdAt` INTEGER NOT NULL, `status` TEXT NOT NULL, `weights` TEXT NOT NULL, `comparedWithSetId` INTEGER NOT NULL, `availableReviews` INTEGER NOT NULL, `trainReviews` INTEGER NOT NULL, `testReviews` INTEGER NOT NULL, `currentLogLoss` REAL NOT NULL, `candidateLogLoss` REAL NOT NULL, `currentRmseBins` REAL NOT NULL, `candidateRmseBins` REAL NOT NULL, `currentAuc` REAL NOT NULL, `candidateAuc` REAL NOT NULL, `zScore` REAL NOT NULL, `activatedAt` INTEGER, `retiredAt` INTEGER)",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, 'c0129a0bc86dbb5b0e8b9fe8c8480b0b')",
        )
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(9) {
                    override fun onCreate(db: SupportSQLiteDatabase) { v9Sql.forEach { db.execSQL(it) } }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        helper.writableDatabase.apply {
            execSQL(
                "INSERT INTO study_units (id,title,subjectId,systemId,studyType,recallPrompt,notes,source,highYield,state,difficulty,stability,retrievability,createdAt,updatedAt,studiedAt,lastReviewedAt,nextReviewAt,currentIntervalDays,reviewCount,lapseCount,archived,modelDueAt,deferredUntil,deletedAt,understandingDueAt,memoryModel,keyPoints,parameterSetId) " +
                    "VALUES (11,'Appendicitis',NULL,NULL,'Topic','Diagnostic criteria','RLQ pain',NULL,1,'Strong',2.13,68.93,0.95,1000,2000,1000,5000,7000,110.3,3,0,0,9000,NULL,NULL,7000,'FSRS-6',NULL,0)"
            )
            execSQL(
                "INSERT INTO review_logs (id,studyUnitId,reviewedAt,memoryRating,understandingRating,previousIntervalDays,nextIntervalDays,previousState,nextState,retrievabilityAtReview,elapsedDays,logType,initialDifficulty,reviewDurationMs,wasImportantAtReview,desiredRetentionAtReview,schedulerVersion,schedulerPolicyVersion,understandingFactorAtReview,calibrationScaleAtReview,keyPointsTotal,keyPointsRecalled,parameterSetId) " +
                    "VALUES (21,11,5000,'Hard','Clear',4.5,110.3,'Building','Strong',0.912,5.0,'RECALL',NULL,23054,1,0.93,'FSRS-6','YADORA-6',1.0,0.87,-1,-1,0)"
            )
            close()
        }
    }

    @Test
    fun `v9 migrates to v10 with every existing review marked not recorded`() = runBlocking {
        seedV9()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()

        val log = db.reviewLogDao().getLogsForUnit(11).first().single()
        assertNull("nobody was asked how they reviewed", log.reviewMethods)
        assertEquals("no question score", -1, log.questionsCorrect)
        assertEquals(-1, log.questionsTotal)
        assertNull("the session kind was not recorded", log.sessionKind)
        assertEquals("rating untouched", "Hard", log.memoryRating)
        assertEquals("prediction untouched", 0.912, log.retrievabilityAtReview, 1e-9)
        assertEquals("calibration scale untouched", 0.87, log.calibrationScaleAtReview, 1e-9)

        val unit = db.studyUnitDao().getUnitById(11)!!
        assertEquals("due date untouched", 7000L, unit.nextReviewAt)
        assertEquals("stability untouched", 68.93, unit.stability, 1e-9)

        // A new row can carry the fields.
        db.reviewLogDao().insertLog(
            log.copy(id = 0, reviewedAt = 6000, reviewMethods = "Questions", questionsCorrect = 7, questionsTotal = 10, sessionKind = "AHEAD")
        )
        val stored = db.reviewLogDao().getLogsForUnit(11).first().first { it.reviewedAt == 6000L }
        assertEquals("Questions", stored.reviewMethods)
        assertEquals(7, stored.questionsCorrect)
        assertEquals(10, stored.questionsTotal)
        assertEquals("AHEAD", stored.sessionKind)
        db.close()
    }
}
