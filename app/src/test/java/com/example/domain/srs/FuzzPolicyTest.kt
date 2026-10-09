package com.example.domain.srs

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class FuzzPolicyTest {
    @Test fun `fuzz cannot invert ordered memory intervals across its three day boundary`() {
        val hard = MedScheduler.fuzzedInterval(2.9921, 2.9921, 6L, 0)
        val good = MedScheduler.fuzzedInterval(3.0996, 3.0996, 6L, 0)
        assertTrue("a longer unfuzzed interval must not come back first: $hard vs $good", good >= hard)
    }

    @Test fun `current fuzz is monotone over the boundary for many topics and review counts`() {
        for (id in 1L..200L) for (count in 0..7) {
            var previous = Double.NEGATIVE_INFINITY
            for (i in 0..80) {
                val base = 2.9 + i * 0.005
                val actual = MedScheduler.fuzzedInterval(base, base, id, count)
                assertTrue("base=$base unit=$id count=$count previous=$previous actual=$actual", actual >= previous)
                previous = actual
            }
        }
    }

    @Test fun `every older policy retains its exact fuzz and frozen FSRS5 keeps its understanding ratio`() {
        val expected = 3.0996 * (1.0 + kotlin.random.Random(6L * 31L).nextDouble(-0.05, 0.05))
        assertTrue(expected < 2.9921)
        for (n in 1..7) assertEquals(expected, MedScheduler.fuzzedInterval(3.0996, 3.0996, 6L, 0,
            policyVersion = "YADORA-$n"), 0.0)
        assertEquals(3.0, MedScheduler.fuzzedInterval(3.0996, 3.0996, 6L, 0), 0.0)
        val clear = MedScheduler.fuzzedInterval(3.0996, 3.0996, 6L, 0, model = MedScheduler.MemoryModel.FSRS_5)
        val partial = MedScheduler.fuzzedInterval(3.0996 * 0.9, 3.0996, 6L, 0, model = MedScheduler.MemoryModel.FSRS_5)
        assertEquals(expected, clear, 0.0)
        assertEquals(clear * 0.9, partial, 1e-12)
    }

    @Test fun `the current boundary leaves tomorrow repairs first study and long term caps intact`() {
        for (id in 1L..200L) {
            assertEquals(1.0, MedScheduler.fuzzedInterval(1.0, 1.0, id, 3), 0.0)
            assertTrue(MedScheduler.fuzzedInterval(5.0, 5.0, id, 0, isFirstStudy = true) in 3.0..5.0)
            assertTrue(MedScheduler.fuzzedInterval(365.0, 1000.0, id, 8) <= 365.0)
        }
    }

    @Test fun `write independent Kotlin reference cases for the Python policy replay`() {
        val rows = mutableListOf<String>()
        for (policy in listOf("YADORA-7", "YADORA-8", "")) for (model in MedScheduler.MemoryModel.entries)
            for (id in listOf(6L, 42L, Long.MAX_VALUE)) for (base in listOf(2.9921, 3.0996, 5.0, 400.0)) {
                val first = base == 5.0
                val interval = if (model == MedScheduler.MemoryModel.FSRS_5) base * 0.9 else base.coerceAtMost(365.0)
                val count = if (first) 0 else 31
                val actual = MedScheduler.fuzzedInterval(interval, base, id, count, first, policy, model)
                rows += """{"policy":"$policy","model":"${model.id}","unit_id":"$id","review_count":$count,"base":$base,"interval":$interval,"first_study":$first,"expected":$actual}"""
            }
        val output = File("build/fuzz-policy/kotlin_fuzz_policy_reference.json")
        requireNotNull(output.parentFile).mkdirs()
        output.writeText("{\"policy\":\"${MedScheduler.POLICY_VERSION}\",\"cases\":[${rows.joinToString(",")}]}\n")
        assertEquals(72, rows.size)
    }
}
