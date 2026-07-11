package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.R

// "Quiet Momentum" type system.
//   EN → Manrope  (geometric, confident, warm — reads as a modern study product, not a toy)
//   FA → Vazirmatn (the reference Persian UI face; correct RTL shaping, full weight range)
// Persian glyphs have a smaller apparent x-height, so the FA scale is bumped in size + line-height
// and letter-spacing is zeroed (letter-spacing breaks Arabic-script joining).

private val Manrope = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
    Font(R.font.manrope_extrabold, FontWeight.ExtraBold),
)

private val Vazirmatn = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    // Vazirmatn ships regular/medium/bold here; Compose maps SemiBold/ExtraBold to the nearest (Bold).
    Font(R.font.vazirmatn_bold, FontWeight.Bold),
)

private fun buildTypography(family: FontFamily, fa: Boolean): Typography {
    val sizeScale = if (fa) 1.06f else 1f
    val lineScale = if (fa) 1.14f else 1f
    fun style(size: Double, line: Double, weight: FontWeight, letter: Double = 0.0) = TextStyle(
        fontFamily = family,
        fontWeight = weight,
        fontSize = (size * sizeScale).sp,
        lineHeight = (line * lineScale).sp,
        letterSpacing = (if (fa) 0.0 else letter).sp,
    )
    return Typography(
        displayLarge   = style(40.0, 48.0, FontWeight.ExtraBold),
        displayMedium  = style(34.0, 42.0, FontWeight.ExtraBold),
        displaySmall   = style(30.0, 38.0, FontWeight.Bold),
        headlineLarge  = style(28.0, 34.0, FontWeight.Bold),
        headlineMedium = style(24.0, 30.0, FontWeight.Bold),
        headlineSmall  = style(21.0, 28.0, FontWeight.SemiBold),
        titleLarge     = style(19.0, 26.0, FontWeight.SemiBold),
        titleMedium    = style(16.0, 22.0, FontWeight.SemiBold, 0.1),
        titleSmall     = style(14.0, 20.0, FontWeight.Medium, 0.1),
        bodyLarge      = style(16.0, 24.0, FontWeight.Normal, 0.15),
        bodyMedium     = style(14.0, 20.0, FontWeight.Normal, 0.15),
        bodySmall      = style(12.5, 18.0, FontWeight.Normal, 0.2),
        labelLarge     = style(15.0, 20.0, FontWeight.SemiBold, 0.1),
        labelMedium    = style(12.5, 16.0, FontWeight.Medium, 0.4),
        labelSmall     = style(11.5, 16.0, FontWeight.Medium, 0.4),
    )
}

val EnglishTypography = buildTypography(Manrope, fa = false)
val PersianTypography = buildTypography(Vazirmatn, fa = true)

fun appTypography(languageCode: String): Typography =
    if (languageCode == "fa") PersianTypography else EnglishTypography

// Default (English/Manrope) — kept so any lingering reference to `Typography` still resolves.
val Typography = EnglishTypography
