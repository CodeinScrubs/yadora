package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.MedReviewApplication
import com.example.data.local.entity.ReviewLogEntity
import com.example.data.local.entity.StudyUnitEntity
import com.example.notifications.NotificationScheduler
import com.example.ui.today.DayBounds
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * The pilot's view of the daily load and of updates (export v15, 2026-10-03). Study is irregular, and whether a year of
 * it keeps up is the first thing an analysis must see; the review logs cannot rebuild it, because the notification's
 * "Not today" defers every due topic without naming them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DailySnapshotTest {

    private val app get() = ApplicationProvider.getApplicationContext<MedReviewApplication>()
    private val day = 86_400_000L

    private fun unit(title: String, reviews: Int, dueAt: Long, deferred: Long? = null, archived: Boolean = false) =
        StudyUnitEntity(
            title = title, studyType = "Topic", studiedAt = dueAt - 30 * day, nextReviewAt = dueAt, modelDueAt = dueAt,
            reviewCount = reviews, state = if (reviews == 0) "New" else "Building", deferredUntil = deferred, archived = archived,
        )

    private fun log(unitId: Long, at: Long, type: String) = ReviewLogEntity(
        studyUnitId = unitId, reviewedAt = at, memoryRating = "Good", understandingRating = "Clear",
        previousIntervalDays = 3.0, nextIntervalDays = 7.0, previousState = "Building", nextState = "Building", logType = type,
    )

    private fun counts(detail: String?): Map<String, Int> =
        detail.orEmpty().split(" ").associate { it.substringBefore("=") to it.substringAfter("=").toInt() }

    private suspend fun snapshots() = app.database.eventLogDao().getAll().filter { it.type == DailySnapshot.EVENT }

    @Test
    fun `the day's load is the plan Today counts, written once a day`() = runBlocking {
        val db = app.database
        val now = LocalDate.of(2026, 10, 3).atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val start = DayBounds.startOf(now)
        // 12 reviews overdue, the oldest due 9 days ago at 10:00; 3 more due later today; 2 first ratings, one carried over
        // from yesterday; a review not due until next week; one deferred to tomorrow; an archived and a deleted topic.
        (1..12).forEach { db.studyUnitDao().insertUnit(unit("late $it", 3, start - (it % 9 + 1) * day + 10 * 3_600_000L)) }
        (1..3).forEach { db.studyUnitDao().insertUnit(unit("today $it", 2, start + 15 * 3_600_000L)) }
        db.studyUnitDao().insertUnit(unit("new today", 0, start + 8 * 3_600_000L))
        db.studyUnitDao().insertUnit(unit("new yesterday", 0, start - 14 * 3_600_000L))
        db.studyUnitDao().insertUnit(unit("next week", 4, start + 7 * day))
        val tomorrow = start + day + 8 * 3_600_000L
        db.studyUnitDao().insertUnit(unit("not today", 3, tomorrow, deferred = tomorrow))
        db.studyUnitDao().insertUnit(unit("archived", 3, start - 3 * day, archived = true))
        val deleted = db.studyUnitDao().insertUnit(unit("deleted", 3, start - 3 * day))
        app.repository.softDeleteUnit(deleted)
        // One review already done this morning; a first study does not count against the limit.
        db.reviewLogDao().insertLog(log(1, start + 8 * 3_600_000L, "RECALL"))
        db.reviewLogDao().insertLog(log(13, start + 8 * 3_600_000L, "FIRST_STUDY"))
        app.getSharedPreferences("medreview_settings", Context.MODE_PRIVATE).edit().putFloat("daily_review_limit", 10f).commit()

        DailySnapshot.recordOnce(app, now)
        DailySnapshot.recordOnce(app, now + 3_600_000L) // the same day: nothing more
        val snap = snapshots().single()
        assertEquals(now, snap.at)
        val c = counts(snap.detail)
        assertEquals("active (not archived, not deleted)", 19, c["active"])
        assertEquals("rated", 17, c["rated"])
        assertEquals("due by the end of today, first ratings included", 17, c["due"])
        assertEquals("overdue: due before today", 13, c["overdue"])
        assertEquals(9, c["oldest_overdue_days"])
        assertEquals(2, c["first"])
        assertEquals("both first ratings and the 9 reviews the limit leaves", 11, c["offered"])
        assertEquals("held for a later day", 6, c["held"])
        assertEquals(1, c["done"])
        assertEquals(1, c["deferred"])
        assertEquals(10, c["limit"])
        // The plan the reminders, the widget and Today count from: the snapshot must never disagree with it.
        val plan = app.todayPlan(now)
        assertEquals(plan.size, c["offered"])
        assertEquals(plan.heldBack, c["held"])

        // The next day gets its own, and the record of the day lives in the device-only prefs, which a restore never
        // carries to another phone.
        DailySnapshot.recordOnce(app, now + day)
        assertEquals(2, snapshots().size)
        val epochDay = LocalDate.of(2026, 10, 4).toEpochDay()
        assertEquals(epochDay, NotificationScheduler.transientPrefs(app).getLong(DailySnapshot.PREF_DAY, 0L))
    }

    @Test
    fun `each build's first run is recorded once, with the build before it`() = runBlocking {
        AppVersionLog.recordIfChanged(app, 4, "1.1")
        AppVersionLog.recordIfChanged(app, 4, "1.1")
        AppVersionLog.recordIfChanged(app, 5, "1.2")
        AppVersionLog.recordIfChanged(app, 5, "1.2")
        val details = app.database.eventLogDao().getAll().filter { it.type == AppVersionLog.EVENT }.sortedBy { it.id }.map { it.detail }
        assertEquals(listOf("code=4 name=1.1 previous=0", "code=5 name=1.2 previous=4"), details)
    }

    /**
     * A restore replaces the event log, so both are recorded again afterwards: the restored library's load the same day,
     * and the build on this phone. Their marks are device-local, and kept they silenced both on a phone restored onto.
     */
    @Test
    fun `after a restore the day's load and the build are recorded again`() = runBlocking {
        val db = app.database
        val now = System.currentTimeMillis()
        // Never rated: a backup whose topics claim reviews must carry their history, or the restore refuses it.
        db.studyUnitDao().insertUnit(unit("a topic", 0, now - 2 * day))
        val backup = BackupManager.buildBackupJson(app) // a file from before anything was recorded
        DailySnapshot.recordOnce(app, now)
        AppVersionLog.recordIfChanged(app, 4, "1.1")
        assertEquals(1, snapshots().size)
        BackupManager.restoreFromJson(app, backup)
        assertEquals("the file's own events replace this phone's", 0, snapshots().size)
        DailySnapshot.recordOnce(app, now + 60_000L)
        AppVersionLog.recordIfChanged(app, 4, "1.1")
        assertEquals("the restored library's load, the same day", 1, snapshots().size)
        assertEquals(listOf("code=4 name=1.1 previous=0"),
            db.eventLogDao().getAll().filter { it.type == AppVersionLog.EVENT }.map { it.detail })
    }

    @Test
    fun `the research export carries both and explains them`() = runBlocking {
        app.database.studyUnitDao().insertUnit(unit("a topic", 2, System.currentTimeMillis() - 2 * day))
        DailySnapshot.recordOnce(app)
        AppVersionLog.recordIfChanged(app)
        val json = JSONObject(AnalyticsExporter.buildJson(app))
        assertEquals(15, json.getInt("exportVersion"))
        val types = json.getJSONArray("eventLogs").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("type") } }
        assertTrue("$types", DailySnapshot.EVENT in types && AppVersionLog.EVENT in types)
        val guide = json.getJSONObject("fieldGuide")
        assertTrue(guide.getString("dailySnapshot").contains("held"))
        assertTrue(guide.getString("eventLogs").contains("APP_VERSION"))
    }
}
