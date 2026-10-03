package com.example

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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

    /**
     * A tap on a reminder, the alarm or the widget is logged once (APP_OPENED), so the pilot can follow a reminder to
     * the review it led to; a rebuilt screen does not count the same tap again (2026-10-03).
     */
    @Test
    fun `a tap that opens the app is logged once`() {
        val app = ApplicationProvider.getApplicationContext<MedReviewApplication>()
        fun opens() = runBlocking { app.database.eventLogDao().getAll().filter { it.type == "APP_OPENED" }.map { it.detail } }
        fun waitFor(n: Int) {
            repeat(100) {
                shadowOf(Looper.getMainLooper()).idle()
                if (opens().size >= n) return
                Thread.sleep(50)
            }
        }
        val controller = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPENED_FROM, "notification"),
        ).setup()
        waitFor(1)
        controller.recreate()
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(300)
        assertEquals("a rebuilt screen is not a second tap", listOf<String?>("from=notification"), opens())

        controller.newIntent(Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPENED_FROM, "widget"))
        waitFor(2)
        assertEquals(listOf<String?>("from=notification", "from=widget"), opens())
        controller.pause().stop().destroy()
    }
}
