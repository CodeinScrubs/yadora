package com.example.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.MedReviewApplication
import com.example.data.local.entity.StudyUnitEntity
import com.example.ui.add.AddUnitScreen
import com.example.ui.i18n.EnglishStrings
import com.example.ui.i18n.LocalStrings
import com.example.ui.progress.ProgressScreen
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the learner had open survives a rotation (an outside audit, 2026-10-03, on the Samsung): Progress went back to
 * Overview and closed the Calendar Plan day that was open, and an open date picker on the Add/Edit screen closed (the
 * date already chosen was kept). The saved-state round trip here is the one a rotation makes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class RotationStateTest {

    @get:Rule val compose = createComposeRule()

    private fun english(content: @Composable () -> Unit): @Composable () -> Unit = {
        MyApplicationTheme(languageCode = "en") {
            CompositionLocalProvider(LocalStrings provides EnglishStrings) { content() }
        }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `progress keeps its tab and an opened forecast day through a rotation`() {
        val app = ApplicationProvider.getApplicationContext<MedReviewApplication>()
        runBlocking {
            val tomorrow = java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.DAY_OF_YEAR, 1)
                set(java.util.Calendar.HOUR_OF_DAY, 10)
            }.timeInMillis
            app.repository.insertUnit(
                StudyUnitEntity(
                    title = "Nephron physiology", studyType = "Topic", stability = 6.0, difficulty = 5.0, retrievability = 0.9,
                    state = "Building", studiedAt = tomorrow - 9 * 86_400_000L, lastReviewedAt = tomorrow - 6 * 86_400_000L,
                    nextReviewAt = tomorrow, modelDueAt = tomorrow, currentIntervalDays = 6.0, reviewCount = 2, memoryModel = "FSRS-6",
                )
            )
        }
        val restore = StateRestorationTester(compose)
        restore.setContent(english { ProgressScreen(repository = app.repository) })

        compose.onNodeWithText("Calendar Plan").performClick()
        waitForText("Tomorrow")
        compose.onNodeWithText("Nephron physiology").assertDoesNotExist() // only today's day starts open
        compose.onNodeWithText("Tomorrow").performClick()
        waitForText("Nephron physiology")

        restore.emulateSavedInstanceStateRestore()
        waitForText("Review Forecast (Next 10 Days)") // still on Calendar Plan
        compose.onNodeWithText("Nephron physiology").assertExists() // and tomorrow is still open
    }

    @Test
    fun `an open date picker stays open through a rotation`() {
        val app = ApplicationProvider.getApplicationContext<MedReviewApplication>()
        val restore = StateRestorationTester(compose)
        restore.setContent(english { AddUnitScreen(repository = app.repository, unitId = null, onBack = {}) })

        waitForText(EnglishStrings.lastStudiedAdded)
        compose.onNodeWithText(EnglishStrings.okBtn).assertDoesNotExist() // the form alone has no OK
        compose.onNodeWithText(EnglishStrings.lastStudiedAdded).performScrollTo().performClick()
        waitForText(EnglishStrings.okBtn)

        restore.emulateSavedInstanceStateRestore()
        waitForText(EnglishStrings.okBtn) // the picker is open again, not dismissed
        compose.onNodeWithText(EnglishStrings.cancel).assertExists()
    }
}
