package com.example.domain.model

import com.example.data.ResearchId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pilot's research fields: stored forms, validation, and the pseudonymous id. */
class ReviewMetadataTest {

    @Test fun methods_round_trip_in_declaration_order() {
        val stored = ReviewMethod.encode(setOf(ReviewMethod.Other, ReviewMethod.Questions, ReviewMethod.Lecture))
        assertEquals("one canonical form whatever the tap order", "Questions,Lecture,Other", stored)
        assertEquals(setOf(ReviewMethod.Questions, ReviewMethod.Lecture, ReviewMethod.Other), ReviewMethod.decode(stored))
    }

    @Test fun no_method_is_stored_as_null_and_unknown_names_are_dropped() {
        assertNull("nothing said = not recorded", ReviewMethod.encode(emptySet()))
        assertTrue(ReviewMethod.decode(null).isEmpty())
        assertTrue(ReviewMethod.decode("").isEmpty())
        assertEquals("a newer build's value must not break an older reader",
            setOf(ReviewMethod.Reading), ReviewMethod.decode("Reading, Hologram"))
    }

    @Test fun a_question_score_is_kept_only_when_it_is_a_real_count() {
        assertEquals(14 to 20, QuestionScore.normalized(14, 20))
        assertEquals("all right", 20 to 20, QuestionScore.normalized(20, 20))
        assertEquals("none right is a real score", 0 to 5, QuestionScore.normalized(0, 5))
        assertEquals("more right than answered", -1 to -1, QuestionScore.normalized(21, 20))
        assertEquals("nothing answered", -1 to -1, QuestionScore.normalized(0, 0))
        assertEquals("half entered", -1 to -1, QuestionScore.normalized(7, null))
        assertEquals("negative", -1 to -1, QuestionScore.normalized(-3, 10))
        assertEquals("absurd total", -1 to -1, QuestionScore.normalized(5, 5000))
        assertFalse(QuestionScore.isValid(-1, -1))
    }

    /** The review screen says why a typed score will not be kept: it used to vanish without a word (2026-09-30). */
    @Test fun a_score_that_will_not_be_kept_has_a_reason() {
        assertNull("nothing typed", QuestionScore.problem(null, null))
        assertNull("a real count", QuestionScore.problem(7, 9))
        assertNull("none right", QuestionScore.problem(0, 1))
        assertNull("the largest total", QuestionScore.problem(999, 999))
        assertEquals(QuestionScore.Problem.INCOMPLETE, QuestionScore.problem(7, null))
        assertEquals(QuestionScore.Problem.INCOMPLETE, QuestionScore.problem(null, 9))
        assertEquals(QuestionScore.Problem.MORE_RIGHT_THAN_ASKED, QuestionScore.problem(9, 5))
        assertEquals(QuestionScore.Problem.BAD_TOTAL, QuestionScore.problem(0, 0))
        assertEquals(QuestionScore.Problem.BAD_TOTAL, QuestionScore.problem(5, 1000))
        // Every pair with no reason is exactly a pair that is kept.
        for (c in -1..12) for (t in -1..12) {
            assertEquals("$c/$t", QuestionScore.problem(c, t) == null, QuestionScore.normalized(c, t) != (-1 to -1))
        }
    }

    /**
     * Persian digits typed on a Persian keyboard ARE kept: Kotlin's toIntOrNull reads any Unicode decimal digit. An
     * outside report (2026-09-30) said they were silently dropped; it was not true, and this pins why.
     */
    @Test fun a_score_typed_in_persian_or_arabic_digits_is_read() {
        assertEquals(8, "۸".toIntOrNull())
        assertEquals(12, "۱۲".toIntOrNull())
        assertEquals(3, "٣".toIntOrNull())
        assertEquals(8 to 10, QuestionScore.normalized(" ۸ ".trim().toIntOrNull(), "۱۰".toIntOrNull()))
        assertTrue("the field's filter lets them through", "۸a۱".filter(Char::isDigit) == "۸۱")
    }

    @Test fun research_ids_are_pseudonymous_well_formed_and_varied() {
        val rng = java.util.Random(42)
        val ids = (1..500).map { ResearchId.generate(rng) }
        assertTrue("format YD-XXXX-XXXX from the unambiguous alphabet: ${ids.first()}", ids.all { ResearchId.isWellFormed(it) })
        assertTrue("no look-alike characters", ids.none { id -> id.drop(3).any { it in "01OILU" } })
        assertEquals("~39 bits: no collisions in a pilot-sized sample", ids.size, ids.toSet().size)
        assertFalse(ResearchId.isWellFormed("YD-0000-0000"))
        assertFalse(ResearchId.isWellFormed("alice"))
        assertFalse(ResearchId.isWellFormed(null))
    }
}
