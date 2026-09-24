package com.example.domain.model

enum class StudyType(val displayName: String) {
    Anatomy("Anatomy"),
    Physiology("Physiology"),
    Pathology("Pathology"),
    Pharmacology("Pharmacology"),
    Microbiology("Microbiology"),
    Biochemistry("Biochemistry"),
    ClinicalMedicine("Clinical Medicine"),
    Other("Other")
}

enum class MemoryRating {
    Forgot, Hard, Good, Easy
}

enum class UnderstandingRating {
    Confused, Partial, Clear
}

enum class StudyState(val displayName: String) {
    New("New"),
    Learning("Learning"),
    Building("Building"),
    Strong("Strong"),
    NeedsRelearn("Needs Relearn")
}

/**
 * How a review was done, as the learner reports it (optional, several allowed). Research data for the
 * pilot: whether the method changes what the next review finds is a question the logs can answer only if
 * the method was recorded. Nothing schedules from it.
 */
enum class ReviewMethod {
    /** Question bank, past papers or flashcards: anything that makes you answer before you look. */
    Questions,
    /** Rereading notes, a book or slides. */
    Reading,
    /** A lecture, video or podcast. */
    Lecture,
    Other;

    companion object {
        /** Stored form: names joined by commas in declaration order, or null for none. */
        fun encode(methods: Set<ReviewMethod>): String? =
            entries.filter { it in methods }.joinToString(",").ifEmpty { null }

        /** Unknown names are dropped rather than failing: a newer build's value must not break an older reader. */
        fun decode(stored: String?): Set<ReviewMethod> =
            stored.orEmpty().split(',').mapNotNull { name -> entries.firstOrNull { it.name == name.trim() } }.toSet()
    }
}

/** Which kind of review session produced a log. Research data: an early review is a different measurement. */
enum class SessionKind {
    /** Today's plan (due topics within the daily limit, first ratings first). */
    PLAN,
    /** "Review more anyway": due topics past the daily limit. */
    EXTRA,
    /** One topic opened on purpose: a Today card, the Library's review-now, or "Save and rate now". */
    TOPIC,
    /** "Review ahead": topics not yet due, weakest predicted recall first. */
    AHEAD,
}

/** A question score is kept only when it is a real count: 1..999 answered and 0..answered right. */
object QuestionScore {
    const val MAX_TOTAL = 999

    fun isValid(correct: Int, total: Int): Boolean = total in 1..MAX_TOTAL && correct in 0..total

    /** (correct, total) to store: the pair itself when valid, otherwise (-1, -1) = not recorded. */
    fun normalized(correct: Int?, total: Int?): Pair<Int, Int> =
        if (correct != null && total != null && isValid(correct, total)) correct to total else -1 to -1
}
