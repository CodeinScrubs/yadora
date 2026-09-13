package com.example.ui.onboarding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemindersSetupPolicyTest {

    @Test
    fun `a fresh Android 14+ install with nothing granted sees the step`() {
        assertTrue(RemindersSetupPolicy.shouldShow(setupDone = false, notificationsEnabled = false, exactAlarmsAllowed = false))
    }

    /** The case that motivated the step: notifications work, but every reminder can be an hour late. */
    @Test
    fun `notifications allowed but exact alarms denied still sees the step`() {
        assertTrue(RemindersSetupPolicy.shouldShow(setupDone = false, notificationsEnabled = true, exactAlarmsAllowed = false))
    }

    @Test
    fun `exact alarms allowed but notifications off still sees the step`() {
        assertTrue(RemindersSetupPolicy.shouldShow(setupDone = false, notificationsEnabled = false, exactAlarmsAllowed = true))
    }

    /** Existing users who already granted both must not be interrupted by a screen with nothing to do. */
    @Test
    fun `a user with everything already granted never sees it`() {
        assertFalse(RemindersSetupPolicy.shouldShow(setupDone = false, notificationsEnabled = true, exactAlarmsAllowed = true))
    }

    /** Once seen, it never returns — even if a permission is revoked later. Settings handles that. */
    @Test
    fun `once completed it never comes back`() {
        for (n in listOf(true, false)) for (e in listOf(true, false)) {
            assertFalse("notifications=$n exact=$e", RemindersSetupPolicy.shouldShow(setupDone = true, notificationsEnabled = n, exactAlarmsAllowed = e))
        }
    }
}
