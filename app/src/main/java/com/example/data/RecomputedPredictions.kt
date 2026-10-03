package com.example.data

import com.example.data.local.entity.EventLogEntity
import com.example.data.local.entity.ReviewLogEntity

/**
 * Which stored predictions a rating correction recomputed. A correction replays its topic and rewrites the stored
 * prediction of every LATER log of that topic (saved order: a higher id) that existed when it happened (reviewed
 * before it). Those numbers were computed knowing what came after, so they are not predictions made at the review and
 * must not count as the calibration's evidence. Read from the RATING_CORRECTED events (`detail` "log=<id> ...", since
 * 2026-10-03); `tools/pilot/analyze.py` applies the same rule (`corrections`).
 *
 * The app's own calibration used to count them (an outside audit, 2026-10-03: two rewritten predictions, 0.808 to 0.753
 * and 0.901 to 0.854, stayed in the evidence). Corrections made before the event existed cannot be seen.
 */
object RecomputedPredictions {

    fun ids(logs: List<ReviewLogEntity>, events: List<EventLogEntity>): Set<Long> {
        val byUnit = HashMap<Long, MutableList<Pair<Long, Long>>>()
        for (e in events) {
            if (e.type != EVENT || e.unitId == null) continue
            val logId = correctedLogId(e.detail) ?: continue
            byUnit.getOrPut(e.unitId) { mutableListOf() } += logId to e.at
        }
        if (byUnit.isEmpty()) return emptySet()
        return logs.asSequence()
            .filter { l -> byUnit[l.studyUnitId]?.any { (corrected, at) -> l.id > corrected && l.reviewedAt < at } == true }
            .map { it.id }
            .toHashSet()
    }

    /** The corrected log's id from a RATING_CORRECTED detail, "log=<id> memory=<a>><b> understanding=<c>><d>". */
    fun correctedLogId(detail: String?): Long? =
        detail?.split(' ')?.firstOrNull { it.startsWith("log=") }?.removePrefix("log=")?.toLongOrNull()

    const val EVENT = "RATING_CORRECTED"
}
