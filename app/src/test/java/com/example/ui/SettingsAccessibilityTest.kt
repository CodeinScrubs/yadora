package com.example.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.ui.i18n.EnglishStrings
import com.example.ui.i18n.LocalStrings
import com.example.ui.settings.SettingsScreen
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Each settings switch is one control with its label: a screen reader announces "Reminder Sound, switch, on", and the
 * whole row toggles. The switches were bare, nameless nodes beside their labels (an outside audit, 2026-09-30, and
 * "NAF" in the owner's Samsung's accessibility tree, 2026-10-02).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsAccessibilityTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun `a settings switch is named by its label and the whole row toggles it`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("medreview_settings", Context.MODE_PRIVATE)
        compose.setContent {
            MyApplicationTheme(languageCode = "en") {
                CompositionLocalProvider(LocalStrings provides EnglishStrings) { SettingsScreen(onBack = {}) }
            }
        }
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText(EnglishStrings.settings).fetchSemanticsNodes().isNotEmpty() }
        for (label in listOf(EnglishStrings.reminderSound, EnglishStrings.vibration, "Ring like an alarm clock", "Fit the model to my reviews")) {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(label))
            assertEquals("'$label' is one toggleable control", 1, compose.onAllNodes(hasText(label) and isToggleable()).fetchSemanticsNodes().size)
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(EnglishStrings.reminderSound))
        compose.onNode(hasText(EnglishStrings.reminderSound) and isToggleable()).performClick()
        assertFalse("tapping the row turns the sound off", prefs.getBoolean("sound_enabled", true))
    }
}
