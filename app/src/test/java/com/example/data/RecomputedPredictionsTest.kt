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

    private fun correction(unit: Long?, logId: Long, at: Long, upTo: Long? = null) = EventLogEntity(
        at = at, type = RecomputedPredictions.EVENT, unitId = unit,
        detail = "log=$logId" + (upTo?.let { " upto=$it" } ?: "") + " memory=Good>Hard understanding=Clear>Clear",
    )

    /**
     * The phone's clock set back between the reviews and the correction (an outside audit, 2026-10-04): the rewritten
     * reviews carry LATER times than the correction. Saved order still tells which existed: the event's `upto`.
     */
    @Test
    fun `with the clock set back, saved order decides, not the time`() {
        // Reviews 2 and 3 were made with the clock running ahead (times 900 and 950); review 1 was corrected at time 500,
        // after the clock was set back, when the topic's last log was 3. Review 4 came after the correction.
        val logs = listOf(log(1, 7, 100), log(2, 7, 900), log(3, 7, 950), log(4, 7, 520))
        assertEquals(setOf(2L, 3L), RecomputedPredictions.ids(logs, listOf(correction(7, 1, 500, upTo = 3))))
        assertEquals("a second correction, after review 4, adds it", setOf(2L, 3L, 4L),
            RecomputedPredictions.ids(logs, listOf(correction(7, 1, 500, upTo = 3), correction(7, 1, 530, upTo = 4))))
        // An event from before the bound was recorded falls back to the clock, and misses both.
        assertEquals(emptySet<Long>(), RecomputedPredictions.ids(logs, listOf(correction(7, 1, 500))))
        assertEquals(3L, RecomputedPredictions.lastLogId("log=1 upto=3 memory=Good>Hard understanding=Clear>Clear"))
        assertNull(RecomputedPredictions.lastLogId("log=1 memory=Good>Hard understanding=Clear>Clear"))
    }

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

    /**
     * A merge moves the corrected topic's logs to the survivor, and the correction's event keeps the old id: its
     * recomputed predictions used to count again after the merge (a production review, 2026-10-10).
     */
    @Test
    fun `a correction still covers its topic's logs after a merge moved them`() {
        // Topic 7's review 1 corrected when its last log was 3; then 7 was merged into 9, and later 9 into 11.
        val corrected = correction(7, 1, 500, upTo = 3)
        val merge = EventLogEntity(at = 600, type = RecomputedPredictions.MERGE_EVENT, unitId = 9, detail = "7")
        val again = EventLogEntity(at = 700, type = RecomputedPredictions.MERGE_EVENT, unitId = 11, detail = "9,12")
        val onNine = listOf(log(1, 9, 100), log(2, 9, 200), log(3, 9, 300), log(8, 9, 800))
        assertEquals(setOf(2L, 3L), RecomputedPredictions.ids(onNine, listOf(corrected, merge)))
        assertEquals(setOf(7L, 9L), RecomputedPredictions.affectedUnits(listOf(corrected, merge)))
        val onEleven = onNine.map { it.copy(studyUnitId = 11) }
        assertEquals("through two merges", setOf(2L, 3L), RecomputedPredictions.ids(onEleven, listOf(corrected, merge, again)))
        assertEquals(setOf(7L, 9L, 11L), RecomputedPredictions.affectedUnits(listOf(corrected, merge, again)))
        assertEquals("a merge alone recomputes nothing", emptySet<Long>(), RecomputedPredictions.ids(onNine, listOf(merge)))
        assertEquals(emptySet<Long>(), RecomputedPredictions.affectedUnits(listOf(merge)))
    }

    @Test
    fun `the corrected log is read from the event detail`() {
        assertEquals(12L, RecomputedPredictions.correctedLogId("log=12 memory=Good>Hard understanding=Clear>Partial"))
        assertNull(RecomputedPredictions.correctedLogId(null))
        assertNull(RecomputedPredictions.correctedLogId("log=x memory=Good>Hard"))
        assertNull(RecomputedPredictions.correctedLogId("memory=Good>Hard"))
    }
}
