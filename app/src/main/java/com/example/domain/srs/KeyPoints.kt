package com.example.domain.srs

/**
 * KEY POINTS: an optional short list of the ideas a topic covers, one per line.
 *
 * REFERENCE ONLY since 2026-09-23 (user decision). Key points used to be a scoring standard: after a
 * recall attempt the learner ticked the ones they produced and the ticks capped the memory rating.
 * That assumed every review is a recall test taken inside the app. It is not: a Yadora review is
 * whatever the learner chooses — questions, rereading, a lecture, a video — done wherever they study,
 * and the rating afterwards says how much of the topic they still had. A tick list fits one of those
 * methods and gets in the way of the others, so the cap was retired. Stored points are still shown
 * with the topic's notes, and old logs keep the scores they recorded (`keyPointsTotal` /
 * `keyPointsRecalled`); new reviews record -1, "not scored".
 */
object KeyPoints {

    /** The editor stops accepting new points here: past a dozen, a topic has become a flashcard deck. */
    const val MAX_POINTS = 12

    /**
     * The points in a stored value, in order: one per line, surrounding whitespace and blank lines
     * dropped. Never truncated — a restored backup may hold more than the editor allows, and hiding
     * part of what the learner wrote would silently drop their own content.
     */
    fun parse(stored: String?): List<String> =
        stored.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** What to store for what the learner typed: one point per line, or null when there is none. */
    fun normalize(typed: String?): String? = parse(typed).takeIf { it.isNotEmpty() }?.joinToString("\n")

    /** Whether the editor may accept [typed]: at most [MAX_POINTS] non-blank lines. */
    fun withinLimit(typed: String): Boolean = parse(typed).size <= MAX_POINTS
}
