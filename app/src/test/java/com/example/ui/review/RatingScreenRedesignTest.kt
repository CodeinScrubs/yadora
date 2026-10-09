package com.example.ui.review

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
import com.example.domain.model.ReviewDay
import com.example.ui.i18n.EnglishStrings
import com.example.ui.i18n.LocalStrings
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

/**
 * The rating screen as the owner decided it (2026-10-09), driven through the real screen: Forgot goes on to the
 * understanding question like every answer, and that step offers the review's day and the optional minutes; what was
 * chosen is what the log records.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RatingScreenRedesignTest {

    @get:Rule val compose = createComposeRule()

    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `Forgot asks about understanding, and the review's day and minutes are what the log records`() {
        val app = ApplicationProvider.getApplicationContext<MedReviewApplication>()
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val id = runBlocking {
            app.repository.insertUnit(
                StudyUnitEntity(
                    title = "Iron deficiency anaemia: treatment", studyType = "Topic", stability = 3.0, difficulty = 5.0,
                    retrievability = 0.9, state = "Building", studiedAt = now - 12 * day, lastReviewedAt = now - 6 * day,
                    nextReviewAt = now - day, modelDueAt = now - day, currentIntervalDays = 5.0, reviewCount = 2,
                    memoryModel = "FSRS-6",
                )
            )
        }
        val strings = EnglishStrings
        compose.setContent {
            MyApplicationTheme(languageCode = "en") {
                CompositionLocalProvider(LocalStrings provides strings) {
                    ReviewSessionScreen(repository = app.repository, unitId = id, onNavigateToEdit = {}, onFinish = {})
                }
            }
        }
        waitForText(strings.ratingFail)
        compose.onNodeWithText(strings.ratingFailMeaning).performScrollTo().performClick()

        // Forgot no longer commits at once: the understanding question, with the day and the minutes above it.
        waitForText(strings.understandingNowQuestion)
        assertEquals("nothing is saved before the last answer", 0, runBlocking { app.database.reviewLogDao().getLogsForUnitOnce(id).size })
        compose.onNodeWithText("About how long? (optional)").performScrollTo()
        compose.onNodeWithText("30 min").performScrollTo().performClick()
        compose.onNodeWithText("Reviewed: today", substring = true).performScrollTo().performClick()
        waitForText("yesterday")
        compose.onNodeWithText("yesterday").performClick()
        waitForText("Reviewed: yesterday")
        compose.onNode(hasText(strings.urConfused, substring = true)).performScrollTo().performClick()

        waitForText(strings.sessionComplete)
        val log = runBlocking { app.database.reviewLogDao().getLogsForUnitOnce(id).single() }
        assertEquals("Forgot", log.memoryRating)
        assertEquals("the understanding answer after Forgot is recorded", "Confused", log.understandingRating)
        assertEquals(30, log.studyMinutes)
        val zone = ZoneId.systemDefault()
        assertEquals("it happened yesterday", ReviewDay.day(now, zone).minusDays(1), ReviewDay.day(log.reviewedAt, zone))
        assertTrue("it was saved now", log.loggedAt >= now && log.loggedAt - log.reviewedAt >= 12 * 3_600_000L)
    }
}
