package com.example.domain.srs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.pow

/**
 * FIDELITY TO THE PUBLISHED FSRS-5 SPECIFICATION.
 *
 * The rest of the suite checks *relations* ("Easy grows more than Good"), which would still pass if a
 * formula were transcribed slightly wrong. This file instead re-implements the FSRS-5 equations
 * INDEPENDENTLY, straight from the published specification, and asserts the production code agrees to
 * near machine precision across a wide sweep of states, grades and elapsed times.
 *
 * Why this matters more than any other test in the app: the whole product promise is that review
 * timing is scientifically derived. A wrong exponent or a dropped clamp would not crash, would not
 * fail a relational test, and would quietly schedule every topic slightly too early (wasting the
 * user's time) or slightly too late (letting them forget) — for years, invisibly.
 *
 * The reference equations (FSRS-5, w0..w18):
 *   FACTOR   = 0.9^(1/DECAY) - 1,  DECAY = -0.5          (so FACTOR = 19/81)
 *   R(t,S)   = (1 + FACTOR·t/S)^DECAY
 *   I(S,r)   = (S/FACTOR)·(r^(1/DECAY) - 1)
 *   S₀(G)    = w[G-1]
 *   D₀(G)    = w4 - e^(w5·(G-1)) + 1                       clamped to [1,10]
 *   D'(D,G)  = w7·D₀(4) + (1-w7)·(D + (-w6·(G-3))·(10-D)/9) clamped to [1,10]
 *   S_recall = S·(1 + e^w8·(11-D)·S^(-w9)·(e^(w10·(1-R))-1)·[w15 if Hard]·[w16 if Easy])
 *   S_lapse  = min(S, w11·D^(-w12)·((S+1)^w13 - 1)·e^(w14·(1-R)))
 *   S_short  = S·e^(w17·(G-3+w18))
 */
class FsrsSpecComplianceTest {

    private val p = FsrsParameters()
    private val w = FsrsParameters.DEFAULT_WEIGHTS
    private val eps = 1e-9

    // --- Independent transcription of the specification -------------------------------------------

    private fun specFactor() = 0.9.pow(1.0 / -0.5) - 1.0
    private fun specR(t: Double, s: Double) = (1.0 + specFactor() * t / s).pow(-0.5)
    private fun specInterval(s: Double, r: Double) = (s / specFactor()) * (r.pow(1.0 / -0.5) - 1.0)
    private fun specD0(g: Int) = (w[4] - exp(w[5] * (g - 1)) + 1.0).coerceIn(1.0, 10.0)

    private fun specNextD(d: Double, g: Int): Double {
        val deltaD = -w[6] * (g - 3)
        val damped = d + deltaD * (10.0 - d) / 9.0
        return (w[7] * specD0(4) + (1.0 - w[7]) * damped).coerceIn(1.0, 10.0)
    }

    private fun specRecallS(s: Double, d: Double, r: Double, g: Int): Double {
        val hard = if (g == 2) w[15] else 1.0
        val easy = if (g == 4) w[16] else 1.0
        return s * (1.0 + exp(w[8]) * (11.0 - d) * s.pow(-w[9]) * (exp(w[10] * (1.0 - r)) - 1.0) * hard * easy)
    }

    private fun specLapseS(s: Double, d: Double, r: Double): Double =
        (w[11] * d.pow(-w[12]) * ((s + 1.0).pow(w[13]) - 1.0) * exp(w[14] * (1.0 - r))).coerceAtMost(s)

    private fun specShortS(s: Double, g: Int) = s * exp(w[17] * (g - 3 + w[18]))

    // --- The checks -------------------------------------------------------------------------------

    @Test
    fun `the forgetting curve matches the specification exactly`() {
        assertEquals("FACTOR", specFactor(), Fsrs.FACTOR, 1e-15)
        assertEquals("FACTOR is 19/81", 19.0 / 81.0, Fsrs.FACTOR, 1e-15)
        for (s in listOf(0.05, 1.0, 3.173, 15.69105, 100.0, 3650.0)) {
            for (t in listOf(0.0, 0.25, 1.0, 5.0, 30.0, 365.0, 3650.0)) {
                assertEquals("R(t=$t, S=$s)", specR(t, s), Fsrs.retrievability(t, s), eps)
            }
            // The defining property of the curve: recall is exactly 90% after one stability.
            assertEquals("R(S,S) must be 0.9", 0.9, Fsrs.retrievability(s, s), 1e-12)
        }
    }

    @Test
    fun `the interval inverse matches the specification exactly`() {
        for (s in listOf(0.05, 1.0, 10.0, 68.927, 365.0)) {
            for (r in listOf(0.70, 0.80, 0.85, 0.90, 0.95, 0.99)) {
                assertEquals("I(S=$s, r=$r)", specInterval(s, r), Fsrs.intervalDays(s, r), 1e-8)
            }
            // I and R are exact inverses: scheduling at r and waiting that long lands on r.
            for (r in listOf(0.80, 0.85, 0.90, 0.95)) {
                assertEquals("round trip at r=$r", r, Fsrs.retrievability(Fsrs.intervalDays(s, r), s), 1e-9)
            }
        }
    }

    @Test
    fun `initial state matches the published weights and difficulty curve`() {
        for (g in Grade.entries) {
            assertEquals("S0(${g.name})", w[g.value - 1], Fsrs.initialState(g, p).stability, 1e-12)
            assertEquals("D0(${g.name})", specD0(g.value), Fsrs.initialDifficulty(g, p), eps)
        }
        // Hand-computed anchors, independent of both implementations above.
        assertEquals("D0(Again) = w4 - 1 + 1", 7.1949, Fsrs.initialDifficulty(Grade.Again, p), 1e-9)
        assertEquals("D0(Hard)", 6.48830, Fsrs.initialDifficulty(Grade.Hard, p), 1e-4)
        assertEquals("D0(Easy)", 3.22450, Fsrs.initialDifficulty(Grade.Easy, p), 1e-4)
    }

    @Test
    fun `difficulty update matches the specification across the whole range`() {
        var d = 1.0
        while (d <= 10.0) {
            for (g in Grade.entries) {
                // nextState exposes difficulty; elapsed is irrelevant to the D update.
                val got = Fsrs.nextState(MemoryState(10.0, d), 10.0, g, p).difficulty
                assertEquals("D'($d, ${g.name})", specNextD(d, g.value), got, eps)
            }
            d += 0.25
        }
    }

    @Test
    fun `stability update matches the specification for recall, lapse and same-day`() {
        val stabilities = listOf(0.05, 0.5, 1.18385, 5.0, 15.69105, 60.0, 300.0)
        val difficulties = listOf(1.0, 2.1302, 5.0, 6.4883, 10.0)
        val elapsedLong = listOf(1.0, 3.0, 12.0, 45.0, 200.0, 900.0)

        for (s in stabilities) for (d in difficulties) for (t in elapsedLong) {
            val r = specR(t, s)
            for (g in Grade.entries) {
                val expected = if (g == Grade.Again) specLapseS(s, d, r) else specRecallS(s, d, r, g.value)
                val got = Fsrs.nextState(MemoryState(s, d), t, g, p).stability
                assertEquals(
                    "S'(S=$s, D=$d, t=$t, ${g.name})",
                    expected.coerceIn(0.01, p.maximumIntervalDays * 10), got, 1e-7,
                )
            }
        }

        // Same-day (elapsed < 1) takes the FSRS-5 short-term branch instead.
        for (s in stabilities) for (g in Grade.entries) {
            val got = Fsrs.nextState(MemoryState(s, 5.0), 0.0, g, p).stability
            assertEquals(
                "short-term S'(S=$s, ${g.name})",
                specShortS(s, g.value).coerceIn(0.01, p.maximumIntervalDays * 10), got, 1e-9,
            )
        }
    }

    @Test
    fun `forgetting can never increase stability and recall can never decrease it`() {
        // Two invariants that must hold no matter what the weights are retrained to.
        for (s in listOf(0.05, 1.0, 10.0, 120.0)) for (d in listOf(1.0, 5.0, 10.0)) for (t in listOf(1.0, 20.0, 400.0)) {
            val lapsed = Fsrs.nextState(MemoryState(s, d), t, Grade.Again, p).stability
            assertTrue("a lapse must not strengthen memory (S=$s D=$d t=$t)", lapsed <= s + 1e-12)
            for (g in listOf(Grade.Hard, Grade.Good, Grade.Easy)) {
                val recalled = Fsrs.nextState(MemoryState(s, d), t, g, p).stability
                assertTrue("a successful recall must not weaken memory (${g.name})", recalled >= s - 1e-12)
            }
        }
    }

    @Test
    fun `no reachable input produces NaN or infinity`() {
        val nasty = listOf(0.01, 1e-6, 1e-3, 1.0, 1e4, 1e6)
        for (s in nasty) for (d in listOf(1.0, 5.5, 10.0)) for (t in listOf(0.0, 1e-9, 1.0, 1e6, 1e9)) {
            for (g in Grade.entries) {
                val next = Fsrs.nextState(MemoryState(s, d), t, g, p)
                assertTrue("S' finite for S=$s D=$d t=$t ${g.name} (got ${next.stability})", next.stability.isFinite())
                assertTrue("D' finite", next.difficulty.isFinite())
                assertTrue("D' in range", next.difficulty in 1.0..10.0)
                assertTrue("S' positive", next.stability > 0.0)
            }
            assertTrue("R finite", Fsrs.retrievability(t, s).isFinite())
            assertTrue("R in (0,1]", Fsrs.retrievability(t, s) in 0.0..1.0)
        }
    }

    /**
     * The behaviour the founder asked about, stated as a property rather than an anecdote: reviewing
     * LATE must never be punished. If a user is 30 days late and still recalls the topic, that is
     * strictly stronger evidence of durable memory than recalling it on time, and the model must say
     * so — monotonically, with no discontinuity anywhere along the curve.
     */
    @Test
    fun `later successful recall always yields at least as much stability as earlier`() {
        for (s in listOf(1.0, 5.0, 15.69105, 90.0)) for (d in listOf(2.0, 5.0, 9.0)) {
            for (g in listOf(Grade.Hard, Grade.Good, Grade.Easy)) {
                var previous = 0.0
                var t = 1.0
                while (t <= 400.0) {
                    val got = Fsrs.nextState(MemoryState(s, d), t, g, p).stability
                    assertTrue("S' must not fall as the review gets later (S=$s D=$d ${g.name} t=$t)", got >= previous - 1e-9)
                    previous = got
                    t += 1.0
                }
            }
        }
    }

    /** And the mirror: forgetting later must not be treated as *better* than forgetting early. */
    @Test
    fun `a later lapse never yields less stability than an earlier lapse, and both stay bounded`() {
        for (s in listOf(1.0, 10.0, 100.0)) for (d in listOf(2.0, 5.0, 9.0)) {
            var previous = 0.0
            var t = 1.0
            while (t <= 400.0) {
                val got = Fsrs.nextState(MemoryState(s, d), t, Grade.Again, p).stability
                assertTrue("post-lapse S must be monotone in lateness", got >= previous - 1e-9)
                assertTrue("post-lapse S is capped by the pre-lapse S", got <= s + 1e-12)
                previous = got
                t += 1.0
            }
        }
    }
}
