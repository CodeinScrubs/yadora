package com.example.data

import com.example.data.local.entity.EventLogEntity
import com.example.data.local.entity.ReviewLogEntity

/**
 * Which stored predictions a rating correction recomputed. A correction replays its topic and rewrites the stored
 * prediction of every LATER log of that topic (saved order: a higher id) that existed when it happened. Those numbers
 * were computed knowing what came after, so they are not predictions made at the review and must not count as the
 * calibration's evidence. Read from the RATING_CORRECTED events (`detail` "log=<id> upto=<id> ...", since 2026-10-03);
 * `tools/pilot/analyze.py` applies the same rule (`corrections`).
 *
 * The app's own calibration used to count them (an outside audit, 2026-10-03: two rewritten predictions, 0.808 to 0.753
 * and 0.901 to 0.854, stayed in the evidence). Which logs existed was then judged by the clock (reviewed before the
 * correction), so with the phone's clock set back between the reviews and the correction, the rewritten ones still
 * counted (another outside audit, 2026-10-04). Since then the event records the topic's last log id when it replayed
 * (`upto`), in the same transaction, and saved order decides; an event without it falls back to the clock. Corrections
 * made before the event existed cannot be seen.
 *
 * A corrected DAY (REVIEW_DATE_CORRECTED, 2026-10-09: "log=<id> upto=<id> from=<ms> to=<ms>") recomputes the corrected
 * review's own prediction as well, since its gap changed, so its range starts at the corrected log itself.
 */
object RecomputedPredictions {

    /**
     * One correction: the log it changed, the topic's last log when it replayed (null before 2026-10-04), when, and
     * whether the corrected log's own prediction was recomputed too (a moved day).
     */
    private class Correction(val corrected: Long, val upTo: Long?, val at: Long, val inclusive: Boolean) {
        fun recomputed(l: ReviewLogEntity): Boolean =
            (if (inclusive) l.id >= corrected else l.id > corrected) && (upTo?.let { l.id <= it } ?: (l.reviewedAt < at))
    }

    fun ids(logs: List<ReviewLogEntity>, events: List<EventLogEntity>): Set<Long> {
        val byUnit = HashMap<Long, MutableList<Correction>>()
        for (e in events) {
            if ((e.type != EVENT && e.type != DATE_EVENT) || e.unitId == null) continue
            val logId = correctedLogId(e.detail) ?: continue
            byUnit.getOrPut(e.unitId) { mutableListOf() } += Correction(logId, lastLogId(e.detail), e.at, inclusive = e.type == DATE_EVENT)
        }
        if (byUnit.isEmpty()) return emptySet()
        return logs.asSequence()
            .filter { l -> byUnit[l.studyUnitId]?.any { it.recomputed(l) } == true }
            .map { it.id }
            .toHashSet()
    }

    /** The corrected log's id from a RATING_CORRECTED detail, "log=<id> upto=<id> memory=<a>><b> understanding=<c>><d>". */
    fun correctedLogId(detail: String?): Long? = field(detail, "log=")

    /** The topic's last log id when the correction replayed it (`upto=`); null in an event from before 2026-10-04. */
    fun lastLogId(detail: String?): Long? = field(detail, "upto=")

    private fun field(detail: String?, key: String): Long? =
        detail?.split(' ')?.firstOrNull { it.startsWith(key) }?.removePrefix(key)?.toLongOrNull()

    const val EVENT = "RATING_CORRECTED"

    /** A review's day corrected from the topic's history (2026-10-09). */
    const val DATE_EVENT = "REVIEW_DATE_CORRECTED"
}
