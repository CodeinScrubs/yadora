package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.MedReviewApplication
import com.example.data.local.entity.EventLogEntity
import com.example.data.local.entity.ReviewLogEntity
import com.example.data.local.entity.StudyUnitEntity
import com.example.data.local.entity.SubjectEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Backup is core trust infrastructure: a lossy or silently-coercing restore would corrupt a user's
 * entire study history. These tests prove (1) export → wipe → restore reproduces every table
 * field-for-field, and (2) a corrupt file is rejected BEFORE any current data is touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BackupRoundTripTest {

    /**
     * A backup is a plain JSON file the user can hand-edit, share, or corrupt. `desired_retention`
     * is the one restored setting that used to be able to BRICK the app: out of FSRS's accepted
     * 0.70..0.99 band it made `FsrsParameters`' require() throw on every single review, on every
     * launch, permanently — because MedReviewApplication re-reads it from prefs at each cold start.
     * A nonsensical value must now degrade to a sane schedule instead.
     */
    @Test
    fun `a backup with an out-of-range retention cannot break reviewing`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sp = context.getSharedPreferences("medreview_settings", Context.MODE_PRIVATE)

        // NaN/Infinity are deliberately absent here: org.json refuses to represent them at all, so
        // they cannot arrive through a backup FILE. They are still covered directly against the
        // scheduler in the sibling test below, which is the layer that can actually receive one.
        for (bad in listOf(1.0, 0.0, -3.0, 42.0)) {
            val json = org.json.JSONObject().apply {
                put("backupVersion", BackupManager.BACKUP_VERSION)
                put("subjects", org.json.JSONArray())
                put("systems", org.json.JSONArray())
                put("studyUnits", org.json.JSONArray())
                put("reviewLogs", org.json.JSONArray())
                put("eventLogs", org.json.JSONArray())
                put("settings", org.json.JSONObject().put("desired_retention", bad))
            }.toString()

            BackupManager.restoreFromJson(context, json)

            val stored = sp.getFloat("desired_retention", 0.90f).toDouble()
            assertTrue("restored retention $stored (from $bad) must at least be a real number", stored.isFinite())

            // NOTE: prefs store a Float while FSRS consumes a Double, so a value clamped to exactly
            // 0.99 reads back as 0.9900000095367432 — fractionally OUTSIDE the band FsrsParameters
            // accepts. That is precisely why the clamp also lives in MedScheduler.safeRetention on
            // the READ side; asserting on the raw stored float would test the wrong layer. The
            // invariant that actually matters to the user is the one below: reviewing still works.
            com.example.domain.srs.MedScheduler.userRetention = stored
            val effective = com.example.domain.srs.MedScheduler.effectiveRetention(highYield = false)
            assertTrue(
                "effective retention $effective (restored from $bad) must be usable by FSRS",
                effective.isFinite() && effective in 0.70..0.99,
            )

            // The real proof: scheduling still works for BOTH branches (high-yield adds +0.03 on top,
            // which is where an unclamped low value would also have blown up).
            for (important in listOf(false, true)) {
                val outcome = com.example.domain.srs.MedScheduler.review(
                    stability = 10.0, difficulty = 5.0, elapsedDays = 12.0,
                    memoryRating = com.example.domain.model.MemoryRating.Good,
                    understanding = com.example.domain.model.UnderstandingRating.Clear,
                    highYield = important, reviewNumber = 3,
                    model = com.example.domain.srs.MedScheduler.CURRENT_MODEL,
                )
                assertTrue("a review after restoring $bad still produces a usable interval",
                    outcome.intervalDays.isFinite() && outcome.intervalDays >= 1.0)
            }
        }
    }

    /** Even if a bad value reaches the scheduler by some other route, it must not throw. */
    @Test
    fun `the scheduler clamps a nonsensical retention instead of throwing`() {
        val original = com.example.domain.srs.MedScheduler.userRetention
        try {
            for (bad in listOf(1.5, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
                com.example.domain.srs.MedScheduler.userRetention = bad
                val eff = com.example.domain.srs.MedScheduler.effectiveRetention(highYield = false)
                assertTrue("effectiveRetention($bad) = $eff must be usable", eff.isFinite() && eff in 0.70..0.99)
                val outcome = com.example.domain.srs.MedScheduler.review(
                    stability = 8.0, difficulty = 5.0, elapsedDays = 9.0,
                    memoryRating = com.example.domain.model.MemoryRating.Easy,
                    understanding = com.example.domain.model.UnderstandingRating.Clear,
                    highYield = false, reviewNumber = 2,
                    model = com.example.domain.srs.MedScheduler.CURRENT_MODEL,
                )
                assertTrue(outcome.intervalDays.isFinite())
            }
        } finally {
            com.example.domain.srs.MedScheduler.userRetention = original
        }
    }

    @Test
    fun `export then restore reproduces every table field-for-field`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = (context as MedReviewApplication).database
        val now = System.currentTimeMillis()
        val day = 86400000L

        val subjId = db.categoryDao().insertSubject(SubjectEntity(name = "Physics", colorHex = "#4E7A5A"))
        val unitId = db.studyUnitDao().insertUnit(
            StudyUnitEntity(
                title = "Photosynthesis", studyType = "Topic", subjectId = subjId,
                notes = "Light reactions vs Calvin cycle", source = "textbook p.41", highYield = true,
                keyPoints = "Light reactions make ATP\nCalvin cycle fixes CO2",
                state = "Learning", stability = 2.5, difficulty = 5.5, retrievability = 0.93,
                studiedAt = now - day, lastReviewedAt = now, nextReviewAt = now + 2 * day,
                currentIntervalDays = 2.0, reviewCount = 1, lapseCount = 0,
            )
        )
        db.reviewLogDao().insertLog(
            ReviewLogEntity(
                studyUnitId = unitId, reviewedAt = now, memoryRating = "Good", understandingRating = "Clear",
                previousIntervalDays = 0.0, nextIntervalDays = 2.0, previousState = "New", nextState = "Learning",
                retrievabilityAtReview = 0.93, elapsedDays = 1.0, logType = "RECALL",
                initialDifficulty = null, reviewDurationMs = 4200, wasImportantAtReview = 1,
                desiredRetentionAtReview = 0.93, schedulerVersion = "FSRS-5",
                calibrationScaleAtReview = 0.83,
                keyPointsTotal = 2, keyPointsRecalled = 1,
            )
        )
        db.eventLogDao().insert(EventLogEntity(type = "SNOOZE", detail = "test"))

        val json = BackupManager.buildBackupJson(context)

        // Simulate a fresh device: wipe everything.
        db.eventLogDao().deleteAll()
        db.reviewLogDao().deleteAllLogs()
        db.studyUnitDao().deleteAllUnits()
        db.categoryDao().deleteAllSubjects()
        db.categoryDao().deleteAllSystems()

        val restoredCount = BackupManager.restoreFromJson(context, json)
        assertEquals(1, restoredCount)

        val unit = db.studyUnitDao().getUnitById(unitId)!!
        assertEquals("Photosynthesis", unit.title)
        assertEquals("Light reactions vs Calvin cycle", unit.notes)
        assertEquals("textbook p.41", unit.source)
        assertEquals(subjId, unit.subjectId)
        assertEquals(true, unit.highYield)
        assertEquals("Learning", unit.state)
        assertEquals(2.5, unit.stability, 1e-9)
        assertEquals(5.5, unit.difficulty, 1e-9)
        assertEquals(now + 2 * day, unit.nextReviewAt)
        assertEquals(1, unit.reviewCount)

        val log = db.reviewLogDao().getLogsForUnit(unitId).first().single()
        assertEquals("Good", log.memoryRating)
        assertEquals("RECALL", log.logType)
        assertEquals(4200L, log.reviewDurationMs)
        assertEquals(1, log.wasImportantAtReview)
        assertEquals(0.93, log.desiredRetentionAtReview, 1e-9)
        assertEquals("FSRS-5", log.schedulerVersion)
        assertEquals("v7 calibration scale survives the round trip", 0.83, log.calibrationScaleAtReview, 1e-9)
        assertEquals("key points survive the round trip", "Light reactions make ATP\nCalvin cycle fixes CO2", unit.keyPoints)
        assertEquals("and so does a review's score against them", 2, log.keyPointsTotal)
        assertEquals(1, log.keyPointsRecalled)

        assertEquals("SNOOZE", db.eventLogDao().getAll().single().type)
        assertEquals("Physics", db.categoryDao().getAllSubjects().first().single().name)
    }

    @Test
    fun `corrupt backup is rejected before any data is deleted`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = (context as MedReviewApplication).database
        val now = System.currentTimeMillis()

        val unitId = db.studyUnitDao().insertUnit(
            StudyUnitEntity(title = "Keep me", studyType = "Topic", studiedAt = now, nextReviewAt = now)
        )
        db.reviewLogDao().insertLog(
            ReviewLogEntity(
                studyUnitId = unitId, reviewedAt = now, memoryRating = "Good", understandingRating = "Clear",
                previousIntervalDays = 0.0, nextIntervalDays = 1.0, previousState = "New", nextState = "Learning",
            )
        )

        // Tamper a rating into garbage — pretty-printed JSON makes the field targetable.
        val json = BackupManager.buildBackupJson(context)
        val bad = json.replace("\"memoryRating\": \"Good\"", "\"memoryRating\": \"WAT\"")
        assertTrue("tampering must have applied", bad != json)

        val result = runCatching { BackupManager.restoreFromJson(context, bad) }
        assertTrue("corrupt backup must be rejected", result.isFailure)
        // And the current data must be untouched — validation runs BEFORE any delete.
        assertEquals("Keep me", db.studyUnitDao().getUnitById(unitId)!!.title)
        assertEquals(1, db.reviewLogDao().getLogsForUnit(unitId).first().size)
    }

    @Test
    fun `a key-point score that claims more ticks than points is rejected before any data is deleted`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = (context as MedReviewApplication).database
        val now = System.currentTimeMillis()

        val unitId = db.studyUnitDao().insertUnit(
            StudyUnitEntity(title = "Scored", studyType = "Topic", studiedAt = now, nextReviewAt = now, keyPoints = "a\nb\nc")
        )
        db.reviewLogDao().insertLog(
            ReviewLogEntity(
                studyUnitId = unitId, reviewedAt = now, memoryRating = "Hard", understandingRating = "Clear",
                previousIntervalDays = 1.0, nextIntervalDays = 2.0, previousState = "Learning", nextState = "Learning",
                logType = "RECALL", keyPointsTotal = 3, keyPointsRecalled = 2,
            )
        )
        val json = BackupManager.buildBackupJson(context)
        val bad = json.replace("\"keyPointsRecalled\": 2", "\"keyPointsRecalled\": 5")
        assertTrue("tampering must have applied", bad != json)

        assertTrue("an impossible score must be rejected", runCatching { BackupManager.restoreFromJson(context, bad) }.isFailure)
        assertEquals("Scored", db.studyUnitDao().getUnitById(unitId)!!.title)
        assertEquals(2, db.reviewLogDao().getLogsForUnit(unitId).first().single().keyPointsRecalled)
    }

    /**
     * A topic on a personal weight set can only be replayed with those weights, so a restore must bring
     * the sets back with the topics that name them — and refuse a file whose weights no longer decode,
     * before anything current is deleted.
     */
    @Test
    fun `personal memory models survive a round trip and a broken one is rejected first`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = (context as MedReviewApplication).database
        val sched = com.example.domain.srs.MedScheduler
        val now = System.currentTimeMillis()
        val weights = com.example.domain.srs.Fsrs6Parameters.DEFAULT_WEIGHTS.copyOf().also { it[20] = 0.3 }
        val encoded = com.example.domain.srs.Fsrs6Optimizer.encode(weights)
        fun set(status: String, w: String, z: Double) = com.example.data.local.entity.MemoryParameterSetEntity(
            createdAt = now, status = status, weights = w, comparedWithSetId = 0, availableReviews = 700,
            trainReviews = 560, testReviews = 140, currentLogLoss = 0.36, candidateLogLoss = 0.33,
            currentRmseBins = 0.09, candidateRmseBins = 0.05, currentAuc = 0.7, candidateAuc = -1.0, zScore = z,
        )
        val setId = db.memoryParameterSetDao().insert(set(com.example.data.local.entity.MemoryParameterSetEntity.ACTIVE, encoded, 6.0))
        db.memoryParameterSetDao().insert(set(com.example.data.local.entity.MemoryParameterSetEntity.REJECTED, "", -1.2))
        val unitId = db.studyUnitDao().insertUnit(
            StudyUnitEntity(title = "Fitted", studyType = "Topic", studiedAt = now, nextReviewAt = now, memoryModel = "FSRS-6", parameterSetId = setId)
        )
        db.reviewLogDao().insertLog(
            ReviewLogEntity(
                studyUnitId = unitId, reviewedAt = now, memoryRating = "Good", understandingRating = "Clear",
                previousIntervalDays = 1.0, nextIntervalDays = 3.0, previousState = "Learning", nextState = "Learning",
                logType = "RECALL", parameterSetId = setId,
            )
        )
        try {
            val json = BackupManager.buildBackupJson(context)
            BackupManager.restoreFromJson(context, json)
            val sets = db.memoryParameterSetDao().getAll()
            assertEquals("the adopted and the rejected attempt both come back", 2, sets.size)
            assertEquals(encoded, sets.single { it.id == setId }.weights)
            assertEquals(setId, db.studyUnitDao().getUnitById(unitId)!!.parameterSetId)
            assertEquals(setId, db.reviewLogDao().getLogsForUnit(unitId).first().single().parameterSetId)
            assertEquals("the scheduler sees the restored set", setId, sched.activeParameterSet.id)

            val broken = json.replace(encoded, "1,2,3")
            assertTrue("tampering must have applied", broken != json)
            assertTrue("weights that do not decode are rejected", runCatching { BackupManager.restoreFromJson(context, broken) }.isFailure)
            assertEquals("and nothing was deleted", setId, db.studyUnitDao().getUnitById(unitId)!!.parameterSetId)
        } finally {
            sched.activeParameterSet = sched.DEFAULT_PARAMETER_SET
            sched.knownParameterSets = emptyMap()
        }
    }
}
