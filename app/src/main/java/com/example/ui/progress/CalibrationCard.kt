package com.example.ui.progress

import com.example.data.local.entity.ReviewLogEntity
import com.example.domain.srs.MedScheduler
import com.example.domain.srs.RecallCalibration

/**
 * The Progress screen's calibration card, from the review logs (ascending, as `getLogsSince` returns them).
 *
 * Every number comes from the SAME rows the scheduler's correction is fitted on — real recall reviews
 * on the live model that pass [RecallCalibration.isEvidence], newest [RecallCalibration.WINDOW] — so
 * the count, the two recall rates and the factor describe one sample. It used to count every recall
 * row while the factor came from a subset, and to label the lot "based on N reviews".
 *
 * "Predicted" is the DEFAULT model's prediction as stored, not the corrected one. The correction is
 * fitted by making the corrected predictions match these very outcomes, so comparing corrected
 * predictions with them agrees by construction: the card could only ever say "close", and never show
 * the gap the factor exists to close.
 *
 * Scoped to the model scheduling the user RIGHT NOW, like the scheduler's evidence: FSRS-5 and FSRS-6
 * fit different curves, and pooling them would describe neither.
 */
internal fun calibrationStatsOf(logs: List<ReviewLogEntity>): ProgressViewModel.CalibrationStats? {
    val evidence = logs
        .filter {
            it.logType == "RECALL" &&
                it.schedulerVersion == MedScheduler.CURRENT_MODEL.id &&
                it.retrievabilityAtReview in 0.0..1.0 &&
                RecallCalibration.isEvidence(it.elapsedDays, it.previousIntervalDays)
        }
        .takeLast(RecallCalibration.WINDOW)
    if (evidence.size < 10) return null // too little to say anything
    val predicted = DoubleArray(evidence.size) { evidence[it].retrievabilityAtReview }
    val recalled = BooleanArray(evidence.size) { evidence[it].memoryRating != "Forgot" }
    return ProgressViewModel.CalibrationStats(
        n = evidence.size,
        predictedPct = Math.round(predicted.average() * 100).toInt(),
        actualPct = Math.round(recalled.count { it } * 100.0 / evidence.size).toInt(),
        model = MedScheduler.CURRENT_MODEL.id,
        scale = RecallCalibration.scale(predicted, recalled),
    )
}
