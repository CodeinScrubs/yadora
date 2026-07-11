package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// "Quiet Momentum": warm paper surfaces + sage brand. tertiary = high-yield amber; error is reserved
// for destructive actions only (rating colors live in Color.kt and are applied per-component, not here).
private val LightColorScheme = lightColorScheme(
    primary = Sage,
    onPrimary = Color.White,
    primaryContainer = SageContainer,
    onPrimaryContainer = OnSageContainer,
    secondary = Strong,
    onSecondary = Color.White,
    secondaryContainer = SageContainer,
    onSecondaryContainer = SageDeep,
    tertiary = HighYield,
    onTertiary = Color.White,
    tertiaryContainer = HighYieldContainer,
    onTertiaryContainer = OnHighYield,
    error = DangerRed,
    onError = Color.White,
    errorContainer = ForgotContainer,
    onErrorContainer = OnForgotContainer,
    background = PaperSurface,
    onBackground = InkPrimary,
    surface = PaperCard,
    onSurface = InkPrimary,
    surfaceVariant = PaperContainer,
    onSurfaceVariant = InkVariant,
    outline = OutlineSoft,
    outlineVariant = PaperContainerHigh,
)

private val DarkColorScheme = darkColorScheme(
    primary = SageDark,
    onPrimary = OnSageDark,
    primaryContainer = SageContainerDark,
    onPrimaryContainer = OnSageContainerDark,
    secondary = StrengthDark4,
    onSecondary = OnSageDark,
    secondaryContainer = SageContainerDark,
    onSecondaryContainer = OnSageContainerDark,
    tertiary = HighYieldTone,
    onTertiary = Color(0xFF3A2E1D),
    tertiaryContainer = HighYieldContainerDark,
    onTertiaryContainer = HighYieldTone,
    error = DangerRedDark,
    onError = Color(0xFF3A0A0A),
    errorContainer = ForgotContainerDark,
    onErrorContainer = ForgotDark,
    background = PaperSurfaceDark,
    onBackground = InkPrimaryDark,
    surface = PaperCardDark,
    onSurface = InkPrimaryDark,
    surfaceVariant = PaperContainerDark,
    onSurfaceVariant = InkVariantDark,
    outline = OutlineDark,
    outlineVariant = PaperContainerLowDark,
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accent: Color? = null, // user-chosen accent overrides the sage primary
    languageCode: String = "en", // selects the Manrope (en) / Vazirmatn (fa) type scale
    dynamicColor: Boolean = false, // brand-controlled by default
    content: @Composable () -> Unit,
) {
    val base = if (darkTheme) DarkColorScheme else LightColorScheme
    val colorScheme = if (accent != null) {
        // In dark mode, lift the accent toward white so a saturated pick stays legible on dark paper.
        val effective = if (darkTheme) androidx.compose.ui.graphics.lerp(accent, Color.White, 0.45f) else accent
        base.copy(
            primary = effective,
            onPrimary = if (darkTheme) PaperSurfaceDark else Color.White,
            primaryContainer = effective.copy(alpha = 0.20f),
            onPrimaryContainer = effective,
        )
    } else base
    MaterialTheme(colorScheme = colorScheme, typography = appTypography(languageCode), content = content)
}
