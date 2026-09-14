package com.example.domain.srs

import com.example.domain.model.MemoryRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Key points turn a recall into a scored answer; these pin how the ticks cap the rating. */
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

    @Test
    fun `without key points there is no ceiling`() {
        assertNull(KeyPoints.ceiling(0, 0))
        assertNull(KeyPoints.ceiling(-1, -1))
        MemoryRating.entries.forEach { assertTrue("$it allowed", KeyPoints.allows(it, 0, 0)) }
    }

    @Test
    fun `the ceiling table`() {
        // total, recalled -> highest rating allowed
        val table = listOf(
            Triple(1, 1, MemoryRating.Easy), Triple(1, 0, MemoryRating.Forgot),
            Triple(2, 2, MemoryRating.Easy), Triple(2, 1, MemoryRating.Hard), Triple(2, 0, MemoryRating.Forgot),
            Triple(3, 3, MemoryRating.Easy), Triple(3, 2, MemoryRating.Hard), Triple(3, 1, MemoryRating.Forgot),
            Triple(4, 3, MemoryRating.Hard), Triple(4, 2, MemoryRating.Hard), Triple(4, 1, MemoryRating.Forgot),
            Triple(5, 5, MemoryRating.Easy), Triple(5, 4, MemoryRating.Hard), Triple(5, 3, MemoryRating.Hard),
            Triple(5, 2, MemoryRating.Forgot), Triple(5, 0, MemoryRating.Forgot),
            Triple(7, 4, MemoryRating.Hard), Triple(7, 3, MemoryRating.Forgot),
        )
        for ((total, recalled, expected) in table) {
            assertEquals("$recalled of $total", expected, KeyPoints.ceiling(total, recalled))
        }
    }

    @Test
    fun `out-of-range tick counts are clamped, never trusted`() {
        assertEquals(MemoryRating.Easy, KeyPoints.ceiling(3, 9))
        assertEquals(MemoryRating.Forgot, KeyPoints.ceiling(3, -4))
    }

    @Test
    fun `the ticks cap the rating but a learner can always rate lower`() {
        // 3 of 5: up to Hard.
        assertTrue(KeyPoints.allows(MemoryRating.Forgot, 5, 3))
        assertTrue(KeyPoints.allows(MemoryRating.Hard, 5, 3))
        assertFalse(KeyPoints.allows(MemoryRating.Good, 5, 3))
        assertFalse(KeyPoints.allows(MemoryRating.Easy, 5, 3))
        // 1 of 5: only Forgot.
        assertTrue(KeyPoints.allows(MemoryRating.Forgot, 5, 1))
        assertFalse(KeyPoints.allows(MemoryRating.Hard, 5, 1))
        // All of them: anything.
        MemoryRating.entries.forEach { assertTrue("$it allowed at 5 of 5", KeyPoints.allows(it, 5, 5)) }
    }

    @Test
    fun `recalling more never lowers the ceiling`() {
        for (total in 1..KeyPoints.MAX_POINTS) {
            val caps = (0..total).map { KeyPoints.ceiling(total, it)!!.let { r -> MemoryRating.entries.indexOf(r) } }
            val ranks = (0..total).map { KeyPoints.ceiling(total, it)!! }.map { r ->
                when (r) { MemoryRating.Forgot -> 0; MemoryRating.Hard -> 1; MemoryRating.Good -> 2; MemoryRating.Easy -> 3 }
            }
            assertTrue("monotone for $total points: $ranks", ranks.zipWithNext().all { (a, b) -> b >= a })
            assertEquals(caps.size, total + 1)
        }
    }
}
