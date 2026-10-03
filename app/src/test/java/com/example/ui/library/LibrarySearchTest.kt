package com.example.ui.library

import com.example.data.local.entity.StudyUnitEntity
import com.example.data.text.TopicTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The Library search folds each topic's texts once per change of the list (LibrarySearch, 2026-10-03), not on every
 * keystroke. It must find exactly what the per-keystroke search found, for Persian, Arabic and English text alike.
 */
class LibrarySearchTest {

    /** The search as it was before the index: every text folded for every keystroke. The rule the index must keep. */
    private fun reference(
        units: List<StudyUnitEntity>,
        query: String,
        subjects: Map<Long, String>,
        systems: Map<Long, String>,
    ): List<StudyUnitEntity> {
        val key = TopicTitle.searchKey(query)
        if (key.isEmpty()) return units
        fun has(text: String?) = text != null && TopicTitle.searchKey(text).contains(key)
        val subjectHits = subjects.filterValues { has(it) }.keys
        val systemHits = systems.filterValues { has(it) }.keys
        return units.filter { u ->
            has(u.title) || has(u.studyType) || has(u.recallPrompt) || has(u.keyPoints) || has(u.notes) || has(u.source) ||
                u.subjectId in subjectHits || u.systemId in systemHits
        }
    }

    // Persian keheh and Farsi yeh against the Arabic kaf and yeh a keyboard may type instead, presentation forms, a
    // half-space (ZWNJ), Persian and Latin digits, doubled spaces and mixed case.
    private val words = listOf(
        "Appendicitis", "acute", "ACUTE", "Heart  failure", "pneumonia", "ECG",
        "کلیه", "كليه", "ﻛﻠﻴﻪ",
        "قلب", "نارسایی", "می‌خواهم",
        "فصل ۱", "فصل 1", "پنومونی", "IV", "Ⅳ",
    )

    private fun library(n: Int, rng: Random, notesWords: Int = 20): List<StudyUnitEntity> = (1..n).map { id ->
        fun text(maxWords: Int) = (0 until rng.nextInt(1, maxWords + 1)).joinToString(" ") { words[rng.nextInt(words.size)] }
        StudyUnitEntity(
            id = id.toLong(),
            title = text(3),
            studyType = if (rng.nextInt(4) == 0) "Lecture" else "Topic",
            studiedAt = 0L,
            nextReviewAt = 0L,
            recallPrompt = if (rng.nextBoolean()) text(4) else null,
            keyPoints = if (rng.nextInt(5) == 0) text(6) else null,
            notes = if (rng.nextBoolean()) text(notesWords) else null,
            source = if (rng.nextInt(3) == 0) text(2) else null,
            subjectId = if (rng.nextInt(6) == 0) null else rng.nextLong(1, 6),
            systemId = if (rng.nextInt(3) == 0) null else rng.nextLong(1, 5),
        )
    }

    private val subjects = mapOf(1L to "Cardiology", 2L to "قلب و عروق", 3L to "Nephrology",
        4L to "كليه", 5L to "Infectious")
    private val systems = mapOf(1L to "Internal medicine", 2L to "داخلی", 3L to "Surgery", 4L to "Step 2")

    @Test fun the_index_finds_exactly_what_the_per_keystroke_search_found() {
        val rng = Random(7)
        val units = library(400, rng)
        val index = LibrarySearch.index(units)
        val queries = words + words.flatMap { w -> (1..w.length).map { w.take(it) } } +
            listOf("", " ", "  acute  ", "a", "1", "۱", "ي", "ی", "card", "قلب و", "step", "zzz")
        for (q in queries.distinct()) {
            assertEquals("query \"$q\"", reference(units, q, subjects, systems).map { it.id },
                LibrarySearch.search(index, q, subjects, systems).map { it.id })
        }
        // A blank query keeps the list as it is, in its order.
        assertEquals(units.map { it.id }, LibrarySearch.search(index, "  ", subjects, systems).map { it.id })
    }

    /** Typing in a year's library: about 2,000 topics, many with long notes. Printed, so a slow phone build shows. */
    @Test fun a_keystroke_no_longer_folds_the_whole_library() {
        val units = library(2_000, Random(11), notesWords = 300)
        val queries = listOf("p", "pn", "pne", "pneu", "pneum", "ک", "کل", "کلی")
        fun best(block: () -> Unit): Double = (1..3).minOf { val t = System.nanoTime(); block(); (System.nanoTime() - t) / 1e6 }
        val before = best { queries.forEach { reference(units, it, subjects, systems) } } / queries.size
        lateinit var index: List<LibrarySearch.Entry>
        val indexing = best { index = LibrarySearch.index(units) }
        val now = best { queries.forEach { LibrarySearch.search(index, it, subjects, systems) } } / queries.size
        println("Library search, 2,000 topics with long notes: %.1f ms a keystroke before, %.1f ms now (the index: %.0f ms, once per change of the list)"
            .format(before, now, indexing))
        assertTrue("a keystroke must cost less than folding every text again ($now ms vs $before ms)", now < before)
    }
}
