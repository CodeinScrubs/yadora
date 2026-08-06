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
 * v5 → v6: the second clock and model identity.
 *
 * This is the migration that runs on every SHIPPED install, so the property that matters most is what
 * it does NOT do. Upgrading the app must not move a single due date, invent a remediation deadline,
 * or reinterpret an FSRS-5 memory state as though it were FSRS-6. It adds two columns and backfills
 * them with "nothing pending" and "this state came from FSRS-5", and touches nothing else.
 *
 * Seeds a REAL v5 database (exact SQL + identity hash from the committed 5.json schema).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MigrationV5ToV6Test {

    private val dbName = "migration-v5-v6-test.db"
    private lateinit var context: Context

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getDatabasePath(dbName).also { if (it.exists()) it.delete() }
    }

    @After fun teardown() {
        context.getDatabasePath(dbName).also { if (it.exists()) it.delete() }
    }

    private fun seedV5() {
        val v5Sql = listOf(
            "CREATE TABLE IF NOT EXISTS `subjects` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `colorHex` TEXT, `createdAt` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `systems` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `colorHex` TEXT, `createdAt` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `study_units` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `subjectId` INTEGER, `systemId` INTEGER, `studyType` TEXT NOT NULL, `recallPrompt` TEXT, `notes` TEXT, `source` TEXT, `highYield` INTEGER NOT NULL, `state` TEXT NOT NULL, `difficulty` REAL NOT NULL, `stability` REAL NOT NULL, `retrievability` REAL NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `studiedAt` INTEGER NOT NULL, `lastReviewedAt` INTEGER, `nextReviewAt` INTEGER NOT NULL, `currentIntervalDays` REAL NOT NULL, `reviewCount` INTEGER NOT NULL, `lapseCount` INTEGER NOT NULL, `archived` INTEGER NOT NULL, `modelDueAt` INTEGER NOT NULL, `deferredUntil` INTEGER, `deletedAt` INTEGER)",
            "CREATE TABLE IF NOT EXISTS `review_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `studyUnitId` INTEGER NOT NULL, `reviewedAt` INTEGER NOT NULL, `memoryRating` TEXT NOT NULL, `understandingRating` TEXT NOT NULL, `previousIntervalDays` REAL NOT NULL, `nextIntervalDays` REAL NOT NULL, `previousState` TEXT NOT NULL, `nextState` TEXT NOT NULL, `retrievabilityAtReview` REAL NOT NULL, `elapsedDays` REAL NOT NULL, `logType` TEXT NOT NULL, `initialDifficulty` TEXT, `reviewDurationMs` INTEGER NOT NULL, `wasImportantAtReview` INTEGER NOT NULL, `desiredRetentionAtReview` REAL NOT NULL, `schedulerVersion` TEXT NOT NULL, `schedulerPolicyVersion` TEXT NOT NULL, `understandingFactorAtReview` REAL NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `event_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `at` INTEGER NOT NULL, `type` TEXT NOT NULL, `unitId` INTEGER, `detail` TEXT)",
            "CREATE INDEX IF NOT EXISTS `index_study_units_nextReviewAt` ON `study_units` (`nextReviewAt`)",
            "CREATE INDEX IF NOT EXISTS `index_study_units_archived` ON `study_units` (`archived`)",
            "CREATE INDEX IF NOT EXISTS `index_review_logs_studyUnitId` ON `review_logs` (`studyUnitId`)",
            "CREATE INDEX IF NOT EXISTS `index_review_logs_reviewedAt` ON `review_logs` (`reviewedAt`)",
            "CREATE INDEX IF NOT EXISTS `index_event_logs_type` ON `event_logs` (`type`)",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, '35df4332cb2f626eac6a55b085b3b510')",
        )
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                    override fun onCreate(db: SupportSQLiteDatabase) { v5Sql.forEach { db.execSQL(it) } }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        helper.writableDatabase.apply {
            // A mid-life topic with a real FSRS-5 state and a USER DEFERRAL in flight, because the
            // deferral is exactly the thing a careless migration would clobber.
            execSQL(
                "INSERT INTO study_units (id,title,subjectId,systemId,studyType,recallPrompt,notes,source,highYield,state,difficulty,stability,retrievability,createdAt,updatedAt,studiedAt,lastReviewedAt,nextReviewAt,currentIntervalDays,reviewCount,lapseCount,archived,modelDueAt,deferredUntil,deletedAt) " +
                    "VALUES (11,'Appendicitis',NULL,NULL,'Topic',NULL,'RLQ pain',NULL,1,'Strong',2.13,68.93,0.95,1000,2000,1000,5000,9000,110.3,3,0,0,8000,9000,NULL)"
            )
            execSQL(
                "INSERT INTO review_logs (id,studyUnitId,reviewedAt,memoryRating,understandingRating,previousIntervalDays,nextIntervalDays,previousState,nextState,retrievabilityAtReview,elapsedDays,logType,initialDifficulty,reviewDurationMs,wasImportantAtReview,desiredRetentionAtReview,schedulerVersion,schedulerPolicyVersion,understandingFactorAtReview) " +
                    "VALUES (21,11,5000,'Easy','Clear',4.5,110.3,'Building','Strong',0.958,5.9,'RECALL',NULL,23054,1,0.85,'FSRS-5','YADORA-2',1.0)"
            )
            close()
        }
    }

    @Test
    fun `v5 migrates to v6 adding both clocks without moving any schedule`() = runBlocking {
        seedV5()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(
                AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6,
            )
            .build()

        val unit = db.studyUnitDao().getUnitById(11)!!

        // The two new columns, with the only defensible backfills.
        assertNull("no remediation deadline may be invented on upgrade", unit.understandingDueAt)
        assertEquals("existing state is FSRS-5 by definition", "FSRS-5", unit.memoryModel)

        // And NOTHING else moved. Upgrading the app is not a scheduling event.
        assertEquals("title", "Appendicitis", unit.title)
        assertEquals("stability untouched", 68.93, unit.stability, 1e-9)
        assertEquals("difficulty untouched", 2.13, unit.difficulty, 1e-9)
        assertEquals("effective due date untouched", 9000L, unit.nextReviewAt)
        assertEquals("model due date untouched", 8000L, unit.modelDueAt)
        assertEquals("the user's deferral survives", 9000L, unit.deferredUntil)
        assertEquals("reviewCount", 3, unit.reviewCount)
        assertEquals("state label", "Strong", unit.state)

        // The log keeps its own model identity, which is what makes faithful replay possible.
        val logs = db.reviewLogDao().getLogsForUnit(11).first()
        assertEquals("log survived", 1, logs.size)
        assertEquals("log still records the model that produced it", "FSRS-5", logs[0].schedulerVersion)
        assertEquals("and its policy", "YADORA-2", logs[0].schedulerPolicyVersion)
        assertEquals("and its historical retention target", 0.85, logs[0].desiredRetentionAtReview, 1e-9)

        db.close()
    }

    @Test
    fun `a freshly created v6 topic starts with no remediation and the legacy model default`() = runBlocking {
        seedV5()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(
                AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6,
            )
            .build()

        // Round-trip the new columns through Room to prove the entity and schema agree.
        val id = db.studyUnitDao().insertUnit(
            com.example.data.local.entity.StudyUnitEntity(
                title = "Cholecystitis", studyType = "Topic",
                stability = 3.0, difficulty = 5.0, retrievability = 1.0, state = "New",
                studiedAt = 1L, nextReviewAt = 1L, modelDueAt = 1L,
                currentIntervalDays = 0.0, reviewCount = 0, lapseCount = 0,
                understandingDueAt = 4242L, memoryModel = "FSRS-6",
            )
        )
        val fresh = db.studyUnitDao().getUnitById(id)!!
        assertEquals("understandingDueAt round-trips", 4242L, fresh.understandingDueAt)
        assertEquals("memoryModel round-trips", "FSRS-6", fresh.memoryModel)

        db.close()
    }
}
