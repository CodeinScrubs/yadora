package com.example.data

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import com.example.MedReviewApplication
import com.example.data.local.entity.MemoryParameterSetEntity
import com.example.data.local.entity.StudyUnitEntity
import com.example.domain.srs.Fsrs6Optimizer
import com.example.domain.srs.Fsrs6Parameters
import com.example.domain.srs.MedScheduler
import com.example.ui.MedReviewApp
import com.example.ui.i18n.EnglishStrings
import com.example.ui.i18n.LocalStrings
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.today.DailyPlan
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Today's share is ordered by each topic's review value on its OWN weight set (MedScheduler.reviewValue). In a new
 * process nothing had loaded the personal sets until a review screen opened, so a topic on one was ordered on the
 * published defaults by Today, the reminders and the widget (a production review, 2026-10-10). What only reads the sets
 * now loads them first ([com.example.data.repository.MedReviewRepository.ensureMemoryModelLoaded]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MemoryModelLoadTest {

    @get:Rule val compose = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<MedReviewApplication>()
    private val day = 86_400_000L

    /** A set that forgets on a much steeper curve than the defaults (w20, the decay, 0.15 -> 0.6). */
    private val personal = Fsrs6Parameters.DEFAULT_WEIGHTS.copyOf().also { it[20] = 0.6 }

    @Before fun freshProcess() {
        MedScheduler.activeParameterSet = MedScheduler.DEFAULT_PARAMETER_SET
        MedScheduler.knownParameterSets = emptyMap()
        MedScheduler.parameterSetsLoaded = false
    }

    @After fun reset() = freshProcess()

    private suspend fun activeSet(): Long = app.database.memoryParameterSetDao().insert(
        MemoryParameterSetEntity(
            createdAt = 1L, status = MemoryParameterSetEntity.ACTIVE, weights = Fsrs6Optimizer.encode(personal),
            comparedWithSetId = 0, availableReviews = 9_000, trainReviews = 7_000, testReviews = 2_000,
            currentLogLoss = 0.36, candidateLogLoss = 0.33, currentRmseBins = 0.09, candidateRmseBins = 0.05,
            currentAuc = 0.70, candidateAuc = 0.73, zScore = 3.0, activatedAt = 1L,
        )
    )

    private suspend fun topicOn(set: Long, now: Long): StudyUnitEntity {
        val id = app.repository.insertUnit(
            StudyUnitEntity(
                title = "Nephrotic syndrome", studyType = "Topic", stability = 5.0, difficulty = 5.0, retrievability = 0.9,
                state = "Building", studiedAt = now - 30 * day, lastReviewedAt = now - 10 * day, nextReviewAt = now - 5 * day,
                modelDueAt = now - 5 * day, currentIntervalDays = 5.0, reviewCount = 3, memoryModel = "FSRS-6",
                parameterSetId = set,
            )
        )
        return app.database.studyUnitDao().getUnitById(id)!!
    }

    @Test fun `today's plan reads a topic on its own weight set in a new process`() = runBlocking {
        val now = System.currentTimeMillis()
        val set = activeSet()
        val unit = topicOn(set, now)
        val onDefaults = DailyPlan.priority(unit, now)

        app.repository.todayPlan(50, now)

        assertTrue("the plan loaded the weight sets", MedScheduler.parameterSetsLoaded)
        assertEquals(set, MedScheduler.activeParameterSet.id)
        val onOwnSet = DailyPlan.priority(unit, now)
        assertNotEquals("the set changes this topic's urgency, so the test can tell them apart", onDefaults, onOwnSet, 1e-9)
        app.repository.refreshMemoryModel()
        assertEquals("the same urgency a review screen's refresh gives", DailyPlan.priority(unit, now), onOwnSet, 0.0)
    }

    @Test fun `the Today screen loads the weight sets before it orders its list`() {
        val now = System.currentTimeMillis()
        val set = runBlocking { activeSet().also { topicOn(it, now) } }
        compose.setContent {
            MyApplicationTheme(languageCode = "en") {
                CompositionLocalProvider(LocalStrings provides EnglishStrings) { MedReviewApp(app.repository) }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Nephrotic syndrome").fetchSemanticsNodes().isNotEmpty() }
        assertTrue("Today loaded the weight sets", MedScheduler.parameterSetsLoaded)
        assertTrue("and holds the topic's own set", MedScheduler.activeParameterSet.id == set || set in MedScheduler.knownParameterSets)
    }

    @Test fun `once loaded, a reading path does not change the set a review is using`() = runBlocking {
        val now = System.currentTimeMillis()
        val set = activeSet()
        topicOn(set, now)
        app.repository.refreshMemoryModel()
        // The daily refit adopts another set while a review screen is open: only the next refresh may switch to it.
        app.database.memoryParameterSetDao().retireActive(now)
        app.repository.todayPlan(50, now)
        assertEquals("the plan did not switch the set under the open review", set, MedScheduler.activeParameterSet.id)
    }
}
