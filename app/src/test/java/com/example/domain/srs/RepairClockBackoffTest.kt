package com.example.domain.srs

import com.example.domain.model.MemoryRating
import com.example.domain.model.UnderstandingRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * POLICY YADORA-6: the understanding repair clock BACKS OFF.
 *
 * Before it, a learner who answered Partial at every review saw the topic every three days forever,
 * however strong its memory was — a two-year simulation showed exactly that loop. Each consecutive
 * unrepaired answer now doubles the deadline, and a deadline that would not beat the memory date is
 * dropped, so the second clock can shorten a gap but never manufacture an endless one.
 */
class RepairClockBackoffTest {

    private val m6 = MedScheduler.MemoryModel.FSRS_6

    // --- The table, and its doubling -----------------------------------------------------------

    @Test
    fun `with no streak the repair table is unchanged`() {
        assertEquals(2.0, MedScheduler.remediationDays(MemoryRating.Hard, UnderstandingRating.Partial, 0)!!, 1e-12)
        assertEquals(3.0, MedScheduler.remediationDays(MemoryRating.Good, UnderstandingRating.Partial, 0)!!, 1e-12)
        assertEquals(4.0, MedScheduler.remediationDays(MemoryRating.Easy, UnderstandingRating.Partial, 0)!!, 1e-12)
        assertEquals(1.0, MedScheduler.remediationDays(MemoryRating.Good, UnderstandingRating.Confused, 0)!!, 1e-12)
        assertNull(MedScheduler.remediationDays(MemoryRating.Good, UnderstandingRating.Clear, 0))
    }

    @Test
    fun `each consecutive unrepaired answer doubles the deadline`() {
        for (streak in 0..6) {
            val expected = Math.pow(MedScheduler.REPAIR_BACKOFF_FACTOR, streak.toDouble())
            assertEquals("Good+Partial, streak $streak", 3.0 * expected,
                MedScheduler.remediationDays(MemoryRating.Good, UnderstandingRating.Partial, streak)!!, 1e-9)
            assertEquals("Hard+Partial, streak $streak", 2.0 * expected,
                MedScheduler.remediationDays(MemoryRating.Hard, UnderstandingRating.Partial, streak)!!, 1e-9)
            assertEquals("Easy+Confused, streak $streak", 1.0 * expected,
                MedScheduler.remediationDays(MemoryRating.Easy, UnderstandingRating.Confused, streak)!!, 1e-9)
        }
    }

    @Test
    fun `a lapse never backs off and Clear never repairs, whatever the streak`() {
        for (streak in listOf(0, 1, 5, 40)) {
            for (u in UnderstandingRating.entries) {
                assertEquals("relearn tomorrow (streak $streak, $u)", MedScheduler.RELEARN_STEP_DAYS,
                    MedScheduler.remediationDays(MemoryRating.Forgot, u, streak)!!, 1e-12)
            }
            assertNull("Clear (streak $streak)", MedScheduler.remediationDays(MemoryRating.Good, UnderstandingRating.Clear, streak))
        }
    }

    @Test
    fun `an absurd streak stays finite`() {
        val days = MedScheduler.remediationDays(MemoryRating.Good, UnderstandingRating.Partial, Int.MAX_VALUE)!!
        assertTrue("finite", days.isFinite())
        assertEquals("capped exponent", 3.0 * Math.pow(2.0, 30.0), days, 1e-3)
        assertEquals("negative streaks are treated as none", 3.0,
            MedScheduler.remediationDays(MemoryRating.Good, UnderstandingRating.Partial, -7)!!, 1e-12)
    }

    // --- What counts as a streak -----------------------------------------------------------------

    @Test
    fun `the streak counts consecutive unrepaired answers back from the latest log`() {
        fun streak(vararg h: Pair<String, String>) = MedScheduler.unrepairedStreak(h.toList())
        assertEquals(0, streak())
        assertEquals(0, streak("Good" to "Clear"))
        assertEquals(1, streak("Good" to "Partial"))
        assertEquals(2, streak("Good" to "Partial", "Hard" to "Confused"))
        assertEquals(0, streak("Good" to "Partial", "Good" to "Clear"))
        assertEquals(1, streak("Good" to "Clear", "Easy" to "Partial"))
        assertEquals(2, streak("Good" to "Clear", "Hard" to "Partial", "Easy" to "Partial"))
        // A lapse ends the streak: the material is about to be relearned and the cycle starts fresh.
        assertEquals(0, streak("Good" to "Partial", "Forgot" to "NotAsked"))
        assertEquals(0, streak("Good" to "Partial", "Forgot" to "Partial"))
        assertEquals(1, streak("Forgot" to "NotAsked", "Good" to "Partial"))
        // Only the tail counts: a repaired answer in the middle is history.
        assertEquals(1, streak("Good" to "Partial", "Good" to "Partial", "Good" to "Clear", "Good" to "Confused"))
    }

    @Test
    fun `the continuation rule matches the counting rule`() {
        assertTrue(MedScheduler.continuesUnrepairedStreak("Good", "Partial"))
        assertTrue(MedScheduler.continuesUnrepairedStreak("Hard", "Confused"))
        assertFalse(MedScheduler.continuesUnrepairedStreak("Good", "Clear"))
        assertFalse(MedScheduler.continuesUnrepairedStreak("Forgot", "Partial"))
        assertFalse(MedScheduler.continuesUnrepairedStreak("Forgot", "NotAsked"))
        assertFalse(MedScheduler.continuesUnrepairedStreak("Good", "NotAsked"))
    }

    // --- Through review(): the deadline must beat the memory date or not exist ------------------

    @Test
    fun `the deadline doubles until it can no longer beat the memory date, then disappears`() {
        // A strong topic: three months of stability, recalled on time.
        val memory = MedScheduler.review(80.0, 3.0, 80.0, MemoryRating.Good, UnderstandingRating.Clear, false, 6, model = m6)
        assertNull("Clear: no deadline", memory.remediationDays)
        var seenNull = false
        for (streak in 0..12) {
            val o = MedScheduler.review(80.0, 3.0, 80.0, MemoryRating.Good, UnderstandingRating.Partial, false, 6, model = m6, unrepairedStreak = streak)
            assertEquals("the memory interval never depends on understanding (streak $streak)",
                memory.intervalDays, o.intervalDays, 1e-12)
            val doubled = 3.0 * Math.pow(2.0, streak.toDouble())
            if (doubled < o.intervalDays) {
                assertEquals("streak $streak keeps a deadline that beats the memory date", doubled, o.remediationDays!!, 1e-9)
                assertFalse("deadlines come before the first dropped one", seenNull)
            } else {
                assertNull("streak $streak: a deadline on or after the memory date is no deadline", o.remediationDays)
                seenNull = true
            }
        }
        assertTrue("the sweep must reach the point where the memory clock stands alone", seenNull)
    }

    @Test
    fun `a first check-in whose memory date is already sooner gets no separate deadline`() {
        // Good on the first check-in: 2.3 days of memory, versus a 3-day Partial repair. The memory
        // date wins outright, so the repair deadline is not written at all. (Before the backoff it
        // was stored and simply never mattered.)
        val seed = MedScheduler.firstStudy(UnderstandingRating.Partial, false).state
        val o = MedScheduler.review(seed.stability, seed.difficulty, 0.0, MemoryRating.Good, UnderstandingRating.Partial, false, 0, model = m6)
        assertTrue("memory date is sooner than 3 days", o.intervalDays < 3.0)
        assertNull(o.remediationDays)
        // Confused still repairs tomorrow, which does beat 2.3 days.
        val c = MedScheduler.review(seed.stability, seed.difficulty, 0.0, MemoryRating.Good, UnderstandingRating.Confused, false, 0, model = m6)
        assertEquals(1.0, c.remediationDays!!, 1e-12)
    }

    @Test
    fun `a lapse keeps its relearn step through review whatever the streak`() {
        val o = MedScheduler.review(40.0, 5.0, 60.0, MemoryRating.Forgot, UnderstandingRating.Partial, false, 4, model = m6, unrepairedStreak = 9)
        assertEquals(MedScheduler.RELEARN_STEP_DAYS, o.intervalDays, 1e-12)
        assertEquals(MedScheduler.RELEARN_STEP_DAYS, o.remediationDays!!, 1e-12)
    }

    @Test
    fun `weaker understanding never returns a topic later than stronger understanding`() {
        // The ordering the user reads off the buttons must survive the backoff at every streak.
        val states = listOf(Triple(2.3, 5.0, 2.0), Triple(14.0, 4.0, 14.0), Triple(80.0, 3.0, 100.0), Triple(400.0, 2.0, 365.0))
        for ((s, d, t) in states) for (m in listOf(MemoryRating.Hard, MemoryRating.Good, MemoryRating.Easy)) for (streak in 0..8) {
            fun effective(u: UnderstandingRating): Double {
                val o = MedScheduler.review(s, d, t, m, u, false, 5, model = m6, unrepairedStreak = streak)
                return minOf(o.intervalDays, o.remediationDays ?: Double.MAX_VALUE)
            }
            val confused = effective(UnderstandingRating.Confused)
            val partial = effective(UnderstandingRating.Partial)
            val clear = effective(UnderstandingRating.Clear)
            assertTrue("S=$s $m streak $streak: Confused ($confused) <= Partial ($partial)", confused <= partial + 1e-9)
            assertTrue("S=$s $m streak $streak: Partial ($partial) <= Clear ($clear)", partial <= clear + 1e-9)
        }
    }

    // --- History replays under the policy that produced it -----------------------------------

    @Test
    fun `older policies replay a flat deadline`() {
        for (v in listOf("YADORA-1", "YADORA-2", "YADORA-3", "YADORA-4", "YADORA-5")) {
            assertFalse(v, MedScheduler.backsOffRepairClock(v))
        }
        assertTrue(MedScheduler.backsOffRepairClock("YADORA-6"))
        assertTrue("a blank (pre-v5) stamp replays under the current policy", MedScheduler.backsOffRepairClock(""))
        assertTrue(MedScheduler.backsOffRepairClock(MedScheduler.POLICY_VERSION))

        val flat = MedScheduler.review(80.0, 3.0, 80.0, MemoryRating.Good, UnderstandingRating.Partial, false, 6,
            model = m6, unrepairedStreak = 3, backOffRepairClock = false)
        assertEquals("a YADORA-5 row keeps the 3-day deadline it was given", 3.0, flat.remediationDays!!, 1e-12)
        val current = MedScheduler.review(80.0, 3.0, 80.0, MemoryRating.Good, UnderstandingRating.Partial, false, 6,
            model = m6, unrepairedStreak = 3)
        assertNotNull(current.remediationDays)
        assertEquals("the same answer today backs off to 24 days", 24.0, current.remediationDays!!, 1e-9)
    }
}
