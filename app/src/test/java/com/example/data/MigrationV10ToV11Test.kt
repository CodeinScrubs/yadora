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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v10 → v11: the learner's optional minutes per review and when a review was saved (the owner's decisions, 2026-10-09).
 *
 * Adds studyMinutes / loggedAt to review_logs, as "not given" / "not recorded" (-1) for every existing row, which is the
 * truth: nobody was asked, and the save time was never kept. Seeds a REAL v10 database (exact SQL and identity hash from
 * the committed 10.json schema) and checks that nothing else moves.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MigrationV10ToV11Test {

    private val dbName = "migration-v10-v11-test.db"
    private lateinit var context: Context

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getDatabasePath(dbName).also { if (it.exists()) it.delete() }
    }

    @After fun teardown() {
        context.getDatabasePath(dbName).also { if (it.exists()) it.delete() }
    }

    private fun seedV10() {
        val v10Sql = listOf(
            "CREATE TABLE IF NOT EXISTS `subjects` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `colorHex` TEXT, `createdAt` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `systems` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `colorHex` TEXT, `createdAt` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `study_units` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `subjectId` INTEGER, `systemId` INTEGER, `studyType` TEXT NOT NULL, `recallPrompt` TEXT, `notes` TEXT, `source` TEXT, `highYield` INTEGER NOT NULL, `state` TEXT NOT NULL, `difficulty` REAL NOT NULL, `stability` REAL NOT NULL, `retrievability` REAL NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `studiedAt` INTEGER NOT NULL, `lastReviewedAt` INTEGER, `nextReviewAt` INTEGER NOT NULL, `currentIntervalDays` REAL NOT NULL, `reviewCount` INTEGER NOT NULL, `lapseCount` INTEGER NOT NULL, `archived` INTEGER NOT NULL, `modelDueAt` INTEGER NOT NULL, `deferredUntil` INTEGER, `deletedAt` INTEGER, `understandingDueAt` INTEGER, `memoryModel` TEXT NOT NULL, `keyPoints` TEXT, `parameterSetId` INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_study_units_nextReviewAt` ON `study_units` (`nextReviewAt`)",
            "CREATE INDEX IF NOT EXISTS `index_study_units_archived` ON `study_units` (`archived`)",
            "CREATE TABLE IF NOT EXISTS `review_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `studyUnitId` INTEGER NOT NULL, `reviewedAt` INTEGER NOT NULL, `memoryRating` TEXT NOT NULL, `understandingRating` TEXT NOT NULL, `previousIntervalDays` REAL NOT NULL, `nextIntervalDays` REAL NOT NULL, `previousState` TEXT NOT NULL, `nextState` TEXT NOT NULL, `retrievabilityAtReview` REAL NOT NULL, `elapsedDays` REAL NOT NULL, `logType` TEXT NOT NULL, `initialDifficulty` TEXT, `reviewDurationMs` INTEGER NOT NULL, `wasImportantAtReview` INTEGER NOT NULL, `desiredRetentionAtReview` REAL NOT NULL, `schedulerVersion` TEXT NOT NULL, `schedulerPolicyVersion` TEXT NOT NULL, `understandingFactorAtReview` REAL NOT NULL, `calibrationScaleAtReview` REAL NOT NULL, `keyPointsTotal` INTEGER NOT NULL, `keyPointsRecalled` INTEGER NOT NULL, `parameterSetId` INTEGER NOT NULL, `reviewMethods` TEXT, `questionsCorrect` INTEGER NOT NULL, `questionsTotal` INTEGER NOT NULL, `sessionKind` TEXT)",
            "CREATE INDEX IF NOT EXISTS `index_review_logs_studyUnitId` ON `review_logs` (`studyUnitId`)",
            "CREATE INDEX IF NOT EXISTS `index_review_logs_reviewedAt` ON `review_logs` (`reviewedAt`)",
            "CREATE TABLE IF NOT EXISTS `event_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `at` INTEGER NOT NULL, `type` TEXT NOT NULL, `unitId` INTEGER, `detail` TEXT)",
            "CREATE INDEX IF NOT EXISTS `index_event_logs_type` ON `event_logs` (`type`)",
            "CREATE TABLE IF NOT EXISTS `memory_parameter_sets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `createdAt` INTEGER NOT NULL, `status` TEXT NOT NULL, `weights` TEXT NOT NULL, `comparedWithSetId` INTEGER NOT NULL, `availableReviews` INTEGER NOT NULL, `trainReviews` INTEGER NOT NULL, `testReviews` INTEGER NOT NULL, `currentLogLoss` REAL NOT NULL, `candidateLogLoss` REAL NOT NULL, `currentRmseBins` REAL NOT NULL, `candidateRmseBins` REAL NOT NULL, `currentAuc` REAL NOT NULL, `candidateAuc` REAL NOT NULL, `zScore` REAL NOT NULL, `activatedAt` INTEGER, `retiredAt` INTEGER)",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, '91516bf98e01396d1c67cc5bd36cb7af')",
        )
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(10) {
                    override fun onCreate(db: SupportSQLiteDatabase) { v10Sql.forEach { db.execSQL(it) } }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        helper.writableDatabase.apply {
            execSQL(
                "INSERT INTO study_units (id,title,subjectId,systemId,studyType,recallPrompt,notes,source,highYield,state,difficulty,stability,retrievability,createdAt,updatedAt,studiedAt,lastReviewedAt,nextReviewAt,currentIntervalDays,reviewCount,lapseCount,archived,modelDueAt,deferredUntil,deletedAt,understandingDueAt,memoryModel,keyPoints,parameterSetId) " +
                    "VALUES (11,'Asthma',NULL,NULL,'Topic','The whole topic','Step therapy',NULL,0,'Strong',4.2,31.5,0.91,1000,2000,1000,5000,7000,30.2,4,1,0,9000,NULL,NULL,NULL,'FSRS-6',NULL,0)"
            )
            execSQL(
                "INSERT INTO review_logs (id,studyUnitId,reviewedAt,memoryRating,understandingRating,previousIntervalDays,nextIntervalDays,previousState,nextState,retrievabilityAtReview,elapsedDays,logType,initialDifficulty,reviewDurationMs,wasImportantAtReview,desiredRetentionAtReview,schedulerVersion,schedulerPolicyVersion,understandingFactorAtReview,calibrationScaleAtReview,keyPointsTotal,keyPointsRecalled,parameterSetId,reviewMethods,questionsCorrect,questionsTotal,sessionKind) " +
                    "VALUES (21,11,5000,'Good','Clear',12.0,30.2,'Building','Strong',0.903,12.0,'RECALL',NULL,41000,0,0.9,'FSRS-6','YADORA-8',1.0,0.94,-1,-1,0,'Questions',17,20,'TOPIC')"
            )
            close()
        }
    }

    @Test
    fun `v10 migrates to v11 with every existing review not given and not recorded`() = runBlocking {
        seedV10()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()

        val log = db.reviewLogDao().getLogsForUnit(11).first().single()
        assertEquals("nobody gave an estimate", -1, log.studyMinutes)
        assertEquals("the save time was never kept", -1L, log.loggedAt)
        assertEquals("rating untouched", "Good", log.memoryRating)
        assertEquals("prediction untouched", 0.903, log.retrievabilityAtReview, 1e-9)
        assertEquals("review time untouched", 5000L, log.reviewedAt)
        assertEquals("question score untouched", 17, log.questionsCorrect)
        assertEquals("TOPIC", log.sessionKind)

        val unit = db.studyUnitDao().getUnitById(11)!!
        assertEquals("due date untouched", 7000L, unit.nextReviewAt)
        assertEquals("stability untouched", 31.5, unit.stability, 1e-9)

        // A new row carries the fields.
        db.reviewLogDao().insertLog(log.copy(id = 0, reviewedAt = 6000, studyMinutes = 45, loggedAt = 90_000))
        val stored = db.reviewLogDao().getLogsForUnit(11).first().first { it.reviewedAt == 6000L }
        assertEquals(45, stored.studyMinutes)
        assertEquals(90_000L, stored.loggedAt)
        db.close()
    }
}
