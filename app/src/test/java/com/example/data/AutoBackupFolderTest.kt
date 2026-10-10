package com.example.data

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.example.notifications.NotificationScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Changing the automatic backup's folder ([AutoBackup.changeFolder]). The old folder's permission used to be released
 * before the new one was taken, so a folder whose provider refuses a lasting permission left automatic backup off, with
 * the working folder dropped (a production review, 2026-10-10).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AutoBackupFolderTest {

    private val app get() = ApplicationProvider.getApplicationContext<Context>()
    private val working = "content://com.android.externalstorage.documents/tree/primary%3AYadora".toUri()
    private val refusing = "content://com.example.cloud.documents/tree/backups".toUri()

    /** A provider that grants no lasting permission, as some cloud document providers do. */
    private class Refusing(base: Context) : ContextWrapper(base) {
        private val resolver = object : ContentResolver(base) {
            override fun takePersistableUriPermission(uri: Uri, modeFlags: Int) {
                throw SecurityException("No persistable permission grants found for $uri")
            }
        }
        override fun getContentResolver(): ContentResolver = resolver
    }

    @Test fun `a folder that cannot be used leaves the one in use`() {
        NotificationScheduler.transientPrefs(app).edit { putString(AutoBackup.PREF_TREE_URI, working.toString()) }
        assertTrue(AutoBackup.isOn(app))

        val failure = runCatching { AutoBackup.changeFolder(Refusing(app), refusing) }.exceptionOrNull()

        assertTrue("the new folder is refused: $failure", failure is SecurityException)
        assertTrue("automatic backup stays on", AutoBackup.isOn(app))
        assertEquals("in the folder it was using", working, AutoBackup.treeUri(app))
    }
}
