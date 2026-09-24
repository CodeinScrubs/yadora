package com.example.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteLinksTest {

    private fun urls(text: String) = NoteLinks.find(text).map { it.url }

    @Test fun `web addresses in notes are found and sentence punctuation is left out`() {
        assertEquals(listOf("https://notion.so/Cardio-12ab"), urls("See https://notion.so/Cardio-12ab."))
        assertEquals(listOf("http://example.org/a?b=1&c=2"), urls("(http://example.org/a?b=1&c=2)"))
        assertEquals(listOf("https://www.youtube.com/watch?v=x"), urls("video: www.youtube.com/watch?v=x, then questions"))
        assertEquals(listOf("https://en.wikipedia.org/wiki/Heart_(disambiguation)"),
            urls("https://en.wikipedia.org/wiki/Heart_(disambiguation)"))
    }

    @Test fun `Persian text around a link and plain notes`() {
        assertEquals(listOf("https://uworld.com/q/123"), urls("سوال‌های https://uworld.com/q/123، صفحهٔ ۴۵"))
        assertEquals(emptyList<String>(), urls("Harrison ch. 12, pages 40-55"))
        assertEquals(emptyList<String>(), urls(""))
    }

    @Test fun `ranges point at the address inside the text`() {
        val text = "read https://a.org/x. then rest"
        val f = NoteLinks.find(text).single()
        assertEquals("https://a.org/x", text.substring(f.range.first, f.range.last + 1))
    }
}
