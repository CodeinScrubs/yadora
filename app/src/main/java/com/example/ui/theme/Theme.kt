package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

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
    // The bar icons follow the app's theme, not the phone's: the activity is edge-to-edge, so the status bar
    // sits on the app's own background, and enableEdgeToEdge picks icon colours from the PHONE's dark mode.
    // With Light chosen in Theme & colors on a phone in dark mode, the clock and battery were white on paper
    // (seen on the emulator, 2026-10-02); the full-screen alarm, a separate activity, showed it every time.
    val view = LocalView.current
    if (!view.isInEditMode) {
        val window = view.context.findActivity()?.window
        if (window != null) SideEffect {
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    CompositionLocalProvider(LocalAppDarkTheme provides darkTheme) {
        MaterialTheme(colorScheme = colorScheme, typography = appTypography(languageCode), content = content)
    }
}

/** The light/dark choice [MyApplicationTheme] was given: Settings → Theme & colors, which may differ from the phone's. */
private val LocalAppDarkTheme = staticCompositionLocalOf<Boolean?> { null }

/**
 * Whether the APP is dark. Colours chosen outside the colour scheme (the rating, overdue and strength palettes)
 * must ask this, not [isSystemInDarkTheme]: with Light chosen in the app on a phone in dark mode, they used to
 * take their dark variants on a light page. Outside [MyApplicationTheme] it falls back to the phone's setting.
 */
@Composable
fun isAppInDarkTheme(): Boolean = LocalAppDarkTheme.current ?: isSystemInDarkTheme()

private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
