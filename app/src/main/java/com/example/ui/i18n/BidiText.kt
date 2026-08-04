package com.example.ui.i18n

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection

/**
 * Bidirectional-text support for USER-ENTERED content (titles, notes, sources, recall prompts).
 *
 * The problem: a study library is genuinely multilingual. A source reads
 * "از qb جلد سه page 97" — Persian, with Latin fragments inside it. By default a Compose `Text`
 * takes its base direction from the SCREEN's layout direction, so on an English screen that whole
 * sentence is laid out left-to-right and the Persian words end up in the wrong visual order (the
 * leading "از" is pushed to the far left, "page 97" lands in the middle, and the phrase reads
 * scrambled). Persian screens have the mirror-image problem with English notes.
 *
 * The fix is the standards-based one: resolve each string's base direction from ITS OWN first
 * strong directional character, per the Unicode Bidi Algorithm (UAX #9) — the same rule the
 * BidiLens toolkit applies, and the same rule as an FSI…PDI isolate, except Compose can do it
 * during layout so the stored text is never mutated. "از qb جلد سه page 97" starts with a strong
 * RTL letter, so the phrase is laid out right-to-left with the Latin runs correctly embedded;
 * "Beta blockers قلبی" starts with a strong LTR letter and stays left-to-right.
 *
 * Applies ONLY to user content. App chrome (labels, buttons, headings) must keep following the
 * chosen UI language, not whatever a topic happens to be written in. Jalali dates are handled
 * separately by [PersianDate.rtlIsolate], because they begin with a weak Latin numeral and so need
 * an explicit RTL base rather than first-strong detection.
 */
fun TextStyle.autoDirection(): TextStyle =
    if (textDirection == TextDirection.Content) this else copy(textDirection = TextDirection.Content)
