package com.example.data

import com.example.data.text.TopicTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Duplicate detection for the case this app was actually built around: the same material entered
 * twice, often in two languages.
 *
 * Persian is where byte comparison fails silently. Two codepoint pairs render identically and are
 * chosen by the keyboard, not the writer, so the user has no way to see that the strings differ.
 */
class TopicTitleTest {

    @Test
    fun `arabic and farsi yeh are the same word`() {
        // Appendicitis, written with U+064A ARABIC YEH vs U+06CC FARSI YEH.
        assertTrue(TopicTitle.sameTopic("آپاندیسیت", "آپاندیسيت"))
    }

    @Test
    fun `arabic kaf and keheh are the same word`() {
        // "kidney" with U+0643 ARABIC KAF vs U+06A9 KEHEH.
        assertTrue(TopicTitle.sameTopic("کلیه", "كلیه"))
    }

    @Test
    fun `decorative marks and joiners do not create a second topic`() {
        assertTrue("tatweel is decoration", TopicTitle.sameTopic("آپاندیسیت", "آپانـدیسیت"))
        assertTrue("zero-width non-joiner", TopicTitle.sameTopic("می‌شود", "میشود"))
        assertTrue("harakat", TopicTitle.sameTopic("قَلب", "قلب"))
    }

    @Test
    fun `latin titles still fold on case and spacing`() {
        assertTrue(TopicTitle.sameTopic("Acute Appendicitis", "acute appendicitis"))
        assertTrue(TopicTitle.sameTopic("Acute  Appendicitis ", "Acute Appendicitis"))
        // SQLite's lower() is ASCII-only, so this pair used to slip through.
        assertTrue(TopicTitle.sameTopic("Äpfel", "äpfel"))
    }

    @Test
    fun `genuinely different topics stay different`() {
        assertFalse(TopicTitle.sameTopic("Appendicitis", "Appendectomy"))
        assertFalse(TopicTitle.sameTopic("آپاندیسیت", "کلیه"))
        assertFalse("an empty title matches nothing real", TopicTitle.sameTopic("", "Appendicitis"))
    }

    @Test
    fun `normalization is idempotent and never throws`() {
        for (s in listOf("", " ", "آپاندیسیت", "Äpfel", "ﻙﻟﻴﻪ", "‌‍", "123")) {
            val once = TopicTitle.normalize(s)
            assertEquals("normalizing twice must change nothing for '$s'", once, TopicTitle.normalize(once))
        }
    }

    /** The stored title is the user's own text — normalization is for comparison only. */
    @Test
    fun `normalization does not claim to be a display form`() {
        val typed = "  Acute   Appendicitis  "
        assertEquals("acute appendicitis", TopicTitle.normalize(typed))
        assertTrue("and the original is untouched", typed.contains("   "))
    }

    /**
     * Library search folds what the keyboard chose, not the writer: Arabic yeh and kaf for Persian ones, and a Persian
     * compound joined with a space, a half-space or nothing. Raw text comparison missed all of them (2026-09-30).
     */
    @Test
    fun `search finds a topic however its Persian was typed`() {
        fun found(query: String, title: String) = TopicTitle.searchKey(title).contains(TopicTitle.searchKey(query))
        assertTrue("Arabic yeh in the query", found("آنمي", "آنمی فقر آهن"))
        assertTrue("Arabic kaf in the query", found("كبد", "بیماری‌های کبد"))
        assertTrue("half-space in the title, space in the query", found("میکروب شناسی", "میکروب‌شناسی بالینی"))
        assertTrue("joined in the query", found("میکروبشناسی", "میکروب‌شناسی بالینی"))
        assertTrue("Latin case", found("heart FAILURE", "Acute Heart Failure"))
        assertFalse("a different word is still not found", found("قلب", "آنمی فقر آهن"))
    }
}
