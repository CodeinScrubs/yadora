package com.example.ui.today

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.MedReviewApplication
import com.example.data.local.entity.StudyUnitEntity
import com.example.ui.i18n.EnglishStrings
import com.example.ui.i18n.LocalStrings
import com.example.ui.review.ReviewSessionScreen
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Today as the owner decided it (2026-10-09): one list, and no session to start. First ratings and the most urgent
 * reviews up to the daily limit are today's share; a line ends it; every other due review stays listed below it, most
 * urgent first; a tap opens that one topic, and its log records where in the list it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2000dp", sdk = [36])
class TodayShareTest {

    @get:Rule val compose = createComposeRule()

    private val day = 86_400_000L
    private val app get() = ApplicationProvider.getApplicationContext<MedReviewApplication>()

    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun top(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    /** One first rating waiting, and three reviews overdue by different amounts. */
    private fun seed(now: Long): Map<String, Long> = runBlocking {
        val ids = LinkedHashMap<String, Long>()
        ids["Asthma"] = app.repository.insertUnit(
            StudyUnitEntity(
                title = "Asthma", studyType = "Topic", studiedAt = now - 3_600_000L, createdAt = now - 3_600_000L,
                nextReviewAt = now - 3_600_000L, modelDueAt = now - 3_600_000L, memoryModel = "FSRS-6",
            )
        )
        for ((title, late) in listOf("Nephrotic syndrome" to 1, "Heart failure drugs" to 9, "Beta-lactams" to 4)) {
            ids[title] = app.repository.insertUnit(
                StudyUnitEntity(
                    title = title, studyType = "Topic", stability = 3.0, difficulty = 5.0, retrievability = 0.9, state = "Building",
                    studiedAt = now - (late + 10) * day, lastReviewedAt = now - (late + 3) * day,
                    nextReviewAt = now - late * day, modelDueAt = now - late * day, currentIntervalDays = 3.0, reviewCount = 2,
                    memoryModel = "FSRS-6",
                )
            )
        }
        ids
    }

    @Test
    fun `today's share ends at the daily limit and the rest stay listed below its line, most urgent first`() {
        app.getSharedPreferences("medreview_settings", android.content.Context.MODE_PRIVATE).edit()
            .putFloat("daily_review_limit", 1f).commit()
        val now = System.currentTimeMillis()
        val ids = seed(now)
        // The order the app itself computes (DailyPlan): the first rating, then the most urgent review, then the rest.
        val due = runBlocking { ids.values.map { app.repository.getUnitById(it)!! } }
        val plan = DailyPlan.plan(due, 0, 1, now)
        val shareTitles = plan.queue.map { it.title }
        val belowTitles = DailyPlan.byPriority(due.filterNot { DailyPlan.isFirstRating(it) }, now)
            .filter { u -> plan.reviews.none { it.id == u.id } }.map { it.title }
        assertEquals("the first rating and one review", listOf("Asthma"), shareTitles.take(1))
        assertEquals(2, shareTitles.size)
        assertEquals(2, belowTitles.size)

        val opened = ArrayList<Pair<Long, String>>()
        compose.setContent {
            MyApplicationTheme(languageCode = "en") {
                CompositionLocalProvider(LocalStrings provides EnglishStrings) {
                    TodayScreen(
                        repository = app.repository, onNavigateToAdd = {}, onNavigateToReview = {}, onNavigateToEdit = {},
                        onNavigateToSettings = {}, onReviewFromToday = { id, kind -> opened += id to kind },
                    )
                }
            }
        }
        waitForText("TODAY'S SHARE")
        waitForText("Today's share ends here")
        val line = top("Today's share ends here; the rest of your time is for new material. Below: the rest of what is due, most urgent first.")
        assertTrue("the share is above the line", shareTitles.all { top(it) < line })
        assertTrue("the rest are below it", belowTitles.all { top(it) > line })
        assertEquals("in the order of urgency", shareTitles.map(::top).sorted(), shareTitles.map(::top))
        assertEquals("in the order of urgency", belowTitles.map(::top).sorted(), belowTitles.map(::top))
        assertTrue("no session to start", compose.onAllNodesWithText(EnglishStrings.startReview).fetchSemanticsNodes().isEmpty())
        assertTrue("no Spread out", compose.onAllNodesWithText("Spread", substring = true).fetchSemanticsNodes().isEmpty())

        compose.onNodeWithText(belowTitles.first()).performClick()
        compose.onNodeWithText(shareTitles.last()).performClick()
        compose.onNodeWithText("Asthma").performClick()
        assertEquals(
            "a tap opens that topic and says where in the list it was",
            listOf(ids.getValue(belowTitles.first()) to "EXTRA", ids.getValue(shareTitles.last()) to "PLAN", ids.getValue("Asthma") to "PLAN"),
            opened,
        )
    }

    @Test
    fun `a topic opened from below the line is logged as such, and its end screen says when it comes back`() {
        val now = System.currentTimeMillis()
        val id = seed(now).getValue("Heart failure drugs")
        val strings = EnglishStrings
        compose.setContent {
            MyApplicationTheme(languageCode = "en") {
                CompositionLocalProvider(LocalStrings provides strings) {
                    ReviewSessionScreen(repository = app.repository, unitId = id, kind = "EXTRA", onNavigateToEdit = {}, onFinish = {})
                }
            }
        }
        waitForText(strings.ratingGoodMeaning)
        compose.onNodeWithText(strings.ratingGoodMeaning).performScrollTo().performClick()
        waitForText(strings.understandingNowQuestion)
        compose.onNode(hasText(strings.urClear, substring = true)).performScrollTo().performClick()

        waitForText("Saved")
        waitForText("next")
        assertTrue("no session summary for one topic", compose.onAllNodesWithText("Session summary").fetchSemanticsNodes().isEmpty())
        val log = runBlocking { app.database.reviewLogDao().getLogsForUnitOnce(id).single() }
        assertEquals("EXTRA", log.sessionKind)
    }
}
