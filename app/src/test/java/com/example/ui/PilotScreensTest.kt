package com.example.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.example.MedReviewApplication
import com.example.data.local.entity.StudyUnitEntity
import com.example.ui.i18n.AppStrings
import com.example.ui.i18n.EnglishStrings
import com.example.ui.i18n.LocalStrings
import com.example.ui.i18n.PersianStrings
import com.example.ui.review.ReviewSessionScreen
import com.example.ui.settings.SettingsScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.today.TodayScreen
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The pilot's screens compose, say what they should, and lead where they should — in English and in Persian (RTL): the
 * "Next up · weakest first" list on a finished Today (it replaced the Review ahead session, 2026-10-09), the optional
 * "How did you review?" row with its question score, and "Share research data" in Settings. Screenshots are written to
 * build/pilot-screens/ when Roborazzi records (-Proborazzi.test.record=true).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class PilotScreensTest {

    @get:Rule val compose = createComposeRule()

    private val day = 86_400_000L

    /** Three rated topics, none due today: Today is finished, and all three are in its "next up" list. */
    private fun seed(): Pair<MedReviewApplication, Map<String, Long>> = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MedReviewApplication>()
        val now = System.currentTimeMillis()
        val ids = listOf("Heart failure drugs" to 0.8, "Nephrotic syndrome" to 3.0, "Beta-lactams" to 12.0).mapIndexed { i, (title, s) ->
            title to app.repository.insertUnit(
                StudyUnitEntity(
                    title = title, studyType = "Topic", stability = s, difficulty = 5.0, retrievability = 0.9,
                    state = "Building", studiedAt = now - (10 + i) * day, lastReviewedAt = now - 4 * day,
                    nextReviewAt = now + (3 + i) * day, modelDueAt = now + (3 + i) * day,
                    currentIntervalDays = 7.0, reviewCount = 2, memoryModel = "FSRS-6",
                )
            )
        }.toMap()
        app to ids
    }

    private fun screen(strings: AppStrings, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            MyApplicationTheme(languageCode = strings.languageCode) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (strings.languageCode == "fa") LayoutDirection.Rtl else LayoutDirection.Ltr,
                    LocalStrings provides strings,
                ) { content() }
            }
        }
    }

    private fun capture(name: String) {
        compose.onRoot().captureRoboImage(filePath = "build/pilot-screens/$name.png")
    }

    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `a finished Today lists next up, weakest first, and a tap opens that topic`() {
        val (app, ids) = seed()
        var opened: Pair<Long, String>? = null
        screen(EnglishStrings) {
            TodayScreen(
                repository = app.repository, onNavigateToAdd = {}, onNavigateToReview = {}, onNavigateToEdit = {},
                onNavigateToSettings = {}, onReviewFromToday = { id, kind -> opened = id to kind },
            )
        }
        waitForText("NEXT UP · WEAKEST FIRST")
        waitForText("Beta-lactams")
        capture("today_next_up_en")
        // Weakest first: stability 0.8 four days ago is the lowest predicted recall of the three.
        val tops = listOf("Heart failure drugs", "Nephrotic syndrome", "Beta-lactams")
            .map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }
        assertEquals("weakest first", tops.sorted(), tops)
        compose.onNodeWithText("Heart failure drugs").performClick()
        assertEquals("a tap opens that topic, as an early review", ids.getValue("Heart failure drugs") to "AHEAD", opened)
    }

    @Test
    fun `a finished Today lists next up in Persian`() {
        val (app, _) = seed()
        screen(PersianStrings) {
            TodayScreen(repository = app.repository, onNavigateToAdd = {}, onNavigateToReview = {}, onNavigateToEdit = {}, onNavigateToSettings = {})
        }
        waitForText("بعدی‌ها، ضعیف‌ترین اول")
        capture("today_next_up_fa")
    }

    @Test
    fun `a next-up topic opens with the optional method row and score`() {
        val (app, ids) = seed()
        screen(EnglishStrings) {
            ReviewSessionScreen(
                repository = app.repository, unitId = ids.getValue("Heart failure drugs"), kind = "AHEAD",
                onNavigateToEdit = {}, onFinish = {},
            )
        }
        waitForText("Heart failure drugs")
        waitForText("How did you review? (optional)")
        compose.onNodeWithText("Questions").performClick()
        waitForText("out of")
        capture("review_next_up_method_en")
    }

    @Test
    fun `the method row and score read right to left in Persian`() {
        val (app, ids) = seed()
        screen(PersianStrings) {
            ReviewSessionScreen(
                repository = app.repository, unitId = ids.getValue("Heart failure drugs"), kind = "AHEAD",
                onNavigateToEdit = {}, onFinish = {},
            )
        }
        waitForText("چطور مرور کردی؟ (اختیاری)")
        compose.onNodeWithText("تست و سؤال").performClick()
        waitForText("از")
        capture("review_next_up_method_fa")
    }

    @Test
    fun `settings shows the research id and the share button`() {
        seed()
        screen(EnglishStrings) { SettingsScreen(onBack = {}) }
        // Settings is a LazyColumn: off-screen rows are not composed until the list scrolls to them.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Share research data"))
        capture("settings_share_en")
        assertTrue(
            "the pseudonymous id is shown next to the button",
            compose.onAllNodes(hasText("Your research ID: YD-", substring = true)).fetchSemanticsNodes().isNotEmpty(),
        )
    }
}
