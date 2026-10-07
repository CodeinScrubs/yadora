package com.example.ui.add

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.MedReviewApplication
import com.example.data.local.entity.StudyUnitEntity
import com.example.ui.i18n.EnglishStrings
import com.example.ui.i18n.LocalStrings
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class RelatedTopicEntryTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<MedReviewApplication>()

    private fun insert(title: String, archived: Boolean = false, deleted: Boolean = false) = runBlocking {
        app.repository.insertUnit(StudyUnitEntity(title = title, studyType = "Topic", nextReviewAt = 1,
            archived = archived || deleted, deletedAt = if (deleted) 1 else null))
    }

    private fun screen(onOpen: (Long, Boolean) -> Unit = { _, _ -> }) {
        compose.setContent {
            MyApplicationTheme(languageCode = "en") {
                CompositionLocalProvider(LocalStrings provides EnglishStrings) {
                    AddUnitScreen(app.repository, unitId = null, onBack = {}, onOpenRelatedTopic = onOpen)
                }
            }
        }
    }

    private fun type(query: String) {
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement(query)
    }

    private fun waitFor(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun `typing a Persian title finds an existing scope and opens it without inserting a topic`() {
        val id = insert("درمان آسم")
        var opened: Pair<Long, Boolean>? = null
        screen { key, archived -> opened = key to archived }
        type("اسم")
        waitFor("درمان آسم")
        compose.onNodeWithText("درمان آسم").performScrollTo().performClick()
        assertEquals(id to false, opened)
        assertEquals(1, runBlocking { app.database.studyUnitDao().getAllActiveOnce().size })
    }

    @Test fun `archived matches are labelled and never silently restored and deleted ones are excluded`() {
        val id = insert("Asthma archived", archived = true)
        insert("Asthma deleted", deleted = true)
        var opened: Pair<Long, Boolean>? = null
        screen { key, archived -> opened = key to archived }
        type("asthma")
        waitFor("Archived · open")
        compose.onNodeWithText("Asthma deleted").assertDoesNotExist()
        compose.onNodeWithText("Asthma archived").performScrollTo().performClick()
        assertEquals(id to true, opened)
        assertTrue(runBlocking { app.repository.getUnitById(id)!!.archived })
    }

    @Test fun `matches refresh when another action adds a topic and disappear when query changes`() {
        screen()
        type("asthma")
        compose.onNodeWithText("Related existing topics").assertDoesNotExist()
        insert("Asthma treatment")
        waitFor("Asthma treatment")
        type("anemia")
        compose.onNodeWithText("Asthma treatment").assertDoesNotExist()
    }
}
