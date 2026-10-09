package com.example.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Which day a review happened on, when it was not today (the owner's decision, 2026-10-09).
 *
 * By default a rating is saved as "finished just now". A "Reviewed: today" chip on the rating screen can say yesterday or
 * an earlier day, and the day of a logged review can be corrected from the topic's history. Only the DAY is chosen:
 * FSRS-6 counts whole local calendar days ([com.example.domain.srs.MedScheduler.modelElapsedDays]), so the hour only
 * keeps a topic's reviews in the order they happened. Pure, so the bounds and the times are tested on their own.
 */
object ReviewDay {
    /** How far back a correction of a topic's first log may go, when no earlier review bounds it. */
    const val FIRST_LOG_DAYS_BACK = 365L

    fun day(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    /**
     * The earliest day a NEW rating may claim: the day of the topic's last review, or for a topic never rated the day it
     * was studied. Never after today.
     */
    fun earliestForRating(lastReviewedAt: Long?, studiedAt: Long, now: Long, zone: ZoneId): LocalDate =
        minOf(day(now, zone), day(lastReviewedAt ?: studiedAt, zone))

    /**
     * The time a new rating claimed for [chosen] is saved with: [now] for today; otherwise that day at this hour, kept
     * after the topic's last review (two reviews of one day stay in their order) and never after [now].
     */
    fun timeForRating(chosen: LocalDate, now: Long, lastReviewedAt: Long?, zone: ZoneId): Long {
        if (chosen >= day(now, zone)) return now
        val atThisHour = chosen.atTime(Instant.ofEpochMilli(now).atZone(zone).toLocalTime()).atZone(zone).toInstant().toEpochMilli()
        val afterLast = if (lastReviewedAt != null && atThisHour <= lastReviewedAt) lastReviewedAt + 1 else atThisHour
        return minOf(afterLast, now)
    }

    /**
     * The days a logged review may be moved to: from the day of the review saved before it to the day of the one saved
     * after it (or today), so a correction never changes the order of a topic's reviews. Null when no day fits (a
     * clock that went back can leave the neighbours in the wrong order).
     */
    fun correctionRange(previous: Long?, next: Long?, now: Long, zone: ZoneId): ClosedRange<LocalDate>? {
        val today = day(now, zone)
        val first = previous?.let { day(it, zone) } ?: today.minusDays(FIRST_LOG_DAYS_BACK)
        val last = minOf(today, next?.let { day(it, zone) } ?: today)
        return if (first <= last) first..last else null
    }

    /**
     * The time a corrected review is saved with: [chosen] at the review's own hour, kept strictly between its
     * neighbours and never after [now].
     */
    fun timeForCorrection(chosen: LocalDate, original: Long, previous: Long?, next: Long?, now: Long, zone: ZoneId): Long {
        var t = chosen.atTime(Instant.ofEpochMilli(original).atZone(zone).toLocalTime()).atZone(zone).toInstant().toEpochMilli()
        if (previous != null && t <= previous) t = previous + 1
        if (next != null && t >= next) t = next - 1
        return minOf(t, now)
    }
}
