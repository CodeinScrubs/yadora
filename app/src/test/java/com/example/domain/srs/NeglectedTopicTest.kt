package com.example.domain.srs

import com.example.domain.model.MemoryRating
import com.example.domain.model.UnderstandingRating
import com.example.ui.today.TodayBuckets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE NEGLECT SCENARIO: a topic falls due and the user does nothing about it.
 *
 * Not for an hour — for a day, a week, a term, a year, a decade. They ignore every reminder, never
 * open the app, and then one day come back. This is the single most likely real-world path in a
 * study app, and it is the one where a scheduler can quietly do something absurd: hand back an
 * interval of ten years, collapse to zero, divide by an elapsed time it never expected, or throw
 * from deep inside the model where the failure surfaces as a blank screen.
 *
 * Everything here is the PRODUCT layer (MedScheduler), because that is what the app actually calls.
 * The rule being enforced is: no matter how long a topic is neglected, and whatever the user
 * eventually answers, the result must be finite, bounded, ordered sensibly, and never throw.
 */
class NeglectedTopicTest {

    private val m6 = MedScheduler.MemoryModel.FSRS_6

    /** A day, three days, a week, a fortnight, a month, a term, a year, five years, a lifetime. */
    private val neglect = listOf(1.0, 3.0, 7.0, 14.0, 30.0, 120.0, 365.0, 1825.0, 36500.0)
    private val states = listOf(
        0.001 to 1.0,   // barely a memory, easiest topic
        1.0 to 5.0,
        12.0 to 5.5,
        68.9 to 2.1,    // a real row from the user's own export
        250.0 to 8.0,
        20000.0 to 10.0, // absurdly stable, hardest topic
    )
    private val ratings = MemoryRating.entries
    private val understandings = UnderstandingRating.entries

    private fun review(s: Double, d: Double, t: Double, m: MemoryRating, u: UnderstandingRating, rn: Int = 4) =
        MedScheduler.review(s, d, t, m, u, highYield = false, reviewNumber = rn, model = m6)

    // --- The topic is ignored: nothing about it may change ----------------------------------------

    /**
     * Being late is not an event. Until the user actually answers, the memory model has no new
     * evidence, so predicted recall is the ONLY thing that may move. This pins the read-only
     * property that the rest of the app depends on: no fabricated reviews, no decayed stability,
     * no silent lapse.
     */
    @Test
    fun `ignoring a topic changes only its predicted recall, never its state`() {
        for ((s, d) in states) {
            var previousR = Double.MAX_VALUE
            for (t in neglect) {
                val r = MedScheduler.retrievability(t, s, m6)
                assertTrue("R must stay a probability at t=$t S=$s, got $r", r > 0.0 && r <= 1.0)
                assertTrue("R must be finite at t=$t S=$s", r.isFinite())
                assertTrue("R must only ever fall while a topic is ignored ($r >= $previousR)", r <= previousR)
                previousR = r
            }
            // Predicted recall is DERIVED on every read, never stored back: the same stability must
            // still produce the same answer for a given age no matter how many times it is asked, so
            // nothing about displaying an overdue topic can accumulate drift.
            // (That the stored ROW is untouched is pinned at the database level in
            // ReplayEqualsLiveTest -- "an overdue topic is never written to just by being looked at".)
            for (t in neglect) {
                assertEquals(
                    "R must be a pure function of (age, stability) at t=$t S=$s",
                    MedScheduler.retrievability(t, s, m6), MedScheduler.retrievability(t, s, m6), 0.0,
                )
            }
        }
    }

    /** However late it gets, the queue must still find it. A topic must never fall out of the app. */
    @Test
    fun `an ignored topic stays due forever and never becomes upcoming`() {
        val day = 86400000L
        val now = System.currentTimeMillis()
        val startOfToday = now - (now % day)
        val endOfToday = startOfToday + day - 1
        for (t in neglect) {
            val dueAt = now - (t * day).toLong()
            assertTrue("still in the load-now set after $t days", TodayBuckets.isDueByEndOfToday(dueAt, endOfToday))
            assertTrue("and correctly bucketed as overdue", TodayBuckets.isOverdue(dueAt, startOfToday))
            assertTrue("never 'upcoming'", !TodayBuckets.isUpcoming(dueAt, endOfToday))
        }
    }

    /** Overdue pressure is uncapped ON PURPOSE so nothing starves — but it must stay a real number. */
    @Test
    fun `priority rises with neglect without overflowing`() {
        val day = 86400000L
        val now = System.currentTimeMillis()
        var previous = -1.0
        for (t in neglect) {
            val score = MedScheduler.priorityScore(
                highYield = false, state = "Building", lapseCount = 3,
                modelDueAt = now - (t * day).toLong(), now = now,
            )
            assertTrue("score must be finite after $t days, got $score", score.isFinite())
            assertTrue("neglect must never lower priority", score > previous)
            previous = score
        }
        // A decade of neglect must still outrank a brand-new important topic, or the backlog starves.
        val ancient = MedScheduler.priorityScore(false, "Building", 0, now - 3650L * day, now)
        val importantToday = MedScheduler.priorityScore(true, "Learning", 0, now, now)
        assertTrue("a decade overdue must outrank a fresh important topic", ancient > importantToday)
    }

    // --- The user finally comes back --------------------------------------------------------------

    /**
     * The exhaustive sweep: every state x every gap x every rating x every understanding. Nothing may
     * throw, and every number the app then stores or displays must be usable.
     */
    @Test
    fun `coming back after any amount of neglect always yields a usable schedule`() {
        var cases = 0
        for ((s, d) in states) {
            for (t in neglect) {
                for (m in ratings) {
                    for (u in understandings) {
                        val o = review(s, d, t, m, u)
                        val ctx = "S=$s D=$d late=${t}d $m/$u"

                        assertTrue("interval finite ($ctx)", o.intervalDays.isFinite())
                        assertTrue("interval at least a day ($ctx): ${o.intervalDays}", o.intervalDays >= MedScheduler.MIN_INTERVAL_DAYS - 1e-9)
                        assertTrue("interval within the ceiling ($ctx): ${o.intervalDays}", o.intervalDays <= 365.0 + 1e-9)

                        assertTrue("stability finite and positive ($ctx)", o.state.stability > 0.0 && o.state.stability.isFinite())
                        assertTrue("difficulty stays in band ($ctx): ${o.state.difficulty}", o.state.difficulty in 1.0..10.0)
                        assertTrue("logged recall stays a probability ($ctx)", o.retrievabilityAtReview in 0.0..1.0)

                        o.remediationDays?.let {
                            assertTrue("remediation is a real deadline ($ctx)", it.isFinite() && it >= 1.0)
                        }
                        cases++
                    }
                }
            }
        }
        assertTrue("the sweep actually ran", cases >= 500)
    }

    /**
     * Forgetting something you neglected for a year is the NORMAL outcome, not a catastrophe. It must
     * come back tomorrow to relearn — never in a week because the model decided the gap was evidence.
     */
    @Test
    fun `forgetting after long neglect always relearns tomorrow`() {
        for ((s, d) in states) {
            for (t in neglect) {
                for (u in understandings) {
                    val o = review(s, d, t, MemoryRating.Forgot, u)
                    assertEquals(
                        "Forgot after ${t}d on S=$s must relearn tomorrow",
                        MedScheduler.RELEARN_STEP_DAYS, o.intervalDays, 1e-9,
                    )
                    assertEquals("and owes a comprehension repair too", 1.0, o.remediationDays!!, 1e-9)
                }
            }
        }
    }

    /**
     * Remembering something after a long gap is STRONGER evidence than remembering it on time, so the
     * next interval should not shrink for having been late. This is the property the user asked about
     * directly: late-but-correct must not be punished.
     */
    @Test
    fun `recalling successfully after neglect is never punished for the delay`() {
        for ((s, d) in states) {
            for (m in listOf(MemoryRating.Hard, MemoryRating.Good, MemoryRating.Easy)) {
                var previous = 0.0
                for (t in neglect) {
                    val o = review(s, d, t, m, UnderstandingRating.Clear)
                    assertTrue(
                        "S=$s $m: waiting longer and still recalling must not shorten the next gap " +
                            "(${o.intervalDays} after ${t}d vs $previous before)",
                        o.intervalDays >= previous - 1e-9,
                    )
                    previous = o.intervalDays
                }
            }
        }
    }

    /**
     * The cliff YADORA-2 exists to prevent, checked at the extreme. A topic added, never rated, and
     * ignored for years is STILL on its first rating when the user finally answers — so it must be
     * capped like any first rating, not handed a year-long interval for having sat untouched.
     */
    @Test
    fun `a never-rated topic ignored for years is still a first rating`() {
        val seed = MedScheduler.firstStudy(UnderstandingRating.Partial, false).state
        for (t in neglect) {
            for (m in listOf(MemoryRating.Hard, MemoryRating.Good, MemoryRating.Easy)) {
                val o = MedScheduler.review(
                    seed.stability, seed.difficulty, t, m, UnderstandingRating.Clear,
                    highYield = false, reviewNumber = MedScheduler.effectiveReviewNumber(0), model = m6,
                )
                assertTrue(
                    "$m after ${t}d of neglect must stay under the first-study cap, got ${o.baseIntervalDays}",
                    o.baseIntervalDays <= MedScheduler.FIRST_STUDY_MAX_DAYS + 1e-9,
                )
                assertTrue("and still be at least a day out", o.intervalDays >= MedScheduler.MIN_INTERVAL_DAYS - 1e-9)
            }
        }
    }

    /**
     * Time is measured in whole local days, so a neglected topic answered at any hour of the day it
     * is finally opened gets the same schedule. Otherwise "I got back to it in the evening" would
     * quietly buy a different interval than the same review that morning.
     */
    @Test
    fun `the hour they finally return does not change the outcome`() {
        val tz = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Tehran"))
            fun at(day: Int, hour: Int): Long = java.util.Calendar.getInstance().apply {
                clear(); set(2026, java.util.Calendar.MAY, day, hour, 0, 0)
            }.timeInMillis

            val lastReviewed = at(1, 21) // reviewed late one evening, then ignored for a month
            val expected = MedScheduler.modelElapsedDays(lastReviewed, at(31, 9), m6)
            assertEquals("a month of neglect is thirty days", 30.0, expected, 0.0)
            for (hour in 0..23) {
                assertEquals(
                    "returning at ${hour}:00 must count the same days",
                    expected, MedScheduler.modelElapsedDays(lastReviewed, at(31, hour), m6), 0.0,
                )
            }
        } finally {
            java.util.TimeZone.setDefault(tz)
        }
    }

    /**
     * Defensive: a device clock moved backwards (manual change, timezone travel, a bad NTP sync)
     * must not produce negative elapsed time and drive the model somewhere undefined.
     */
    @Test
    fun `a backwards clock cannot produce negative elapsed time`() {
        val day = 86400000L
        val now = System.currentTimeMillis()
        for (back in listOf(1L, 30L, 3650L)) {
            assertEquals(
                "clock moved back $back days",
                0.0, MedScheduler.modelElapsedDays(now, now - back * day, m6), 0.0,
            )
        }
        // And the review path survives it rather than throwing.
        val o = review(12.0, 5.0, 0.0, MemoryRating.Good, UnderstandingRating.Clear)
        assertNotNull(o)
        assertTrue("still a usable interval", o.intervalDays >= MedScheduler.MIN_INTERVAL_DAYS - 1e-9)
    }

    /**
     * The two clocks must stay coherent after neglect: whatever the memory model says, a topic with
     * unresolved understanding still comes back on the earlier date, and that date is a real one.
     */
    @Test
    fun `the understanding clock still wins after a long absence`() {
        for (t in listOf(30.0, 365.0, 36500.0)) {
            val partial = review(250.0, 3.0, t, MemoryRating.Good, UnderstandingRating.Partial)
            val clear = review(250.0, 3.0, t, MemoryRating.Good, UnderstandingRating.Clear)

            assertEquals("memory prediction is identical — understanding never touches it",
                clear.intervalDays, partial.intervalDays, 0.0)
            assertNotNull("partial understanding still owes a repair after ${t}d", partial.remediationDays)
            assertTrue("and it lands before the memory date", partial.remediationDays!! < partial.intervalDays)
            org.junit.Assert.assertNull("clear understanding owes nothing", clear.remediationDays)
        }
    }
}
