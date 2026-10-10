package com.example.ui.review

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import com.example.MedReviewApplication
import com.example.data.local.entity.StudyUnitEntity
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

/**
 * A topic rated on its own screen, then the app sent to the background and its process ended by Android. The screen comes
 * back from its saved state with a NEW ViewModel, and it used to load the topic again with its rating buttons, inviting
 * the same review a second time (a production review, 2026-10-10). It comes back on its "Saved" end screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReviewProcessDeathTest {

    @get:Rule val compose = createComposeRule()

    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun freshViewModels() = object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }

    @Test
    fun `a topic rated before the process ended comes back saved, not to be rated again`() {
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
        val strings = EnglishStrings
        var owner: ViewModelStoreOwner = freshViewModels()
        val restore = StateRestorationTester(compose)
        restore.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                MyApplicationTheme(languageCode = "en") {
                    CompositionLocalProvider(LocalStrings provides strings) {
                        ReviewSessionScreen(repository = app.repository, unitId = id, onNavigateToEdit = {}, onFinish = {})
                    }
                }
            }
        }
        compose.waitUntil(10_000) { shown(strings.ratingFail) }
        compose.onNodeWithText(strings.ratingGood).performScrollTo().performClick()
        compose.waitUntil(10_000) { shown(strings.understandingNowQuestion) }
        compose.onNode(hasText(strings.urClear, substring = true)).performScrollTo().performClick()
        compose.waitUntil(10_000) { shown("Saved") }
        assertEquals(1, runBlocking { app.database.reviewLogDao().getLogsForUnitOnce(id).size })

        // The process ends: the ViewModels are gone, the screen's saved state is kept.
        owner = freshViewModels()
        restore.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.waitUntil(10_000) { shown("Saved") || shown(strings.ratingFail) }

        assertTrue("it comes back on its end screen", shown("Saved"))
        assertTrue("with no rating buttons to give the review again", !shown(strings.ratingFail))
        assertEquals("one review, logged once", 1, runBlocking { app.database.reviewLogDao().getLogsForUnitOnce(id).size })
    }
}
