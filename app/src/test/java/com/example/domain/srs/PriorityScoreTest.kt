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
     * The state LABEL and the lapse COUNT never reorder the queue; only the memory state does, through the review
     * value (next tests). Here no topic has a last review, so lateness alone decides.
     *
     * The score used to add +80/+40/+20 for NeedsRelearn/Learning/Building and +10 per lapse (up to 5). With
     * the daily limit binding, that spent the day's slots on the topics a review strengthens least, while
     * stronger ones slid further past due: in five simulated backlogs (tools/pilot/experiments.py) it knew
     * less through the year every time (0.12-0.55 points).
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

    private fun memory(stability: Double, lastReviewDaysAgo: Int, difficulty: Double = 5.0) = MedScheduler.QueueMemory(
        stability, difficulty, now - lastReviewDaysAgo * day, MedScheduler.MemoryModel.FSRS_6, 0L,
    )

    /**
     * The review value (2026-09-29): at the same lateness, the topic a review would strengthen most comes first.
     * A young topic's next successful review multiplies its stability; a mature one's barely moves it, and FSRS-6's
     * flat curve lets it wait. In simulated backlogs this order knew more than lateness alone at the one-year quiz
     * and lifted the weakest tenth of topics (tools/pilot/experiments.py, docs/RESEARCH.md 2.4).
     */
    @Test fun at_equal_lateness_the_topic_a_review_helps_most_comes_first() {
        val young = MedScheduler.priorityScore(false, now - day, now, memory = memory(stability = 3.0, lastReviewDaysAgo = 4))
        val mature = MedScheduler.priorityScore(false, now - day, now, memory = memory(stability = 120.0, lastReviewDaysAgo = 121))
        assertTrue("young ($young) before mature ($mature)", young > mature)
        val value = MedScheduler.reviewValue(memory(3.0, 4), now)
        assertTrue("the value is a finite positive number, $value", value.isFinite() && value > 0.0)
    }

    /** Lateness is linear and uncapped: a neglected mature topic overtakes a young one, so nothing starves. */
    @Test fun a_long_neglected_mature_topic_overtakes_a_young_one_just_due() {
        val youngJustDue = MedScheduler.priorityScore(false, now, now, memory = memory(stability = 3.0, lastReviewDaysAgo = 3))
        val matureLongOverdue = MedScheduler.priorityScore(false, now - 60 * day, now, memory = memory(120.0, 180))
        assertTrue("60 days overdue ($matureLongOverdue) beats a young topic just due ($youngJustDue)", matureLongOverdue > youngJustDue)
    }

    /**
     * The value is CAPPED at 40 days of lateness. Without the cap a very small stability (a personal weight set with
     * a steep gain, a history of lapses) could put a topic ahead of others hundreds of days more overdue. With it,
     * nothing waits more than 40 days behind a topic the queue would otherwise have served after it.
     */
    @Test fun the_review_value_is_capped_at_forty_days_of_lateness() {
        val extreme = memory(stability = 0.01, lastReviewDaysAgo = 1)
        assertTrue("this state's raw value exceeds the cap", MedScheduler.QUEUE_VALUE_WEIGHT * MedScheduler.reviewValue(extreme, now) > MedScheduler.QUEUE_VALUE_CAP)
        val capped = MedScheduler.priorityScore(false, now, now, memory = extreme)
        assertEquals("it adds exactly the cap", MedScheduler.QUEUE_VALUE_CAP, capped, 1e-9)
        assertEquals("the cap is 40 days of lateness", 40.0 * 5.0, MedScheduler.QUEUE_VALUE_CAP, 1e-9)
        val waited41 = MedScheduler.priorityScore(false, now - 41 * day, now, memory = memory(stability = 400.0, lastReviewDaysAgo = 441))
        assertTrue("41 days more overdue always goes first ($waited41 > $capped)", waited41 > capped)
    }

    /**
     * Important keeps its meaning, 20 days of lateness, beside the value (up to 40). At equal lateness and equal memory
     * the Important topic comes first; an unmarked topic can come first only on a review value worth more than 100
     * points more, and never by more than the cap allows.
     */
    @Test fun important_still_counts_twenty_days_beside_the_review_value() {
        val same = memory(stability = 6.0, lastReviewDaysAgo = 9)
        val marked = MedScheduler.priorityScore(true, now - 3 * day, now, memory = same)
        val unmarked = MedScheduler.priorityScore(false, now - 3 * day, now, memory = same)
        assertEquals("the same topic, marked Important, scores exactly 100 more", 100.0, marked - unmarked, 1e-9)
        val importantMature = MedScheduler.priorityScore(true, now - 3 * day, now, memory = memory(120.0, 123))
        val unmarkedExtreme = MedScheduler.priorityScore(false, now - 3 * day, now, memory = memory(0.01, 1))
        assertTrue("the most an unmarked topic of equal lateness can lead by is the cap less 100",
            unmarkedExtreme - importantMature <= MedScheduler.QUEUE_VALUE_CAP - 100.0 + 1e-9)
    }

    /** The value reads the topic's own model: a topic still on FSRS-5 is scored on FSRS-5's curve and never throws. */
    @Test fun the_value_is_read_on_the_topics_own_model_and_never_fails() {
        val fsrs5 = MedScheduler.QueueMemory(5.0, 5.0, now - 6 * day, MedScheduler.MemoryModel.FSRS_5, 0L)
        assertTrue(MedScheduler.reviewValue(fsrs5, now).let { it.isFinite() && it >= 0.0 })
        val unknownSet = MedScheduler.QueueMemory(5.0, 5.0, now - 6 * day, MedScheduler.MemoryModel.FSRS_6, 987654L)
        assertEquals("an unknown weight set reads the defaults", MedScheduler.reviewValue(unknownSet.copy(parameterSetId = 0L), now),
            MedScheduler.reviewValue(unknownSet, now), 1e-12)
        val zero = MedScheduler.QueueMemory(0.0, 5.0, now - 6 * day, MedScheduler.MemoryModel.FSRS_6, 0L)
        assertTrue("a zero stability is floored, not divided by", MedScheduler.reviewValue(zero, now).isFinite())
    }

    /** A deferral changes neither lateness nor the review value: it still buys no quieter queue. */
    @Test fun a_deferral_does_not_change_the_value_either() {
        val m = memory(stability = 6.0, lastReviewDaysAgo = 10)
        val ignored = MedScheduler.priorityScore(false, now - 4 * day, now, effectiveDueAt = now - 4 * day, memory = m)
        val deferred = MedScheduler.priorityScore(false, now - 4 * day, now, effectiveDueAt = now + day, memory = m)
        assertEquals(ignored, deferred, 1e-12)
    }
}
