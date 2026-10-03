package com.example.ui.add

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.example.MedReviewApplication
import com.example.data.local.entity.StudyUnitEntity
import com.example.ui.i18n.EnglishStrings
import com.example.ui.i18n.LocalStrings
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Save and review now" on a topic's own page (the owner, 2026-10-03: "sometimes I may need to review a topic even if the
 * app has not asked for it"). It saves the form first, then opens the one-topic session, due or not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class SaveAndReviewNowTest {

    @get:Rule val compose = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<MedReviewApplication>()
    private val day = 86_400_000L

    private fun english(content: @Composable () -> Unit): @Composable () -> Unit = {
        MyApplicationTheme(languageCode = "en") {
            CompositionLocalProvider(LocalStrings provides EnglishStrings) { content() }
        }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    /**
     * Until the screen has called back. The save resumes on the main thread after its database write, and a condition
     * that reads no UI state does not let that run (a semantics query idles the main thread; a plain variable does not),
     * so each check idles it first.
     */
    private fun waitForCallback(done: () -> Boolean) {
        compose.waitUntil(timeoutMillis = 10_000) { compose.waitForIdle(); done() }
    }

    /** A rated topic not due for another three weeks, or one never rated. */
    private fun insert(title: String, rated: Boolean): Long = runBlocking {
        val now = System.currentTimeMillis()
        val next = if (rated) now + 21 * day else now - day
        app.repository.insertUnit(
            StudyUnitEntity(
                title = title, studyType = "Topic", stability = if (rated) 30.0 else 1.0, state = if (rated) "Building" else "New",
                studiedAt = now - 40 * day, lastReviewedAt = if (rated) now - 9 * day else null, nextReviewAt = next,
                modelDueAt = next, reviewCount = if (rated) 3 else 0, memoryModel = "FSRS-6",
            )
        )
    }

    @Test
    fun `a topic not due yet is saved, then opened for review`() {
        val id = insert("Nephron physiology", rated = true)
        var reviewed: Long? = null
        var backs = 0
        compose.setContent(english {
            AddUnitScreen(repository = app.repository, unitId = id, onBack = { backs++ }, onReviewNow = { reviewed = it })
        })
        waitForText("Save and review now")
        waitForText("Nephron physiology")
        compose.onNode(hasSetTextAction() and hasText("Nephron physiology")).performTextReplacement("Nephron physiology, revised")
        compose.onNodeWithText("Save and review now").performScrollTo().assertIsEnabled().performClick()
        waitForCallback { reviewed != null }
        assertEquals(id, reviewed)
        assertEquals("the review replaces the page; it does not go back first", 0, backs)
        assertEquals("the edit is saved before the review opens", "Nephron physiology, revised",
            runBlocking { app.repository.getUnitById(id)!!.title })
    }

    @Test
    fun `a topic never rated offers its first rating instead`() {
        val id = insert("Renal tubular acidosis", rated = false)
        var reviewed: Long? = null
        compose.setContent(english {
            AddUnitScreen(repository = app.repository, unitId = id, onBack = {}, onReviewNow = { reviewed = it })
        })
        waitForText("Save and rate now")
        waitForText("Renal tubular acidosis") // the form is filled, so Save is enabled
        compose.onNodeWithText("Save and review now").assertDoesNotExist()
        compose.onNodeWithText("Save and rate now").performScrollTo().assertIsEnabled().performClick()
        waitForCallback { reviewed != null }
        assertEquals(id, reviewed)
    }

    @Test
    fun `opened from a review in progress, the page does not offer another`() {
        val id = insert("Glomerulonephritis", rated = true)
        compose.setContent(english { AddUnitScreen(repository = app.repository, unitId = id, onBack = {}, showReviewNow = false) })
        waitForText("Glomerulonephritis") // the topic has loaded, so the button would be there by now
        compose.onNodeWithText("Save and review now").assertDoesNotExist()
    }
}
