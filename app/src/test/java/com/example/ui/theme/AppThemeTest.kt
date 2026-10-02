package com.example.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.core.view.WindowCompat
import com.example.domain.model.MemoryRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The palettes and the bar icons follow the theme chosen IN THE APP (Settings → Theme & colors), not the phone's
 * dark mode. With Light chosen on a phone in dark mode, the overdue label, the rating buttons and the strength bars
 * took their dark variants on a light page, and the status bar's clock and battery were white on paper; the
 * full-screen alarm showed the white icons whatever was chosen (emulator, 2026-10-02).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppThemeTest {

    @get:Rule val compose = createComposeRule()

    private class Seen {
        var appDark: Boolean? = null
        var overdue: androidx.compose.ui.graphics.Color? = null
        var forgot: androidx.compose.ui.graphics.Color? = null
        var view: View? = null
    }

    private fun render(appDark: Boolean): Seen {
        val seen = Seen()
        compose.setContent {
            MyApplicationTheme(darkTheme = appDark) {
                seen.appDark = isAppInDarkTheme()
                seen.overdue = overdueTone().main
                seen.forgot = ratingTone(MemoryRating.Forgot).container
                seen.view = LocalView.current
            }
        }
        compose.waitForIdle()
        return seen
    }

    private fun lightStatusIcons(view: View): Boolean {
        tailrec fun Context.activity(): Activity = if (this is Activity) this else (this as ContextWrapper).baseContext.activity()
        return WindowCompat.getInsetsController(view.context.activity().window, view).isAppearanceLightStatusBars
    }

    @Test
    @Config(qualifiers = "night")
    fun light_chosen_in_the_app_on_a_phone_in_dark_mode_is_light_throughout() {
        val seen = render(appDark = false)
        assertEquals(false, seen.appDark)
        assertEquals("the overdue label takes the light palette", Overdue, seen.overdue)
        assertEquals("so do the rating buttons", ForgotContainer, seen.forgot)
        assertTrue("dark status-bar icons on the light page", lightStatusIcons(seen.view!!))
    }

    @Test
    @Config(qualifiers = "notnight")
    fun dark_chosen_in_the_app_on_a_phone_in_light_mode_is_dark_throughout() {
        val seen = render(appDark = true)
        assertEquals(true, seen.appDark)
        assertEquals(OverdueDark, seen.overdue)
        assertEquals(ForgotContainerDark, seen.forgot)
        assertFalse("light status-bar icons on the dark page", lightStatusIcons(seen.view!!))
    }

    @Test
    @Config(qualifiers = "night")
    fun outside_the_app_theme_the_phone_decides() {
        var dark: Boolean? = null
        compose.setContent { dark = isAppInDarkTheme() }
        compose.waitForIdle()
        assertEquals(true, dark)
    }

    /**
     * Before Android 10 the navigation bar keeps the colour edge-to-edge picked from the PHONE's mode, so the theme gives
     * it the app's own scrim: on Android 8 a dark app on a light phone showed white icons on a light bar (emulator,
     * 2026-10-02). From Android 10 the system draws the contrast itself and the colour is left alone.
     */
    @Test
    fun `before Android 10 the navigation bar takes the app's own scrim`() {
        assertEquals(DARK_NAVIGATION_SCRIM, navigationBarScrimBeforeQ(darkTheme = true, sdk = 26))
        assertEquals(LIGHT_NAVIGATION_SCRIM, navigationBarScrimBeforeQ(darkTheme = false, sdk = 28))
        assertEquals(null, navigationBarScrimBeforeQ(darkTheme = true, sdk = 29))
        assertEquals(null, navigationBarScrimBeforeQ(darkTheme = false, sdk = 36))
    }
}
