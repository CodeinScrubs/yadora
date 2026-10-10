package com.example.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration

/**
 * Links inside a topic's notes, made tappable: a Notion page, a video, a question-bank block. Notes stay
 * plain text as typed; only the display turns a web address into a link.
 */
object NoteLinks {
    private val URL = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"]+""")

    /**
     * Punctuation that ends a sentence rather than the address ("see https://x.org/a."), in Persian too: its question
     * mark, guillemets and curly quotes were left in the address (a production review, 2026-10-10).
     */
    private const val TRAILING = ".,;:!?'\"،؛؟»«”“’‘…)]}"

    data class Found(val range: IntRange, val url: String)

    fun find(text: String): List<Found> = URL.findAll(text).mapNotNull { m ->
        var end = m.range.last
        while (end >= m.range.first && text[end] in TRAILING) {
            // A closing bracket that closes one opened inside the address belongs to it (Wikipedia URLs).
            val ch = text[end]
            val opener = when (ch) { ')' -> '('; ']' -> '['; '}' -> '{'; else -> null }
            if (opener != null && text.substring(m.range.first, end).count { it == opener } >
                text.substring(m.range.first, end).count { it == ch }
            ) break
            end--
        }
        if (end < m.range.first) return@mapNotNull null
        val raw = text.substring(m.range.first, end + 1)
        val url = if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw
        Found(m.range.first..end, url)
    }.toList()

    fun annotate(text: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
        append(text)
        val style = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
        for (f in find(text)) {
            addLink(LinkAnnotation.Url(f.url, style), f.range.first, f.range.last + 1)
        }
    }
}
