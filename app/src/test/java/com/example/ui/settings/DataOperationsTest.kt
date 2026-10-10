package com.example.ui.settings

import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Settings' long data operations run on the application's scope, one at a time, with their progress outside the screen
 * (a production review, 2026-10-10): a rotation used to hide the progress dialog of a running restore and let "Delete all
 * data" start beside it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DataOperationsTest {

    @Test fun `one operation at a time, its progress outside any screen, and a rebuild asked for after it`() {
        val gate = CompletableDeferred<Unit>()
        val rebuildBefore = DataOperations.rebuild.value
        assertTrue(DataOperations.run("Restoring") { gate.await(); DataOperations.requestRebuild() })
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Restoring", DataOperations.busy.value)
        assertFalse("a second one waits for none to be running", DataOperations.run("Deleting") {})

        gate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(DataOperations.busy.value)
        assertEquals(rebuildBefore + 1, DataOperations.rebuild.value)
        assertTrue("and then the next can start", DataOperations.run("Deleting") {})
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(DataOperations.busy.value)
    }
}
