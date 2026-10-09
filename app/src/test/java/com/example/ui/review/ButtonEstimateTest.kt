package com.example.ui.review

import androidx.test.core.app.ApplicationProvider
import com.example.MedReviewApplication
import com.example.data.local.entity.StudyUnitEntity
import com.example.domain.model.MemoryRating
import com.example.domain.model.SessionKind
import com.example.domain.model.UnderstandingRating
import com.example.domain.srs.MedScheduler
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the review screen's buttons say is what the commit does.
 *
 * Every memory button shows roughly when its answer brings the topic back ("~27d"), and every understanding button
 * shows it exactly. Both come from [previewReturnDays]; the commit is [com.example.data.repository.MedReviewRepository.rateUnit].
 * For topics in every kind of state and every answer, this rates for real and checks that the date written is the
 * date previewed, that the memory button's figure (Clear understanding) is the LATEST the topic can come back, so
 * the estimate never promises more time than any answer gives, and that Undo restores the topic exactly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ButtonEstimateTest {

    private val day = 86_400_000L

    @After fun resetScheduler() {
        MedScheduler.calibrationScale = 1.0
        MedScheduler.userRetention = MedScheduler.BASE_RETENTION
    }

    @Test
    fun `every button's figure is the date the commit writes, and the memory estimate is the latest possible`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MedReviewApplication>()
        val repo = app.repository
        val now = System.currentTimeMillis()

        fun topic(title: String, highYield: Boolean, studiedDaysAgo: Int) = runBlocking {
            val at = now - studiedDaysAgo * day
            repo.insertUnit(
                StudyUnitEntity(
                    title = title, studyType = "Topic", highYield = highYield, stability = 1.0, difficulty = 5.0,
                    retrievability = 1.0, state = "New", studiedAt = at, nextReviewAt = at, modelDueAt = at,
                    currentIntervalDays = 0.0, reviewCount = 0, lapseCount = 0, createdAt = at,
                )
            )
        }
        suspend fun rate(id: Long, at: Long, m: MemoryRating, u: UnderstandingRating) = repo.rateUnit(
            unitId = id, now = at, memoryRating = m, understandingRating = u,
            understandingAsked = m != MemoryRating.Forgot, sessionKind = SessionKind.PLAN, reviewDurationMs = 60_000,
        )!!

        // States: never rated; rated once; an Important topic with history; a topic two Partials into a repair streak;
        // a topic that lapsed; one reviewed very late.
        val fresh = topic("fresh", false, 0)
        val once = topic("once", false, 5).also { rate(it, now - 5 * day, MemoryRating.Good, UnderstandingRating.Clear) }
        val important = topic("important", true, 40).also {
            rate(it, now - 40 * day, MemoryRating.Good, UnderstandingRating.Clear)
            rate(it, now - 37 * day, MemoryRating.Good, UnderstandingRating.Clear)
            rate(it, now - 20 * day, MemoryRating.Easy, UnderstandingRating.Clear)
        }
        val streaking = topic("streaking", false, 30).also {
            rate(it, now - 30 * day, MemoryRating.Good, UnderstandingRating.Clear)
            rate(it, now - 27 * day, MemoryRating.Good, UnderstandingRating.Partial)
            rate(it, now - 21 * day, MemoryRating.Good, UnderstandingRating.Partial)
        }
        val lapsed = topic("lapsed", false, 20).also {
            rate(it, now - 20 * day, MemoryRating.Hard, UnderstandingRating.Clear)
            rate(it, now - 18 * day, MemoryRating.Forgot, UnderstandingRating.Partial)
        }
        val late = topic("late", false, 400).also { rate(it, now - 400 * day, MemoryRating.Easy, UnderstandingRating.Clear) }

        var checked = 0
        for (scale in listOf(1.0, 0.8)) {
            MedScheduler.calibrationScale = scale
            for (id in listOf(fresh, once, important, streaking, lapsed, late)) {
                for (m in MemoryRating.entries) {
                    val understandings = if (m == MemoryRating.Forgot) listOf(UnderstandingRating.Partial) else UnderstandingRating.entries
                    // What the memory button shows: Clear, or the Forgot placeholder the screen commits with.
                    val shown = repo.projectOntoCurrentModel(repo.getUnitById(id)!!)
                    val streak = repo.unrepairedStreak(id)
                    val estimate = previewReturnDays(
                        shown, now, m, if (m == MemoryRating.Forgot) UnderstandingRating.Partial else UnderstandingRating.Clear, streak,
                    )
                    for (u in understandings) {
                        val preview = previewReturnDays(shown, now, m, u, streak)
                        val rated = rate(id, now, m, u)
                        val written = (rated.effectiveDueAt - now).toDouble() / day
                        val what = "topic $id, $m + $u, scale $scale"
                        assertEquals("$what: the button's date is the committed date", preview, written, 1e-6)
                        assertTrue("$what: the memory estimate ($estimate) is never shorter than the real return ($written)", estimate >= written - 1e-6)
                        repo.undoReview(rated)
                        assertEquals("$what: undo restores the row as it was when the rating began", rated.before, repo.getUnitById(id))
                        checked++
                    }
                }
            }
        }
        assertEquals("every state, answer and scale was checked", 2 * 6 * (1 + 3 * 3), checked)
    }
}
