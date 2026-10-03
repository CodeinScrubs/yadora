package com.example.data

import com.example.data.local.entity.EventLogEntity
import com.example.data.local.entity.ReviewLogEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which stored predictions a rating correction recomputed: the later reviews of the corrected topic (saved order) that
 * existed when it was made. `tools/pilot/analyze.py` applies the same rule to the export (`corrections`).
 */
class RecomputedPredictionsTest {

    private fun log(id: Long, unit: Long, at: Long) = ReviewLogEntity(
        id = id, studyUnitId = unit, reviewedAt = at, memoryRating = "Good", understandingRating = "Clear",
        previousIntervalDays = 1.0, nextIntervalDays = 2.0, previousState = "Learning", nextState = "Building",
    )

    private fun correction(unit: Long?, logId: Long, at: Long) = EventLogEntity(
        at = at, type = RecomputedPredictions.EVENT, unitId = unit, detail = "log=$logId memory=Good>Hard understanding=Clear>Clear",
    )

    @Test
    fun `the later reviews of the corrected topic that existed at the correction`() {
        val logs = listOf(log(1, 7, 100), log(2, 7, 200), log(3, 7, 300), log(4, 9, 350), log(5, 7, 400), log(6, 7, 900))
        // Topic 7's review 2 corrected at time 500: reviews 3 and 5 were replayed; 6 came after; topic 9 is untouched.
        assertEquals(setOf(3L, 5L), RecomputedPredictions.ids(logs, listOf(correction(7, 2, 500))))
        // Review 1 corrected at time 1000 replays 2, 3, 5 and 6 (all later and all existing by then).
        assertEquals("a second correction adds its own", setOf(2L, 3L, 5L, 6L), RecomputedPredictions.ids(logs, listOf(correction(7, 2, 500), correction(7, 1, 1000))))
        assertEquals("no corrections, nothing recomputed", emptySet<Long>(), RecomputedPredictions.ids(logs, emptyList()))
        assertEquals(
            "an event without a topic or a readable log is ignored", emptySet<Long>(),
            RecomputedPredictions.ids(
                logs,
                listOf(correction(null, 2, 500), EventLogEntity(at = 500, type = RecomputedPredictions.EVENT, unitId = 7, detail = "memory=Good>Hard")),
            ),
        )
        assertEquals(
            "other events are not corrections", emptySet<Long>(),
            RecomputedPredictions.ids(logs, listOf(EventLogEntity(at = 500, type = "MERGE", unitId = 7, detail = "log=2"))),
        )
    }

    @Test
    fun `the corrected log is read from the event detail`() {
        assertEquals(12L, RecomputedPredictions.correctedLogId("log=12 memory=Good>Hard understanding=Clear>Partial"))
        assertNull(RecomputedPredictions.correctedLogId(null))
        assertNull(RecomputedPredictions.correctedLogId("log=x memory=Good>Hard"))
        assertNull(RecomputedPredictions.correctedLogId("memory=Good>Hard"))
    }
}
