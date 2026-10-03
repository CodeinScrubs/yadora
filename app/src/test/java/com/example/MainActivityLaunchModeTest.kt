package com.example

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The app opens as ONE screen however it is opened. A task that a reminder, the widget or the ringer started has that
 * intent at its root, and with the standard launch mode every later tap on the app icon (a different intent) put
 * another MainActivity on top: on an emulator, 1, 2, 3, 4 copies, and Back walked through stale screens (2026-10-03).
 * Only a device shows the stacking; this pins the manifest attribute that prevents it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MainActivityLaunchModeTest {

    @Test
    fun `the main screen is single top so the app icon reuses it`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TOP, info.launchMode)
    }
}
