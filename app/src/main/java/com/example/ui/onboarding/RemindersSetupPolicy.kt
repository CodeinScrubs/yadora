package com.example.ui.onboarding

/**
 * Whether first-run onboarding shows the reminders step. Pure, so it can be unit-tested.
 *
 * Why the step exists: on Android 14+ a freshly installed app is denied `SCHEDULE_EXACT_ALARM` by
 * default, so every reminder silently falls back to an inexact alarm that can arrive up to an hour
 * late (observed on an Android 16 emulator). Nothing told the user, and the only way to fix it was
 * buried in Settings. Separately, the notification permission used to be requested the instant the
 * language was picked, with no explanation of why — the worst moment to ask.
 *
 * The step is shown once, and only when something is actually missing: a user who already has both
 * permissions never sees it. Once they've been through it — granted or not — it never comes back;
 * Settings keeps the same controls for later. The "done" flag is device-local state and lives in the
 * backup-excluded prefs file, because permissions do not travel to a new phone — a restore onto new
 * hardware must show the step again.
 */
object RemindersSetupPolicy {

    /** Stored in `NotificationScheduler.transientPrefs`, which is excluded from backup and transfer. */
    const val PREF_DONE = "reminders_setup_done"

    fun shouldShow(setupDone: Boolean, notificationsEnabled: Boolean, exactAlarmsAllowed: Boolean): Boolean =
        !setupDone && !(notificationsEnabled && exactAlarmsAllowed)
}
