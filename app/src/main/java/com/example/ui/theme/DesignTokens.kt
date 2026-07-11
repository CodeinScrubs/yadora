package com.example.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.unit.dp

/**
 * Design tokens for the "Quiet Momentum" system. As each screen is redesigned, replace hardcoded
 * spacing/radius values with these so the app stays visually consistent and premium.
 */
object AppSpacing {
    val Screen = 20.dp
    val Section = 24.dp
    val Card = 16.dp
    val Item = 12.dp
    val Compact = 8.dp
}

object AppRadius {
    val Card = 18.dp
    val CardLarge = 24.dp
    val Dialog = 24.dp
    val Button = 14.dp
    val Input = 14.dp
    val Chip = 999.dp
}

object AppAlpha {
    const val BorderSubtle = 0.08f
    const val IconMuted = 0.60f
    const val Disabled = 0.38f
}

/** Motion: calm, confident easing. Nothing bounces or celebrates — momentum, not fireworks. */
object AppMotion {
    const val FastMs = 120
    const val StandardMs = 260
    const val EmphasisMs = 420
    // Gentle deceleration — settles rather than snaps.
    val Standard = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    val Emphasis = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
}
