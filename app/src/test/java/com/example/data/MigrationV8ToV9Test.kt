package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.database.AppDatabase
import com.example.data.local.entity.MemoryParameterSetEntity
import com.example.domain.srs.Fsrs6Optimizer
import com.example.domain.srs.Fsrs6Parameters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v8 → v9: the personal memory model.
 *
 * Adds the weight-set table (empty) and parameterSetId to topics and logs, 0 — the published defaults —
 * for every existing row, because that is exactly what computed them. Seeds a REAL v8 database (exact
 * SQL + identity hash from the committed 8.json schema).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MigrationV8ToV9Test {

    private val dbName = "migration-v8-v9-test.db"
    private lateinit var context: Context

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getDatabasePath(dbName).also { if (it.exists()) it.delete() }
    }

    @After fun teardown() {
        context.getDatabasePath(dbName).also { if (it.exists()) it.delete() }
    }

    private fun seedV8() {
        val v8Sql = listOf(
            "CREATE TABLE IF NOT EXISTS `subjects` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `colorHex` TEXT, `createdAt` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `systems` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `colorHex` TEXT, `createdAt` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `study_units` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `subjectId` INTEGER, `systemId` INTEGER, `studyType` TEXT NOT NULL, `recallPrompt` TEXT, `notes` TEXT, `source` TEXT, `highYield` INTEGER NOT NULL, `state` TEXT NOT NULL, `difficulty` REAL NOT NULL, `stability` REAL NOT NULL, `retrievability` REAL NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `studiedAt` INTEGER NOT NULL, `lastReviewedAt` INTEGER, `nextReviewAt` INTEGER NOT NULL, `currentIntervalDays` REAL NOT NULL, `reviewCount` INTEGER NOT NULL, `lapseCount` INTEGER NOT NULL, `archived` INTEGER NOT NULL, `modelDueAt` INTEGER NOT NULL, `deferredUntil` INTEGER, `deletedAt` INTEGER, `understandingDueAt` INTEGER, `memoryModel` TEXT NOT NULL, `keyPoints` TEXT)",
            "CREATE INDEX IF NOT EXISTS `index_study_units_nextReviewAt` ON `study_units` (`nextReviewAt`)",
            "CREATE INDEX IF NOT EXISTS `index_study_units_archived` ON `study_units` (`archived`)",
            "CREATE TABLE IF NOT EXISTS `review_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `studyUnitId` INTEGER NOT NULL, `reviewedAt` INTEGER NOT NULL, `memoryRating` TEXT NOT NULL, `understandingRating` TEXT NOT NULL, `previousIntervalDays` REAL NOT NULL, `nextIntervalDays` REAL NOT NULL, `previousState` TEXT NOT NULL, `nextState` TEXT NOT NULL, `retrievabilityAtReview` REAL NOT NULL, `elapsedDays` REAL NOT NULL, `logType` TEXT NOT NULL, `initialDifficulty` TEXT, `reviewDurationMs` INTEGER NOT NULL, `wasImportantAtReview` INTEGER NOT NULL, `desiredRetentionAtReview` REAL NOT NULL, `schedulerVersion` TEXT NOT NULL, `schedulerPolicyVersion` TEXT NOT NULL, `understandingFactorAtReview` REAL NOT NULL, `calibrationScaleAtReview` REAL NOT NULL, `keyPointsTotal` INTEGER NOT NULL, `keyPointsRecalled` INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_review_logs_studyUnitId` ON `review_logs` (`studyUnitId`)",
            "CREATE INDEX IF NOT EXISTS `index_review_logs_reviewedAt` ON `review_logs` (`reviewedAt`)",
            "CREATE TABLE IF NOT EXISTS `event_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `at` INTEGER NOT NULL, `type` TEXT NOT NULL, `unitId` INTEGER, `detail` TEXT)",
            "CREATE INDEX IF NOT EXISTS `index_event_logs_type` ON `event_logs` (`type`)",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, '029a41aa2c4dcfd287e930691a887fdc')",
        )
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(8) {
                    override fun onCreate(db: SupportSQLiteDatabase) { v8Sql.forEach { db.execSQL(it) } }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        helper.writableDatabase.apply {
            execSQL(
                "INSERT INTO study_units (id,title,subjectId,systemId,studyType,recallPrompt,notes,source,highYield,state,difficulty,stability,retrievability,createdAt,updatedAt,studiedAt,lastReviewedAt,nextReviewAt,currentIntervalDays,reviewCount,lapseCount,archived,modelDueAt,deferredUntil,deletedAt,understandingDueAt,memoryModel,keyPoints) " +
                    "VALUES (11,'Appendicitis',NULL,NULL,'Topic','Diagnostic criteria','RLQ pain',NULL,1,'Strong',2.13,68.93,0.95,1000,2000,1000,5000,7000,110.3,3,0,0,9000,NULL,NULL,7000,'FSRS-6','McBurney''s point\nAlvarado score')"
            )
            execSQL(
                "INSERT INTO review_logs (id,studyUnitId,reviewedAt,memoryRating,understandingRating,previousIntervalDays,nextIntervalDays,previousState,nextState,retrievabilityAtReview,elapsedDays,logType,initialDifficulty,reviewDurationMs,wasImportantAtReview,desiredRetentionAtReview,schedulerVersion,schedulerPolicyVersion,understandingFactorAtReview,calibrationScaleAtReview,keyPointsTotal,keyPointsRecalled) " +
                    "VALUES (21,11,5000,'Hard','Clear',4.5,110.3,'Building','Strong',0.912,5.0,'RECALL',NULL,23054,1,0.93,'FSRS-6','YADORA-6',1.0,0.87,2,1)"
            )
            close()
        }
    }

    @Test
    fun `v8 migrates to v9 putting every existing row on the default weights`() = runBlocking {
        seedV8()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()

        val unit = db.studyUnitDao().getUnitById(11)!!
        assertEquals("computed by the defaults, so it says so", 0L, unit.parameterSetId)
        assertEquals("key points untouched", "McBurney's point\nAlvarado score", unit.keyPoints)
        assertEquals("stability untouched", 68.93, unit.stability, 1e-9)
        assertEquals("due date untouched", 7000L, unit.nextReviewAt)
        assertEquals("model identity untouched", "FSRS-6", unit.memoryModel)

        val log = db.reviewLogDao().getLogsForUnit(11).first().single()
        assertEquals(0L, log.parameterSetId)
        assertEquals("key-point score untouched", 1, log.keyPointsRecalled)
        assertEquals("calibration scale untouched", 0.87, log.calibrationScaleAtReview, 1e-9)

        assertTrue("no personal model exists yet", db.memoryParameterSetDao().getAll().isEmpty())
        val id = db.memoryParameterSetDao().insert(
            MemoryParameterSetEntity(
                createdAt = 8000, status = MemoryParameterSetEntity.ACTIVE,
                weights = Fsrs6Optimizer.encode(Fsrs6Parameters.DEFAULT_WEIGHTS), comparedWithSetId = 0,
                availableReviews = 700, trainReviews = 560, testReviews = 140,
                currentLogLoss = 0.36, candidateLogLoss = 0.34, currentRmseBins = 0.09, candidateRmseBins = 0.06,
                currentAuc = 0.70, candidateAuc = 0.72, zScore = 4.2, activatedAt = 8000,
            )
        )
        assertEquals("a set round-trips", id, db.memoryParameterSetDao().getActive()!!.id)
        db.close()
    }
}
