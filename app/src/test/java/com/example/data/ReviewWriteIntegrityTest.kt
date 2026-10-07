package com.example.data

import android.content.Context
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
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real transactions: stale screens must never roll another action's history or schedule back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReviewWriteIntegrityTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: MedReviewRepository
    private val day = 86_400_000L
    private val origin = 1_790_000_000_000L

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = MedReviewRepository(db.studyUnitDao(), db.categoryDao(), db.reviewLogDao(), db)
        MedScheduler.activeParameterSet = MedScheduler.DEFAULT_PARAMETER_SET
        MedScheduler.knownParameterSets = emptyMap()
        MedScheduler.calibrationScale = 1.0
    }

    @After fun teardown() { db.close() }

    private suspend fun topic() = repo.insertUnit(StudyUnitEntity(
        title = "Asthma", studyType = "Topic", studiedAt = origin, createdAt = origin, updatedAt = origin,
        nextReviewAt = origin, modelDueAt = origin, memoryModel = "FSRS-6",
    ))

    private suspend fun rate(id: Long, at: Long = origin, grade: MemoryRating = MemoryRating.Good) = repo.rateUnit(
        id, at, grade, UnderstandingRating.Clear, sessionKind = SessionKind.TOPIC, reviewDurationMs = 1000,
    )!!

    private suspend fun snapshot() = Triple(
        (db.studyUnitDao().getAllActiveOnce() + db.studyUnitDao().getArchivedOnce() +
            db.studyUnitDao().getRecentlyDeletedOnce()).sortedBy { it.id },
        db.reviewLogDao().getAllLogsOnce(), db.eventLogDao().getAll(),
    )

    @Test fun `undo from an older screen preserves a subsequent review even after clock rollback`() = runBlocking {
        val id = topic()
        val first = rate(id)
        rate(id, origin - day, MemoryRating.Hard)
        val before = snapshot()
        repo.undoReview(first)
        assertEquals("the later committed review and both logs must survive", before, snapshot())
    }

    @Test fun `undo preserves an intervening correction`() = runBlocking {
        val id = topic()
        rate(id)
        val last = rate(id, origin + 4 * day)
        repo.editReviewRating(id, last.logId, MemoryRating.Forgot, UnderstandingRating.Clear)
        val before = snapshot()
        repo.undoReview(last)
        assertEquals("a corrected answer cannot be deleted by a stale undo button", before, snapshot())
    }

    @Test fun `undo preserves an intervening deferral`() = runBlocking {
        val id = topic()
        val last = rate(id)
        repo.procrastinateUnit(id, last.after.nextReviewAt + 10 * day)
        val before = snapshot()
        repo.undoReview(last)
        assertEquals("the deliberate later date and its review must stay together", before, snapshot())
    }

    @Test fun `undo after merge cannot remove a log now owned by the survivor`() = runBlocking {
        val survivor = topic()
        rate(survivor)
        val absorbed = topic()
        val last = rate(absorbed, origin + day)
        repo.mergeUnits(survivor, listOf(absorbed))
        val before = snapshot()
        repo.undoReview(last)
        assertEquals("the merged history must not lose the absorbed topic's review", before, snapshot())
    }

    @Test fun `undo after replacement of the history cannot delete a reused log id`() = runBlocking {
        val id = topic()
        val last = rate(id)
        // A restore keeps ids, but those ids can now name a different answer.
        val log = db.reviewLogDao().getLogsForUnitOnce(id).single()
        db.reviewLogDao().insertLog(log.copy(memoryRating = "Hard", initialDifficulty = "Hard"))
        val before = snapshot()
        repo.undoReview(last)
        assertEquals("ids alone are not an undo token across a restore", before, snapshot())
    }

    @Test fun `a topic archived or deleted after opening cannot receive a stale rating`() = runBlocking {
        for (delete in listOf(false, true)) {
            val id = topic()
            if (delete) repo.softDeleteUnit(id) else repo.archiveUnit(id)
            val before = snapshot()
            val result = repo.rateUnit(id, origin, MemoryRating.Good, UnderstandingRating.Clear,
                sessionKind = SessionKind.TOPIC, reviewDurationMs = 1000)
            assertNull(result)
            assertEquals(before, snapshot())
        }
    }
}
