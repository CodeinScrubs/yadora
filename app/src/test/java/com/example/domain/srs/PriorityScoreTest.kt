package com.example.domain.srs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the single source-of-truth queue/redistribution ordering score. */
class PriorityScoreTest {

    private val now = 1_000_000_000_000L
    private val day = 86_400_000L

    @Test fun high_yield_dominates_ordinary_items() {
        val hy = MedScheduler.priorityScore(highYield = true, modelDueAt = now, now = now)
        val normal = MedScheduler.priorityScore(highYield = false, modelDueAt = now - 2 * day, now = now)
        assertTrue("high-yield ($hy) outranks a non-high-yield item two days overdue ($normal)", hy > normal)
        assertEquals("Important is worth 20 days of lateness", 100.0, hy - MedScheduler.priorityScore(false, now, now), 1e-9)
    }

    @Test fun more_overdue_raises_the_score_but_future_due_does_not() {
        val onTime = MedScheduler.priorityScore(false, now, now)
        val overdue5 = MedScheduler.priorityScore(false, now - 5 * day, now)
        val future = MedScheduler.priorityScore(false, now + 5 * day, now)
        assertEquals("5 days overdue adds 25", 25.0, overdue5 - onTime, 1e-9)
        assertEquals("not-yet-due items get no overdue bonus", 0.0, future, 1e-9)
    }

    /**
     * The queue is Important first, then the most overdue, and NOTHING about a topic's past.
     *
     * The score used to add +80/+40/+20 for NeedsRelearn/Learning/Building and +10 per lapse (up to 5). With
     * the daily limit binding, that spent the day's slots on the topics a review strengthens least, while
     * stronger ones slid further past due: in five simulated backlogs (tools/pilot/experiments.py) it knew
     * less through the year every time (0.12-0.55 points). A topic that just lapsed and one that never did,
     * due on the same day, are now exactly as urgent; the one due EARLIER goes first.
     */
    @Test fun a_topics_state_and_lapse_history_do_not_reorder_the_queue() {
        fun unit(id: Long, state: String, lapses: Int, dueDaysAgo: Int) = com.example.data.local.entity.StudyUnitEntity(
            id = id, title = "t$id", studyType = "Topic", state = state, lapseCount = lapses, reviewCount = 3,
            stability = 5.0, difficulty = 5.0, studiedAt = now - 60 * day,
            nextReviewAt = now - dueDaysAgo * day, modelDueAt = now - dueDaysAgo * day,
        )
        val strugglingDueYesterday = unit(1, "NeedsRelearn", lapses = 5, dueDaysAgo = 1)
        val strongDueLastWeek = unit(2, "Strong", lapses = 0, dueDaysAgo = 7)
        val learningDueThreeDaysAgo = unit(3, "Learning", lapses = 2, dueDaysAgo = 3)
        val plan = com.example.ui.today.DailyPlan.plan(
            due = listOf(strugglingDueYesterday, learningDueThreeDaysAgo, strongDueLastWeek),
            reviewsDoneToday = 0, dailyLimit = 2, now = now,
        )
        assertEquals("the longest overdue first, whatever its history", listOf(2L, 3L), plan.reviews.map { it.id })
        assertEquals(1, plan.heldBack)
    }

    @Test fun a_neglected_topic_still_keeps_rising() {
        // The overdue term is uncapped so nothing can starve: a topic nobody reviews keeps climbing until
        // it is actually seen, past any importance bonus.
        val justDue = MedScheduler.priorityScore(false, now, now)
        val longNeglected = MedScheduler.priorityScore(false, now - 90 * day, now)
        assertTrue("90 days overdue outranks just-due", longNeglected > justDue)
        val importantJustDue = MedScheduler.priorityScore(true, now, now)
        assertTrue("and, past 20 days, an Important topic that is merely due", longNeglected > importantJustDue)
    }

    @Test fun score_is_pure_and_deterministic() {
        val a = MedScheduler.priorityScore(true, now - day, now)
        val b = MedScheduler.priorityScore(true, now - day, now)
        assertEquals(a, b, 0.0)
    }

    /**
     * Deferring must not buy a quieter queue.
     *
     * "Not today" moves the EFFECTIVE date to tomorrow but leaves the model's own date alone. When
     * ranking used the effective date, a user who tapped it every morning kept every topic at zero
     * overdue pressure indefinitely, while a user who simply ignored the notification watched theirs
     * climb. The app was rewarding active procrastination with a shorter-looking backlog. Memory does
     * not care that you postponed, so the ordering is taken from the model's date.
     */
    @Test
    fun `deferring daily does not hide how overdue a topic really is`() {
        val day = 86400000L
        val now = System.currentTimeMillis()

        // Same topic, same model date a month ago. One was ignored; one was deferred every day.
        val ignored = MedScheduler.priorityScore(
            highYield = false,
            modelDueAt = now - 30 * day, now = now, effectiveDueAt = now - 30 * day,
        )
        val deferredDaily = MedScheduler.priorityScore(
            highYield = false,
            modelDueAt = now - 30 * day, now = now, effectiveDueAt = now, // reset to "due today"
        )
        assertEquals("a deferral cannot lower real urgency", ignored, deferredDaily, 1e-9)
        assertTrue("and a month of debt must actually register", deferredDaily > 100.0)
    }

    /** A row predating modelDueAt (never backfilled, so 0) must fall back, not look infinitely overdue. */
    @Test
    fun `a missing model date falls back to the effective date`() {
        val day = 86400000L
        val now = System.currentTimeMillis()
        val fallback = MedScheduler.priorityScore(
            highYield = false,
            modelDueAt = 0L, now = now, effectiveDueAt = now - 2 * day,
        )
        val direct = MedScheduler.priorityScore(
            highYield = false,
            modelDueAt = now - 2 * day, now = now,
        )
        assertEquals("epoch-0 must not be read as 1970", direct, fallback, 1e-9)
    }

    /**
     * An understanding repair left waiting must build urgency like an overdue memory review.
     *
     * The repair deadline is the scheduler's own date, not a user deferral, yet ranking used to read only
     * the memory model's date. A topic rated Good + Partial (memory 50 days, repair 3 days) and then left
     * for 20 days scored no lateness at all, because its memory date was still a month away, while Today
     * listed it as 17 days overdue.
     */
    @Test
    fun `an unanswered understanding repair counts as overdue, and a deferral still buys nothing`() {
        val reviewedAt = now - 20 * day
        val memoryDue = reviewedAt + 50 * day
        val repairDue = reviewedAt + 3 * day
        val memoryOnly = MedScheduler.priorityScore(false, modelDueAt = memoryDue, now = now)
        val withRepair = MedScheduler.priorityScore(
            false, modelDueAt = memoryDue, now = now,
            effectiveDueAt = repairDue, understandingDueAt = repairDue,
        )
        assertEquals("17 days of unanswered repair add 85 points", 85.0, withRepair - memoryOnly, 1e-6)

        val deferredToTomorrow = MedScheduler.priorityScore(
            false, modelDueAt = memoryDue, now = now,
            effectiveDueAt = now + day, understandingDueAt = repairDue,
        )
        assertEquals("tapping Not today must not hide the owed repair", withRepair, deferredToTomorrow, 1e-9)

        val repairNotYetDue = MedScheduler.priorityScore(
            false, modelDueAt = memoryDue, now = now,
            effectiveDueAt = now + 2 * day, understandingDueAt = now + 2 * day,
        )
        assertEquals("a repair that is not due yet adds nothing", memoryOnly, repairNotYetDue, 1e-9)
    }
}
