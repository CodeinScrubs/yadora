package com.example.ui.add

import com.example.data.local.dao.RelatedTopicTitle
import org.junit.Assert.*
import org.junit.Test

class RelatedTopicsTest {
    private fun topic(id: Long, title: String, subject: Long? = null, archived: Boolean = false) =
        RelatedTopicTitle(id, title, subject, archived)

    @Test fun `Persian search finds the whole topic and narrower titles without conflating them`() {
        val topics = listOf(topic(1, "آسم"), topic(2, "درمان آسم"), topic(3, "افتراق آسم و COPD"), topic(4, "آنمی"))
        val found = RelatedTopics.search(RelatedTopics.index(topics), "اسم", null)
        assertEquals(setOf(1L, 2L, 3L), found.map { it.id }.toSet())
        assertEquals(1L, found.first().id)
        assertEquals("آسم", found.first().title)
    }

    @Test fun `keyboard variants spaces digits and word order match`() {
        val index = RelatedTopics.index(listOf(topic(1, "درمان كم‌كاري تيروئيد ۱۲")))
        assertEquals(1, RelatedTopics.search(index, "تیروئید 12 درمان", null).size)
        assertEquals(1, RelatedTopics.search(index, "کم کاری", null).size)
        assertTrue(RelatedTopics.search(index, "درمان آسم", null).isEmpty())
        assertTrue(RelatedTopics.search(index, "   ", null).isEmpty())
    }

    @Test fun `exact matches rank first while subject and archive state disambiguate`() {
        val topics = listOf(topic(1, "Asthma treatment", 2), topic(2, "Asthma", 1),
            topic(3, "Asthma", 2, true), topic(4, "ASTHMA", 2))
        assertEquals(listOf(4L, 3L, 2L, 1L),
            RelatedTopics.search(RelatedTopics.index(topics), "asthma", 2).map { it.id })
    }

    @Test fun `a two-thousand-topic index keeps all candidates and updates after a rename`() {
        val topics = (1L..2000L).map { topic(it, "Asthma section $it") }
        val index = RelatedTopics.index(topics)
        assertEquals(2000, RelatedTopics.search(index, "asthma", null).size)
        assertEquals(1999L, RelatedTopics.search(index, "section 1999", null).single().id)
        val renamed = RelatedTopics.index(topics.map { if (it.id == 1999L) it.copy(title = "Anemia") else it })
        assertTrue(RelatedTopics.search(renamed, "section 1999", null).isEmpty())
        assertEquals(1999L, RelatedTopics.search(renamed, "anemia", null).single().id)
    }
}
