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
 * Full-chain migration test for a v3 install climbing 3 → 4 → 5 in one open. v3 already has logType
 * (added v3) but none of the v4 diagnostic columns/indices nor the v5 honest-scheduling columns; this
 * proves that stack lands correctly on a row whose logType was a real value, not the migration default.
 *
 * Seeds a REAL v3 database (exact SQL + identity hash from the committed 3.json schema) and proves the
 * row + log survive and every later column defaults as documented (v4 diagnostics, v5 policy),
 * while the pre-existing logType value is preserved untouched.
 * Runs on the JVM under Robolectric — no device needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MigrationV3ToV5Test {

    private val dbName = "migration-v3-v5-test.db"
    private lateinit var context: Context

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getDatabasePath(dbName).also { if (it.exists()) it.delete() }
    }

    @After fun teardown() {
        context.getDatabasePath(dbName).also { if (it.exists()) it.delete() }
    }

    /** Build a v3-shaped database exactly as Room v3 would have left it: no indices, review_logs has
     *  logType but none of the v4/v5 columns. */
    private fun seedV3() {
        val v3Sql = listOf(
            "CREATE TABLE IF NOT EXISTS `subjects` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `colorHex` TEXT, `createdAt` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `systems` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `colorHex` TEXT, `createdAt` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `study_units` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `subjectId` INTEGER, `systemId` INTEGER, `studyType` TEXT NOT NULL, `recallPrompt` TEXT, `notes` TEXT, `source` TEXT, `highYield` INTEGER NOT NULL, `state` TEXT NOT NULL, `difficulty` REAL NOT NULL, `stability` REAL NOT NULL, `retrievability` REAL NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `studiedAt` INTEGER NOT NULL, `lastReviewedAt` INTEGER, `nextReviewAt` INTEGER NOT NULL, `currentIntervalDays` REAL NOT NULL, `reviewCount` INTEGER NOT NULL, `lapseCount` INTEGER NOT NULL, `archived` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `review_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `studyUnitId` INTEGER NOT NULL, `reviewedAt` INTEGER NOT NULL, `memoryRating` TEXT NOT NULL, `understandingRating` TEXT NOT NULL, `previousIntervalDays` REAL NOT NULL, `nextIntervalDays` REAL NOT NULL, `previousState` TEXT NOT NULL, `nextState` TEXT NOT NULL, `retrievabilityAtReview` REAL NOT NULL, `elapsedDays` REAL NOT NULL, `logType` TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `event_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `at` INTEGER NOT NULL, `type` TEXT NOT NULL, `unitId` INTEGER, `detail` TEXT)",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, 'bd6124655fdbaa82499924497749d4dd')",
        )
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) { v3Sql.forEach { db.execSQL(it) } }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        helper.writableDatabase.apply {
            execSQL(
                "INSERT INTO study_units (id,title,subjectId,systemId,studyType,recallPrompt,notes,source,highYield,state,difficulty,stability,retrievability,createdAt,updatedAt,studiedAt,lastReviewedAt,nextReviewAt,currentIntervalDays,reviewCount,lapseCount,archived) " +
                    "VALUES (4,'Action potential',NULL,NULL,'Topic',NULL,'depolarization',NULL,0,'Building',6.0,10.0,0.88,300,400,300,380,4800,7.0,4,1,0)"
            )
            // A v3 log: has a REAL logType ('FIRST_STUDY') that must be preserved, not overwritten by any default.
            execSQL(
                "INSERT INTO review_logs (id,studyUnitId,reviewedAt,memoryRating,understandingRating,previousIntervalDays,nextIntervalDays,previousState,nextState,retrievabilityAtReview,elapsedDays,logType) " +
                    "VALUES (9,4,380,'Hard','Fuzzy',5.0,7.0,'Building','Building',0.85,5.0,'FIRST_STUDY')"
            )
            close()
        }
    }

    @Test
    fun `v3 database migrates to v5 preserving data and its real logType`() = runBlocking {
        seedV3()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()

        val unit = db.studyUnitDao().getUnitById(4)!!
        assertEquals("row survived the 3->5 chain", "Action potential", unit.title)
        assertEquals("scientific state preserved", 10.0, unit.stability, 1e-9)
        assertEquals("reviewCount preserved", 4, unit.reviewCount)
        assertEquals("modelDueAt backfilled from nextReviewAt", 4800L, unit.modelDueAt)
        assertNull("no deferral introduced by migration", unit.deferredUntil)
        assertNull("not deleted", unit.deletedAt)

        val logs = db.reviewLogDao().getLogsForUnit(4).first()
        assertEquals("log survived", 1, logs.size)
        assertEquals("log memory rating preserved", "Hard", logs[0].memoryRating)
        assertEquals("pre-existing logType preserved, not defaulted", "FIRST_STUDY", logs[0].logType)
        // Columns added after v3, each with the default its migration declared.
        assertEquals("schedulerVersion defaults empty (added v4)", "", logs[0].schedulerVersion)
        assertEquals("reviewDurationMs defaults -1 (added v4)", -1L, logs[0].reviewDurationMs)
        assertEquals("policy version defaults empty (added v5)", "", logs[0].schedulerPolicyVersion)
        assertEquals("understanding factor defaults -1 (added v5)", -1.0, logs[0].understandingFactorAtReview, 1e-9)

        db.close()
    }
}
