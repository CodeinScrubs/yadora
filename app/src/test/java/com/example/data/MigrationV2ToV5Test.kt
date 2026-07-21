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
 * Full-chain migration test for the OLDEST user still upgradable through the exported schemas: a v2
 * install climbing 2 → 3 → 4 → 5 in one open. This is the migration path most likely to break
 * silently, because it stacks every ALTER (logType, the v4 diagnostic columns + indices, the v5
 * honest-scheduling columns) on top of a row that predates all of them.
 *
 * It seeds a REAL v2 database (exact SQL + identity hash from the committed 2.json schema) and proves:
 *   - Room accepts the migrated schema (else .build()+query throws),
 *   - the v2 row and its bare v2 log survive,
 *   - every added column lands with its documented default (logType='UNKNOWN', schedulerVersion='',
 *     modelDueAt backfilled from nextReviewAt, deferredUntil/deletedAt null, v5 policy defaults).
 * Runs on the JVM under Robolectric — no device needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MigrationV2ToV5Test {

    private val dbName = "migration-v2-v5-test.db"
    private lateinit var context: Context

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getDatabasePath(dbName).also { if (it.exists()) it.delete() }
    }

    @After fun teardown() {
        context.getDatabasePath(dbName).also { if (it.exists()) it.delete() }
    }

    /** Build a v2-shaped database exactly as Room v2 would have left it: no indices, review_logs has
     *  neither logType (added v3) nor the v4 diagnostic columns nor the v5 policy columns. */
    private fun seedV2() {
        val v2Sql = listOf(
            "CREATE TABLE IF NOT EXISTS `subjects` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `colorHex` TEXT, `createdAt` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `systems` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `colorHex` TEXT, `createdAt` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `study_units` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `subjectId` INTEGER, `systemId` INTEGER, `studyType` TEXT NOT NULL, `recallPrompt` TEXT, `notes` TEXT, `source` TEXT, `highYield` INTEGER NOT NULL, `state` TEXT NOT NULL, `difficulty` REAL NOT NULL, `stability` REAL NOT NULL, `retrievability` REAL NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `studiedAt` INTEGER NOT NULL, `lastReviewedAt` INTEGER, `nextReviewAt` INTEGER NOT NULL, `currentIntervalDays` REAL NOT NULL, `reviewCount` INTEGER NOT NULL, `lapseCount` INTEGER NOT NULL, `archived` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `review_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `studyUnitId` INTEGER NOT NULL, `reviewedAt` INTEGER NOT NULL, `memoryRating` TEXT NOT NULL, `understandingRating` TEXT NOT NULL, `previousIntervalDays` REAL NOT NULL, `nextIntervalDays` REAL NOT NULL, `previousState` TEXT NOT NULL, `nextState` TEXT NOT NULL, `retrievabilityAtReview` REAL NOT NULL, `elapsedDays` REAL NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `event_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `at` INTEGER NOT NULL, `type` TEXT NOT NULL, `unitId` INTEGER, `detail` TEXT)",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, 'cc66ba43323b3cf3e4b730a6b0d0d4a8')",
        )
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) { v2Sql.forEach { db.execSQL(it) } }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        helper.writableDatabase.apply {
            execSQL(
                "INSERT INTO study_units (id,title,subjectId,systemId,studyType,recallPrompt,notes,source,highYield,state,difficulty,stability,retrievability,createdAt,updatedAt,studiedAt,lastReviewedAt,nextReviewAt,currentIntervalDays,reviewCount,lapseCount,archived) " +
                    "VALUES (3,'Krebs cycle',NULL,NULL,'Topic',NULL,'acetyl-CoA',NULL,1,'Building',5.5,8.0,0.9,100,200,100,180,4200,6.0,2,0,0)"
            )
            // A bare v2 log: only the columns that existed at v2 — logType/schedulerVersion/etc. don't exist yet.
            execSQL(
                "INSERT INTO review_logs (id,studyUnitId,reviewedAt,memoryRating,understandingRating,previousIntervalDays,nextIntervalDays,previousState,nextState,retrievabilityAtReview,elapsedDays) " +
                    "VALUES (5,3,180,'Good','Clear',3.0,6.0,'Learning','Building',0.9,3.0)"
            )
            close()
        }
    }

    @Test
    fun `v2 database migrates all the way to v5 preserving data and defaulting new columns`() = runBlocking {
        seedV2()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(
                AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5,
            )
            .build()

        val unit = db.studyUnitDao().getUnitById(3)!!
        assertEquals("row survived the full 2->5 chain", "Krebs cycle", unit.title)
        assertEquals("scientific state preserved", 8.0, unit.stability, 1e-9)
        assertEquals("reviewCount preserved", 2, unit.reviewCount)
        assertEquals("modelDueAt backfilled from nextReviewAt", 4200L, unit.modelDueAt)
        assertNull("no deferral introduced by migration", unit.deferredUntil)
        assertNull("not deleted", unit.deletedAt)

        val logs = db.reviewLogDao().getLogsForUnit(3).first()
        assertEquals("log survived", 1, logs.size)
        assertEquals("log memory rating preserved", "Good", logs[0].memoryRating)
        // Columns added after v2, each with the default its migration declared.
        assertEquals("logType defaults UNKNOWN (added v3)", "UNKNOWN", logs[0].logType)
        assertEquals("schedulerVersion defaults empty (added v4)", "", logs[0].schedulerVersion)
        assertEquals("reviewDurationMs defaults -1 (added v4)", -1L, logs[0].reviewDurationMs)
        assertEquals("policy version defaults empty (added v5)", "", logs[0].schedulerPolicyVersion)
        assertEquals("understanding factor defaults -1 (added v5)", -1.0, logs[0].understandingFactorAtReview, 1e-9)

        db.close()
    }
}
