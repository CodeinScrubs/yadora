package com.example.domain.srs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Key points are reference text shown with a topic's notes; these pin how they are stored and read. */
class KeyPointsTest {

    @Test
    fun `parse keeps the learner's points in order and drops only blank lines and padding`() {
        assertEquals(
            listOf("McBurney's point tenderness", "Alvarado score", "Appendectomy"),
            KeyPoints.parse("  McBurney's point tenderness \r\n\n Alvarado score\n\n\nAppendectomy  \n"),
        )
        assertEquals(emptyList<String>(), KeyPoints.parse(null))
        assertEquals(emptyList<String>(), KeyPoints.parse(" \n\t\n "))
    }

    @Test
    fun `parse never hides points beyond the editor's limit`() {
        val many = (1..20).joinToString("\n") { "point $it" }
        assertEquals("a restored backup's twenty points are all shown", 20, KeyPoints.parse(many).size)
        assertFalse("but the editor would not have accepted them", KeyPoints.withinLimit(many))
        assertTrue(KeyPoints.withinLimit((1..KeyPoints.MAX_POINTS).joinToString("\n") { "p$it" }))
    }

    @Test
    fun `normalize stores one point per line, or nothing at all`() {
        assertEquals("a\nb", KeyPoints.normalize(" a \n\n b "))
        assertNull(KeyPoints.normalize("   \n  "))
        assertNull(KeyPoints.normalize(null))
    }
}
