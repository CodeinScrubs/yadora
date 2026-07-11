package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AppMotion
import com.example.ui.theme.StrengthGradient
import com.example.ui.theme.StrengthGradientDark

/**
 * Memory-strength bar — the "seed → forest" gradient and a signature of "Quiet Momentum".
 * A calm, non-numeric read of retention: the fill grows and greens as an item gets stronger.
 * The gradient spans only the filled portion, so a weak item shows pale seed tones and a strong
 * one shows deep forest — the color itself carries the meaning.
 */
@Composable
fun StrengthBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
) {
    val stops = if (isSystemInDarkTheme()) StrengthGradientDark else StrengthGradient
    // Fill eases up to its target so a card's memory strength "grows" into place on appear/change.
    val p by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(AppMotion.EmphasisMs, easing = AppMotion.Standard),
        label = "strengthFill",
    )
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(p)
                .fillMaxHeight()
                .clip(RoundedCornerShape(percent = 50))
                .background(Brush.horizontalGradient(stops))
        )
    }
}

/** Maps the stored memory "state" label to a strength fraction for [StrengthBar]. */
fun strengthOf(state: String): Float = when (state) {
    "Strong" -> 0.92f
    "Building" -> 0.7f
    "Learning" -> 0.42f
    "NeedsRelearn" -> 0.2f
    else -> 0.08f
}
