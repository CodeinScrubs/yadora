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
