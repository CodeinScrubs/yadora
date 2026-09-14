package com.example.domain.srs

import com.example.domain.model.MemoryRating

/**
 * KEY POINTS: the answer to a topic split into the few ideas a complete recall must contain.
 *
 * Every interval FSRS computes rests on the memory rating, and a rating given as a global feeling
 * ("did I remember Appendicitis?") is where most of the error lives. Learners judge partly wrong
 * answers as right, and the more overconfident ones stop practising too early and retain less
 * (Dunlosky & Rawson 2012). Scoring a recall against the correct answer broken into idea units
 * measurably reduces that overconfidence (Dunlosky, Hartwig, Rawson & Lipko 2011). The per-user
 * calibration cannot catch an optimistic judge on its own, because it learns from the same ratings:
 * simulated, a learner who calls a quarter of failed recalls "Hard" drove the scale to 1.44 and real
 * recall at review down to 0.895 while the ratings reported 0.92.
 *
 * The ticks set a CEILING, never a floor. All points recalled allows any rating; at least half
 * allows up to Hard (a success, recalled with difficulty); fewer than half means the topic was not
 * recalled. A learner may always rate lower than the ticks allow. The half threshold is POLICY: in a
 * three-year simulation of topics holding 3, 5 and 7 points it cost almost nothing over a global
 * judgement (1.42 against 1.39 reviews a year for five points) while lifting recall of the points
 * themselves; stricter thresholds bought more recall at two to three times the reviews, which is the
 * retention slider's job, not the rating's.
 */
object KeyPoints {

    /** The editor stops accepting new points here: past a dozen, a topic has become a flashcard deck. */
    const val MAX_POINTS = 12

    /**
     * The points in a stored value, in order: one per line, surrounding whitespace and blank lines
     * dropped. Never truncated — a restored backup may hold more than the editor allows, and hiding
     * part of what the learner wrote would change what "all of it" means without telling them.
     */
    fun parse(stored: String?): List<String> =
        stored.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** What to store for what the learner typed: one point per line, or null when there is none. */
    fun normalize(typed: String?): String? = parse(typed).takeIf { it.isNotEmpty() }?.joinToString("\n")

    /** Whether the editor may accept [typed]: at most [MAX_POINTS] non-blank lines. */
    fun withinLimit(typed: String): Boolean = parse(typed).size <= MAX_POINTS

    /**
     * The highest rating [recalled] of [total] key points supports, or null when the topic has no key
     * points (every rating allowed, exactly as before key points existed). Out-of-range counts are
     * clamped rather than trusted, so a stale tick set can never unlock more than the points shown.
     */
    fun ceiling(total: Int, recalled: Int): MemoryRating? {
        if (total <= 0) return null
        val got = recalled.coerceIn(0, total)
        return when {
            got == total -> MemoryRating.Easy
            got * 2 >= total -> MemoryRating.Hard
            else -> MemoryRating.Forgot
        }
    }

    /** Whether [rating] is allowed after [recalled] of [total] key points. */
    fun allows(rating: MemoryRating, total: Int, recalled: Int): Boolean {
        val cap = ceiling(total, recalled) ?: return true
        return rank(rating) <= rank(cap)
    }

    private fun rank(rating: MemoryRating): Int = when (rating) {
        MemoryRating.Forgot -> 0
        MemoryRating.Hard -> 1
        MemoryRating.Good -> 2
        MemoryRating.Easy -> 3
    }
}
