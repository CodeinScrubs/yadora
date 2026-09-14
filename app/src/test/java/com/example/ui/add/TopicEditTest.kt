package com.example.ui.add

import com.example.data.local.entity.StudyUnitEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Save on the Edit screen must never undo what happened to the topic after the form was filled, and
 * must change a date only when the user changed it.
 */
class TopicEditTest {

    private val day = 86_400_000L
    private val t0 = 1_800_000_000_000L

    private fun topic() = StudyUnitEntity(
        id = 7, title = "Appendicitis", studyType = "Topic", notes = "RLQ pain", source = "", highYield = false,
        state = "Building", stability = 9.0, difficulty = 5.0, studiedAt = t0, lastReviewedAt = t0 + 3 * day,
        nextReviewAt = t0 + 12 * day, modelDueAt = t0 + 12 * day, currentIntervalDays = 9.0, reviewCount = 2,
        memoryModel = "FSRS-6",
    )

    private fun formOf(u: StudyUnitEntity) = TopicEdit.Form(
        title = u.title, subjectId = u.subjectId, systemId = u.systemId, studyType = u.studyType,
        recallPrompt = u.recallPrompt, keyPoints = u.keyPoints, notes = u.notes ?: "", source = u.source ?: "", highYield = u.highYield,
        studiedAt = u.studiedAt, nextReviewAt = u.nextReviewAt,
    )

    @Test
    fun `saving after a review that happened behind the form keeps the review`() {
        val loaded = topic()
        // A reminder's "Review now" opened a session on top of the Edit screen, and the topic was rated.
        val fresh = loaded.copy(
            stability = 31.0, difficulty = 4.6, state = "Strong", reviewCount = 3, lastReviewedAt = t0 + 12 * day,
            nextReviewAt = t0 + 43 * day, modelDueAt = t0 + 43 * day, currentIntervalDays = 31.0,
        )
        val plan = TopicEdit.plan(loaded, fresh, formOf(loaded).copy(title = "Acute appendicitis"), t0 + 13 * day)

        assertEquals("the form's own edit is applied", "Acute appendicitis", plan.updated.title)
        assertEquals("the review's stability survives", 31.0, plan.updated.stability, 0.0)
        assertEquals("and its count", 3, plan.updated.reviewCount)
        assertEquals("and its dates", fresh.nextReviewAt, plan.updated.nextReviewAt)
        assertEquals(fresh.modelDueAt, plan.updated.modelDueAt)
        assertEquals(fresh.lastReviewedAt, plan.updated.lastReviewedAt)
        assertNull("no deferral invented from the stale date", plan.updated.deferredUntil)
        assertFalse(plan.nextDateChanged)
        assertFalse(plan.studyDateChanged)
        assertFalse(plan.tightenForImportant)
    }

    @Test
    fun `edited key points are saved onto the row as it is now`() {
        val loaded = topic()
        val fresh = loaded.copy(stability = 31.0, reviewCount = 3)
        val plan = TopicEdit.plan(loaded, fresh, formOf(loaded).copy(keyPoints = "RLQ pain\nAlvarado score"), t0 + 13 * day)
        assertEquals("RLQ pain\nAlvarado score", plan.updated.keyPoints)
        assertEquals("the review behind the form survives", 31.0, plan.updated.stability, 0.0)
        assertNull("clearing them saves none", TopicEdit.plan(loaded, fresh.copy(keyPoints = "x"), formOf(loaded), t0).updated.keyPoints)
    }

    @Test
    fun `a deferral written behind the form survives a save that did not touch the date`() {
        val loaded = topic()
        val fresh = loaded.copy(nextReviewAt = t0 + 13 * day, deferredUntil = t0 + 13 * day) // the notification's "Not today"
        val plan = TopicEdit.plan(loaded, fresh, formOf(loaded), t0 + 12 * day)
        assertEquals(t0 + 13 * day, plan.updated.nextReviewAt)
        assertEquals(t0 + 13 * day, plan.updated.deferredUntil)
    }

    @Test
    fun `a next-review date the user changed is a deferral, never the model's date`() {
        val loaded = topic()
        val plan = TopicEdit.plan(loaded, loaded, formOf(loaded).copy(nextReviewAt = t0 + 20 * day), t0 + 5 * day)
        assertTrue(plan.nextDateChanged)
        assertEquals(t0 + 20 * day, plan.updated.nextReviewAt)
        assertEquals(t0 + 20 * day, plan.updated.deferredUntil)
        assertEquals("the model's own date is untouched", loaded.modelDueAt, plan.updated.modelDueAt)
    }

    @Test
    fun `a changed study date recomputes the schedule, rated or not`() {
        val rated = topic()
        val ratedPlan = TopicEdit.plan(rated, rated, formOf(rated).copy(studiedAt = t0 - 2 * day), t0)
        assertTrue(ratedPlan.studyDateChanged)
        assertEquals(t0 - 2 * day, ratedPlan.updated.studiedAt)

        val unrated = topic().copy(reviewCount = 0, lastReviewedAt = null, nextReviewAt = t0, modelDueAt = t0)
        val unratedPlan = TopicEdit.plan(unrated, unrated, formOf(unrated).copy(studiedAt = t0 - 2 * day), t0)
        assertTrue("an unrated topic is due on its study date, so it must be recomputed too", unratedPlan.studyDateChanged)
        assertNull("and no deferral is recorded, so the due date follows the study date", unratedPlan.updated.deferredUntil)
    }

    @Test
    fun `switching Important on tightens only from real reviews and only without a date change`() {
        val loaded = topic()
        val fresh = loaded.copy(stability = 31.0, reviewCount = 3)
        assertTrue(TopicEdit.plan(loaded, fresh, formOf(loaded).copy(highYield = true), t0).tightenForImportant)
        assertFalse("a date the user picked wins",
            TopicEdit.plan(loaded, fresh, formOf(loaded).copy(highYield = true, nextReviewAt = t0 + 30 * day), t0).tightenForImportant)
        assertFalse("already important: nothing to tighten",
            TopicEdit.plan(loaded, fresh.copy(highYield = true), formOf(loaded).copy(highYield = true), t0).tightenForImportant)
        val unrated = loaded.copy(reviewCount = 0, lastReviewedAt = null)
        assertFalse("no reviews, no interval to tighten",
            TopicEdit.plan(unrated, unrated, formOf(unrated).copy(highYield = true), t0).tightenForImportant)
    }
}
