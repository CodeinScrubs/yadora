package com.example.data

import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.database.AppDatabase
import com.example.data.local.entity.StudyUnitEntity
import com.example.data.repository.MedReviewRepository
import com.example.domain.model.MemoryRating
import com.example.domain.model.UnderstandingRating
import com.example.domain.srs.MedScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Merging duplicate topics (the same material added twice, often in two languages) must never throw
 * away work already done on either copy. These tests pin that promise.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MergeUnitsTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: MedReviewRepository

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).openHelperFactory(FrameworkSQLiteOpenHelperFactory()).allowMainThreadQueries().build()
        repo = MedReviewRepository(db.studyUnitDao(), db.categoryDao(), db.reviewLogDao(), db)
    }

    @After fun teardown() = db.close()

    private val day = 86400000L

    private suspend fun addUnit(
        title: String,
        stability: Double,
        difficulty: Double,
        reviewCount: Int,
        dueAt: Long,
        lapses: Int = 0,
        highYield: Boolean = false,
    ): Long = repo.insertUnit(
        StudyUnitEntity(
            title = title, studyType = "Topic",
            stability = stability, difficulty = difficulty, retrievability = 1.0,
            state = MedScheduler.masteryState(stability, false).name,
            studiedAt = 1000L, nextReviewAt = dueAt, modelDueAt = dueAt,
            currentIntervalDays = 5.0, reviewCount = reviewCount, lapseCount = lapses,
            highYield = highYield,
        )
    )

    /**
     * THE post-merge regression. A merged topic ends up carrying one FIRST_STUDY log per absorbed
     * copy, because merging re-points their histories onto the survivor. The replay used to treat
     * EVERY FIRST_STUDY row as a fresh reviewNumber-0 seed (Fsrs.initialState), so correcting any
     * old rating — or merely editing the studied date — re-seeded mid-replay and threw away all the
     * stability the merged topic had accumulated. Only the chronologically first log may seed.
     */
    @Test
    fun `correcting a rating after a merge preserves the merged memory state`() = runBlocking {
        val now = System.currentTimeMillis()
        val keep = addUnit("Appendicitis", stability = 30.0, difficulty = 3.0, reviewCount = 2, dueAt = now + 20 * day)
        val other = addUnit("آپاندیسیت", stability = 12.0, difficulty = 5.0, reviewCount = 2, dueAt = now + 6 * day)

        // Each copy has its own first study followed by a recall — exactly what a real duplicate pair
        // looks like, and the shape that produces two FIRST_STUDY rows on the survivor after merging.
        repo.insertReviewLog(logFor(keep, now - 40 * day, type = "FIRST_STUDY"))
        repo.insertReviewLog(logFor(keep, now - 30 * day))
        repo.insertReviewLog(logFor(other, now - 20 * day, type = "FIRST_STUDY"))
        repo.insertReviewLog(logFor(other, now - 10 * day))

        val merged = repo.mergeUnits(keep, listOf(other))!!
        val logs = db.reviewLogDao().getLogsForUnit(keep).first().sortedBy { it.reviewedAt }
        assertEquals("all four reviews are on the survivor", 4, logs.size)
        assertEquals("two first-study rows exist post-merge", 2, logs.count { it.logType == "FIRST_STUDY" })
        // The merge itself already counts the absorbed first study as the exposure it now is. It used to sum
        // 2 + 2 = 4, and the correction below then quietly turned that into 3.
        assertEquals("the merge counts graded reviews the way replay does", 3, merged.reviewCount)

        // Correct the LAST rating (Good to Hard). Nothing about that edit should reset the topic to a brand-new memory.
        repo.editReviewRating(keep, logs.last().id, MemoryRating.Hard, UnderstandingRating.Clear)
        val after = repo.getUnitById(keep)!!

        val freshSeed = MedScheduler.firstStudy(UnderstandingRating.Partial, false).state.stability
        assertTrue(
            "replay must not re-seed from initialState (stability collapsed to ${after.stability})",
            after.stability > freshSeed * 2,
        )

        // THREE graded retrievals, not four. The absorbed copy's own first-study row is a RE-ENCODING
        // exposure — the user studied the material again under another title — not a retrieval. It
        // must not be counted as one, and must not earn recall credit: that would reward re-reading
        // as if it were remembering, and would contradict the reason first ratings are damped at all.
        assertEquals("only genuine retrievals count as reviews", 3, after.reviewCount)

        // And history stays HONEST: every row survives, and the exposure keeps its own logType rather
        // than being laundered into RECALL, so the distinction is still there on the next replay.
        val replayed = db.reviewLogDao().getLogsForUnit(keep).first().sortedBy { it.reviewedAt }
        assertEquals("no log is dropped", 4, replayed.size)
        assertEquals("both study events keep their identity", 2, replayed.count { it.logType == "FIRST_STUDY" })
        assertEquals("the earliest log is still the seed", "FIRST_STUDY", replayed.first().logType)
    }

    /**
     * The exposure must not silently strengthen memory. Replaying a merged history where the absorbed
     * copy contributes only a re-study must leave stability where the real retrievals put it.
     */
    @Test
    fun `a re-study exposure after a merge earns no recall credit`() = runBlocking {
        val now = System.currentTimeMillis()
        val keep = addUnit("Appendicitis", stability = 20.0, difficulty = 4.0, reviewCount = 2, dueAt = now + 15 * day)
        val other = addUnit("آپاندیسیت", stability = 6.0, difficulty = 6.0, reviewCount = 1, dueAt = now + 5 * day)
        repo.insertReviewLog(logFor(keep, now - 50 * day, type = "FIRST_STUDY"))
        repo.insertReviewLog(logFor(keep, now - 20 * day))
        // The only thing the absorbed copy contributes is a much later re-study, with a long gap that
        // would look like a very strong delayed recall if it were misread as one.
        repo.insertReviewLog(logFor(other, now - 2 * day, type = "FIRST_STUDY"))

        repo.mergeUnits(keep, listOf(other))!!
        val logs = db.reviewLogDao().getLogsForUnit(keep).first().sortedBy { it.reviewedAt }
        repo.editReviewRating(keep, logs.first().id, MemoryRating.Easy, UnderstandingRating.Clear)
        val after = repo.getUnitById(keep)!!

        assertEquals("the re-study is not a graded retrieval", 2, after.reviewCount)
        assertTrue("and it did not inflate stability", after.stability.isFinite() && after.stability > 0.0)
        assertEquals(
            "the exposure row keeps its honest type",
            "FIRST_STUDY",
            db.reviewLogDao().getLogsForUnit(keep).first().maxByOrNull { it.reviewedAt }!!.logType,
        )
    }

    @Test
    fun `a merged topic keeps its state when its studied date is edited`() = runBlocking {
        val now = System.currentTimeMillis()
        val keep = addUnit("Appendicitis", stability = 30.0, difficulty = 3.0, reviewCount = 2, dueAt = now + 20 * day)
        val other = addUnit("آپاندیسیت", stability = 12.0, difficulty = 5.0, reviewCount = 2, dueAt = now + 6 * day)
        repo.insertReviewLog(logFor(keep, now - 40 * day, type = "FIRST_STUDY"))
        repo.insertReviewLog(logFor(keep, now - 30 * day))
        repo.insertReviewLog(logFor(other, now - 20 * day, type = "FIRST_STUDY"))
        repo.insertReviewLog(logFor(other, now - 10 * day))
        repo.mergeUnits(keep, listOf(other))!!

        val before = repo.getUnitById(keep)!!
        repo.updateUnitReplayingHistory(before.copy(studiedAt = before.studiedAt - 5 * day))
        val after = repo.getUnitById(keep)!!

        val freshSeed = MedScheduler.firstStudy(UnderstandingRating.Partial, false).state.stability
        assertTrue("editing the studied date must not collapse the merged state", after.stability > freshSeed * 2)
    }

    @Test
    fun `an absorbed copy comes back clean, not as a zombie claiming reviews it does not own`() = runBlocking {
        val now = System.currentTimeMillis()
        val keep = addUnit("Appendicitis", stability = 20.0, difficulty = 4.0, reviewCount = 3, dueAt = now + 10 * day)
        val other = addUnit("آپاندیسیت", stability = 9.0, difficulty = 6.0, reviewCount = 4, dueAt = now + 2 * day, lapses = 2)
        repeat(4) { repo.insertReviewLog(logFor(other, now - (it + 1) * day)) }

        repo.mergeUnits(keep, listOf(other))!!
        repo.restoreDeletedUnit(other)
        val revived = repo.getUnitById(other)!!

        assertEquals("its history went to the survivor, so its own count must be zero", 0, revived.reviewCount)
        assertEquals("lapses too", 0, revived.lapseCount)
        assertEquals("no reviews left on it", 0, db.reviewLogDao().getLogsForUnit(other).first().size)
        assertEquals("back to an unrated state", "New", revived.state)
        assertNull("never reviewed any more", revived.lastReviewedAt)
        assertEquals("title is kept — the user can re-study it", "آپاندیسیت", revived.title)
    }

    @Test
    fun `an already-absorbed copy cannot be merged a second time`() = runBlocking {
        val now = System.currentTimeMillis()
        val keep = addUnit("Appendicitis", stability = 20.0, difficulty = 4.0, reviewCount = 3, dueAt = now + 10 * day)
        val other = addUnit("آپاندیسیت", stability = 8.0, difficulty = 6.0, reviewCount = 2, dueAt = now + 4 * day)

        val first = repo.mergeUnits(keep, listOf(other))!!
        assertEquals("counts combined once", 5, first.reviewCount)

        // Simulates a double-tap slipping past the UI guard, or a stale selection being re-submitted.
        assertNull("second merge of a soft-deleted copy is refused", repo.mergeUnits(keep, listOf(other)))
        assertEquals("counts were not folded in twice", 5, repo.getUnitById(keep)!!.reviewCount)
    }

    /**
     * The merged topic comes back on the earliest date any copy had; here that is the lapsed copy's DEFERRED
     * date, later than its own memory date. That deferral is the user's, so it is kept as a deferral. The
     * merge used to clear it while keeping the date, leaving a row due on a day neither clock explains (the
     * export's self-check flagged it on the Samsung).
     */
    @Test
    fun `merging preserves an in-progress relearn and never launders a deferral into modelDueAt`() = runBlocking {
        val now = System.currentTimeMillis()
        val keep = addUnit("Appendicitis", stability = 20.0, difficulty = 4.0, reviewCount = 3, dueAt = now + 30 * day)
        val lapsed = addUnit("آپاندیسیت", stability = 2.0, difficulty = 8.0, reviewCount = 2, dueAt = now + 1 * day)
        // The lapsed copy is mid-relearn, and the user has ALSO deferred it to a later date.
        val lapsedUnit = repo.getUnitById(lapsed)!!
        repo.updateUnit(
            lapsedUnit.copy(
                state = "NeedsRelearn",
                modelDueAt = now + 1 * day,
                nextReviewAt = now + 3 * day,
                deferredUntil = now + 3 * day,
            )
        )

        val merged = repo.mergeUnits(keep, listOf(lapsed))!!

        assertEquals("a relearn in progress survives the merge", "NeedsRelearn", merged.state)
        assertEquals("the earliest MODEL date wins, not the deferred one", now + 1 * day, merged.modelDueAt)
        assertEquals("the topic keeps the date the user deferred it to", now + 3 * day, merged.nextReviewAt)
        assertEquals("and says so: the user's deferral is kept, not laundered away", now + 3 * day, merged.deferredUntil)
    }

    @Test
    fun `a merge of copies that were never deferred creates no deferral`() = runBlocking {
        val now = System.currentTimeMillis()
        val keep = addUnit("Appendicitis", stability = 20.0, difficulty = 4.0, reviewCount = 3, dueAt = now + 12 * day)
        val other = addUnit("آپاندیسیت", stability = 6.0, difficulty = 6.0, reviewCount = 2, dueAt = now + 4 * day)
        val merged = repo.mergeUnits(keep, listOf(other))!!
        assertEquals(now + 4 * day, merged.nextReviewAt)
        assertEquals("the effective date is the earlier clock", merged.modelDueAt, merged.nextReviewAt)
        assertNull("a merge is not a user deferral", merged.deferredUntil)
    }

    @Test
    fun `merging keeps every review log and sums the counts`() = runBlocking {
        val now = System.currentTimeMillis()
        val keep = addUnit("Appendicitis", stability = 20.0, difficulty = 4.0, reviewCount = 3, dueAt = now + 10 * day)
        val other = addUnit("آپاندیسیت", stability = 5.0, difficulty = 8.0, reviewCount = 1, dueAt = now + 2 * day)

        // Give each copy real history, so "no work is lost" is actually testable.
        repeat(3) { repo.insertReviewLog(logFor(keep, now - (it + 1) * day)) }
        repo.insertReviewLog(logFor(other, now - 5 * day))

        val merged = repo.mergeUnits(keep, listOf(other))
        assertNotNull("merge returned the survivor", merged)

        val logs = db.reviewLogDao().getLogsForUnit(keep).first()
        assertEquals("every log from both copies now belongs to the survivor", 4, logs.size)
        assertEquals("no logs left on the absorbed copy", 0, db.reviewLogDao().getLogsForUnit(other).first().size)
        assertEquals("review counts add up", 4, merged!!.reviewCount)
    }

    @Test
    fun `merged memory state leans toward the copy with more reviews`() = runBlocking {
        val now = System.currentTimeMillis()
        // 9 reviews of a well-known copy vs 1 review of a shaky one: the result must sit near the former.
        val keep = addUnit("Appendicitis", stability = 30.0, difficulty = 3.0, reviewCount = 9, dueAt = now + 20 * day)
        val other = addUnit("آپاندیسیت", stability = 10.0, difficulty = 7.0, reviewCount = 1, dueAt = now + 3 * day)

        val merged = repo.mergeUnits(keep, listOf(other))!!

        // Weighted by review count: (30*9 + 10*1) / 10 = 28.0, and (3*9 + 7*1) / 10 = 3.4
        assertEquals("stability is review-count weighted", 28.0, merged.stability, 1e-9)
        assertEquals("difficulty is review-count weighted", 3.4, merged.difficulty, 1e-9)
        assertTrue("result sits nearer the well-drilled copy", merged.stability > (30.0 + 10.0) / 2)
    }

    @Test
    fun `merging never pushes the material further away than the schedule already had`() = runBlocking {
        val now = System.currentTimeMillis()
        val keep = addUnit("Appendicitis", stability = 30.0, difficulty = 3.0, reviewCount = 9, dueAt = now + 40 * day)
        val soon = addUnit("آپاندیسیت", stability = 4.0, difficulty = 8.0, reviewCount = 1, dueAt = now + 2 * day)

        val merged = repo.mergeUnits(keep, listOf(soon))!!

        assertEquals("the earliest due date wins", now + 2 * day, merged.nextReviewAt)
        assertEquals("model due date agrees", now + 2 * day, merged.modelDueAt)
        assertNull("a merge is not a user deferral", merged.deferredUntil)
    }

    @Test
    fun `absorbed copies are recoverable and importance is a union`() = runBlocking {
        val now = System.currentTimeMillis()
        val keep = addUnit("Appendicitis", stability = 20.0, difficulty = 4.0, reviewCount = 2, dueAt = now + 10 * day)
        val other = addUnit("آپاندیسیت", stability = 6.0, difficulty = 6.0, reviewCount = 1, dueAt = now + 4 * day, highYield = true)

        val merged = repo.mergeUnits(keep, listOf(other))!!

        assertTrue("importance carries over from either copy", merged.highYield)
        val deleted = repo.recentlyDeleted.first().map { it.id }
        assertTrue("a mistaken merge stays recoverable for 30 days", other in deleted)
        assertTrue("the survivor is not deleted", keep !in deleted)
    }

    /**
     * The two history reconstructors must agree, exactly.
     *
     * A merged topic carries a later FIRST_STUDY row -- the absorbed copy's own post-study rating,
     * a re-encoding EXPOSURE. Projection (migration onto the current model) and the replay inside
     * editReviewRating are two separate implementations of "rebuild this topic from its history",
     * and they disagreed: projection dropped exposures from the list entirely, so the elapsed clock
     * was never re-anchored and the following recall was credited with the time since the previous
     * GRADED review instead. The same evidence produced a different memory state depending on
     * whether the topic arrived via migration or via a rating correction.
     */
    @Test
    fun `projection and replay reconstruct an exposure history identically`() = runBlocking {
        val now = System.currentTimeMillis()
        // study -> recall -> RE-STUDY (exposure) -> recall. The gap that matters is the last one:
        // measured from the exposure (10 days) or from the previous recall (20 days)?
        suspend fun history(id: Long) {
            repo.insertReviewLog(logFor(id, now - 40 * day, type = "FIRST_STUDY"))
            repo.insertReviewLog(logFor(id, now - 30 * day))
            repo.insertReviewLog(logFor(id, now - 20 * day, type = "FIRST_STUDY"))
            repo.insertReviewLog(logFor(id, now - 10 * day))
        }

        val viaProjection = addUnit("Projected", stability = 40.0, difficulty = 3.0, reviewCount = 3, dueAt = now + day)
        val viaReplay = addUnit("Replayed", stability = 40.0, difficulty = 3.0, reviewCount = 3, dueAt = now + day)
        history(viaProjection)
        history(viaReplay)

        // Both must end up reconstructed under the SAME model or the comparison is meaningless:
        // one crosses over by projection, the other is already on the current model and rebuilds
        // through the replay path. Same evidence, same model, two implementations.
        val projected = repo.projectOntoCurrentModel(repo.getUnitById(viaProjection)!!)
        repo.updateUnit(repo.getUnitById(viaReplay)!!.copy(memoryModel = MedScheduler.CURRENT_MODEL.id))
        repo.updateUnitReplayingHistory(repo.getUnitById(viaReplay)!!)
        val replayed = repo.getUnitById(viaReplay)!!

        assertEquals("same stability from the same evidence", projected.stability, replayed.stability, 1e-9)
        assertEquals("same difficulty from the same evidence", projected.difficulty, replayed.difficulty, 1e-9)
        assertEquals("same graded review count", projected.reviewCount, replayed.reviewCount)
        assertEquals("the exposure is not a retrieval", 3, projected.reviewCount)
    }

    /**
     * A recall prompt defines what "remembering" a topic means, so a merge must never silently drop
     * one. The same material often arrives twice — once with a prompt, once without — and the user may
     * well choose to keep the copy that has none.
     */
    @Test
    fun `merging keeps a recall prompt from whichever copy has one`() = runBlocking {
        val now = System.currentTimeMillis()
        val bare = addUnit("Appendicitis", stability = 10.0, difficulty = 5.0, reviewCount = 1, dueAt = now + 5 * day)
        val prompted = addUnit("آپاندیسیت", stability = 10.0, difficulty = 5.0, reviewCount = 1, dueAt = now + 5 * day)
        repo.updateUnit(repo.getUnitById(prompted)!!.copy(recallPrompt = "presentation, scores, management"))

        val merged = repo.mergeUnits(bare, listOf(prompted))!!
        assertEquals(
            "the survivor had no prompt, so it inherits the absorbed copy's",
            "presentation, scores, management", merged.recallPrompt,
        )

        // And a survivor's own prompt is never overwritten by an absorbed copy's.
        val own = addUnit("Cholecystitis", stability = 10.0, difficulty = 5.0, reviewCount = 1, dueAt = now + 5 * day)
        val other = addUnit("کوله‌سیستیت", stability = 10.0, difficulty = 5.0, reviewCount = 1, dueAt = now + 5 * day)
        repo.updateUnit(repo.getUnitById(own)!!.copy(recallPrompt = "Murphy's sign, ultrasound findings"))
        repo.updateUnit(repo.getUnitById(other)!!.copy(recallPrompt = "something else"))
        val kept = repo.mergeUnits(own, listOf(other))!!
        assertEquals("the survivor's own prompt wins", "Murphy's sign, ultrasound findings", kept.recallPrompt)
    }

    /**
     * Key points are the scoring standard for the material, so a merge keeps them by the prompt's rule
     * and never unions two copies' lists (the same points in two languages would count twice).
     */
    @Test
    fun `merging keeps key points from whichever copy has them, never a union`() = runBlocking {
        val now = System.currentTimeMillis()
        val bare = addUnit("Appendicitis", stability = 10.0, difficulty = 5.0, reviewCount = 1, dueAt = now + 5 * day)
        val scored = addUnit("آپاندیسیت", stability = 10.0, difficulty = 5.0, reviewCount = 1, dueAt = now + 5 * day)
        repo.updateUnit(repo.getUnitById(scored)!!.copy(keyPoints = "McBurney's point\nAlvarado score"))
        assertEquals("McBurney's point\nAlvarado score", repo.mergeUnits(bare, listOf(scored))!!.keyPoints)

        val own = addUnit("Cholecystitis", stability = 10.0, difficulty = 5.0, reviewCount = 1, dueAt = now + 5 * day)
        val other = addUnit("کوله‌سیستیت", stability = 10.0, difficulty = 5.0, reviewCount = 1, dueAt = now + 5 * day)
        repo.updateUnit(repo.getUnitById(own)!!.copy(keyPoints = "Murphy's sign"))
        repo.updateUnit(repo.getUnitById(other)!!.copy(keyPoints = "Murphy's sign\nUltrasound"))
        assertEquals("the survivor's own points win", "Murphy's sign", repo.mergeUnits(own, listOf(other))!!.keyPoints)
    }

    @Test
    fun `merging three copies at once combines all of them`() = runBlocking {
        val now = System.currentTimeMillis()
        val keep = addUnit("Appendicitis", stability = 12.0, difficulty = 5.0, reviewCount = 2, dueAt = now + 9 * day)
        val b = addUnit("آپاندیسیت", stability = 12.0, difficulty = 5.0, reviewCount = 2, dueAt = now + 6 * day)
        val c = addUnit("Blinddarmentzündung", stability = 12.0, difficulty = 5.0, reviewCount = 2, dueAt = now + 3 * day)
        // A fourth, untouched copy with the SAME history: the yardstick for what one copy on its own
        // is worth, so "averaging identical states is the identity" can be asserted without pinning a
        // literal that would have to be rewritten every time the model's weights change.
        val solo = addUnit("Solo", stability = 12.0, difficulty = 5.0, reviewCount = 2, dueAt = now + 9 * day)
        for (id in listOf(keep, b, c, solo)) {
            repo.insertReviewLog(logFor(id, now - 20 * day, type = "FIRST_STUDY"))
            repo.insertReviewLog(logFor(id, now - 17 * day))
        }

        val merged = repo.mergeUnits(keep, listOf(b, c))!!
        val one = repo.projectOntoCurrentModel(repo.getUnitById(solo)!!)

        // Six ratings, but in ONE history only the earliest first study seeds it; the other two copies' first
        // studies are re-encoding exposures, which replay and the export's self-check do not count.
        assertEquals("every graded review counts, the demoted first studies do not", 4, merged.reviewCount)
        assertEquals("identical states average to themselves", one.stability, merged.stability, 1e-9)
        assertEquals("and so do their difficulties", one.difficulty, merged.difficulty, 1e-9)
        assertEquals("earliest of all three", now + 3 * day, merged.nextReviewAt)
        assertEquals("all three histories are on the survivor", 6, db.reviewLogDao().getLogsForUnit(keep).first().size)
    }

    /**
     * A merge must never average across memory models. Two copies of the same material can sit on
     * different ones — one reviewed since the FSRS-6 switch, one not — and an FSRS-5 stability is not
     * measured in the same units as an FSRS-6 stability, so averaging them yields a number belonging
     * to neither model.
     *
     * The same fix closes a second hole: the merged row must be stamped with the CURRENT model, or
     * the lazy projection at the next review would replay the now-combined history and quietly
     * REPLACE the weighted average with a full chronological replay — the exact behaviour that was
     * deliberately not chosen.
     */
    @Test
    fun `merging copies on different memory models reconciles them first`() = runBlocking {
        val now = System.currentTimeMillis()
        val legacy = addUnit("Appendicitis", stability = 68.9, difficulty = 2.1, reviewCount = 2, dueAt = now + 30 * day)
        val current = addUnit("آپاندیسیت", stability = 9.4, difficulty = 5.5, reviewCount = 2, dueAt = now + 8 * day)
        // An untouched control with the same legacy history: what ONE un-migrated copy is worth once
        // reconciled. It must stay out of the merge, or its own history would move onto the survivor.
        val control = addUnit("Control", stability = 68.9, difficulty = 2.1, reviewCount = 2, dueAt = now + 30 * day)
        for (id in listOf(legacy, current, control)) {
            repo.insertReviewLog(logFor(id, now - 20 * day, type = "FIRST_STUDY"))
            repo.insertReviewLog(logFor(id, now - 17 * day))
        }
        // The second copy has already crossed over; the first has not.
        repo.updateUnit(repo.getUnitById(current)!!.copy(memoryModel = MedScheduler.CURRENT_MODEL.id))

        val merged = repo.mergeUnits(legacy, listOf(current))!!

        assertEquals("the merged topic is owned by one model", MedScheduler.CURRENT_MODEL.id, merged.memoryModel)

        // The average must be taken over RECONCILED values: the legacy copy replayed onto FSRS-6,
        // and the already-migrated copy as-is. Equal review counts, so equal weights.
        val reconciledLegacy = repo.projectOntoCurrentModel(repo.getUnitById(control)!!).stability
        assertEquals("averaged in current-model units", (reconciledLegacy + 9.4) / 2.0, merged.stability, 1e-9)

        // The bug this pins: averaging the RAW numbers would have landed near 39, because a stale
        // FSRS-5 stability of 68.9 is a far bigger number than the same memory expressed in FSRS-6.
        assertTrue("the raw legacy stability did not leak into the average", merged.stability < 20.0)

        // And because it is stamped current, the next review will NOT re-derive it by replay.
        val reloaded = repo.projectOntoCurrentModel(repo.getUnitById(legacy)!!)
        assertEquals("the weighted average survives the next review", merged.stability, reloaded.stability, 0.0)
    }

    @Test
    fun `merging a topic into itself is a no-op`() = runBlocking {
        val now = System.currentTimeMillis()
        val keep = addUnit("Appendicitis", stability = 20.0, difficulty = 4.0, reviewCount = 3, dueAt = now + 10 * day)
        assertNull("nothing to merge", repo.mergeUnits(keep, listOf(keep)))
        assertEquals("the topic is untouched", 3, repo.getUnitById(keep)!!.reviewCount)
    }

    private fun logFor(unitId: Long, at: Long, type: String = "RECALL") = com.example.data.local.entity.ReviewLogEntity(
        studyUnitId = unitId, reviewedAt = at,
        memoryRating = MemoryRating.Good.name, understandingRating = UnderstandingRating.Clear.name,
        previousIntervalDays = 3.0, nextIntervalDays = 6.0,
        previousState = "Learning", nextState = "Building",
        retrievabilityAtReview = 0.9, elapsedDays = 3.0, logType = type,
        reviewDurationMs = 1000, wasImportantAtReview = 0,
        desiredRetentionAtReview = 0.9, schedulerVersion = MedScheduler.SCHEDULER_VERSION,
        schedulerPolicyVersion = MedScheduler.POLICY_VERSION, understandingFactorAtReview = 1.0,
    )
}
