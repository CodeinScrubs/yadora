package com.example.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.example.MedReviewApplication
import com.example.data.local.entity.StudyUnitEntity
import com.example.ui.i18n.AppStrings
import com.example.ui.i18n.EnglishStrings
import com.example.ui.i18n.LocalStrings
import com.example.ui.i18n.PersianStrings
import com.example.ui.review.ReviewSessionScreen
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The review screen on a SMALL phone (360 x 640 dp): the topic stays visible and every control stays reachable.
 *
 * A review became "any method" (2026-09-23), and the rating area grew: the optional method row, a question score,
 * a hint, one full-width button per rating. With the topic card simply weighted above it, a small phone in Persian
 * (whose type is set larger) squeezed the card to an empty sliver, so the learner could not see which topic they
 * were rating, and "Not today" fell off the bottom. The keyboard opened for a score does the same on any phone.
 * Screenshots go to build/small-screen/ when Roborazzi records (-Proborazzi.test.record=true).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-xhdpi", sdk = [36])
class SmallScreenReviewTest {

    @get:Rule val compose = createComposeRule()

    private val title = "Nephrotic syndrome"

    private fun seed(): MedReviewApplication = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MedReviewApplication>()
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        app.repository.insertUnit(
            StudyUnitEntity(
                title = title, studyType = "Topic", stability = 3.0, difficulty = 5.0, retrievability = 0.9,
                state = "Building", studiedAt = now - 10 * day, lastReviewedAt = now - 4 * day,
                nextReviewAt = now - day, modelDueAt = now - day, currentIntervalDays = 3.0, reviewCount = 2,
                memoryModel = "FSRS-6", recallPrompt = "Causes, the tetrad, complications, treatment",
                notes = (1..12).joinToString("\n") { "Line $it of the notes: proteinuria, hypoalbuminaemia, oedema." },
            )
        )
        app
    }

    private fun screen(strings: AppStrings) {
        val app = seed()
        compose.setContent {
            MyApplicationTheme(languageCode = strings.languageCode) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (strings.languageCode == "fa") LayoutDirection.Rtl else LayoutDirection.Ltr,
                    LocalStrings provides strings,
                ) {
                    ReviewSessionScreen(repository = app.repository, onNavigateToEdit = {}, onFinish = {})
                }
            }
        }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun reachable(label: String) {
        compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
    }

    private fun check(strings: AppStrings, questionsChip: String, name: String) {
        screen(strings)
        waitForText(strings.ratingFail)
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onRoot().captureRoboImage(filePath = "build/small-screen/${name}_rating.png")

        // A question score adds two fields; the topic must stay on screen, and every control within reach.
        compose.onNodeWithText(questionsChip).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onRoot().captureRoboImage(filePath = "build/small-screen/${name}_questions.png")
        listOf(strings.ratingEasy, strings.ratingGood, strings.ratingHard, strings.ratingFail, strings.notToday).forEach(::reachable)
        compose.onNodeWithText(strings.ratingGood).performScrollTo().performClick()

        // The understanding step.
        waitForText(strings.understandingNowQuestion)
        compose.onRoot().captureRoboImage(filePath = "build/small-screen/${name}_understanding.png")
        listOf(strings.urConfused, strings.urPartial, strings.urClear).forEach { label ->
            compose.onNode(androidx.compose.ui.test.hasText(label, substring = true)).performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun `in English the topic and every control fit a small phone`() = check(EnglishStrings, "Questions", "en")

    @Test fun `in Persian the topic and every control fit a small phone`() = check(PersianStrings, "تست و سؤال", "fa")
}
