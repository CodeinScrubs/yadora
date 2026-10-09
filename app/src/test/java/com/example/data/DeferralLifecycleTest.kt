package com.example.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.database.AppDatabase
import com.example.data.local.entity.StudyUnitEntity
import com.example.data.repository.MedReviewRepository
import com.example.domain.model.MemoryRating
import com.example.domain.model.SessionKind
import com.example.domain.model.UnderstandingRating
import com.example.domain.srs.MedScheduler
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A stale review screen's "Not today" must not change material removed from the active queue. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeferralLifecycleTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: MedReviewRepository
    private val now = 1_790_000_000_000L
    private val day = 86_400_000L

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = MedReviewRepository(db.studyUnitDao(), db.categoryDao(), db.reviewLogDao(), db)
        MedScheduler.activeParameterSet = MedScheduler.DEFAULT_PARAMETER_SET
        MedScheduler.knownParameterSets = emptyMap()
        MedScheduler.calibrationScale = 1.0
    }

    @After fun teardown() {
        db.close()
        MedScheduler.activeParameterSet = MedScheduler.DEFAULT_PARAMETER_SET
        MedScheduler.knownParameterSets = emptyMap()
        MedScheduler.calibrationScale = 1.0
    }

    private suspend fun topic(rated: Boolean = true): Long {
        val id = repo.insertUnit(StudyUnitEntity(
            title = "Asthma", studyType = "Topic", studiedAt = now - 10 * day,
            nextReviewAt = now - 10 * day, modelDueAt = now - 10 * day,
        ))
        if (rated) repo.rateUnit(id, now - 10 * day, MemoryRating.Good, UnderstandingRating.Clear,
            sessionKind = SessionKind.TOPIC, reviewDurationMs = -1)!!
        return id
    }

    private suspend fun assertNoEffect(id: Long) {
        val row = repo.getUnitById(id)
        val logs = db.reviewLogDao().getAllLogsOnce()
        val events = db.eventLogDao().getAll()
        repo.procrastinateUnit(id, now + day)
        assertEquals("every field stays unchanged", row, repo.getUnitById(id))
        assertEquals("history stays unchanged", logs, db.reviewLogDao().getAllLogsOnce())
        assertEquals("no invented adherence event", events, db.eventLogDao().getAll())
    }

    @Test fun `archived topic ignores a stale deferral`() = runBlocking {
        val id = topic(); repo.archiveUnit(id); assertNoEffect(id)
    }

    @Test fun `deleted topic ignores a stale deferral`() = runBlocking {
        val id = topic(); repo.softDeleteUnit(id); assertNoEffect(id)
    }

    @Test fun `merged away topic ignores a stale deferral`() = runBlocking {
        val kept = topic(); val absorbed = topic()
        repo.mergeUnits(kept, listOf(absorbed))!!
        assertNoEffect(absorbed)
    }

    @Test fun `unrated topic remains due for its first check in`() = runBlocking {
        assertNoEffect(topic(rated = false))
    }

    @Test fun `active deferral preserves both scientific clocks and is idempotent`() = runBlocking {
        val id = topic()
        val before = repo.getUnitById(id)!!
        repo.procrastinateUnit(id, now + day)
        val after = repo.getUnitById(id)!!
        assertEquals(now + day, after.nextReviewAt)
        assertEquals(now + day, after.deferredUntil)
        assertEquals(before.modelDueAt, after.modelDueAt)
        assertEquals(before.understandingDueAt, after.understandingDueAt)
        assertEquals(before.reviewCount, after.reviewCount)
        assertNoEffect(id)
        assertEquals(1, db.eventLogDao().getAll().count { it.type == "PROCRASTINATE" })
    }

    @Test fun `early review screen cannot pull a later topic forward`() = runBlocking {
        val id = topic()
        repo.procrastinateUnit(id, now + 20 * day)
        assertNoEffect(id)
    }

    @Test fun `purged topic ignores a stale deferral`() = runBlocking {
        val id = topic(); repo.softDeleteUnit(id)
        repo.purgeExpiredDeleted(graceMillis = -day)
        assertNull(repo.getUnitById(id)); assertNoEffect(id)
    }
}
