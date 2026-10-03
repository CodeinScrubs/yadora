package com.example.data.text

import java.text.Normalizer
import java.util.Locale

/**
 * Canonical form of a topic title, used ONLY for comparison — never for display or storage.
 *
 * Duplicate detection used to be SQL `lower(trim(title))`, which is byte equality with an ASCII-only
 * lowercase. That misses the single most likely duplicate in this app. Persian text routinely mixes
 * two codepoints that render identically depending on the keyboard:
 *
 *   ی  U+06CC FARSI YEH        vs  ي  U+064A ARABIC YEH
 *   ک  U+06A9 KEHEH            vs  ك  U+0643 ARABIC KAF
 *
 * So "آپاندیسیت" typed on one keyboard and the same word typed on another are different strings, and
 * the user adding the same topic twice — the exact scenario merge exists for — got no warning.
 *
 * SQLite's `lower()` is also ASCII-only, so "Äpfel" and "äpfel" did not match either, which matters
 * for the German locale.
 *
 * Deliberately NOT applied to stored titles: the user typed what they typed, and Yadora shows it
 * back verbatim.
 */
object TopicTitle {

    private const val ZERO_WIDTH_NON_JOINER = '‌'
    private const val ZERO_WIDTH_JOINER = '‍'
    private const val ARABIC_TATWEEL = 'ـ'

    /** Arabic diacritics/harakat: decorative in Persian, and typed inconsistently. */
    private val ARABIC_MARKS = 'ً'..'ٟ'
    private val ARABIC_MARKS_EXTRA = 'ٰ'..'ٰ'

    fun normalize(raw: String): String {
        // NFKC folds compatibility forms (presentation-form Arabic letters, full-width Latin) onto
        // their canonical equivalents before any of the manual mapping below.
        val nfkc = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        val sb = StringBuilder(nfkc.length)
        for (ch in nfkc) {
            val mapped = when (ch) {
                'ي', 'ى' -> 'ی' // Arabic yeh / alef maksura -> Farsi yeh
                'ك' -> 'ک'           // Arabic kaf -> keheh
                'ة' -> 'ه'           // teh marbuta -> heh (Persian spelling of Arabic loans)
                'أ', 'إ', 'آ' -> 'ا' // hamza-carrying alefs -> bare alef
                'ؤ' -> 'و'           // waw with hamza -> waw
                'ئ' -> 'ی'           // yeh with hamza -> yeh
                // Persian and Arabic-Indic digits -> ASCII: NFKC leaves them alone, and a Persian keyboard types
                // "فصل ۱" for a topic saved as "فصل 1" (an outside report, 2026-10-03).
                in '۰'..'۹' -> '0' + (ch - '۰')
                in '٠'..'٩' -> '0' + (ch - '٠')
                else -> ch
            }
            when {
                mapped == ARABIC_TATWEEL -> Unit // kashida is pure decoration
                mapped == ZERO_WIDTH_NON_JOINER || mapped == ZERO_WIDTH_JOINER -> Unit
                mapped in ARABIC_MARKS || mapped in ARABIC_MARKS_EXTRA -> Unit
                else -> sb.append(mapped)
            }
        }
        // Collapse every run of whitespace to one space so "Acute  appendicitis" matches
        // "Acute appendicitis". Locale.ROOT: a Turkish locale would otherwise lowercase I to a
        // dotless ı and make two identical English titles stop matching on that device only.
        return sb.toString().trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
    }

    /** True when two titles are the same material as far as duplicate detection is concerned. */
    fun sameTopic(a: String, b: String): Boolean = normalize(a) == normalize(b)

    /**
     * The Library search key: [normalize]'s folding (Arabic ي/ك typed for Persian ی/ک, case, diacritics) with every
     * space removed as well, so a Persian compound matches however it was joined: a space, a half-space or nothing.
     * The search used to compare raw text, so a topic typed on a Persian keyboard was not found from an Arabic one
     * (an outside audit, 2026-09-30).
     */
    fun searchKey(raw: String): String = normalize(raw).replace(" ", "")
}
