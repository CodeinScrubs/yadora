package com.example.domain.model

/**
 * The learner's own rough estimate of how long a review or a first study took (the owner's decision, 2026-10-09).
 *
 * Optional quick choices under the understanding question. Choosing none means NOT KNOWN, never zero: the learner said
 * they will often skip it, and an estimate is a guess, not a measurement. 60 stands for "60 minutes or more". Nothing
 * schedules from it. It is what the logs need to say whether about 2,000 topics fit one year, which review count alone
 * cannot (RESEARCH.md §2.8: the time a review takes decides it). Distinct from `reviewDurationMs`, the seconds the
 * rating screen was open.
 */
object StudyMinutes {
    /** Not given (the default), and every review saved before DB v11. */
    const val NOT_GIVEN = -1

    /** The quick choices, in minutes; the last means that many or more. */
    val CHOICES = listOf(10, 20, 30, 45, 60)

    /** A stored value is either not given or a plausible whole number of minutes (a day at most). */
    fun isValid(minutes: Int): Boolean = minutes == NOT_GIVEN || minutes in 1..24 * 60

    /** What a commit stores: a valid estimate, otherwise not given. */
    fun normalized(minutes: Int?): Int = minutes?.takeIf { it != NOT_GIVEN && isValid(it) } ?: NOT_GIVEN
}
