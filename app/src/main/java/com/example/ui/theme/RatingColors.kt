package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.domain.model.MemoryRating

/**
 * The rating palette — the heart of "Quiet Momentum". Ratings run a warm→cool ramp
 * (effort→ease): Forgot = burnt sienna, Hard = ochre, Good = sage, Easy = steel blue.
 *
 * Crucially, **"Forgot" is NOT alarm-red.** Red triggers avoidance (Elliot & Maier) and makes people
 * under-report lapses to dodge the "bad" button — which quietly corrupts the FSRS training signal.
 * A calm sienna keeps honest grading, which keeps the schedule accurate.
 *
 * [container]/[onContainer] are the calm resting style (soft chip + legible ink), used for the tap
 * targets. [solid]/[onSolid] are for emphasis (selected / progress). Every pair is AA-legible in
 * both themes by construction, so callers never need their own contrast logic.
 */
data class RatingTone(val container: Color, val onContainer: Color, val solid: Color, val onSolid: Color)

/**
 * The "overdue / you were away" status tone — warm terracotta, recoverable, never error-red.
 * Distinct from the high-yield amber so the two states never read as the same thing.
 * [main] tints text/borders/fills; [container] is the soft card wash; [onSolid] is legible on [main].
 */
data class StatusTone(val main: Color, val container: Color, val onSolid: Color)

@Composable
fun overdueTone(): StatusTone =
    if (isSystemInDarkTheme()) StatusTone(OverdueDark, OverdueContainerDark, PaperSurfaceDark)
    else StatusTone(Overdue, OverdueContainer, Color.White)

@Composable
fun ratingTone(rating: MemoryRating): RatingTone {
    val dark = isSystemInDarkTheme()
    return when (rating) {
        MemoryRating.Forgot ->
            if (dark) RatingTone(ForgotContainerDark, ForgotDark, ForgotDark, PaperSurfaceDark)
            else RatingTone(ForgotContainer, OnForgotContainer, Forgot, Color.White)
        MemoryRating.Hard ->
            if (dark) RatingTone(HardContainerDark, HardDark, HardDark, PaperSurfaceDark)
            else RatingTone(HardContainer, OnHardContainer, Hard, Color(0xFF2E2413)) // ochre needs dark ink
        MemoryRating.Good ->
            if (dark) RatingTone(GoodContainerDark, SageDark, SageDark, PaperSurfaceDark)
            else RatingTone(GoodContainer, OnGoodContainer, Good, Color.White)
        MemoryRating.Easy ->
            if (dark) RatingTone(EasyContainerDark, EasyDark, EasyDark, PaperSurfaceDark)
            else RatingTone(EasyContainer, OnEasyContainer, Easy, Color.White)
    }
}
