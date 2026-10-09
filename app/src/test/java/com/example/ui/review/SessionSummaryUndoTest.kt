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
import com.example.ui.i18n.EnglishStrings
import com.example.ui.i18n.LocalStrings
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The last rating of a session can be a slip too. Undo lived only in the card's header, which the summary replaces,
 * so a wrong tap on the last topic (or the only one, from a Today card) could not be taken back there (two outside
 * audits, 2026-09-30). The summary offers it now, and it brings the topic back exactly as it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SessionSummaryUndoTest {

    @get:Rule val compose = createComposeRule()

    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `the last rating of a session can be undone from its summary`() {
        val app = ApplicationProvider.getApplicationContext<MedReviewApplication>()
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val id = runBlocking {
            app.repository.insertUnit(
                StudyUnitEntity(
                    title = "Nephrotic syndrome", studyType = "Topic", stability = 3.0, difficulty = 5.0, retrievability = 0.9,
                    state = "Building", studiedAt = now - 10 * day, lastReviewedAt = now - 4 * day,
                    nextReviewAt = now - day, modelDueAt = now - day, currentIntervalDays = 3.0, reviewCount = 2,
                    memoryModel = "FSRS-6",
                )
            )
        }
        val before = runBlocking { app.repository.getUnitById(id)!! }
        val strings = EnglishStrings
        compose.setContent {
            MyApplicationTheme(languageCode = "en") {
                CompositionLocalProvider(LocalStrings provides strings) {
                    ReviewSessionScreen(repository = app.repository, unitId = id, onNavigateToEdit = {}, onFinish = {})
                }
            }
        }
        waitForText(strings.ratingFail)
        compose.onNodeWithText(strings.ratingGood).performScrollTo().performClick()
        waitForText(strings.understandingNowQuestion)
        compose.onNode(hasText(strings.urClear, substring = true)).performScrollTo().performClick()

        waitForText("Saved")
        assertEquals("the rating was logged", 1, runBlocking { app.database.reviewLogDao().getLogsForUnitOnce(id).size })
        compose.onNodeWithText("Undo last rating").performClick()

        waitForText(strings.ratingFail)
        compose.onNodeWithText("Nephrotic syndrome").performScrollTo()
        assertEquals("the log is gone", 0, runBlocking { app.database.reviewLogDao().getLogsForUnitOnce(id).size })
        val after = runBlocking { app.repository.getUnitById(id)!! }
        assertEquals("the schedule is back", before.nextReviewAt, after.nextReviewAt)
        assertEquals(before.stability, after.stability, 0.0)
        assertEquals(before.reviewCount, after.reviewCount)
    }
}
