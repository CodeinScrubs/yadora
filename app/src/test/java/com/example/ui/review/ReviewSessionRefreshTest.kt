package com.example.ui.review

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.MedReviewApplication
import com.example.data.local.entity.StudyUnitEntity
import com.example.domain.model.MemoryRating
import com.example.domain.model.SessionKind
import com.example.domain.model.UnderstandingRating
import com.example.ui.add.AddUnitViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Coming back to a screen must show the topic as the database holds it, and saving an edit must change only what
 * the form shows. Real view models on the real repository (outside audits, 2026-09-27).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReviewSessionRefreshTest {

    private val day = 86_400_000L

    /** Run the main looper (the view models' scope) until [done], while Room works on its own threads. */
    private fun waitFor(what: String, done: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (!done()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
            Thread.sleep(5)
        }
    }

    private fun ratedTopic(app: MedReviewApplication, title: String, now: Long, highYield: Boolean = false): Long = runBlocking {
        val repo = app.repository
        val at = now - 40 * day
        val id = repo.insertUnit(
            StudyUnitEntity(
                title = title, studyType = "Pathology", highYield = highYield, stability = 1.0, difficulty = 5.0,
                retrievability = 1.0, state = "New", studiedAt = at, nextReviewAt = at, modelDueAt = at,
                currentIntervalDays = 0.0, reviewCount = 0, lapseCount = 0, createdAt = at,
            )
        )
        for ((offset, rating) in listOf(40 to MemoryRating.Good, 37 to MemoryRating.Good, 27 to MemoryRating.Good)) {
            repo.rateUnit(id, now - offset * day, rating, UnderstandingRating.Clear, sessionKind = SessionKind.PLAN, reviewDurationMs = 1)
        }
        id
    }

    @Test
    fun `coming back from the Edit screen shows the topic as it is now, and the answer chosen stays`() {
        val app = ApplicationProvider.getApplicationContext<MedReviewApplication>()
        val repo = app.repository
        val id = ratedTopic(app, "Heparin & HIT", System.currentTimeMillis())

        val vm = ReviewViewModel(app, repo)
        vm.startSessionOnce(unitId = id)
        waitFor("the topic on screen") { vm.currentUnit.value?.id == id }
        val shown = vm.currentUnit.value!!

        // The pencil: on the Edit screen the learner corrects the latest rating to Forgot and renames the topic.
        runBlocking {
            val latest = app.database.reviewLogDao().getLogsForUnitOnce(id).last()
            repo.editReviewRating(id, latest.id, MemoryRating.Forgot, null)
            repo.updateUnit(repo.getUnitById(id)!!.copy(title = "Heparin-induced thrombocytopenia"))
        }
        val inDb = runBlocking { repo.getUnitById(id)!! }
        assertNotEquals("the correction moved the state", shown.stability, inDb.stability, 1e-9)

        vm.refreshCurrentUnit()
        waitFor("the row as it is now") { vm.currentUnit.value?.title == "Heparin-induced thrombocytopenia" }
        val now = vm.currentUnit.value!!
        assertEquals("the same topic", id, now.id)
        assertEquals("previewed from the state the commit will read", inDb.stability, now.stability, 0.0)
        assertEquals(inDb.nextReviewAt, now.nextReviewAt)
    }

    @Test
    fun `switching Important on while moving the study date tightens the schedule the replay wrote`() {
        val app = ApplicationProvider.getApplicationContext<MedReviewApplication>()
        val repo = app.repository
        val now = System.currentTimeMillis()
        val plain = ratedTopic(app, "Nephrotic syndrome", now)
        val important = ratedTopic(app, "Nephritic syndrome", now)

        fun save(id: Long, highYield: Boolean) {
            val vm = AddUnitViewModel(repo)
            vm.loadUnit(id)
            waitFor("the row loaded") { vm.existingUnit?.id == id }
            val loaded = vm.existingUnit!!
            var saved = false
            vm.saveUnit(
                title = loaded.title, subjectId = loaded.subjectId, systemId = null, studyType = "Topic", prompt = "",
                keyPoints = null, notes = loaded.notes.orEmpty(), source = loaded.source.orEmpty(), highYield = highYield,
                studiedAt = loaded.studiedAt - day, nextReviewAt = loaded.nextReviewAt, onSaved = { saved = true },
            )
            waitFor("the save") { saved }
        }
        save(plain, highYield = false)
        save(important, highYield = true)

        val a = runBlocking { repo.getUnitById(plain)!! }
        val b = runBlocking { repo.getUnitById(important)!! }
        assertTrue("Important", b.highYield)
        assertTrue(
            "the Important topic comes back sooner (${b.nextReviewAt - b.lastReviewedAt!!} vs ${a.nextReviewAt - a.lastReviewedAt!!} ms)",
            b.nextReviewAt - b.lastReviewedAt!! < a.nextReviewAt - a.lastReviewedAt!!,
        )
    }

    /**
     * The Edit screen re-reads its topic each time it comes back (a review opened on top of it from a topic notification,
     * or after process death), while the form keeps the dates it was filled with. Save compared the form with the fresher
     * row, so the due date the review had just set looked edited, and Save wrote the old one back as a deferral (a
     * production review, 2026-10-10).
     */
    @Test
    fun `saving the Edit form after a review made on top of it keeps the review's due date`() {
        val app = ApplicationProvider.getApplicationContext<MedReviewApplication>()
        val repo = app.repository
        val now = System.currentTimeMillis()
        val id = ratedTopic(app, "Hypokalemia", now)
        val vm = AddUnitViewModel(repo)
        vm.loadUnit(id)
        waitFor("the row loaded") { vm.existingUnit?.id == id }
        val form = vm.existingUnit!! // what the form was filled with

        // A topic notification opens its review on top of the Edit screen, and the learner rates it.
        runBlocking { repo.rateUnit(id, now, MemoryRating.Good, UnderstandingRating.Clear, sessionKind = SessionKind.PLAN, reviewDurationMs = 1) }
        val reviewed = runBlocking { repo.getUnitById(id)!! }
        assertNotEquals("the review moved the due date", form.nextReviewAt, reviewed.nextReviewAt)
        // Back on the Edit screen: it re-reads the row, the form keeps its own dates.
        vm.loadUnit(id)
        waitFor("the row re-read") { vm.existingUnit?.nextReviewAt == reviewed.nextReviewAt }

        var saved = false
        vm.saveUnit(
            title = "Hypokalemia — ECG", subjectId = form.subjectId, systemId = null, studyType = "Topic", prompt = "",
            keyPoints = null, notes = form.notes.orEmpty(), source = form.source.orEmpty(), highYield = false,
            studiedAt = form.studiedAt, nextReviewAt = form.nextReviewAt, onSaved = { saved = true },
            loadedStudiedAt = form.studiedAt, loadedNextReviewAt = form.nextReviewAt,
        )
        waitFor("the save") { saved }

        val after = runBlocking { repo.getUnitById(id)!! }
        assertEquals("Hypokalemia — ECG", after.title)
        assertEquals("the review's due date stands", reviewed.nextReviewAt, after.nextReviewAt)
        assertEquals("and nobody deferred it", null, after.deferredUntil)
    }

    /**
     * A next-review date picked in the same save as a new study date is the learner's deferral. The study date replays
     * the history, and the replay gave the rated topic the model's date instead: the picked date was dropped (a
     * production review, 2026-10-10).
     */
    @Test
    fun `a next-review date picked with a new study date is kept`() {
        val app = ApplicationProvider.getApplicationContext<MedReviewApplication>()
        val repo = app.repository
        val now = System.currentTimeMillis()
        val id = ratedTopic(app, "Hyperkalemia", now)
        val vm = AddUnitViewModel(repo)
        vm.loadUnit(id)
        waitFor("the row loaded") { vm.existingUnit?.id == id }
        val loaded = vm.existingUnit!!
        val picked = now + 9 * day
        var saved = false
        vm.saveUnit(
            title = loaded.title, subjectId = loaded.subjectId, systemId = null, studyType = "Topic", prompt = "",
            keyPoints = null, notes = loaded.notes.orEmpty(), source = loaded.source.orEmpty(), highYield = false,
            studiedAt = loaded.studiedAt - day, nextReviewAt = picked, onSaved = { saved = true },
        )
        waitFor("the save") { saved }

        val after = runBlocking { repo.getUnitById(id)!! }
        assertEquals("the study date moved", loaded.studiedAt - day, after.studiedAt)
        assertEquals("the picked date stands", picked, after.nextReviewAt)
        assertEquals("as the learner's deferral", picked, after.deferredUntil)
        assertNotEquals("the model's own date is the replay's", picked, after.modelDueAt)
    }

    /** The form no longer shows a topic's collection or study type, so saving it must leave them alone. */
    @Test
    fun `saving an edit keeps the collection and study type the form does not show`() {
        val app = ApplicationProvider.getApplicationContext<MedReviewApplication>()
        val repo = app.repository
        val id = ratedTopic(app, "Aortic stenosis", System.currentTimeMillis())
        val collection = runBlocking {
            val c = repo.insertSystem("Cardiology block")
            repo.updateUnit(repo.getUnitById(id)!!.copy(systemId = c))
            c
        }

        val vm = AddUnitViewModel(repo)
        vm.loadUnit(id)
        waitFor("the row loaded") { vm.existingUnit?.id == id }
        val loaded = vm.existingUnit!!
        var saved = false
        vm.saveUnit(
            title = "Aortic stenosis — murmur", subjectId = loaded.subjectId, systemId = null, studyType = "Topic", prompt = "",
            keyPoints = null, notes = "", source = "", highYield = false,
            studiedAt = loaded.studiedAt, nextReviewAt = loaded.nextReviewAt, onSaved = { saved = true },
        )
        waitFor("the save") { saved }

        val after = runBlocking { repo.getUnitById(id)!! }
        assertEquals("Aortic stenosis — murmur", after.title)
        assertEquals("the collection stays", collection, after.systemId)
        assertEquals("the study type stays", "Pathology", after.studyType)
    }
}
