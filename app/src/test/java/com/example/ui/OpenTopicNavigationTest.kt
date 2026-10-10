package com.example.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextInput
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A tap on a topic's own notification opens THAT topic to rate (TopicNotifications, MainActivity.EXTRA_OPEN_TOPIC),
 * also when the review screen of another topic is already open: the learner pulls the shade down in the middle of a
 * review and taps the next topic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OpenTopicNavigationTest {

    @get:Rule val compose = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<MedReviewApplication>()

    private fun topic(title: String): Long = runBlocking {
        val now = System.currentTimeMillis()
        app.repository.insertUnit(
            StudyUnitEntity(
                title = title, studyType = "Topic", stability = 3.0, difficulty = 5.0, retrievability = 0.9, state = "Building",
                studiedAt = now - 20 * 86_400_000L, lastReviewedAt = now - 5 * 86_400_000L, nextReviewAt = now - 86_400_000L,
                modelDueAt = now - 86_400_000L, currentIntervalDays = 3.0, reviewCount = 2, memoryModel = "FSRS-6",
            )
        )
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `a topic notification opens its own topic even over another topic's review`() {
        val first = topic("Nephrotic syndrome")
        val second = topic("Beta-lactams")
        val tap = mutableStateOf<OpenTopic?>(null)
        compose.setContent {
            MyApplicationTheme(languageCode = "en") {
                CompositionLocalProvider(LocalStrings provides EnglishStrings) {
                    MedReviewApp(app.repository, openTopic = tap.value)
                }
            }
        }
        tap.value = OpenTopic(first, 1)
        compose.waitUntil(10_000) { shown(EnglishStrings.ratingGoodMeaning) && shown("Nephrotic syndrome") }

        tap.value = OpenTopic(second, 2)
        compose.waitUntil(10_000) { shown("Beta-lactams") }
        compose.waitForIdle()
        assertTrue("the tapped topic is the one on screen", shown("Beta-lactams"))
        assertTrue("not the one that was open before", !shown("Nephrotic syndrome"))
    }

    /**
     * "Review now" on the reminder, the alarm or the widget opens Today. It used to pop everything above Today to get
     * there, so an Add form being filled in was thrown away (a production review, 2026-10-10). Today now goes on top of
     * it, and Back returns to the form as it was.
     */
    @Test
    fun `Review now from a reminder keeps a new topic being typed`() {
        val signal = mutableStateOf(0)
        var back: androidx.activity.OnBackPressedDispatcher? = null
        compose.setContent {
            back = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            MyApplicationTheme(languageCode = "en") {
                CompositionLocalProvider(LocalStrings provides EnglishStrings) {
                    MedReviewApp(app.repository, openReviewSignal = signal.value)
                }
            }
        }
        compose.onNodeWithContentDescription(EnglishStrings.addNewTopic).performClick()
        compose.waitUntil(10_000) { shown(EnglishStrings.topicTitleLabel) }
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("Hyponatremia draft")
        compose.waitUntil(10_000) { shown("Hyponatremia draft") }

        signal.value = 1
        compose.waitUntil(10_000) { !shown(EnglishStrings.topicTitleLabel) }
        assertTrue("Today is shown", compose.onAllNodesWithContentDescription(EnglishStrings.addNewTopic).fetchSemanticsNodes().isNotEmpty())

        compose.runOnUiThread { back!!.onBackPressed() }
        compose.waitUntil(10_000) { shown(EnglishStrings.topicTitleLabel) }
        assertTrue("the draft is still there", shown("Hyponatremia draft"))
    }

    @Test
    fun `tapping the topic already being rated keeps the answers chosen so far`() {
        val id = topic("Nephrotic syndrome")
        val tap = mutableStateOf<OpenTopic?>(null)
        compose.setContent {
            MyApplicationTheme(languageCode = "en") {
                CompositionLocalProvider(LocalStrings provides EnglishStrings) {
                    MedReviewApp(app.repository, openTopic = tap.value)
                }
            }
        }
        tap.value = OpenTopic(id, 1)
        compose.waitUntil(10_000) { shown(EnglishStrings.ratingGoodMeaning) }
        compose.onNodeWithText(EnglishStrings.ratingGoodMeaning).performScrollTo().performClick()
        compose.waitUntil(10_000) { shown(EnglishStrings.understandingNowQuestion) }

        tap.value = OpenTopic(id, 2)
        compose.waitForIdle()
        assertTrue("still on the understanding step", shown(EnglishStrings.understandingNowQuestion))
    }

}
