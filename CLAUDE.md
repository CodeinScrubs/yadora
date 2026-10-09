# Yadora — CLAUDE.md

Offline Android study-review **scheduler** (not a flashcard app) built around
FSRS spaced repetition — **FSRS-6 is live**, FSRS-5 is kept frozen for replay.
Kotlin + Jetpack Compose + Material 3 + Room. No backend. English + Persian
(RTL, Persian digits, Jalali calendar) + German.

## Build & verify

Requires Android Studio's bundled JDK:

```
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest   # unit tests
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug        # debug APK
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:lintDebug            # lint (keep 0 errors)
python3 tools/pilot/test_yadora_model.py && python3 tools/pilot/test_analyze.py              # research toolkit
```

(PowerShell: `$env:JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"` first.) The bundled JDK is
currently JDK 25. Android Studio updates itself, the Gradle wrapper and AGP on its own, so check
`git status` for uncommitted version bumps at the start of a session and run the gate before
committing them. `:app:assembleRelease` builds an R8-minified release, unsigned unless the keystore
environment variables are set.

**CI:** `github.com/CodeinScrubs/yadora`, a PUBLIC repository (public since it was created on 2026-09-13; this file called it private until 2026-10-02, and making it private is the owner's call). `.github/workflows/android-ci.yml` runs unit
tests, lint and debug + R8 release builds on every push and PR to `main` (Temurin 21; markdown-only
changes skipped). A red CI run is a blocker exactly like a local failure. Inspect with `gh run list` /
`gh run watch`.

**Dependency baseline** (2026-09): compileSdk 37 (needs SDK platform `android-37.0`) with targetSdk
still 36 on purpose — raising targetSdk changes runtime behaviour, so it is its own change, made after
reading Android 17's behaviour changes. Compose BOM 2026.09.00 (Material3 1.4.0), current AndroidX,
Kotlin 2.2.10. Two pins are deliberate and commented in `gradle/libs.versions.toml`: Vico stays 1.15.0
(2.x and 3.x are API rewrites) and kotlinx-serialization-json stays 1.9.0 (the last release for Kotlin
2.2). Vico 1.15 was compiled against Compose 1.6, so after ANY Compose bump open Progress on a device
and scroll to the "Review Consistency" chart — it always draws, unlike the retention chart, which stays
empty until real recall reviews exist.

An INTERRUPTED Gradle build (a killed session, a sleeping PC) can leave corrupt state that looks like a
real failure: a build-cache entry that fails with `invalid stored block lengths`, or a truncated lint
model (`Could not deserialize ... lint model`, then `Unexpected lint invalid arguments`). Neither is a
code problem. Delete the entry the error names from `~/.gradle/caches/build-cache-1`, then rebuild with
`./gradlew --no-build-cache clean ...`. Robolectric tests need `--add-opens` for
`java.base/jdk.internal.access` on JDK 17+ (set in `app/build.gradle.kts`); without it every Robolectric
test fails in setup with "Failed to interact with raw FileDescriptor internals".

**Device testing:** `adb` is at `%LOCALAPPDATA%/Android/Sdk/platform-tools/adb.exe` (not on PATH).
The user's Samsung Galaxy A52s (Android 14) is a test device and destructive testing of Yadora on it
is OK'd. Reboot, Doze and clock-change tests belong on the emulator (AVD `Medium_Phone_API_36.1`).
Unit tests cannot prove reminder or alarm behaviour — only a device can.

Verified on hardware 2026-09-13 (build 1.1 / 4, AGP 9.4.0): on the Samsung (Android 14) an in-place
upgrade install, launch with no crash/ANR, both daily reminders armed as EXACT alarms
(`exactAllowReason=permission`, allowed in Doze), both notification channels, the WorkManager safety
job, and a test reminder that actually posts (private on the lock screen, two actions). On the
emulator (Android 16): a fresh install arms both reminders as INEXACT alarms with a one-hour window,
because Android 14+ denies `SCHEDULE_EXACT_ALARM` to new installs by default. The onboarding REMINDERS
step exists for exactly that: granting there re-armed both as exact (`window=0`,
`exactAllowReason=permission`), and the step itself was checked in English and Persian, light and dark.
`BootReceiver` re-arms both after a reboot; `connectedDebugAndroidTest` runs. Still unverified: Doze
delivery over real days and Samsung battery management over days (the full-screen alarm was checked on the
Samsung on 2026-10-02, below). Since 2026-10-03 the pilot measures exactly that on every participant's phone (entry
"Reminder delivery is measured on every pilot phone", docs/PILOT.md D11).

Verified 2026-09-27 (build 1.1 / 4). On the Samsung: with the day's limit done and one review held back, the
20:00 alarm fired, posted nothing, logged no NOTIF_SHOWN and re-armed only tomorrow's two slots. On the emulator
(`cmd alarm set-timezone` / `set-time`, `settings put global auto_time 0`): a time-zone change re-arms both slots in
the NEW local time and posts one catch-up if a slot already passed with topics due; changing back posts nothing
more. Setting the clock FORWARD past a slot used to post twice within a second (the catch-up and the overdue alarm,
same notification id, two alerts, two NOTIF_SHOWN); an alarm within 5 minutes of a real reminder now only re-arms
(`NotificationScheduler.shownJustBefore`). Setting the clock BACK re-arms the real next slots: a saved nudge more
than one repeat ahead is stale (`isLiveNudge`), and a "last shown" time in the future no longer counts as shown
today (`shownToday`), which had silenced that day's catch-up and safety sweep. `ReminderSlotsTest` pins the three
rules. The R8 release, signed with the SDK debug key only to install over the debug build, then ran on the Samsung:
launch, a rating and its Undo, "Back up now", a full restore and the automatic-backup worker, with no crash. The
phone's research export replayed through `analyze.py` with 0 mismatches and 0 self-check issues (D1 OK).

Verified 2026-09-28 on the Samsung (the audit fixes, from test backups made from `DeviceSeedBackupTest`'s seed). Restore
refused a file whose topic list is null and one naming an unknown memory model (Toast "Restore failed — invalid
backup", the library byte-identical after each), and a topic the file marked deleted but not archived came back
archived. An edit save kept a collection and study type the form does not show. A rating correction saved unchanged
wrote nothing (the row, every log column and the event count identical); a real one replayed, and Good→Hard→Good
restored S and D exactly (the corrected row takes today's calibration, by design). Back from the pencil, the card
re-read its topic (Important on: Easy ~5d→~4d, Medium ~2d→~1d) and the commit matched the preview (1.1076 d). An
FSRS-5 topic shown in a session was not written. That pass found the Undo defect in the Undo entry below. The export
then replayed through `analyze.py`: 140 logs, 0 mismatches, 0 self-check issues; `smoke.sh` and `test_reminder.sh`
clean.

Verified 2026-10-02 on an emulator (Android 16, a temporary AVD; the Samsung was not connected). With alarm mode on and
full-screen access granted, the screen locked and the device forced into deep Doze (`dumpsys battery unplug`,
`dumpsys deviceidle force-idle`, clock set to 19:58:30), the 20:00 reminder fired on time: `AlarmRingActivity` opened over
the lock screen and woke the display, the notification posted on the alarm channel (category alarm, private on the lock
screen, three actions, "14 topics to review · 4 important", the same plan Today showed), Dismiss closed the ringer and
cleared the notification, and both next slots were re-armed. With full-screen access denied (`appops set com.yadora.app
USE_FULL_SCREEN_INTENT deny`, what Play does to apps that are not alarm or calling apps) the same reminder arrived as an
ordinary notification on the reminder channel, and Reminder Health showed "Full-screen alarm — Enable". With the phone
unlocked and in use, Android shows the alarm as a heads-up instead of the ringer, by design. Gotcha: `am force-stop`
cancels an app's alarms, so relaunch the app before a timed alarm test. The same pass found the theme defect in the
"Colours follow the APP's theme" entry below.

Verified 2026-10-02 on the Samsung (main at 5101886, installed over 1.1 / 4): no crash, both reminders exact. With alarm
mode on, a test reminder sent with the screen off, and then the REAL 20:00 slot after the phone had sat idle with its
screen off for an hour, each woke the display with the full-screen ringer over the lock screen; the tone played on the
alarm stream although the phone was on silent, the owner confirmed it rang and vibrated, Dismiss closed it, and the next
slot (10:00) was re-armed exact. Gotcha: on Android 14 `dumpsys vibrator_manager` prints "scale: 0.00" for a vibration
that is NOT scaled, not for a muted one; this pass misread it as a silent alarm. The ringer now asks for an ALARM
vibration (`VibrationAttributes.USAGE_ALARM`, `AudioAttributes.USAGE_ALARM` before API 33) so the phone's alarm-vibration
setting and Do Not Disturb treat it as an alarm. On Android 8.0 (a temporary emulator) the ringer closed itself 0.4 s
after it opened whenever the screen was off: Android stops an activity started while the device sleeps before the
display wakes, and `onStop` took that for the learner leaving. It now finishes on a stop only once it has had window
focus (`AlarmRingActivity.seen`); with the old build the screen never woke, with the new one it woke and the ringer
stayed, and Dismiss and Home still closed it. A new AVD wants a 6 GB data partition and C: had 6.6 GB free, so that
emulator lived on F: (`avdmanager create avd -p F:\...`).

Then with PR #15's build (debug, then the R8 release debug-signed). On the Samsung: the alarm vibration reads
`Usage=ALARM`; today's session served all 17 topics (one first rating, then 16 reviews) in exactly the order
`order1002/expected_plan.py` predicted from the pulled database, near ties included; Undo on the summary put the last
topic back field-for-field (stability, due date, count, its log gone); a seed restored with a limit of 10 in its
settings showed that limit in Settings at once; with 12 overdue reviews, nothing done and none due today, Spread out kept
the 10 most urgent due and moved the other two to 08:00 tomorrow and the day after, exactly as predicted, model dates
untouched; both Progress charts drew (a dot per day). On Android 8.0: a backup copied in by adb (no MIME type, shown with
a generic icon) could be picked; restoring one that said Persian, dark and purple switched the app at once; the Jalali
picker in landscape scrolled to day 30 and kept it through a rotation; the widget at its default 2x1 showed its count
and label. That pass found one more defect, a regression from PR #14 on Android 8 and 9 only: the bar ICONS followed the
app's theme but the navigation bar kept the colour edge-to-edge picked from the phone's mode, so a dark app on a light
phone showed white icons on a light bar, and the ringer (not edge-to-edge) showed a light theme's dark icons on a black
status bar. Fixed and re-checked on the emulator (entry "Colours follow the APP's theme"). The picker on the owner's
phone lists their own Download folder: put test files in the auto-backup test folder it opens on
(`Download/YadoraAutoTest`, any name that is not `yadora_backup_*`), scan them with
`am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://...`, and delete them afterwards.

Verified 2026-10-03 on an emulator (Android 16, a temporary AVD on F:; the Samsung was not connected). An outside report
saw three copies of MainActivity in Yadora's task on the Samsung. Its own `am start -n` commands made them (each adds
one; reproduced), but a real path exists: a task STARTED by a reminder, the widget or the ringer has that intent at its
root, and every later tap on the app icon stacked another copy (1, 2, 3, 4; Back walked through them). With
`launchMode="singleTop"` it stays one copy, and one Back leaves the app. Every screen also counted the status and
navigation bars twice, since at least 2026-09-27 (entry "Screens with text fields"): a status bar's height of empty
space above every title, and the + button and the keyboard's edge a navigation bar too high. Re-checked after the fix:
Today, Library, Progress, Settings, Add with the keyboard on its last field, Review with the keyboard on the question
score. Reminder delivery logging: a test reminder, the real 10:00 slot and the 20:00 slot in forced deep Doze
(`dumpsys deviceidle force-idle`, screen off) each logged REMINDER_FIRED with `late_s=0 exact=1` (and `idle=1` in
Doze); the seed's export (v13) replayed 125 of 125 logs through `analyze.py` with 0 self-check issues and printed the
reminder table. Contrary to the report, the last card of Today and the Library clears the + button (96 dp of
padding) and Enter in the Library search closes the keyboard. Gotcha: `cmd alarm set-time <ms>` works on the Play
Store emulator image without root, after `settings put global auto_time 0`.
Later the same day, on a second temporary emulator, with saved-order replay and the staged backup: choosing a folder
wrote the first automatic backup as `yadora_partial_…` (seen mid-write) and renamed it to `yadora_backup_…` once
complete (Android's own storage provider renames; the file parsed as backup v9 with all 43 topics and 125 logs),
"Back up now" left one file for the day and no staged file, and a tap on a test reminder logged APP_OPENED
`from=notification`.

Device-testing gotchas: in Git Bash set `MSYS_NO_PATHCONV=1` before adb commands — otherwise a device
path like `/sdcard/ui.xml` is silently rewritten into a Windows path and the command "succeeds" doing
nothing. After a reboot wait up to two minutes past `sys.boot_completed` before judging whether reminders
were re-armed: under load the boot broadcast reached Yadora ~40 s late (and over 80 s late on 2026-09-27,
with a Gradle build running), and a 25 s check reported a re-arm bug that did not exist. Drive the UI with `uiautomator dump` + `input tap` on the node bounds,
found with `tools/device/ui_find.py`. It tests text and content-description SEPARATELY, so anchor
patterns: `^Allow$` is the button, while `^Allow` also hits the dialog title "Allow Yadora to send you
notifications?". To revoke exact alarms for a test use `appops set --uid com.yadora.app
SCHEDULE_EXACT_ALARM deny`: once granted in system settings the grant is a UID mode, and the plain
package form reports nothing and changes nothing. Windows Python prints cp1252, so set
`PYTHONIOENCODING=utf-8` for any ad-hoc script that prints Persian UI text.

Both checks are scripted — run them (Git Bash, repo root) instead of rebuilding them by hand:
`tools/device/smoke.sh <serial> [apk]` installs, launches, and reports crashes/ANRs, armed reminders
(exact vs inexact), channels and jobs (on a signing-key mismatch it stops instead of uninstalling, which would delete
the app's data, unless `ALLOW_UNINSTALL=1`); `tools/device/test_reminder.sh <serial>` fires Settings → "Send a
test reminder" through the real UI and prints the notification Android actually posted. Run both on a
real phone before every release.

For a realistic library on a device, `DeviceSeedBackupTest` writes `app/build/device-seed/yadora_seed_backup.json`:
~40 English and Persian topics with a month of history made through the real commit path, more reviews due than a
limit of 10 allows, first ratings waiting, a deferral, an archived and a deleted topic. Its dates are relative to
the run, so regenerate it the day you use it (`--tests "com.example.data.DeviceSeedBackupTest"`), push it to
`/sdcard/Download` and restore it with Settings → Import backup. It carries no settings block.

## Identity (permanent — never change)

- `applicationId = "com.yadora.app"` — permanent once on Play; do NOT rename.
- `namespace = "com.example"` — deliberately kept; internal names
  (`medreview_db`, `medreview_settings`, channel ids) are intentionally NOT
  renamed. Cosmetic churn only; skip it.
- Version: `versionName` is the public string (currently "1.1"); bump
  `versionCode` by 1 for every Play upload (currently 4).

## Architecture

- `domain/srs/` — `Fsrs6.kt` (the LIVE model), `Fsrs.kt` (FSRS-5, frozen for
  replay), `MedScheduler.kt` (product layer: the understanding clock,
  high-yield retention, first-study window, interval fuzz, queue priority score),
  `Fsrs6Optimizer.kt` (fits and judges the personal weight set) and `KeyPoints.kt`
  (reference text only). This is the tested core — keep it pure and covered.
- `ui/today/QueuePlanning.kt` — pure `DailyPlan` (what today's session offers), `DayBounds`,
  `TodayBuckets`, `OverdueRedistributor`. Tested in `QueuePlanningTest`.
- `data/` — Room (`AppDatabase`, DAOs, entities), `MedReviewRepository`,
  `BackupManager` (versioned JSON export/import), `AnalyticsExporter`,
  `PersonalModelWorker` (the daily refit).
- `ui/<screen>/` — each screen file holds its ViewModel + factory + composables.
- `notifications/` — exact-alarm reminder stack + boot catch-up + WorkManager
  safety net.
- Manual DI: `MedReviewApplication` builds the DB + repository; the repository is
  passed down through composables.
- `tools/pilot/` — the research toolkit (standard-library Python): `yadora_model.py` (FSRS-6 + the
  interval rules, transcribed and checked against the py-fsrs goldens and kotlin-stdlib's RNG),
  `analyze.py` (reads research exports, replays every review, writes the pilot report) and `simulate.py`
  (the identical-twins simulation), `experiments.py` (each scheduling choice against its alternatives) and
  `residency.py` (two years to an exam, against competitor-style schedulers). `docs/RESEARCH.md` holds the
  evidence and results, `docs/PILOT.md` the pilot protocol and its pre-registered decision rules,
  `docs/PILOT_GUIDE_FA.md` the participant guide, `docs/COMPETITOR_REVIEWS.md` what 271 users of the closest
  comparable app valued and suffered, with Yadora's answer to each, and `docs/RESEARCHER_PROMPT.md` the open
  scientific questions, written for an outside researcher.

## Settled decisions — do NOT re-propose these

These were decided deliberately. Re-suggesting them wastes a session:

- **A REVIEW IS WHATEVER THE LEARNER CHOOSES** (user decision 2026-09-23). Yadora schedules WHEN to
  study a topic again; it is not a flashcard app and not a recall test. A review can be rereading,
  doing questions, a lecture, a video — done anywhere, usually outside the app. So the review screen
  has NO reveal step and NO "recall first" gate: title, scope, notes and source are all visible, and
  the learner rates afterwards. The memory question is "How much did you still remember?" — what they
  still had when they came back to the topic, BEFORE rereading or checking answers — because that is
  the recall outcome FSRS models; each button states its meaning (Forgot: most of it was gone; Hard:
  the core was there, with real gaps; Good: remembered most of it; Easy: knew it thoroughly). Rating
  how hard the session FELT would feed the model the wrong quantity. Then "How well do you understand it
  now?" drives the repair clock as before. The Settings guide says the same, and that doing questions
  usually sticks better than rereading alone. Re-confirmed 2026-10-03, when a recall-first review was put to the
  owner: the learner may rate whenever they like, but the aim is a rating after the review.
- **The review screen's topic card never shrinks below 200 dp** (`ReviewCardLayout` in `ReviewSessionScreen`,
  2026-09-26). The card used to be simply weighted above the rating controls. When the controls grew (method row,
  question score, hint, one full-width button per rating), a 360x640 phone in Persian squeezed the card to an
  empty sliver: the learner could not see WHICH topic they were rating, and "Not today" fell off the screen. Now
  the card fills what the controls leave, exactly as before on a normal phone, but keeps 200 dp, and the page
  scrolls when that does not fit. `SmallScreenReviewTest` pins it in English and Persian. Adding anything to the
  rating area: run it.
- **Every rating button says roughly when its answer brings the topic back** (user request 2026-09-27). The four
  memory buttons (and Easy/Medium/Hard on a first rating) show an estimate in whole days under the label ("~27d",
  "حدود ۲۷ روز"; a tenth of a day on a guess claims precision it does not have); the understanding buttons show the
  exact figure as before. Both come from ONE function, `previewReturnDays` in `ReviewSessionScreen`: the same
  `MedScheduler.review`, fuzz and two clocks as `MedReviewRepository.rateUnit`. The memory estimate is computed for Clear
  understanding, because Partial/Confused can only bring a topic back SOONER (the repair clock), so the "~" figure is the
  latest the topic can return; Forgot is exact (tomorrow) and is printed without "~". Checked by hand on the phone: an
  easy topic (S 13.8 d, D 2.1, 12 days since the last review) previewed 55/78/128 d for Hard/Good/Easy = FSRS-6's
  37/52/86 d × 1.906 (a 0.85 target) × 0.81 (the learner's calibration) × the topic's −4% fuzz. `ButtonEstimateTest` rates every answer on
  topics in six states at two calibration scales and pins preview == commit and "estimate >= actual". Never compute a
  button's figure any other way.
- **The review screen fits a phone without scrolling** (seen on the owner's Samsung, 2026-09-27): one metadata row in
  the card (the stage is already in the header), method chips short enough for one row (EN "Lecture", DE "Vorlesung",
  FA "کلاس/ویدیو", "سایر"), a one-sentence memory hint, 48 dp rating buttons. Before, the rating area pushed the card
  to its 200 dp minimum and "Not today" below the fold.
- **Progress's 14-day charts show all fourteen days, ending today** (`FourteenDayChart`, 2026-09-27). Vico scrolled
  them sideways and opened on the OLDEST ten days, so the four most recent (today included) were hidden unless the
  learner swiped the chart. Scrolling and zoom are off, x is labelled by day of month (the learner's calendar and
  digits, every other day), retention is a straight line on a fixed 0–100% axis, review counts are bars on a whole-number
  step. After any Vico or Compose change, look at both charts on a phone.
- **Colours follow the APP's theme, not the phone's** (`isAppInDarkTheme`, 2026-10-02). Settings → Theme & colors can
  choose Light or Dark against the phone's own mode. The rating, overdue and strength-bar palettes asked
  `isSystemInDarkTheme()`, so Light chosen on a phone in dark mode drew dark-palette buttons, a pale "OVERDUE" label and
  dark strength bars on a light page; and nothing set the bar icons, so the status bar's clock and battery were white on
  paper there, and on the full-screen alarm (its own activity) every time. `MyApplicationTheme` now publishes its
  `darkTheme` (read it with `isAppInDarkTheme()`) and sets the status- and navigation-bar icons to match. Any colour
  chosen outside the colour scheme must ask `isAppInDarkTheme()`; `AppThemeTest` pins both mismatched cases (it fails
  on the old code). Before Android 10 the bar BACKGROUND has to follow too (2026-10-02): the system draws no contrast
  scrim there, and the navigation bar kept the colour edge-to-edge chose from the phone's mode, so a dark app on a light
  Android 8 phone showed white icons on a light bar; the theme now sets the app's own scrim on API 26–28
  (`navigationBarScrimBeforeQ`). The full-screen alarm is edge-to-edge too, so its status bar is its own background:
  on Android 8 it was black, and a light theme's dark icons vanished in it.
- **Digits follow the interface language, dates the calendar setting** (2026-09-27). `AppDate.date/dateTime/weekdayDate`
  take a REQUIRED `persianDigits` (the Persian interface): Jalali dates used to print Latin digits beside Persian ones.
  An English interface on the Jalali calendar keeps Latin digits. `PersianDate.faDigits` also turns a decimal point
  between two digits into the Persian separator "٫" ("۱۱٫۲ روز", "×۰٫۸۱").
- **Screens with text fields take the keyboard's height** (`.consumeWindowInsets(padding).imePadding()` on the
  Review, Add/Edit and Settings content, 2026-09-26). The activity is edge-to-edge, so the keyboard covers the
  window instead of resizing it, and without this a focused field near the bottom (a question score, the notes)
  sat under the keyboard. Consume the Scaffold padding first, or the navigation bar is counted twice. The same rule one
  level up (2026-10-03): `MedReviewApp`'s Scaffold pads the NavHost for the system bars AND consumes them. Every screen
  has its own Scaffold and top bar, and without the consume each counted both bars again: a status bar's height of
  empty space above every title since at least 2026-09-27, the + button and the keyboard's edge a navigation bar high.
- **First rating happens on the REVIEW screen, not the Add screen.** The Add
  screen intentionally has no confidence/difficulty section. A topic is due on
  its study date; the first rating there is review #0, and the schedule counts from the moment of that
  rating (the user's model: "when I rate it, that is when I studied it"). The Add screen's "Save and
  rate now" (new topics studied today or earlier) opens that first rating straight away, so the anchor
  does not drift to whenever the learner next opens Today.
- **Day-granularity due model** (date-only). Not a bug; intervals are whole days.
  Due dates are `reviewedAt + intervalDays * 86_400_000` — ELAPSED milliseconds, not calendar
  addition. That is required: forgetting is physical, so FSRS must be fed true elapsed time, and a
  calendar-based due date would make preview/commit/replay depend on the device time zone at the
  moment each ran. The cost, measured and pinned by `DueDateDstTest`, is that a review between 23:00
  and midnight on a DST spring-forward night slips one day, and its mirror image: a review between
  midnight and 01:00 on the fall-back day comes due one calendar day EARLY (a one-day interval that same
  evening; pinned since 2026-09-28, when the test's comment still said a date never arrives early).
  Accepted; do NOT "fix" it by switching to calendar addition.
- **The recall prompt is an OPTIONAL per-topic field** (restored 2026-09 by user decision, after
  being cut as v1 bloat), worded since 2026-09-23 as SCOPE — "What does this topic cover?" — not as a
  quiz question, because a review is not a recall test. A bare title like "Appendicitis" leaves open
  what "I still remembered it" means, and that noise sits under every interval FSRS computes; one
  optional line fixes most of it. It shows on the review screen under the title. The column keeps its
  name (`recallPrompt`). A merge keeps a prompt (survivor's, else the first absorbed copy's). Analytics
  exports only `hasRecallPrompt`, never the text — it is user content, like titles and notes.
- **Key points are REFERENCE TEXT ONLY; the rating cap is RETIRED** (user decision 2026-09-23,
  `domain/srs/KeyPoints`). From DB v8 until then, a topic's key points were a scoring standard: after a
  reveal the learner ticked the points they produced and the ticks capped the memory rating (all =
  any rating, at least half = up to Hard, fewer = Forgot), because in simulation optimistic self-ratings
  moved true recall more than any scheduling rule. The user retired it: a review is done by any method,
  not recited inside the app, and a tick list fits one method and obstructs the others. What remains:
  stored points are shown as bullets with the notes on the review screen; the Edit screen offers the
  field only for a topic that already has points (so they can be read, edited or cleared) — new topics
  use the notes; the editor still stops at 12 and a stored list is never truncated on read; a merge
  keeps the survivor's points, else the first absorbed copy's, never a union; backup carries them;
  analytics exports only `keyPointCount`. Old logs keep their `keyPointsTotal` / `keyPointsRecalled`;
  every new log records -1/-1 ("not scored"). The DB columns stay (migrations are additive only). The
  overconfidence risk the cap addressed is now carried by the rating copy and by the calibration and
  personal model, which learn from outcomes; do not bring the cap back without the user.
- **First-run onboarding has a REMINDERS step** after language selection
  (`ui/onboarding/RemindersSetupScreen` + pure `RemindersSetupPolicy`). Android 14+ denies
  `SCHEDULE_EXACT_ALARM` to new installs, so without it every reminder silently fell back to an
  inexact alarm up to an hour late; and notification permission used to be requested the instant the
  language was chosen, with no explanation. The step explains both, both are optional, it is shown
  once and only if something is missing, and its "done" flag lives in the backup-excluded transient
  prefs so a restore onto a new phone shows it again. Settings keeps the same controls; both use
  `NotificationScheduler.exactAlarmSettingsIntent`, which carries the package URI.
  Anything drawn OUTSIDE `MedReviewApp` (both first-run screens) must paint its own background
  (`Surface(color = colorScheme.background)`) and pad for the system bars: the XML window theme is
  `Theme.DeviceDefault.NoActionBar`, which is dark, and the activity is edge-to-edge, so an unpainted
  screen showed light-scheme cards and grey text on a dark window whenever the phone was in light mode.
- **"Forgot" is not red.** Ratings use a calm palette; red = destructive actions
  only. No emoji, confetti, or "Great job!" language (mature tone).
- **FSRS mean-reversion targets D0(Easy)** — this is canonical FSRS. "Revert to
  Good" is wrong; do not change it.
- **Interval fuzz** is deterministic per (unitId, reviewCount), multiplicative
  ±5%, and never applied when the BASE interval < 3 days. Preview == commit ==
  replay is an invariant; `ReplayEqualsLiveTest` guards it bit-for-bit.
- **Room migrations are additive only** (`MIGRATION_1_2/…/9_10`, currently DB v10,
  `exportSchema=true`, schemas 2–10 committed; every builder adds `AppDatabase.ALL_MIGRATIONS`).
  Never `fallbackToDestructiveMigration`.
- **DB v5 honest-scheduling model**: `nextReviewAt` = the effective date every
  query uses; `modelDueAt` = the memory model's own date; `deferredUntil` = set
  only by user deferrals (Not today / redistribute / manual edit) and cleared by
  a real review. Deferrals must NEVER write `modelDueAt`.
- **Merging topics never discards work.** The same material often gets added
  twice (frequently in two languages). `MedReviewRepository.mergeUnits` keeps one
  survivor and, in ONE transaction: re-points every review log at it (logs are
  never deleted), sums `reviewCount`/`lapseCount` LESS each absorbed copy's first
  study that the combined history demotes to a re-encoding exposure (only the
  earliest first log still seeds; replay and the export's self-check count it the
  same way), sets stability and difficulty
  to a **review-count-weighted average** so the result sits nearer the copy with
  more iterations (an unrated copy still weighs 1, never 0), takes the
  **earliest** `nextReviewAt` (a merge must never push material further away than
  the schedule already had), unions `highYield`, KEEPS a copy's deferral when the
  earliest date came from one (`deferredUntil` = that date; without deferrals the
  earliest date is the earlier clock and nothing is deferred), and
  SOFT-deletes the absorbed copies so a mistaken merge is recoverable. No schema
  change — it is a re-pointing of existing rows. `MergeUnitsTest` pins all of it, and
  `AnalyticsExportConsistencyTest` pins that a merge of rated, deferred copies (and a
  correction after it) leaves the export's self-check empty. Until 2026-09-27 the merge
  summed the counts and cleared the deferral while keeping the deferred date; on the
  Samsung the export then flagged the survivor twice (REVIEW_COUNT_MISMATCH,
  CLOCK_DISAGREEMENT). Topics merged by older builds keep those flags until a correction
  replays them.
- **Per-topic delete is SOFT** (`deletedAt`, 30-day grace, purge on app start,
  restore from the archive screen). The only hard deletes are the purge and
  "Delete all data".
- **The exam date is DECORATIVE on purpose.** It powers the countdown on Today /
  Library / Progress and nothing else. It must NEVER compress intervals, cap the
  schedule, or otherwise feed the scheduler. Confirmed by the user 2026-08;
  do not propose exam-horizon capping again. Re-confirmed 2026-10-03: no exam hint on Today either (the date is
  purely cosmetic).
- **The FIRST graded rating is always review #0** (POLICY `YADORA-2`), however
  late it happens. It seeds the model from the rating (`Fsrs.initialState`) and
  is capped by `FIRST_STUDY_MAX_DAYS`. The old rule treated a back-dated first
  rating as a recall against the neutral placeholder state AddUnit seeds, which
  both skipped the cap and exploded intervals the longer a topic sat (5d on time
  → ~43d at five days late → ~108d back-dated a month). `RegressionTest` pins the
  no-cliff property. A rated topic's schedule no longer depends on `studiedAt` at
  all; an UNRATED topic is still due on its study date, and editing that date
  moves the due date.
- **Fidelity to FSRS-5 is verified, not assumed.** `FsrsSpecComplianceTest` re-implements the
  published FSRS-5 equations independently and asserts `Fsrs.kt` agrees to ~1e-9 across a
  sweep of stabilities, difficulties, grades and elapsed times — plus no NaN/∞ from any
  reachable input, and the monotonicity properties (a later successful recall never yields
  less stability; a lapse never strengthens memory). `SchedulerInvariantsTest` does the same
  for the product layer. If you change `Fsrs.kt` or `MedScheduler.kt`, these two are the
  tests that matter: a wrong exponent would not crash and would not fail a relational test,
  it would silently mis-time every review for years.
- **CONFORMANCE IS CHECKED AGAINST py-fsrs ITSELF, not against a transcription.**
  `Fsrs6GoldenVectorTest` compares against `golden_fsrs6.json`, generated by
  `tools/generate_fsrs6_goldens.py` CALLING py-fsrs 6.3.1's own methods: 4,932 vectors (2,640
  single-equation transitions, 2,112 composed steps, 88 recall and 88 interval points, 4 seeds),
  7,050 compared values.
  This exists because a hand-written spec test cannot catch a misread equation: it shares an
  author with the code, so the same mistake lands in both and the suite stays green. Four real
  deviations survived exactly that way and were only found by running the reference:
  mean reversion must target the **unclamped** `D₀(Easy)` (−4.77, not the clamped 1.0 — py-fsrs
  passes `clamp=False` there and `clamp=True` only when seeding a new card); a lapse is bounded
  by the **short-term branch** `S / e^(w17·w18)` ≈ 0.9518·S, not by `S`; the same-day multiplier
  is floored at 1 for **Good and Easy only** (6.3.1 lists just those two; py-fsrs 6.3.2, August 2026,
  added Hard, and the pin stays at 6.3.1 on purpose: adopting a new reference means a new model identity,
  and this one only touches a topic reviewed twice on one calendar day and rated Hard, where it cuts
  stability by more than half, 100 days to 45. The app almost never produces that: the daily plan offers
  a topic again only on a later day, Review ahead leaves out topics reviewed today (2026-09-28), and the
  soak's 24,631 reviews contain none. What remains is a deliberate second review from the Library and the
  fall-back-night hour in the due-date entry above); and the stability floor is 0.001, not 0.01.
  A fifth was found only after the goldens were extended to the COMPOSED step: Yadora clamped
  stability at a MAXIMUM of 3650 days, which the reference does not do. Testing the internal
  functions alone left the seams unchecked — branch selection, argument order, final clamps — and
  the original sweep stopped at S=1000, below the clamp. The sweep now runs to S=20000 on purpose.
  A ceiling on the interval is a product decision and lives in `MedScheduler`; a ceiling on the
  STATE corrupts the model, because every later transition then reads a stability the evidence
  does not support.
  Regenerate the goldens only to adopt a new pinned reference version, and treat every resulting
  diff as a decision to record — never relax the tolerance.
- **FSRS-6 elapsed time is LOCAL CALENDAR DAYS** (`MedScheduler.modelElapsedDays`), not floored
  elapsed milliseconds. The queue offers a topic from 00:00 on its due day, so a topic reviewed at
  20:00 with a one-day interval is offered at 00:00 the next morning — only 13 hours of elapsed
  time, which floors to ZERO completed days and routes a genuine next-day review into the SAME-DAY
  branch, worth a ~5% stability bump instead of a real one. Milliseconds also leave a discontinuity
  at the previous review's hour. Calendar days remove both and match the fitted domain (the weights
  come from Anki histories, where elapsed time is a difference of day numbers). Accepted cost: the
  count depends on the device time zone, so a history replayed after moving continents can shift by
  a day — rare and bounded, unlike the queue mismatch. FSRS-5 keeps fractional ms; it is frozen.
  An outside audit (2026-09-27) showed the shift can matter: two reviews an hour apart across midnight
  became a same-day pair after a move, and the current interval went from 40 to 26 days. Since then an
  UNCHANGED rating correction replays nothing, so only a real correction made after a move can shift a
  history. FIXED 2026-10-04 without a schema change: every replay (a correction, the move onto a new weight set, the
  personal model's training data) reads back each FSRS-6 recall's own stored day count (`MedScheduler.storedModelDays`,
  `replayElapsedDays`; a merged history is still counted again, since its copies' counts run from their own reviews),
  and TIME_ZONE events record the phone's zone, so `analyze.py` counts each review in the zone it was made in. The
  live rule is unchanged: a review counts its days in the zone the phone is in then. A clock set BACK between two
  reviews no longer reorders a replay (saved order since 2026-10-03, entry "A topic's history is walked in the order
  it was saved").
- **FSRS-6 is fed COMPLETED WHOLE DAYS** (`MedScheduler.completedModelDays`). The reference
  measures a review's age in whole days and the weights were fitted that way, so fractional
  elapsed time runs the model outside its fitted domain — and on a day-granularity app it also
  means reviewing at 22:00 rather than 09:00 buys a different interval for identical evidence.
  FSRS-5 keeps fractional elapsed time because it is frozen.
- **FSRS-5 carries the same four deviations ON PURPOSE.** Frozen means it reproduces the
  schedules users actually received; correcting it would rewrite their history. Do not "fix" it.
- **The live memory model is FSRS-6** (`MedScheduler.CURRENT_MODEL`), policy `YADORA-5`,
  parameter set `FSRS6-DEFAULT-21-PYFSRS-6.3.1` — the id names the weights **and** the
  implementation, because the same vector through different equations is a different model and
  calibration must never pool the two.
  FSRS-5 is KEPT, frozen, in `Fsrs.kt` — a stored stability is only meaningful together with
  the model that produced it, so old history must keep replaying under FSRS-5. Never delete it.
  - `study_units.memoryModel` says which model owns a row's state; `ReviewLogEntity
    .schedulerVersion` says which produced each log.
  - `MedReviewRepository.projectOntoCurrentModel` carries a topic across by REPLAYING its real
    rating history through FSRS-6 — the stored FSRS-5 stability is not portable. It runs lazily
    at the next review (so upgrading moves nothing), is idempotent, skips re-encoding
    exposures, and deliberately does NOT recompute the schedule: the date the user was already
    promised is kept, only the latent state moves.
  - `editReviewRating` replays the WHOLE history under the topic's CURRENT model, not per-log.
    Mixing models mid-stream would yield a state belonging to neither, and this keeps replay
    consistent with the projection. A topic still on FSRS-5 replays under FSRS-5.
  - **Projection happens at DISPLAY time, not only at commit.** The rating buttons preview an
    interval from the unit currently on screen, so `ReviewSessionScreen.advanceUnit` projects before
    assigning `_currentUnit`. Showing a raw FSRS-5 row while the commit projects to FSRS-6 first
    previewed one number and scheduled another for every un-migrated topic. Projection is a pure
    function of the logs and idempotent, so running it twice is free and cannot drift.
  - **Anything that reads a stability must ask which model owns it.** `MedScheduler.retrievability(
    elapsed, stability, model)` dispatches; the forgetting-curve sparkline and the exposure log both
    go through it. The two curves agree only at t = stability and diverge sharply in the tail.
  - **The in-app calibration card is scoped to `CURRENT_MODEL`**, matching the rule the analytics
    export already states. Pooling FSRS-5 and FSRS-6 logs averages two different forgetting curves
    and reports an accuracy belonging to neither. It goes quiet after a model change until ten new
    reviews exist — the honest answer, since nothing is yet known about the new model on this user.
  - **`ReplayEqualsLiveTest` must mirror the real commit path** (project + `CURRENT_MODEL`).
    When FSRS-6 went live it kept passing while production disagreed with itself, because the
    test's own helper still defaulted to FSRS-5. If you change the commit path, change it there.
- **Understanding uses a SECOND CLOCK, not a multiplier** (DB v6). `modelDueAt` holds the pure
  memory prediction; `understandingDueAt` holds a short repair deadline (Confused or any lapse:
  1 day; Partial: 2/3/4 by recall strength; Clear: none); `nextReviewAt` is the earlier of the
  two. The old ×0.8/×0.9 was incoherent at long intervals — a topic the user said they did NOT
  understand still vanished for 80 days after a 100-day prediction. FSRS-5 keeps the multiplier
  so legacy replay reproduces what users actually experienced.
- **The repair clock BACKS OFF** (POLICY `YADORA-6`, user-chosen 2026-09-14). Each consecutive
  answer that leaves understanding unrepaired (Partial/Confused on a successful recall — a lapse or
  a Clear ends the streak) doubles the deadline (3 → 6 → 12 → 24 d), and a deadline that would not
  beat the memory date is dropped (`remediationDays` returns null; since YADORA-7, 2026-10-04, the date as finally
  scheduled, fuzz included: `MedScheduler.repairDays`). A two-year simulation showed
  the flat clock looping a topic every three days forever while its memory date sat months out; a
  repair asked for and not done is evidence that re-quizzing is not fixing it. The streak is
  derived from the logs (`MedScheduler.unrepairedStreak`) by the preview, the commit AND the replay
  — never stored — and rows stamped YADORA-5 or older replay their flat deadline
  (`backsOffRepairClock`). `RepairClockBackoffTest` pins the table, the streak rule and the
  ordering; `ReplayEqualsLiveTest` pins live == replay with a streak in play.
- **Intervals are CALIBRATED to the user's own recall** (`RecallCalibration`, DB v7). The default
  FSRS-6 weights describe an average Anki flashcard user; a Yadora topic is bigger and a given
  learner forgets faster or slower. `MedScheduler.calibrationScale` is ONE number learned from the
  last 600 real recall reviews on the live model: the stability scale at which the model's predicted
  recall count equals the observed count (method of moments on the RAW stored predictions, so it
  never chases the corrected schedule), shrunk toward 1 in log space by `n / (n + 120)` and clamped
  to 0.5–1: since 2026-09-28 it shortens intervals but NEVER lengthens them (entry below); scales up to 2
  that older builds stored still replay unchanged (`safeScale`). It multiplies the memory interval only, before the caps — equivalent to a per-user
  retention adjustment — and never touches stability or difficulty. It is refreshed from the logs
  right before anything schedules with it (session start, the Important toggle, a rating
  correction) — NOT at app start, where a startup thread raced the session — each log stores the
  scale it was scheduled with (`calibrationScaleAtReview`), and replay uses the stored one for
  untouched rows. The shrinkage is deliberately strong: the FSRS-6 curve is so flat that a
  one-point recall gap is a ~15 % stability change, so 100 reviews alone would swing the scale by
  ~1.5× on noise. It stays as the first-order correction on top of whichever weight set is active;
  the full refit of all 21 weights is the personal weight set below, and neither is presented as the other. Constants are POLICY.
  `RecallCalibrationTest` pins recovery of a planted scale, shrinkage, bounds and reach.
  **Evidence** (`RecallCalibration.isEvidence`, stated again in SQL by
  `ReviewLogDao.getRecentRecallLogsOnce`; `RecallCalibrationEvidenceTest` pins the two together):
  at least 3 calendar days after the previous review, and at least half the memory interval that
  review set. The 3-day rule is load-bearing: FSRS-6 is fed whole calendar days, so a 2.5-day gap is
  predicted at t = 3, short-interval predictions run pessimistic, and fed those rows the estimator
  drove a perfectly average simulated learner to 1.58× within three months. The half-interval rule
  drops reviews brought forward early (a repair deadline, an on-demand review): at a 90% target they
  sit above ~0.94 predicted recall, carry a fraction of an on-time review's information, and are a
  selected set. Backed-off repairs later than half the interval DO pass — accepted. A stricter
  "not before the due day" rule was simulated (2026-09-14): no measurable gain, and a noisier
  estimate for an average learner (0.67–1.74 over two years against 0.76–1.25). Do not raise the
  fraction to 0.8 either: an on-time review of a 3.99-day interval happens 3 whole days later
  (0.75), so on-time reviews would drop out while late repairs still passed. The Progress card is
  computed from these same rows (`calibrationStatsOf`) and compares the DEFAULT model's stored
  predictions with outcomes; the corrected predictions agree with them by construction. Both leave out the predictions
  a rating correction recomputed (every later review of the corrected topic that existed by then, read from its
  RATING_CORRECTED event: `data/RecomputedPredictions`, 2026-10-03), as `analyze.py` does; a correction made before that
  event existed cannot be seen. "Existed by then" is the event's `upto`, the topic's last log id in the correction's
  transaction (since 2026-10-04), not the clock, which can have gone back since those reviews.
- **The memory model is fitted to the learner — a PERSONAL WEIGHT SET — only when their own later
  reviews prove it predicts better** (DB v9, `domain/srs/Fsrs6Optimizer`, `memory_parameter_sets`,
  `data/PersonalModelWorker`). Once a day (battery not low, Settings switch on) the repository rebuilds
  every topic's graded history exactly as projection does and, with at least 640 loss-eligible reviews
  and 20% more than the last attempt saw (or 30 days since it), runs py-fsrs 6.3.1's own training
  procedure: binary cross-entropy, same-day reviews out of the loss, the first 64 steps per topic, Adam
  at 0.04 with cosine annealing, five epochs, batches of 512, every weight clamped to the reference
  bounds, starting from the defaults — preceded by the initial-stability PRETRAIN Anki's optimizer uses
  (each first grade's second reviews, shrunk toward the default, monotone in grade), without which five
  epochs cannot move S₀ far enough. Gradients are exact (dual numbers), pinned against the verified
  `Fsrs6` and central finite differences by `Fsrs6OptimizerTest`. THE GATE is Yadora's, not py-fsrs's:
  the history is cut in time into five chunks, each of the last four is predicted by a fit on the reviews
  before it (`FOLDS`), and the pooled per-review log loss must beat the weights in use with a one-sided
  paired z of at least `Fsrs6Optimizer.ACCEPT_Z` (2.33: a nominal one-sided 1% if every review were
  independent; the fit is repeated, so the real rate of adopting a worse set is NOT established — 0 in 40 below
  has a 95% upper bound of about 7%). Reviews of one topic turned out NOT to matter here: their held-out log-loss
  differences barely move together (design effect median 0.97, measured 2026-10-03), so a topic-clustered z reaches
  the same verdict every time, and `Fsrs6OptimizerGateTest` pins it. Measured by `Fsrs6OptimizerGateTest`:
  a learner the defaults describe was adopted 0 times in 40 refits; moderate departures 0 in 10 (once
  real reviews correct the state, the defaults' predictions differ too little); a strong departure 9 in
  10 at ~9,000 reviews and not yet at ~4,000. Only then
  is it refitted on everything and stored ACTIVE (the previous set RETIRED); otherwise the attempt is
  stored REJECTED with its scores. Two more conditions since 2026-10-02. The initial stabilities stay in grade
  order through training (`poolInOrder` after every step, weighted by each first grade's evidence, so a grade the
  learner never uses follows the ones they do): Adam moved each weight on its own, and S0(Good) could fall below an
  S0(Hard) no review touched, or the data could lift Hard above Good (an outside audit adopted S0(Hard) 3.53 d over
  S0(Good) 2.51 d), so "Hard" brought a new topic back later than "Medium". And the refit must NOT LENGTHEN this
  learner's intervals against the published defaults (`Fsrs6Optimizer.lengthening`: each set replays every topic to
  its own state, and the geometric mean of the next intervals' ratios at the learner's target must be at most 1; the
  owner's never-lengthen rule, see the calibration entry). Measured with both: the defaults' learner 0 of 40, the
  strong departure 9 of 10, a faster forgetter's set accepted (it shortens to 0.70), and the generous rater an outside
  audit built (true memory the defaults', 60% of lapses called Hard) refused although it predicted the RATINGS better
  (z = 2.66): it would have lengthened intervals by 14% while predicting true recall worse. Progress says when a set
  that predicted better was refused, and the event log records `lengthening=`. The comparison is with the published
  defaults, not with the defaults times this learner's calibration, on purpose (an outside audit, 2026-10-03, asked for
  the calibrated baseline after a calibration of x0.51 gave way to a set at x0.70; it was built, measured and reverted
  the same day): one calibration number cannot bend the curve, so for a learner who forgets more steeply it
  over-shortens, often to its x0.5 floor, and a calibrated baseline would refuse 22 of 40 such learners' sets that
  predict better (`Fsrs6OptimizerGateTest`). Either way no learner is scheduled longer than the defaults ON AVERAGE: the
  check is the geometric mean of the next intervals' ratios, so single topics can come out longer (an outside audit,
  2026-10-04, built a set at 0.98 on average with Good at 1.25; a per-topic ceiling would refuse sets that predict
  better, and is the owner's call). An ACTIVE set
  whose first-rating grades are out of order (adopted before 2026-10-02, or restored from such a file) is retired when
  the model is loaded, with a PERSONAL_MODEL_RETIRED event; it stays readable for replay. Model identity is now (model, weight set):
  `study_units.parameterSetId` and `review_logs.parameterSetId`, 0 = the published defaults. Every
  FSRS-5→6 rule applies to sets: adoption writes no topic; a topic crosses by `projectOntoCurrentModel`
  (replay onto the active set) at its next display or commit; `editReviewRating` replays a topic on the
  set it is ON, and `MedScheduler.weightsFor` throws for a set the registry does not hold, so a
  correction fails closed; the calibration and the Progress card pool evidence within one set and read
  its predictions on that set's own curve. The in-memory active set changes only in
  `refreshMemoryModel` — session start, the Important toggle, a correction, a merge, a restore — never
  from the worker, so a session never previews with one set and commits with another. Retired sets are
  never deleted: replay needs them. `useDefaultMemoryModel` (the Settings switch) retires the active set.
  Why: per-user fitting cuts calibration error by about 30% against the defaults on the public
  benchmark, and the one-number calibration cannot change the curve's shape, what a first study is
  worth, or what a lapse costs. Backup v8 and analytics v10 carry the sets.
- **On-demand review from the Library is allowed; interval compression by exam date is not.**
  Long-press a topic → play. It opens the single-topic session whether or not the topic is due; an
  early review is ordinary FSRS (high predicted recall, a small stability gain, a lapse is a lapse)
  and the schedule is recomputed from it honestly. This is the learner choosing to check a topic
  before a test. It does not read the exam date, and the exam date still feeds nothing.
- **"Not today" never moves a topic sooner** (`MedReviewRepository.procrastinateUnit`). A topic not
  due before tomorrow 08:00 — reachable through review-now — is left untouched: no deferral, no event.
- **Today shows NO time estimate** (2026-09-23). It used to print "about N min" from the median of the
  last 50 measured review durations. A review is done however the learner likes, mostly outside the
  app, so the seconds a card sits open measure nothing, and the estimate would be invented.
  `reviewDurationMs` is still logged as research data. Nor does a review ask how long it took (the owner,
  2026-10-03): the time a student spends depends on how much time they have that day, not on the topic.
- **The daily limit is a limit per DAY** (`ui/today/DailyPlan`, 2026-09-23). It used to cap each
  session: finishing N and starting again loaded the next N, while Today claimed the rest were "held for
  later by your daily limit". Now the reviews already done today (`logType != FIRST_STUDY` since local
  midnight, `ReviewLogDao.countReviewsSince`) count against it. FIRST RATINGS ARE NEVER HELD BACK and
  come first: they log a study that already happened, and the schedule counts from the rating, so
  holding one behind the limit would move its anchor to another day. Reviews are ordered by
  `priorityScore`. ONE plan is counted everywhere — the review session, the Today card, the reminder
  receiver, the notification's list, the safety worker, the boot catch-up and the widget
  (`MedReviewApplication.todayPlan`) — so once the day's reviews are done the reminders stop nagging
  and the widget says "done for today". Today then shows "Today's reviews are done" with "Review more
  anyway", which opens a session without the limit (`Screen.ReviewSession(ignoreLimit = true)`); a
  Today card or the Library's review-now opens a single topic regardless.
- **Edit-screen Save writes against the row as it is NOW** (`ui/add/TopicEdit`). The form is filled
  once, and the screen can sit in the back stack while a reminder's "Review now", the notification's
  "Not today" or a rating correction writes the same row; saving the loaded copy silently undid them
  (the review's log stayed, the row went back). Save re-reads the row in a transaction, applies the
  fields the form owns, and changes a date only when the form's date differs from the one it was
  LOADED with. A changed study date always recomputes the schedule — unrated topics too, which used
  to stay due on the old day. Editing a topic that no longer exists fails instead of inserting a
  copy. `TopicEditTest` pins the rules.
- **Slider-backed settings are read ROUNDED** (`MedScheduler.safeDailyLimit(Float)`, the Settings
  sliders). Compose's slider interpolates in Float: the 70 and 130 daily-limit stops were stored as
  69.99999 and 129.99998, and every `toInt()` read made them 69 and 129.
- **YADORA-3 damping is superseded on the live path.** FSRS-6's own refit puts S₀(Easy) at 8.30
  versus FSRS-5's 15.69, so the hand-chosen shrink is no longer needed — a fitted value replaced
  it. The damping code stays for FSRS-5 replay only; applying both would double-count.
- **WHAT THE TESTS DO AND DO NOT PROVE.** `FsrsSpecComplianceTest` and
  `SchedulerInvariantsTest` establish **implementation validity**: the code computes the
  FSRS-5 equations correctly, intervals stay bounded, replay is deterministic, invariants
  hold. They establish **nothing** about **predictive validity** (are the predicted recall
  probabilities calibrated for real Yadora users?) or **decision validity** (does this
  schedule actually minimise study time for a given retention?). Those need held-out
  outcome data, which does not exist yet. Never write "the algorithm is verified" — write
  "the implementation is verified." The product-layer constants below are POLICY, chosen by
  reasoning, not fitted to evidence.
- **`FIRST_STUDY_MAX_DAYS = 5`, the understanding factors, the relearn step and the
  YADORA-3 damping amount are UNVALIDATED POLICY CONSTANTS.** Their *direction* is
  defensible from the literature (see below); their *magnitudes* are judgement calls. Treat
  them as candidates to test against real data, not as settled science.
- **The scientific basis of the product-layer choices** (do not "simplify" these away):
  power-law forgetting `R = (1 + FACTOR·t/S)^-0.5` is FSRS-4.5+/5's deliberate replacement for
  the exponential curve because it fits real review data better; scheduling at ~0.90 retention
  (slider 0.85–0.97; 0.90 is the practical default — in Yadora's own sweep the equal-time advantage is a
  tie across 0.85–0.90, and 0.90 knows more in absolute terms — 0.95–0.97 buys more recall at roughly
  1.4–2× the reviews, but is NOT the exam playbook: see "The exam playbook" below) sits in the workload-optimal band from FSRS's own retention simulations
  and matches Bjork's desirable-difficulty argument that retrieval should be effortful but
  successful; and `FIRST_STUDY_MAX_DAYS` exists because a self-rating taken immediately after
  studying measures *current fluency*, not delayed retention (the well-documented
  judgment-of-learning illusion), so the model's own `S₀(Easy) ≈ 15.7 d` must not be trusted
  before one real retrieval test has happened.
- **The first-study prior is DAMPED toward neutral** (POLICY `YADORA-3`). A rating given
  moments after studying measures current *fluency*, not durable memory — the
  judgment-of-learning illusion — whereas FSRS's `S₀(Easy) ≈ 15.7 d` was fitted on genuine
  *delayed* recall. `MedScheduler.firstRatingState` therefore seeds the geometric mean of the
  rating's own `S₀` and the neutral Good prior (Easy 15.7 → 7.1 d, Good unchanged, Hard
  1.2 → 1.9 d), preserving the ordering the user expressed while limiting how far one
  over-confident answer propagates. DIFFICULTY is deliberately left undamped (bounded,
  mean-reverting, and it does not set the interval directly). Replay honors the damping rule of
  the policy each log was STAMPED with, so correcting an old rating reproduces the schedule the
  user actually had rather than re-deciding it under today's rules.
  CAVEAT, stated honestly: the *direction* of damping is supported (judgment-of-learning
  illusion), but the *amount* — geometric mean, i.e. halfway in log space — was chosen by
  reasoning, not fitted. The one piece of independent corroboration is that FSRS-6's own refit
  moved `S₀(Easy)` from 15.69 to 8.30 (−47%) while this damping gives 7.06 (−55%): a large
  independent re-estimation landed close to the same place. That is encouraging, not proof.
- **A later `FIRST_STUDY` log is a RE-ENCODING EXPOSURE, never a recall.** Only reachable after
  a merge (the absorbed copy's own post-study self-rating). The replay leaves stability,
  difficulty and the graded review count untouched for it and only re-anchors the elapsed-time
  clock, and it KEEPS its `logType` rather than being rewritten to `RECALL`. Feeding it through
  the recall path would reward re-reading as if it were remembering, and would trust completely
  the very signal YADORA-3 exists to distrust.
- **A merge RECONCILES models before averaging.** Every copy is projected onto `CURRENT_MODEL`
  first and the survivor is stamped with it. Two copies of the same material can sit on different
  models (one reviewed since the switch, one not), and an FSRS-5 stability is not measured in the
  same units as an FSRS-6 one. This also closes a second hole: leaving the merged row on the old
  model meant the lazy projection at the next review replayed the now-COMBINED history and silently
  replaced the weighted average with a chronological replay — the behaviour deliberately not chosen.
  The understanding clock takes the earliest non-null across copies (same never-push-further-away
  rule as the due date, and it keeps `nextReviewAt` equal to the earlier of the two clocks);
  neutralized absorbed copies get it cleared along with their counts.
- **Merging keeps the review-count-weighted average** rather than replaying the combined history
  chronologically. Replay is arguably more principled (stability/difficulty are nonlinear
  summaries, so averaging them is not a real memory state), and the machinery exists — but the
  averaging behaviour is conservative, tested, and easy to explain. User-confirmed 2026-08;
  revisit only with evidence, not on theory alone.
- **ONE canonical history reconstructor, two call sites.** `projectWithHistory` (migration/merge)
  and `editReviewRating`'s replay must reconstruct a history IDENTICALLY. They did not: projection
  dropped later `FIRST_STUDY` exposures from the list entirely, so the elapsed clock was never
  re-anchored and the following recall was credited with the time since the previous GRADED review
  — 20 days against the replay's 10 on a study/recall/re-study/recall history. A topic's state
  therefore depended on whether it arrived via migration or via a rating correction. `MergeUnitsTest`
  now pins the two paths against each other; if you touch one, touch both.
- **A topic's history is walked in the order it was SAVED** (`REVIEW_HISTORY_ORDER`, by log id, 2026-10-03). The id
  is autoincrement and a restore keeps it, so it records the true sequence; the time stamp does not, because a phone
  clock set back between two reviews gives the later review the earlier time. The live path clamps that gap to zero.
  Sorted by time, every replay (projection, a rating correction, the personal fit's histories, a merge's choice of
  seed), the repair-clock streak and the export's derived fields put the two reviews in the wrong order and rebuilt a
  state the topic never had. All of them walk saved order now, and so does `analyze.py` (`history_order`); for an
  ordinary history the two orders are identical, so nothing else moved. `ReplayEqualsLiveTest` pins live == replay with
  the clock set back, and the pilot fixture carries such a topic, so `test_analyze.py` checks that the toolkit agrees.
  Elapsed days after a time-zone move: fixed 2026-10-04 (replays read each review's stored day count; the
  calendar-days entry).
- **Projection FAILS CLOSED.** `advanceUnit` used to fall back to the raw row when
  `projectOntoCurrentModel` threw — an FSRS-5 stability behind a preview that computes FSRS-6
  intervals, exactly the mismatch the projection exists to prevent. A topic whose history cannot be
  replayed is left out of the session and COUNTED, and the count is shown on the session summary; a
  silently shorter queue is indistinguishable from "nothing was due".
- **Backlog recovery is capacity-aware** (`OverdueRedistributor`, 3–14 days). The old fixed
  three-day window turned 100 overdue topics into ~34 a day for a user whose daily limit is 10 — a
  plan they cannot execute, which teaches them the dates mean nothing. Days are derived from the
  daily limit and capped at 14; past that the plan overloads rather than pushing memory reviews
  months out. Keep the Settings copy honest about it. It spreads overdue REVIEWS only
  (`OverdueRedistributor.spreadable`, 2026-09-29): a never-rated topic stays due, where the daily plan offers it
  first, because its schedule counts from the rating. Spreading it moved that anchor days past the study (seen on
  the emulator), and the card counted it among "reviews waiting". The notification's "Not today" leaves first ratings
  due for the same reason (`procrastinateAllDue`, 2026-10-02; the review screen never offered it for them). Since
  2026-10-02 the plan first KEEPS what is left of today's limit, after the reviews already done and today's own
  reviews, for the most urgent of the backlog, and spreads the rest from tomorrow (`keptToday`, `dayOffsets`). It
  started tomorrow whatever the hour, so pressed before studying it left the day's capacity unused while the card
  promised "let's recover the important ones first" (two outside audits, 2026-09-30); pressed after the day's limit it
  keeps none, as before. The card says how many stay today and over how many days the rest go, and Settings no
  longer promises "never more than you can do": a backlog over two weeks of the limit puts more on each of those days.
- **Duplicate detection normalizes Persian/Arabic** (`TopicTitle`). SQL `lower(trim(title))` is byte
  equality with an ASCII-only lowercase, so Farsi yeh vs Arabic yeh and keheh vs Arabic kaf —
  chosen by the keyboard, not the writer, and visually identical — produced two topics with no
  duplicate warning. That is precisely the "same material added twice, often in two languages" case
  merge exists for. Comparison only; stored titles stay exactly as typed.
- **One memory seed per history.** `Fsrs.initialState` may only be re-applied for the
  chronologically FIRST review log of a topic. A merged topic legitimately carries
  several `logType = "FIRST_STUDY"` rows (one per absorbed copy), and treating each
  as a seed silently reset the merged FSRS state on any later rating correction or
  study-date edit. The replay keeps the extra rows as `FIRST_STUDY` re-encoding exposures (see the
  exposure entry below); it does not rewrite them to `RECALL`.
- **`MedScheduler.review` takes a REQUIRED model parameter.** A default is a trap: a new call site
  that forgets it schedules on the retired model, compiles, runs and looks right. Tests that pin
  FSRS-5-only behaviour (the retired ×0.9/×0.8 understanding multiplier) now say `FSRS_5` out loud
  and are named "legacy"; everything else runs on `CURRENT_MODEL`.
- **The post-review explanation takes BOTH clocks.** It used to receive only the memory interval,
  so a Good + Partial review announced "next in 100 days" and then came back in three. The headline
  is always the date that actually applies; the memory estimate is named beside it when they differ.
- **Release binaries are NOT tracked in git.** The committed `app/release/app-release.aab` had gone
  stale by dozens of commits and would have shipped the retired scheduler. Build and sign from a tag.
- **The queue is ordered by `modelDueAt`, NOT `nextReviewAt`.** Whether a topic is OFFERED still
  follows `nextReviewAt`, so a deferral is honoured — but the ORDER among today's due topics comes
  from the model's own date. Ranking by the effective date meant every "Not today" reset the overdue
  term to zero, so a user who tapped it daily kept their backlog permanently quiet while a user who
  simply ignored the notification watched theirs climb. Deferring is a scheduling choice, not
  evidence about memory. `PriorityScoreTest` pins that the two users get identical urgency.
  The understanding repair deadline counts too: lateness runs from the EARLIER of `modelDueAt` and
  `understandingDueAt`. The repair date is the scheduler's own, not a deferral, and reading only
  `modelDueAt` let a 3-day repair wait for weeks with zero urgency while Today listed it as overdue.
- **Important never means less retention.** `effectiveRetention(true)` is the user's target + 0.03,
  capped at 0.97 and never below the normal target. Above a 0.97 target (reachable only through a
  restored backup) the cap used to put important topics BELOW normal, scheduling them later.
  `SchedulerInvariantsTest` sweeps targets up to 0.99.
- **An IGNORED topic is never written to.** Nothing in the app mutates an overdue row: every
  `UPDATE` is user-initiated, and app start only purges already-deleted rows. Displaying one
  projects it onto the current model for the preview, but that copy is never persisted — the row
  stays on its old model until a real review commits. `NeglectedTopicTest` sweeps neglect from one
  day to a century across every state/rating/understanding combination (finite, bounded, ordered,
  never throws), and `ReplayEqualsLiveTest` pins the row as byte-identical after repeated reads.
- **The queue order is IMPORTANCE, LATENESS and a CAPPED REVIEW VALUE, nothing else** (`priorityScore`,
  2026-09-29; validated at the owner's request, "switch only if everything holds"). Important adds 100 (= 20 days of
  lateness); lateness adds 5 per day on the model's and the repair clock, uncapped so nothing can starve; the review
  value, (1 − R) · R · the relative stability gain of a Good review NOW, read on the topic's own model and weight set
  (`MedScheduler.reviewValue`), adds 80 × itself, capped at 200 (= 40 days of lateness). Unrated topics score
  lateness alone. So among topics equally late, the one a review would strengthen most comes first: a young topic
  whose recall is falling before a mature one whose flat curve can wait.
  - **Measured** (`experiments.py --only order`, RESEARCH §2.4, 16 paired seeds): where the limit binds for weeks,
    +0.42 to +1.53 points at the one-year quiz against lateness alone and +2.1 to +4.8 on the weakest tenth; after
    a holiday a tie; at the default load equal within noise. With 30% of lapses rated Hard +0.90, with a true curve
    twice as steep as the model's +2.36. On the real app (`TwoYearSoakTest`'s learner at a limit of 25, one seed):
    exam day 94.1% → 95.6%, weakest tenth 66.6% → 76.4%.
  - **The price:** the longest wait past due rose from 22–65 days to 24–84 across seven worlds. The cap makes the
    bound a rule: no topic is passed over by one more than 40 days less overdue (60 if that one is Important). It
    also stops an extreme state (a tiny stability, a personal set with a steep gain) from jumping the queue.
  - **Where it came from:** an outside report's "Whittle index + concave aging" (2026-09-28): the same value with
    lateness as 10·ln(1 + days). That form knew more still (+0.1 to +3.9 at the quiz, +7.9 to +15.1 on the
    weakest tenth) but let topics wait 153–291 days past due. It is the owner's call if that trade is ever wanted;
    do not switch silently. Also rejected: its write-off below 10% recall (FSRS-6 reaches it only after about
    three million stabilities), its +60 repair bonus and its Important multiplier. Weights 40/100/160 and caps
    100/300 were tried: more weight gains a little and waits longer; a cap of 100 lost up to half the gain; 300
    never bound.
  - **Lowest recall first and relative lateness lift the weakest tenth most** (+5.5 to +16.5 against this order)
    but lose 1.7–3.0 points on the average, which is what an exam sampling the syllabus measures, and leave mature
    topics unreviewed for 227–312 days. Not adopted.
  - **History:** until 2026-09-24 the score also added +80/+40/+20 for NeedsRelearn/Learning/Building and +10 per
    lapse (capped at 5). Set by label, large and blind to recall, they lost to lateness alone in all five backlogs
    (+0.12 to +0.55 through the year). Do not add them back. `MAX_SCORED_LAPSES` caps only the Library's "weakest
    first" sort. Two outside reports (2026-09-28) called uncapped lateness a "FIFO trap" and proposed ranking by
    recall deficit × recoverability, (R* − R)+ · (1 − e^(−S/τ)), or by the largest 3-day recall loss; both lost to
    lateness alone (0.4–2.0 and 0.6–8.0 points at the quiz; RESEARCH §2.4). Their premise was wrong for FSRS-6: a
    topic 30 days overdue still sits at 59–88% predicted recall.
  - The order matters only when more is due than the day allows. It is not replayed, so no POLICY bump.
    `PriorityScoreTest` pins the terms, the cap and the fallbacks; the Spread-out plan uses the same order
    (`DailyPlan.byPriority`).
- **Retention is clamped on read** (`MedScheduler.safeRetention`), not just on write.
  Prefs store a `Float` and FSRS consumes a `Double`, so even a value clamped to
  exactly `0.99` reads back fractionally outside the band `FsrsParameters` accepts —
  and that `require()` throws. A bad setting must degrade to a sane schedule, never
  brick reviewing.
- **First-study intervals ARE fuzzed** (Good/Easy first ratings clear the 3-day
  threshold). This is intentional: it stops five topics added in one session coming
  due together forever. Only the doc comment claiming otherwise was wrong.
- **The database is EXCLUDED from cloud backup and INCLUDED in device transfer.** User-confirmed
  2026-08 after an external audit argued for including it everywhere. Phone-to-phone setup carries
  the full study history; a cloud restore after a lost or wiped phone carries settings only, and
  topics come back from the app's own JSON export (Settings -> Export full backup), which is the
  path that is actually tested. The audit's point that Android stops the app before Auto Backup is
  correct and does weaken the torn-WAL argument -- the decision stands anyway, because a restored
  database that will not open is a worse failure than one that is absent. Do not re-propose it.
- **Transient reminder state lives in its own `medreview_transient` prefs file**,
  excluded from cloud backup and device transfer. Android backs up whole prefs FILES,
  so `last_notif_shown_at` / `reminder_snoozed_until` / `reminder_next_nudge_at`
  otherwise travel to a new phone and silence its first reminders.
- **Policy versioning**: bump `MedScheduler.POLICY_VERSION` whenever a product-layer rule that
  REPLAY re-applies changes (understanding factors, relearn step, caps, fuzz, high-yield retention,
  repair backoff). Logs store the version, the applied understanding factor and the calibration
  scale; replay honors the stored values and the stamped policy's rules for untouched rows.
  Calibration constants (prior, window, clamps, evidence rules) do NOT bump it: nothing replays
  them, because every log already stores the scale it was scheduled with. Currently `YADORA-7` (2026-10-04: a repair
  deadline is kept when it beats the memory interval as finally scheduled, fuzz included; YADORA-6 compared it before
  the fuzz).
- **Behaviour changes are simulated before they are argued.** A two-year simulated student
  (honest ratings drawn from a true memory that may forget faster or slower than the defaults,
  a daily limit, a holiday, the Spread-Out button) found the Partial loop and the leech spiral
  that code reading missed. `tools/pilot/simulate.py` is the committed simulator: the owner's
  identical-twins test (same classes, same review time, Yadora vs review without a schedule, a quiz
  a year later) across learner types, inflated ratings, missed days, cramming and retention targets.
  Use it, extended if needed, rather than reasoning from one worked example when a policy number is
  on the table. Results as of 2026-09-29 (re-run with the new queue order) are in `docs/RESEARCH.md`
  §2: Yadora ahead by 5.0–8.4 points in every realistic scenario (8/8 seeds), about half the forgetting
  of the other twin at equal time; the only loss is an announced-exam cram needing 100–200 topic reviews
  a day; and the equal-time advantage is largest at 0.85–0.90 (a tie within noise; 0.90 stays the
  default because it knows 2.5 points more for 1.3× the reviews).
- **Review ahead** (2026-09-24, `ui/today/ReviewAhead`, Today once the day is done). Rated topics not due
  today, weakest predicted recall first (each topic read on its OWN model and weight set; one that cannot
  be predicted is left out), 20 per session, `ReviewSession(ahead = true)`. It exists because the twin
  simulation found one losing case: an announced exam where the other twin saves time for a final push.
  With the same realistic push, Yadora spending it weakest-first wins again (96.5% vs 91.8%). It reads NO
  exam date and compresses no interval: every review it offers is an ordinary early review FSRS scores
  honestly, and the calibration evidence rules already drop early reviews. Unrated topics are left out
  (their first rating belongs on the study day), topics due today stay with today's plan, and topics the
  learner DEFERRED ("Not today", "Spread out") are left out too: offering one again the same evening
  (first, since it is overdue on the model's clock) contradicted the learner's own choice.
  `ReviewAhead.isCandidate` is the one rule, used by the queue and by the Today button, so the button
  never opens an empty session.
- **Scheduling choices are checked against their alternatives by simulation** (`tools/pilot/experiments.py`,
  results in `docs/RESEARCH.md` §2.4, 2026-09-24). Queue order under a binding limit: see the queue-order entry
  above (lateness plus a capped review value since 2026-09-29, ahead of lateness alone, the old weakness/lapse
  bonuses, lowest recall first and relative lateness at the quiz); "highest recall first" is 7–8 points worse
  wherever the limit binds for weeks.
  The relearn step (1 d vs 2 d vs FSRS's post-lapse interval), the first-study cap (3/5/7 d/none) and the
  maximum interval (180/365/none over three years) are all within noise of each other at EQUAL TIME;
  the cap keeps ~0.2 points over the year when first ratings are overconfident and costs nothing when
  they are honest. A DIFFICULTY-ADAPTIVE target (lower for harder topics) buys ~0.2–0.4 points at equal
  time but costs the hardest quarter of topics 0.5–0.7 points. It is NOT adopted: that is the owner's trade-off to make, ideally with pilot data. Do not
  ship it silently, and do not re-run these experiments as if they were open questions.
  Re-run on 2026-09-28, with the calibration cap and the out-of-range guard: every budget fell inside its sweep
  (nothing published was extrapolated), with the same conclusions. The one open pattern: the 180-day ceiling
  led at the three-year quiz in both runs (+0.6 and +0.7 points, the same average). Both runs used the same three
  seeds, so it is one observation. A larger paired run would settle it before anyone argues for a change.
- **Backup, restore and the research export STREAM** (`data/JsonStreams`, 2026-09-24). They used to build
  one org.json tree and one String. Measured on a multi-year history (3,300 topics, ~20,000 reviews): a
  backup cost ~141 MB of live heap, and a restore held the file text, its parsed tree AND a full safety
  backup at once. That is past a phone's heap exactly when the data matters most. Now records are written
  one at a time, and read one at a time straight into entities: ~15 MB to write, ~9 MB to restore. The
  output is compact JSON with the same fields. Every older build's backup still restores: pretty-printed,
  whole doubles as integers, with or without a byte-order mark (`BackupStreamingTest` pins it). Restore
  validates the WHOLE file before it writes the safety copy or touches the database. A `backupVersion` that is present
  must be a whole number (text such as "10" used to read as version 1 and slip past the newer-version refusal); a file
  without one still reads as version 1. Settings runs the
  write and the restore NonCancellable, so leaving the screen can never leave a truncated backup the
  user believes is complete; a write that fails part-way deletes the file it was writing (the picked
  document, or the half-written share file), and the restore's reminder re-arm and widget refresh run
  inside the same non-cancellable block, because a cancelled `withContext` throws on return and used to
  skip them. The import holds the picked URI, not the file's text. The export's
  per-review deferral count is a binary search over sorted event times, not a scan of every event for
  every review. "Delete all data" also removes `cache/exports/`.
- **Pilot research data never feeds the scheduler** (DB v10, analytics export v12, backup v9). Each log
  can carry how the learner reviewed (`reviewMethods`: Questions / Reading / Lecture / Other, optional,
  several allowed, reset for every topic so no remembered choice is recorded as a new one), an optional
  question score (`questionsCorrect`/`questionsTotal`, kept only when it is a real count, else -1/-1), and
  the session that logged it (`sessionKind`: PLAN / EXTRA / TOPIC / AHEAD). A first study records no
  method or score. Nothing schedules from these fields until a pilot shows what they mean (docs/PILOT.md
  D6, D7). The export also carries a pseudonymous research id (`data/ResearchId`, `YD-XXXX-XXXX`, random,
  in the settings file so a JSON restore keeps it, cleared by "Delete all data"), content-free size
  proxies (notes length, has source, title length) and a `fieldGuide` that explains the file to whoever
  reads it cold. Settings → "Share research data" sends it through a FileProvider limited to
  `cache/exports/`.
- **Reminder delivery is measured on every pilot phone** (export v13, `notifications/ReminderTelemetry`,
  2026-10-03). Every reminder alarm carries the time it was armed for, its slot and whether it was armed exact
  (`NotificationScheduler.EXTRA_SCHEDULED_AT`, `EXTRA_SLOT`, `EXTRA_EXACT`; FLAG_UPDATE_CURRENT replaces them at every
  re-arm, and cancelling needs none). When it fires, the receiver logs REMINDER_FIRED (slot, scheduled, late_s, exact,
  Doze, battery saver, standby bucket, outcome, due count) AFTER the next alarm is armed, swallowing every error; the
  Settings switch logs REMINDERS_ON/OFF; the export adds `reminderHealth` (the Reminder Health checks plus battery
  optimization, background restriction and the standby bucket). Every day with reminders on has at least one fire, even
  with nothing due, so `analyze.py` reports per phone the days without one, fires more than 10 minutes late, fires in
  Doze and safety-net catches, and docs/PILOT.md D11 judges them (95% of days, 95% on time, 14+ days of data). Before
  this, a reminder that never came left no trace: NOTIF_SHOWN is written only when something is posted. Export v14
  (2026-10-03) adds APP_OPENED (`MainActivity.EXTRA_OPENED_FROM`: a tap on the notification, the alarm or the widget,
  logged once, not again when the screen is rebuilt), so the report follows each posted reminder to a tap within three
  hours and a review that day; and RATING_CORRECTED, the answer a correction replaced.
- **The pilot toolkit is part of the scheduling contract.** `tools/pilot/yadora_model.py` transcribes
  every rule that decides an interval, and `analyze.py` replays each exported review and demands the
  stored elapsed days, prediction and interval come out EXACTLY (fuzz included). A mismatch in a
  participant's file is a bug found without the phone. So a change to `Fsrs6.kt`, `MedScheduler`'s
  interval rules or `RecallCalibration` must be made in `yadora_model.py` too.
  `python3 tools/pilot/test_analyze.py` checks it against `tools/pilot/fixtures/sample_export.json`, a
  REAL export written by `PilotExportFixtureTest` through the review screen's commit path. Regenerate
  the fixture when the export format changes.
- **ONE commit path for a rating: `MedReviewRepository.rateUnit`** (2026-09-24). The review screen calls it,
  and so do `ReplayEqualsLiveTest`, `PilotExportFixtureTest` and `TwoYearSoakTest`. It used to live inline in
  `ReviewViewModel.rateCurrentUnit`, and three tests kept hand-made copies of it; when FSRS-6 went live one copy
  still defaulted to FSRS-5 and passed while production disagreed with itself. The move was verified
  byte-for-byte: the regenerated pilot fixture matched all 208 logs and every topic. Never reintroduce a copy
  in a test: call `rateUnit` with the `now` you need.
- **`TwoYearSoakTest` runs a residency candidate's two years through the real app** (2026-09-24). It covers
  730 days, 4 new topics on 6 days a week (about 2,400 topics, 23,000 reviews), and a simulated true memory
  (FSRS-6 defaults) giving honest ratings. It runs in Asia/Tehran (UTC+3:30), where the learners are: every
  local midnight falls on a half hour of UTC.
  - **What it drives:** each evening's session exactly as the screen opens it (`refreshMemoryModel`, the
    calibration refresh, `todayPlan` at the default limit), with a holiday, occasional "Not today", 15% Partial
    understanding, and 40 Review ahead topics a day in the last four weeks.
  - **Asserted every day:** the plan respects the daily limit, offers every first rating and counts what was done.
  - **Asserted at the end:** nothing overdue by more than two weeks and no gap over 400 days; the export has zero
    self-check issues; backup → restore → backup is the identity; a pure replay of EVERY topic reproduces its
    live row; and the twin claim holds on the real schedule.
  - **Measured:** exam-day recall 96.8% against 91.0% for a random-review twin at equal time (the twin's time
    matches Yadora's to within one review since 2026-09-27; before, it got a few percent more and scored 90.1%);
    100% of topics at 90%+; weakest tenth 93.1%; 24,631 reviews. (Before the calibration stopped lengthening
    intervals, 2026-09-28: 96.6%, 92.7% and 22,804 reviews. The 2026-09-29 queue order changed neither figure at
    this limit, which rarely binds; it changed which random draw each review gets, and the count, from 24,335. YADORA-7,
    2026-10-04, did the same: 96.9%, 90.6% and 24,673 before it.)
  - **CI:** `analyze.py` replays the export (27,063 logs, all exact) in the "Pilot toolkit agrees with the app"
    step, and exits non-zero on a single mismatch. Runtime is about 65 s.
  - **Thresholds:** do not loosen them to get a change through. If a deliberate scheduling change moves the
    measured numbers, re-measure and record why.
- **The exam playbook is 0.90 plus Review ahead, NOT a higher target** (`tools/pilot/residency.py`,
  `docs/RESEARCH.md` §2.5, 2026-09-24). Over two years at equal time, staying at 0.90 and using Review ahead in
  the last four weeks brought 99–100% of topics to 90%+ recall on exam day. Raising the target to 0.95 for the
  last six months cost more reviews and bought no more than the push (the same average or less, a weaker tail).
  For a fast forgetter or a heavy load the extra reviews overflow the daily limit and the weakest tenth fell from
  ~90% to 85–87% (~81% with the queue order before 2026-09-29). The Settings exam copy said "raise the retention
  target months ahead" and now says this instead. Against a fixed-interval ladder at equal time the AVERAGE is
  close (+0.2–1.4 points at 0.90; at 0.95 level only for a slow forgetter), but the ladder leaves its weakest tenth
  at 72–83% where Yadora keeps 90–92% (re-run 2026-09-29 with the new queue order). Against plain FSRS-6 at equal
  time Yadora is +0.3–0.5. Against
  random or oldest-first review it is +3 to +9 in every world (least for a slow forgetter). Do not claim a large
  algorithmic lead over another FSRS app: the lead is the product around the model (the final push, reliable free
  reminders, the honest plan, backups).
- **Automatic backup** (`data/AutoBackup`, 2026-09-24). Data loss was the second-loudest complaint about the
  closest comparable app ("I lost all my data", "wiped 7 years of data"), and the database is excluded from
  cloud backup by decision.
  - **How it works:** once a day `AutoBackupWorker` writes a full streamed backup into a folder the learner
    picked (`OpenDocumentTree`, persisted permission). A folder a sync app mirrors survives losing the phone.
  - **Files:** never overwritten; each run writes a new dated file (`yadora_backup_YYYY-MM-DD_HHmmss.json`; older
    minute-only names and a provider's clash copy "… (1).json" are recognised too). A failed write deletes its own file.
    Since 2026-10-03 a backup is written under a staged name (`yadora_partial_…`) and renamed once complete, so a write
    the system kills half-way leaves a file nobody takes for a backup, not a truncated `yadora_backup_…` that the pruning
    would keep as the day's newest in place of a good one. A staged file older than an hour is a killed write and is
    deleted; a provider that cannot rename gets the backup under its real name directly, as before (`AutoBackupTest`).
  - **One file per day, and "Back up now" always writes:** on the owner's Samsung, choosing a folder started the daily
    job AND "back up now" at once and left two identical files, the second renamed "(1)" and never pruned. Now runs
    are serialised (`runLock`), names carry seconds, a renamed copy is recognised, and pruning keeps the day's newest,
    so two backups moments apart leave one file. A forced run never reuses an earlier file (`AutoBackup.shouldWrite`):
    it must hold everything up to the moment the button was pressed. Choosing a new folder clears the last-backup
    record, so the new folder always gets its first file.
  - **Pruning:** only after a success, and only files with exactly that name pattern. It keeps the newest backup
    of each of the last 7 days that have one, plus the newest of each of the last 6 months.
  - **Empty library:** never backed up, so "Delete all data" cannot rotate the good backups out.
  - **The folder's address** lives in the device-only transient prefs: a restore onto another phone asks for a
    folder again instead of carrying a permission that phone never granted.
  - **Today's suggestion:** shown once a real history exists (20+ topics), only while automatic backup is off,
    and at most monthly; a manual export or "Not now" quiets it (`AutoBackup.shouldNudge`).
  - **Tests:** `AutoBackupTest` pins all of it on a real folder.
- **Features the comparable app's users asked for, decided** (`docs/COMPETITOR_REVIEWS.md`).
  - **Built:** the empty Today explains the three-step loop (the most-thumbed request was a tutorial); web
    addresses in notes open when tapped (`ui/components/NoteLinks`).
  - **Deliberately NOT built:** fixed custom intervals (the ladder's forgotten tail; the retention target is the
    principled control); file attachments (a link does the job); sync, web and iOS (offline Android; the
    automatic backup into a synced folder covers a new phone).
  - **Their bugs Yadora avoids:** reminders when nothing is due, paywalled reminders, a retention setting that
    would not stick, the wrong default language, silent task limits. Keep avoiding them.
- **Two outside audits, 2026-09-27: what was real, what changed, what did not** (the owner asked for them to
  be checked; every item was verified against the code and every fix has a test that fails without it).
  - **Fixed:** `studyUnits: null`, a null review list or an unknown `memoryModel` is refused before anything is
    replaced, and a topic the file marks deleted is restored archived (`BackupRoundTripTest`). A rating
    correction that changes nothing replays nothing, runs in one transaction with one-shot reads, and stamps
    every replayed log with the model and weight set that computed it (`AuditFindingsTest`). `rateUnit` is one
    transaction from read to write; the purge chooses and deletes in one transaction; Undo restores only the
    fields a review writes, so an edit made since survives. The daily limit counts reviews whose time falls on
    today, both ends. The review card re-reads its topic when the learner comes back to it (the pencil, or the
    app from the background) and each queued topic before it is shown; answers already chosen are kept per
    topic id (`ReviewSessionRefreshTest`). A personal-model fit re-checks the switch and the history inside the
    transaction that would adopt it, one fit runs at a time, and merged topics are left out of the fit as
    `analyze.py` leaves them out. Today reads the library once per change instead of five times. Switching
    Important on while moving the study date now tightens the replayed schedule; saving an edit keeps the
    collection and study type the form no longer shows (both found while checking the reports). The twins in
    `simulate.py`, `residency.py` and `TwoYearSoakTest` carry a day's overspend into the next day, so "equal
    time" holds to within one review (it gave them 2.3–2.6% more time, which had understated Yadora's lead).
  - **Kept, on purpose:** calendar-day replay across time zones (entry above); a real correction on a merged
    topic replays its combined history, which replaces the weighted average (the dialog says the whole schedule
    is recalculated; recording merge checkpoints would need a schema change); a topic answered Good + Confused
    again and again can reach Strong and a long interval once the repair clock backs off past the memory date
    (YADORA-6, the owner's choice; an "unresolved understanding" state would be a product decision); a
    projection that meets an unreadable rating skips it (restore already refuses such ratings).
  - **Not true:** one report said the reminders use `AlarmManager.setAlarmClock()` (they use
    `setExactAndAllowWhileIdle`), that the 81% weakest-tenth result came from exam-date compression (it came from
    raising the target to 0.95), and promised "100%" exam outcomes. Its scorecard is not evidence.
- **Undo puts back the row as it was STORED** (`MedReviewRepository.RatedReview.before`, 2026-09-28). A topic still on
  an older model or weight set is shown and scheduled from a copy projected onto the current one, and Undo used to
  write that copy back: on the Samsung, rate-then-Undo left a row labelled FSRS-6 over a history FSRS-5 wrote, which the
  export's self-check reports as MODEL_OWNERSHIP (and a new `updatedAt`). The snapshot is now the row the commit read
  inside its transaction, before projecting it. After Undo the session shows the re-read row projected, and a topic that
  cannot be re-read and projected gives way to the next card (fail closed, as `advanceUnit`). `AuditFindingsTest` pins
  it; the old code failed it on exactly those two fields.
- **The calibration NEVER LENGTHENS intervals** (`RecallCalibration.MAX_ESTIMATE = 1`, the owner's decision
  2026-09-28, on `tools/pilot/experiments.py` section 7). A learner who calls some forgotten topics "Hard" gives
  the calibration exactly the evidence a slow forgetter gives (more "recalls" than predicted), and self-ratings
  cannot tell them apart. Lengthening every interval for the first turned out to be most of what inflated
  ratings cost: at a 0.90 target, with 30% of lapses rated Hard it caused 1.8 of the 2.9 points lost at the
  year-end quiz and the weakest tenth fell to 75%; with 60%, 4.1 of 7.2 points and 59%. Capped at x1 those
  learners keep almost all of it (93.8% and 92.0% at the quiz, weakest tenth 79.9% and 67.5%); an honest
  average learner knows 0.3 points more for about 3% more reviews (the estimate's upward noise no longer
  stretches anything); a fast forgetter keeps its full correction. The cost falls on the honest SLOW forgetter:
  about 16% more reviews than strictly needed, for 1.1 points more knowledge. The raw estimate
  (`momentScale`) stays unclamped, and `analyze.py` reports it, so the pilot still sees slow forgetters. Do
  not lift the cap without an objective signal that separates slow forgetting from generous rating (the
  question score, once the pilot shows what it means). The personal weight set learns from the same ratings, so
  since 2026-10-02 it follows the same rule (the owner's decision): a set that would lengthen this learner's intervals
  is not adopted (`Fsrs6Optimizer.lengthening`, the personal-set entry above).
- **What is left to improve in the algorithm, checked 2026-09-28** (`experiments.py` sections 6 and 8,
  `docs/RESEARCH.md` §2.7). Do not re-run these as open questions.
  - **The memory model is at its ceiling in simulation.** An ORACLE twin that schedules from the learner's true
    memory, under the same product rules and the same fixed-target rule, beats Yadora at equal review count by
    at most 0.25 points at the year-end quiz and 0.3 over the year, for a learner the defaults describe, one who
    forgets 2x faster or slower, a first study worth half, and a steeper curve. That is what a better MODEL could
    add under this rule in these worlds: FSRS-7 (which now exists, predicts better on the benchmark, has 34
    weights and no pinned py-fsrs release), a personal fit. It is NOT a bound on everything (an outside audit,
    2026-09-28, rightly said the docs overclaimed it): a different rule can add more (the stability-adaptive
    target below does while the limit has room), and the simulated learners' true memory is itself FSRS-6-shaped,
    so only the pilot can show whether real topics leave more room. Do not chase a new model for retention gains
    without pilot evidence that the curve misfits.
  - **The one large gap is inflated ratings.** The oracle's weakest tenth sits 10 points above Yadora's for a
    learner who calls 30% of lapses Hard. That is missing information, not missing mathematics; the calibration
    cap above recovers about half of it, and the rest needs honest ratings or an objective signal.
  - **A stability-adaptive target (lower for young topics, higher for mature ones; a one-parameter version of
    cost-optimal scheduling) is NOT adopted.** It buys 0.4–0.8 points at the quiz when the daily limit has room
    (measured before the calibration cap), but when the limit binds (a heavy load, the medical student's normal
    case) it costs the weakest tenth 3.7 to 7.6 points (re-measured with the cap, 2026-09-28; 1.6 to 6.2 before
    it): the extra reviews of mature topics take the slots weak topics need. With the 2026-09-29 queue order
    (which gives those slots to the topics a review helps most) the cost falls to 1.5–3.3 and the quiz turns
    +0.1 to +0.3 (heavy load, 8 paired seeds). Still a trade-off like the difficulty-adaptive target: the
    owner's call, ideally with pilot data.
  - **No outcome can be guaranteed.** Even an identical twin with identical time loses some exams by the luck of
    which topics are asked (it scores lower than the disciplined no-app twin on about 6% of 100-question exams and
    ties on 4%), and a different student can study more, start ahead or review better. Never claim a guaranteed
    score; MARKETING.md applies.
- **Five more outside reports, 2026-09-28: what was real, what changed, what did not** (each item checked against
  the code; every fix has a test that fails without it).
  - **Fixed:**
    - A backup whose topics were reviewed but that carries no review history (`reviewLogs` missing or empty) is
      refused before anything is replaced; an unreviewed library without the section still restores
      (`BackupRoundTripTest`). A missing section used to restore topics that claimed reviews and had none.
    - The personal fit's "did the history change while it ran?" check is a SHA-256 over every log the fit learned
      from (id, topic, time, both ratings, type, model, set, stored elapsed days), the active set, merged-topic
      exclusions, current time zone and retention target (`MedReviewRepository.fitIdentity`). These are captured
      with the training snapshot and checked again before adoption: a restore, merge or settings change can
      invalidate a fit without adding or deleting any review ids.
    - Validation folds use saved review ids, not wall-clock timestamps. A phone clock rollback must not put a
      later review into the training prefix for an earlier held-out review. Actual timestamps and stored elapsed
      days still determine memory gaps. The pooled pilot fit uses the same saved-order split per learner;
      `pooled_fit.validation_order` states that choice in the analysis output. This changes validation and stale
      result detection, not the FSRS equations or the adoption thresholds (`PersonalFitSnapshotTest`,
      `OptimizerValidationOrderTest`, and the pilot clock-rollback regression).
      The old fingerprint (count and sum of times) missed a changed rating at the same time, a merge re-pointing
      logs, and a restore of the same times with other ratings, so a fit begun on one history could be adopted
      after another. A review added after the fit began changes nothing (`AuditFindingsTest`).
    - A weight set's calibration evidence, and the Progress card, count only reviews made from the moment the set
      began scheduling (`MedScheduler.ParameterSet.activatedAt`, the `since` of `getRecentRecallLogsOnce`). A
      correction replays a topic's older rows on the set it is on now and stamps them with it; those predictions
      were never made at review time, and a fitted set was trained on their outcomes. The stored values stay what
      replay computes; only their use as evidence changed. `analyze.py` leaves the same rows out, and the export's
      field guide says so. Keeping the original predictions beside the replayed ones would be a schema change.
    - A MERGED topic crosses to a new weight set of the same model by carrying its merge-averaged state over
      (`carriedOver`), not by replaying the combined history, which replaced the average the merge chose (identical
      weights under a new id moved stability 97.9 -> 127.3). A change of MODEL still replays (an FSRS-5 stability
      is not in FSRS-6 units), and a real rating correction still replays the combined history (known, above).
    - Review ahead leaves out topics already reviewed today (`ReviewAhead.isCandidate`).
    - Research tooling and docs: no equal-time comparison is extrapolated any more (`experiments.py`,
      `residency.py`; out of range is reported, or that seed left out; one probe had read 101.56% recall);
      `experiments.py`'s "equal time" is labelled an equal review COUNT; the oracle is a perfect-state comparator
      under the same rule, not a bound on everything; PILOT.md's week-8 check supports the model, not the twin
      result, and D5's time split within participants needs a leave-one-participant-out check before any default
      ships; RESEARCH.md §1 no longer calls delayed judgements "nearly perfect", the spacing shape "nothing to
      tune", or FSRS-6 the most accurate model (FSRS-7 now predicts better); the stale "unreleased" comment in
      `Fsrs6.kt`. Found while checking: a review between midnight and 01:00 on a DST fall-back day comes due a day
      early (the due-date entry above; `DueDateDstTest`).
  - **Kept, on purpose:** the pin at py-fsrs 6.3.1 (6.3.2's same-day Hard floor is real; adopting it is a new model
    identity, and the case is nearly unreachable; conformance entry above); the repair-clock backoff that lets
    Good + Confused reach Strong (YADORA-6; one report proposed freezing such a topic at 21 days, another showing
    unresolved understanding separately: both are the owner's product call); no rating is blocked or overridden by
    a question score (D6: suggest, never override); no method multiplier until D7 survives a refit; the 1-day
    relearn step (one report re-ran it at equal modeled cost: within noise).
  - **Not true:** "the Yadora twin scores higher with certainty, 100%" and "every minute studied is retained 2x"
    (a simulation is not proof; by which topics are asked, the twin scores lower than the disciplined no-app twin on
    about 6% of 100-question exams and ties on 4%); "the core sits exactly on the Pareto frontier, no inefficient formula exists" (the
    same week found four real defects); "0.95 costs 2.45x the reviews, 0.97 4.5x" (a fixed-state interval ratio;
    the simulated yearly cost is 1.7x and 2.4x); the reason given for the 365-day cap, that the real brain outruns
    FSRS-6's tail after a year (the simulated memory IS FSRS-6; the three caps are within noise and 180 days scored
    highest); MEMORIZE's optimal review intensity "proportional to recall" (it is proportional to 1 - recall, and
    stochastic); a scheduler blueprint built on FSRS v4's curve (1 + t/9S)^-1 with 17 weights and a
    lowest-recall-first queue (1.2-1.6 points worse in four of five simulated backlogs, §2.4).
- **More outside reports, 2026-09-28: a literature review, a five-phase "enhancement plan", and answers to five
  follow-up questions.** Each was checked against the code, and the queue claim in the simulator.
  - **Refuted by simulation:** the queue "FIFO trap" (queue-order entry above).
  - **False about this code:**
    - `domain/srs/Topic.kt` and `domain/srs/DailyPlan.kt` do not exist (`DailyPlan` is in `ui/today/QueuePlanning.kt`).
    - The review log stores no stability.
    - New calibration estimates stop at 1.0, not 2.0.
    - Review methods came with DB v10, not v9.
    - `analyze.py` computed no Brier score or calibration slope (it does now, below).
  - **False about the simulations:**
    - 0.85 does not "cut reviews 12% and raise exam error 18%". In the one-year sweep it does 24% fewer reviews
      and has 53% more forgetting.
    - 0.90 is not an "exact Pareto knee": 0.85 and 0.90 tie for the equal-time advantage.
  - **Other overclaims:** several references are real papers on unrelated subjects, and a "core guarantee" of
    superior retention is ruled out by MARKETING.md.
  - **Done:**
    - `analyze.py` reports the Brier score, O/E, calibration-in-the-large and the calibration slope, with 95%
      intervals, per weight set. They are descriptive; no decision rule reads them.
    - `test_analyze.py` recovers a planted miscalibration with them.
    - On the soak's export the slope reads 0.96 (0.85–1.07).
  - **Recorded for the pilot, not built** (PILOT.md, "Why these thresholds"):
    - If D7 fires, a method effect multiplies the stability GAIN, S′ = S·[1 + m·(SInc − 1)], with Reading anchored
      at 1 and the weights frozen. The plan multiplied stability itself (up to ×1.25), and a follow-up answer used
      this gain form (m up to 2.2); neither range has a source.
    - D7 is observational, so a fitted m is a candidate to simulate, not an effect.
    - If D6 leads to a score-based suggestion, correct multiple-choice scores for chance first. A score never
      overrides a rating.
  - **Not adopted:**
    - Round-robin "interleaving" by subject. It mixes the least similar topics, where interleaving helps least,
      and the second assistant proposed the opposite (group confusable topics).
    - Chronotype-timed reminders. The learner already sets both times.
    - Flashcard machinery: atomic cards, learning steps, leeches, and load balancing (which would make an interval
      depend on other topics).
- **Two more outside reports, 2026-09-28: a "foundations" essay written without access to this repository, and an
  `Fsrs6.kt` audit with numerical-stability, causal-inference and queueing answers.**
  - **Changed:** D7 compares methods WITHIN each learner who used both (`analyze.py`
    `within_participant_difference`, pinned by `test_analyze.py`). Pooled, a generous rater who mostly does
    questions and a strict one who mostly reads posed as a method effect.
  - **False:**
    - "The weights are not py-fsrs's defaults." The goldens py-fsrs 6.3.1 itself generated carry exactly these
      (w0 0.212, w3 8.2956); the reports quoted older FSRS versions.
    - "Grade ordinals are fragile." `Grade` has explicit values.
    - "w20 can drive `factor` to 0 or infinity." The optimizer, restore and `analyze.py` all bound it to
      [0.1, 0.8], so `factor` stays 0.14–1.87.
    - "expm1/log1p are needed." Measured: at most a 6e-16 effect on S′, even at S = 365,000.
    - "The on-device fit is Nelder-Mead." It is Adam with exact gradients.
    - "Focal loss protects against misclicks." It is backwards: it keeps surprising outcomes at full weight, and it
      is not a proper scoring rule, so it would bias the probabilities the scheduler runs on.
    - "The Beta-binomial MAP is strictly concave." Not when a Beta parameter is below 1, as with its own κ = 3
      Forgot and Easy priors.
    - "log(1 + overdue) stops old topics swallowing the queue." Top-N by any increasing function of lateness is the
      same order, and each topic takes one slot.
    - "Same-day Again/Hard are untested." The goldens hold 66 of each, single-equation and composed.
    - Package `com.codeinscrubs.yadora`, cloze/Markdown parsing, timing wheels, SIMD and graph diffusion do not
      exist here.
  - **Kept on purpose:**
    - No S_MAX: a state ceiling was one of the deviations the goldens caught.
    - `Fsrs6.intervalDays` stays raw: `MedScheduler` owns the 1–365 bounds and the fuzz (`IntervalCeilingTest`,
      `SchedulerInvariantsTest`).
    - `decay` and `factor` are recomputed per call: the optimizer has its own dual-number path, so this costs
      nothing that matters.
  - **Not adopted:** a Bayesian calibrator that overrides the grade; method multipliers shipped with default
    values (1.55/0.85); a 15% "contrastive" stability bonus; pulling interleaving partners forward; circadian
    routing of topics. Each is settled or pilot-gated.
  - **The owner's call, later:** a randomised method suggestion for a future study (it steers how participants
    study). A model separating slow forgetting from generous rating is weakly identified while reviews cluster
    near 90% predicted recall; the question score stays the anchor.
- **Four "deliverables" from another assistant, 2026-09-28: a guarded `Fsrs6.kt`, a method-effect analysis, a queue
  index and a cap relaxation.** Each was checked against the code, and the queue index in the simulator.
  - **Adopted in changed form:** the queue's review value (the queue-order entry above). As proposed, with
    ln(1 + days) lateness, it let topics wait 150–290 days past due under a heavy load; the app keeps linear
    lateness and caps the value. Its write-off below 10% recall, its +60 repair bonus and its Important multiplier
    were not taken.
  - **Rejected:**
    - The "guarded" kernel clamps stability at 3,650 days and the interval inside `Fsrs6`. 848 golden vectors
      have a stability above 3,650 going in or coming out (384 of the 2,112 composed steps start there and 288
      end there), and 49 of the 88 interval points lie outside [1, 365], so it would fail conformance: the fifth
      deviation again. `MedScheduler` owns the interval bounds.
    - Its premises: "`Fsrs6Optimizer.LOWER = 0.01`" (LOWER is py-fsrs's per-weight array, and w20's bounds are
      0.1–0.8); "a Yadora pilot set, not py-fsrs 6.3.1's defaults" (false, entry above); "optimal review value
      scales with recall probability" (MEMORIZE's scales with 1 − recall).
    - The method analysis scores Hard (grade 2) as a failure (`grade ≥ 3`); FSRS and Yadora count Hard as a
      recall. Its weighted model passes `freq_weights` to statsmodels' `Logit`, which takes no frequency weights
      (GLM does), and it names `sm.PanelOLS`, which is in linearmodels, not statsmodels. D7's within-learner
      comparison stands; a randomised method suggestion stays the owner's call.
    - The interval-sensitivity gate would lift the calibration cap on self-ratings alone, with the same
      Hard-as-failure error. The cap stays until an objective signal exists (the calibration entry above).
- **Seven more outside reports, 2026-09-30 to 10-02: two emulator QA passes (a release build on Android 16 and 8.0),
  a UI and edge-case pass, a math audit, an algorithm audit, an evidence re-check and a "path to 90" plan.** Every item
  was checked against the code, the reproducible ones on a device or an emulator; each fix has a test that fails without
  it, or was checked on a device where only a device can show it (the alarm, the layouts).
  - **Fixed:** the Android 8 ringer that closed itself (entry above); the notification's "Not today" deferring first
    ratings and Spread out leaving today's capacity unused (the backlog entry); a restore accepting a file with one
    reviewed topic's history cut out (now refused; topics in the trash are exempt, since copies a merge absorbed before
    2026-08-06 kept their counts); the restore picker greying out a valid backup that Android 8 labels
    application/octet-stream (now JSON, octet-stream and text); a restore or "Delete all data" leaving the old language,
    theme and settings on screen until a restart (the activity is rebuilt); a restore failing with "invalid backup"
    when the file could not even be read (two messages now, and the cause is logged); "Delete all data" leaving a
    pending test reminder armed (`NotificationScheduler.cancelAll`); Calendar Plan listing a topic due at exactly 00:00
    tomorrow under Today (`TodayBuckets.isInForecastDay`, half-open days); no Undo for a session's last rating (the
    summary offers it); a question score that will not be kept vanishing without a word (`QuestionScore.problem`, shown
    under the fields; the rating still goes ahead); Library search comparing raw text, so Arabic ي/ك or a half-space
    found nothing (`TopicTitle.searchKey`, off the main thread); the search field squeezed to a sliver beside "Archived"
    and "Sort" in German at 360 dp and everywhere at 320 dp (own row, a clear button, the list above the keyboard;
    Sort moved to the top bar with a check mark on the current order, Archived to the end of the chips); a
    card's subject and state running into its date; the review header pushing the pencil off at a large font; the
    reminder row's switch past the edge at 320 dp; the Jalali picker cut after its first row in landscape, resetting
    on rotation, and printing Persian digits and weekday letters on an English screen (digits and letters now follow
    the interface language, the title included); dialogs and the new-subject draft lost on rotation; Add enabled on an
    empty subject name; the settings switches and colour swatches nameless to a screen reader (`SwitchRow`); "Back"
    read in English in every language; the widget's label clipped at its default size; a one-day retention chart that
    drew nothing (a dot per day); `analyze.py` dying with UnicodeEncodeError on a redirected Windows console and
    refusing a Notepad-saved export (byte-order mark); `test_reminder.sh` not finding the Persian button; the
    personal set's grade order and never-lengthen (the personal-set entry); RESEARCH.md counting a tie as half a win
    (strictly higher: 79%, 90% and 97% of 50-, 100- and 200-question exams; ties 10%, 4%, 1%), and its claim that
    correlated answers make the order more certain (it depends on how they correlate); this file calling the
    repository private.
  - **Kept, on purpose:** the same-day Hard collapse (the 6.3.1 pin); difficulty's linear damping that makes D = 10
    sticky (canonical FSRS-6; changing it breaks conformance); no sub-day relearning step (the app counts whole days;
    after four or five lapses in a row the model does predict only 59–63% a day later, and the topic simply returns
    daily until it is remembered); the exam date feeding nothing (the cram loss is known and Review ahead answers it);
    Review ahead only once the day is done, weakest first today; the calibration cap and its 3-day evidence rule;
    the gate's 2.33 and its 640-review floor (measured: it finds a strong departure at about 9,000 reviews); local
    calendar days at midnight; the repair clock's backoff (YADORA-6); fractional lateness in the queue score; Undo
    after a "Not today" undoing the last RATING, which is what it says.
  - **Real, recorded, not fixed:** a device clock set BACK between two reviews of a topic makes the replay order its
    logs differently from what happened (the live path clamps the gap to 0; replay sorts by time), so a later
    correction or a new weight set rebuilds a different state. Rare (a manual clock change, or a phone that boots with
    the wrong date). FIXED 2026-10-03 without a schema change: histories are walked in saved order (log id).
    (FIXED 2026-10-04 in YADORA-7.) The repair deadline is dropped when it does not beat the memory interval BEFORE fuzz, so in a 5% band a topic can
    come back up to 5% later than its repair date would have; changing it is a replayed rule, for the next POLICY
    bump. Within one weight set a correction's replayed predictions counted as the APP's calibration evidence: FIXED
    2026-10-03 without a schema change. Every correction is logged (RATING_CORRECTED, with the answer it replaced), and
    the app and `analyze.py` both leave the predictions it recomputed out; the original values are not kept. `experiments.py`'s equal review count is not equal time (labelled since 2026-09-28; about 0.7% in
    the audit's case). In `simulate.py` the other twin cannot spend time it has no topic for (each topic once a
    day), so in a small library it can end with less time (1.6% in the audit's 30-day case); in the published worlds
    that can only happen in the first days, while the library is a handful of topics.
  - **Not true:** a Persian-digit score being dropped (`toIntOrNull` reads any Unicode digit; pinned); German "OK"
    being untranslated; "dead code" (smart-cast null checks the compiler needs); the colours ignoring the app's theme
    (fixed in PR #14, after the commit the reports tested); a widget or reminder tap losing the Add form (the review
    opens on top and Back returns to the intact draft, as the second QA pass itself found); "the personal optimizer
    can never activate" (it does at about 9,000 reviews for a strong departure); a 4 AM day rollover or an exam
    horizon being a fix (both are settled decisions above).
  - **Not built, the owner's call or the pilot's:** a minutes-per-review chip (declined 2026-10-03), research dither of intervals, a
    randomised score nudge, a "still shaky" chip, renaming the retention slider, D2b (item-level shortfall) and the
    other parts of the "path to 90" plan, whose numbers came from its author's own simulated world; subject rename and
    delete; a hint that long-press opens review-now.
- **An eighth outside report, 2026-10-03: a device pass on the Samsung, "three bugs", three rounds of "research" and a
  12-item developer handover** (another assistant). Every claim was checked against the code, the reproducible ones on
  an emulator.
  - **Fixed:** duplicate main screens (a real path, though not the reported one: see the 2026-10-03 device paragraph;
    `singleTop`, not the proposed `singleTask`, which would also close a file picker or share sheet left open);
    "فصل ۱" and "فصل 1" counted as different in the search and the duplicate warning (`TopicTitle.normalize` folds
    Persian and Arabic-Indic digits); the repository's unused `deleteLogById` wrapper (removed; Undo calls the DAO
    inside its transaction); `simulate.py --weights` refusing a file saved with a byte-order mark. Found while
    checking: the doubled system bars (entry "Screens with text fields") and reminders the pilot could not see (entry
    "Reminder delivery is measured on every pilot phone").
  - **Not true:**
    - that the phone held the owner's real study data. It held the test seed restored on 2026-10-02, so the
      "91% predicted, 89% observed, x0.94" it praised is a simulated learner's (the same seed's export reads
      x0.94 in `analyze.py`). Nor was the build an "official release": it was an R8 build signed with the SDK debug key.
    - that each review log stores its time zone, stability and difficulty. It has 26 fields and none of these: the
      per-log zone is the known schema change, and S and D come from replay. Its methods were not "testing, rereading,
      teaching yourself, video" but Questions, Reading, Lecture and Other.
    - 12 decision rules D1–D12 (there were ten; D11 was added the same day), a fatigue analysis in `analyze.py`, and a
      "zero privacy leak" (the file is sensitive, not anonymous: subject names, device model).
    - paths `data/export/AnalyticsExporter.kt`, `data/local/ReviewLogEntity.kt` and `domain/srs/DayBounds.kt`.
    - the + button covering the last card, and Enter doing nothing in the Library search (both checked on the
      emulator).
    - ZWNJ or RLM marks breaking a Persian score: the field keeps digits only, and its own "before" code showed the filter.
    - `simulate.py` and `experiments.py` crashing on a cp1252 console: they print only "±", which cp1252 has.
    - "no clamp" in `modelElapsedDays`: it clamps at 0, as its own quote of the code shows.
    - `setExactAndAllowWhileIdle` crashing without the permission: it is checked first, and the exception is caught.
  - **Real, kept:**
    - midnight is the day boundary: a topic rated Forgot at 23:58 is due from 00:00. The 4 AM boundary is settled out.
    - the same-day Hard drop (the 6.3.1 pin). The report blamed w15, the recall branch's Hard penalty, which never
      lowers stability. The same-day branch uses w17–w19 and cuts S = 100 to 45, more than the 40% it claimed.
    - "OK" in German.
    - in landscape, the keyboard covers the new-subject dialog's colours and buttons while typing; its ✓ brings them
      back. The proposed `imePadding` inside the dialog would not help, because the dialog window pans.
  - **Not adopted:**
    - a LINEX calibration that lifts the never-lengthen cap as reviews accumulate. It learns from the same
      self-ratings, so it cannot tell a slow forgetter from a generous rater, and its own Kotlin draft clamps at 1.
    - a horizon-aware Whittle/CARA queue index. It reads the exam date, which feeds nothing, and "γ = ln 10 targets
      exactly the worst tenth" is not a theorem: that bound needs the parameter optimised per case.
    - a CVaR boost for the weakest tenth, method multipliers, interleaving, a fatigue model and a dwell-time
      "unreliable" flag (in-app seconds measure nothing).
    - a prerequisite graph: its own third round showed one person's data cannot fit it.
    - a retention target derived from the load: intervals would depend on library size, the rejected load balancing.
    - continuous importance weights and "value per minute" with study time estimated from note length. A review
      happens mostly outside the app, and neither was simulated against the validated queue order.
    - a Room v11 time-zone column: the known per-log record, not needed for a pilot in one zone (`analyze.py` flags
      a zone change). One of its two drafts declared the column NOT NULL for a nullable field, which Room's schema
      check rejects at startup, and dropped `SystemEntity` from `@Database`.
    - a WorkManager copy of every inexact alarm: in Doze it runs later than the alarm, and it is a second path to a
      duplicate reminder.
    - a Today banner for revoked exact alarms: its draft never re-checked after the learner came back from settings.
    - "Bestätigen" for "OK", and `isError` borders on the score fields: the reason already shows under them.
  - Two assistants agreeing is not independent evidence when one orchestrator wrote both prompts. Several of the
    citations are real papers; none supports these formulas or constants.
- **A ninth outside report, 2026-10-03: an architecture review, a 78/100 scorecard, a research brief, two researchers'
  dossiers and two syntheses of them** (another assistant). The code claims were checked against the repository, the
  research claims against their sources where they could be found.
  - **True, and done:**
    - three stale comments: `app/build.gradle.kts` listed DB v6, backup v6 and analytics v5; `StudyUnitEntity.keyPoints`
      described the retired rating cap; `RecallCalibration` said the app carries no 21-weight training loop;
    - replay in time order (saved order now, no schema change; entry above);
    - a corrected rating left no trace of the original (RATING_CORRECTED);
    - nothing recorded whether a reminder led to an open (APP_OPENED);
    - a killed backup write could displace a good backup (the staged write);
    - whether the immediate first rating carries information. `analyze.py`'s first-interval section now scores the first
      reviews with the rating's own initial stability and with Medium's for everyone (paired log loss); on the
      fixture's simulated learner z = 0.23.
  - **True, kept:**
    - a restore reads up to 512 MB into memory; it validates before replacing anything, so an absurd file can only fail;
    - CI pins actions to major tags, not SHAs; it holds no secrets and does not build the release;
    - the scheduler's process-global model state, refreshed only at session boundaries (a context-object refactor is not
      worth the risk now);
    - the large files.
  - **Agreed, and already the case:**
    - FSRS-6 stays. FSRS-7 has 34 parameters and predicts Anki cards better (log loss 0.337 against 0.346 without
      same-day reviews); it can be scored offline on the pilot's exports later, because a replay that uses only past
      reviews is prospective, so no in-app shadow model is needed;
    - none of: a lapse floor, method multipliers, a Beta or mixture topic state, Kalman uncertainty, a value-of-information
      bonus, fixed retention numbers;
    - LINEX stays out (the owner's never-lengthen rule);
    - a Yadora population prior only after a leave-one-participant-out check (PILOT.md D5).
  - **Not true, or overstated:**
    - FSRS-7 with "35 trainable parameters": the benchmark says 34 (another report said 21);
    - MEMORIZE (Tabibian et al., PNAS 2019) solving only one item: it extends to many under independence;
    - an "August 2026" study of forgetting across timescales: could not be found;
    - one researcher's planner scored each review outcome at its own 90% date, which makes every branch 0.9 (the synthesis
      caught it), and its guessing model and grade probabilities were placeholders.
    - Verified, by contrast: the 2026 medical meta-analysis (Maye et al., The Clinical Teacher: 13 studies, 21,415
      learners, SMD 0.78 against ordinary study, not an equal-time active control).
  - **Put to the owner and declined, 2026-10-03 (settled; do not re-propose):**
    - a recall-first review (the memory rating before the notes and answers). The learner may rate whenever they
      like; the aim stays a rating AFTER the review, and the 2026-09-23 decision stands;
    - an optional time-spent answer per review. How long a student spends depends on how much time they have that
      day, so it would not measure the topic;
    - a research-grade DB v11 (original predictions beside replayed ones, a per-log zone, merge checkpoints): only if
      an analysis turns out to need it. The pilot runs in one zone, and corrections are already logged and left out
      of the evidence;
    - an exam hint on Today: the exam date stays purely cosmetic.
  - **Later, no decision needed:** the repair-vs-fuzz fix at the next policy bump; an equal-time randomised study
    after the pilot. Its design is one of the questions in `docs/RESEARCHER_PROMPT.md` (next entry).
- **The open scientific questions are written down for an outside researcher** (`docs/RESEARCHER_PROMPT.md`,
  2026-10-03, at the owner's request). It states the algorithm exactly, the fixed constraints (the owner's decisions
  above) and 17 questions in three priorities. The most important ask:
  - whether whole topics forget like flashcards;
  - how much ratings given after re-exposure are inflated;
  - a measurement model that could separate generous rating from slow forgetting with the question score;
  - faster personalization, and an adoption test that holds its error rate;
  - the pilot's power, and the design of the equal-time study.

  Check any answer that comes back against its sources and against this file before changing anything: outside
  reports so far have invented papers and constants. If the algorithm changes, update section 2 of the prompt
  (and rebuild its copy page; the Handbook memory note says how).
- **Four AI researchers answered that prompt, 2026-10-03** (Priority A in full, B partly; one in Persian). Every
  citation was checked against PubMed and Crossref, every number recomputed, and the gate claim measured.
  - **Done:**
    - `analyze.py`:
      - the calibration gap with an interval that counts each topic's reviews as one cluster, and its design effect
        (2.1 on the fixture's simulated learner: one topic's reviews DO move together there);
      - each learner's raw interval scale with a 95% interval (delta method; it matches the estimate's real spread in
        simulation, about 19/n for var(ln k) near 90% predicted recall);
      - the between-learner spread τ that the 120 prior assumes, and the prior it implies;
      - memory ratings against question-score bands, per learner;
      - calibration by subject (Sense et al. 2016: materials forget at different rates).
    - PILOT.md: what each rule can detect, with false-alarm and hit rates. D2 and D7 were amended before any data:
      past the threshold they are LOOK only when the 95% interval excludes 0. On equal methods D7's threshold alone
      fired about one time in four.
    - RESEARCH.md: verified evidence rows, and §5.1 on the later equal-time study. The comparison method decides
      whether that study is feasible: the expected gain over a fixed ladder is about 1 point, over unscheduled review
      5–8.
    - `Fsrs6OptimizerGateTest` pins the gate measurement above.
  - **True, and already the case:**
    - κ (forgetting speed) and φ (lapses rated as successes) cannot be told apart from ratings when reviews sit near
      90% predicted recall. That is the reason for the never-lengthen rule; only the question score separates them.
    - The 120 prior is the empirical-Bayes value 19/τ² for τ = 0.4 (the `RecallCalibration` note).
    - The by-time and by-predicted-recall diagnostics were already in the report.
    - Hindsight inflation is the largest threat to the ratings. Manipulations designed to reduce hindsight bias did
      not (Guilbault et al. 2004, 95 studies), so the rating copy is not changed for it.
  - **False:**
    - "One stability hides a topic's weak tail; one curve is not within 2 points for a disease or a lecture." One
      FSRS-6 curve stays within 1.8 points of their own 70/30 two-part topic over a year (at most 1 point for a
      log-normal spread of 1). The weak parts inside a topic are the known cost of reviewing whole topics, which the
      owner chose (a review is whatever the learner chooses; topics are not split into cards).
    - "The gate's z is inflated by a third": measured median design effect 0.97.
    - "Maye et al. 2026 not found" (two researchers): it is Maye & Hurley, The Clinical Teacher 23(2):e70353.
    - FSRS-7 "35 weights": the srs-benchmark table says 34.
    - One researcher's corrections of another were themselves wrong: Murre 2022 is in Scientific Reports, and
      Ingendahl et al. 2025 (JOL reactivity g = 0.22) exists.
    - D6 "under-powered": it is a point threshold, not a significance test. At n = 50 it flags a true correlation of
      0.1 92% of the time.
    - The Persian report:
      - invented quotes with page numbers (Rubin & Wenzel found four functions fit about equally, not "power best");
      - wrong authors for the Memory 2021 paper (it is Zimdahl & Undorf);
      - references the brief never had;
      - an optimal target of about 0.82 that its own formula contradicts (that efficiency peaks at 0.70 for every
        cost);
      - a between-student design effect applied to a within-student design.
    - The third report:
      - sources on croup, delirium and cardiac anaesthesia;
      - wrong authors and DOIs (the DPT study is Ambler et al. 2025; Bell et al. 2008, not "Prince 2007");
      - "slower decay = higher w20" (it is the reverse);
      - invented constants ("S0 ×1.2 for large topics", "prior 60", "φ = 0.3 from Davis 2006");
      - an "anticipation rating" before the review, which is the recall-first review the owner declined.
  - **Not adopted:**
    - an A/B test of a new rating wording inside the pilot (5–20 learners cannot test it);
    - per-topic interval caps (the aggregate never-lengthen rule is the owner's);
    - an ordinal likelihood, inverse-propensity weights and topic decomposition (tentative: pilot data first);
    - anytime-valid e-values for the gate (the defaults' learner never comes near 2.33, highest z 0.84 in 40 refits,
      and never-lengthen bounds what a false adoption could cost).
- **A tenth outside report, 2026-10-03: Codex's production audit (at 8bbffd0), its review of the researchers' answers (an
  88-row claim ledger), an engineering plan (ENG-01–09) and an independent mathematical review (C01–C10).** Every finding
  was checked against the current code and the reproducible ones re-run; each fix has a test that fails without it,
  except the rotation and `smoke.sh` changes.
  - **Fixed:**
    - R3 / ENG-02 / C01: within one weight set the app's calibration and the Progress card still counted the predictions
      a rating correction recomputed (its case: 0.808 → 0.753 and 0.901 → 0.854). They are left out now (the calibration
      entry; `AuditFindingsTest`, `RecomputedPredictionsTest`). Keeping the original values beside the replayed ones is
      the declined DB v11.
    - MATH-03: a personal set with its grades out of order stayed ACTIVE if it was adopted before 2026-10-02 or restored.
      It is retired when the model is loaded.
    - BACKUP-01: `"backupVersion": "10"` read as version 1 (`BackupRoundTripTest`).
    - DATA-01: `analyze.py` kept a participant's newest export and said it held the whole history, so a topic purged
      between two exports vanished without a word. The loss is counted and reported, not merged back: a corrected log, a
      reused id or deleted data must not return.
    - DATA-02: one malformed export stopped the whole batch. It is set aside with its reason.
    - DATA-03: the pooled refit (D5) was judged on z alone. It now also needs the app's two other conditions (grades in
      order, never longer than the defaults), computed as the app computes them, and PILOT.md no longer calls it the
      app's gate.
    - C05 / ENG-05: two learners' personal sets with the same local id formed one calibration group. Each is its own
      group, D2 judges every group with 300 reviews, and the per-user scale is reported on the set in use as well.
    - DEV-01: `smoke.sh` uninstalled the app (deleting its data) on a signing-key mismatch. Now only with
      `ALLOW_UNINSTALL=1`.
    - UI-01: a rotation reset Progress to Overview, closed an open Calendar Plan day and closed an open date picker (the
      chosen date was kept, as the report itself noted). `rememberSaveable`.
  - **True, kept on purpose:**
    - ALG-01 / ENG-03 / C02: an adopted set can schedule longer than the calibrated intervals it replaces (its case: one
      topic 1.26 → 1.91 days), never longer than the defaults. The stricter baseline would refuse 22 of 40 sets that
      predict better (the personal-set entry).
    - MATH-02 / ENG-04 / C03: the repair deadline is dropped before fuzz. Fixed 2026-10-04 in YADORA-7 (the eleventh
      report's entry).
    - B1, MATH-05 / ENG-06: an equal review count is not equal time in `experiments.py`, and `simulate.py`'s other twin
      can end with unspent time. Known and labelled (the 2026-09-30 to 10-02 entry).
    - C04 / ENG-08: the fit reads the first 64 steps of each topic, py-fsrs's procedure. A topic reviewed 64 times is
      far outside Yadora's use; the soak's topics have about 10 reviews each.
    - C06 / ENG-07: the gate's real error rate under repeated looks is not established (the personal-set entry says
      so). Never-lengthen bounds what a false adoption can cost.
  - **Already settled, or the owner's:** C07 (time spent, recall-first: declined 2026-10-03); C08 (load forecasts,
    actions for unresolved understanding: product direction); C09 / ENG-09 (value over a horizon: the exam date stays
    cosmetic, and any queue change goes to shadow first); C10 (FSRS-7, fractional time: offline on the pilot's exports,
    later).
  - **Stale or not true:** MATH-01 / ENG-01 (replay by wall-clock order) was fixed in PR #17, as its own later check
    agrees; the instrumentation "Process crashed" was its harness's build-variant mismatch, not a product defect;
    "retention 70–99%" (the slider is 0.85–0.97); "the gate's z is inflated by clustering" (design effect 0.97).
- **The owner's own year, checked end to end (2026-10-03)** (`OwnerYearSoakTest`; `tools/pilot/one_exam.py`, RESEARCH.md
  §2.8). The owner's real use: one residency exam in about a year, at least seven hours of study a day, about 2,000 topics
  by the end, new topics on some days and none on others, all reviews done on some days and some left on others, and
  topics reviewed on their own initiative.
  - **Changed:** "Save and review now" on an existing topic's page saves the form, then opens the one-topic session
    ("Save and rate now" for an unrated topic; hidden when the page was opened from a review in progress,
    `Screen.EditUnit.fromReview`; `SaveAndReviewNowTest`). The Library search folds each topic's texts once per change
    of the list, not per keystroke (`ui/library/LibrarySearch`: 2,000 topics with long notes, about 50 ms a keystroke
    before, 0.3–2 ms now), and since 2026-10-04 matches each WORD of the query on its own: "قلب نارسایی" finds
    "نارسایی قلب", "neuro 303" a Neurology topic with 303 in its title (the whole query used to have to appear as one
    run of text). One word is the old rule exactly, and more words only add matches; `LibrarySearchTest` pins both
    against the per-keystroke search. A restore inserts in batches, and
    Settings shows a progress dialog, which cannot be dismissed, during a backup, an export, a restore or a share (the
    soak's year, 1,935 topics and 16,699 reviews in a 14 MB file, restored in 2.8 s on the desktop JVM against 9.6 s one
    row at a time; a phone is several times slower). "Spread out" writes `OverdueRedistributor.deferrals`, the one definition the soak drives too.
    Today's Review-ahead caption and the exam-countdown copy call it a good use of ANY spare time, not only of the last
    four weeks.
  - **Logged for the analysis (export v15):** DAILY_SNAPSHOT (`data/DailySnapshot`), at most once a local day, the day's
    load as counts (due, overdue and the oldest, first ratings, offered, held back by the limit, done, deferred, the
    limit), written by MainActivity's start or the 6-hourly safety worker, whichever runs first; APP_VERSION
    (`data/AppVersionLog`), the first run of each build. Neither is written at process start: a thread there raced
    every test that reads the event log (BackupRoundTripTest's `single()`). The day already recorded and the last build
    live in the device-only transient prefs; a restore clears both marks, because it replaces the event log (kept, they
    stopped a phone restored onto from logging that day's load, or its build at all). `analyze.py` reports a daily-load
    table per phone (the backlog's trend per 30 days included) and each phone's builds; descriptive, no decision rule.
    `DailySnapshotTest`, `test_analyze.py`.
  - **On an emulator** (2026-10-04, Android 16, a temporary AVD on F:, Persian): install, launch and both reminders as
    `smoke.sh` checks them; the 1,935-topic year (`app/build/owner-soak/yadora_owner_year_backup.json`, written by the
    soak) restored through Settings with the progress dialog up the whole time, Today then showing 37 reviews, the
    Library 1,935 topics; the R8 release installed over it; "drugs heart" found "Heart failure — drugs"; after a restore
    of the seed the rebuilt app logged APP_VERSION and a DAILY_SNAPSHOT matching Today (14 offered: 2 first ratings, 12
    reviews). Not measured: how long a year-sized restore takes on a phone (this emulator, with software graphics and a
    busy host, gave 40–200 s and is no guide). Gotcha: the headless emulator (`-no-window`, SwiftShader) crashed, an
    access violation in its JIT-compiled renderer code, each time a reviewed topic's page opened (the forgetting-curve
    chart; all 120,475 of its points across both test libraries are finite and in [0, 1], and the page works on the
    Samsung). Started from Git Bash such a crash leaves no Windows error report and looks like the emulator being
    killed; start it with PowerShell's `Start-Process` to see the report.
  - **Measured on the real code** (`OwnerYearSoakTest`: Asia/Tehran, 365 days, the default limit of 50; 10% of days
    off, 15% light, 0–10 new topics on the rest; Not today, Spread out, Review more anyway, review-now including
    same-day second looks, Review ahead on spare evenings and a last-month push, rating corrections, a mid-year phone
    change, a refit every 20 days): 1,935 topics, 14,989 reviews; exam day 97.1% against 90.7% for random review and
    93.3% oldest-first at equal time, which is measured (each twin spent what Yadora spent, to within one review); 100%
    of topics at 90%+, weakest tenth 93.6%, nothing overdue on exam day; every refit refused (the simulated learner is
    the defaults' learner); the export replays all 16,924 logs exactly (YADORA-7, 2026-10-04; before it 14,764 reviews,
    96.9%). CI runs
    it and replays its export.
  - **The simulation's finding** (§2.8, model-based): with a fixed daily budget and a finite syllabus, the split of the
    day between new material and reviews decides the exam score when time is tight (20–45 points at 20 units a day),
    and the target then moves it by about one point; spare time on Review ahead beats a higher target (98.7% against
    95.0% unused and 97.6% at 0.95); targets switched in phases never beat a flat one. For the owner's 2,000 topics, 30
    review-units a day cannot cover them, 45 does only with new material protected (92%), 70 is comfortable (97.6%):
    seven hours is 70 units only at about six minutes a review. The lever in the app is the daily limit. Its default
    (50) and the 0.90 target stay.
  - **Not built** (the owner, 2026-10-03): a mock-exam log (the results go to the chat, to be compared with the logs),
    and a time field per review (a topic's review time varies, and that is fine).
- **An eleventh outside report, 2026-10-04: Codex's owner-goal review and its flexible-use review** (another assistant;
  its witnesses ran in a copy outside the repository). Every claim was checked against the code, and each reproducible
  one re-run; each fix has a test that fails without it.
  - **Fixed:**
    - G01: a rating correction with the phone's clock set back since the reviews left its recomputed predictions in the
      calibration (0.808 → 0.753 and 0.901 → 0.854 counted). The RATING_CORRECTED event records `upto`, the topic's last
      log in the correction's transaction, and saved order decides (`RecomputedPredictions`, `analyze.py`
      `recomputed_by`); an event without it falls back to the clock.
    - G02: every replay counted elapsed days again in the CURRENT zone; after a zone change, reviews at 23:30 and 00:30
      in Tehran became a same-day pair and stability fell from 7.32 to 2.31 days. Replays read back each FSRS-6 recall's
      stored day count now (the calendar-days entry); TIME_ZONE events (`data/TimeZoneLog`: app start, foreground,
      worker) let `analyze.py` count each review in the zone it was made in (`zone_lookup`), and its replay chain
      follows the stored counts too, so a miscounted day is flagged on its own row. No schema change.
    - G03: the repair deadline was compared with the memory interval before the ±5% fuzz (a 4-day repair dropped for a
      memory date 4.05 days out). POLICY YADORA-7 compares with the final interval (`MedScheduler.repairDays`, one rule
      for the preview, the commit and the replay); rows stamped YADORA-6 or older replay as they were given.
      `RepairAfterFuzzTest`.
    - G04: the daily-load trend mixed snapshots taken before and after the day's reviews (a steady backlog read +22.5 a
      month). Snapshots carry `source`; the trend is drawn through those taken before any review that day when there are
      14, through all otherwise, and the report says which; a count a snapshot lacks is skipped, not read as 0.
    - F01: D3's question and PILOT.md gave only "−7/+5"; the rule since 2026-09-24 also needs over 95% recalled on the
      upper side (a first review that early is nearly wasted; the 5-day first-study cap makes it early by design). Both
      say so now; the rule is unchanged.
    - F02: changes of the daily limit, the retention target and the reminder time left no trace. SETTINGS_CHANGED
      (`data/SettingsChangeLog`, written on the application's own scope so leaving Settings loses nothing).
    - APP_VERSION carries the install time: a test build over another with the same version code is a new build.
    - The Persian "Strong" state read "مسلط" (mastered); it is "پایدار" (durable), as EN "Strong" and DE "Gefestigt":
      a stability of 21+ days, not exam mastery.
    - Claims narrowed: never-lengthen is on AVERAGE (the geometric mean; one topic can come out longer); an equal review
      count is not equal time (RESEARCH.md §5.1); a twin run on fitted weights is still a simulation (§5); "70 units
      comfortable at any target" needed a condition (§2.8); Review ahead was weighed only against unused time and a
      higher target; target phases are not study phases; the weakest tenth counts studied topics only; reviewDurationMs
      is screen time, not study time (export field guide). `OwnerYearSoakTest` now measures the twins' review time
      (equal to within a review).
  - **Put to the owner, not done:** the same-day Hard floor of py-fsrs 6.3.2. "Save and review now" and reviews the owner
    chooses make a second review the same day far more reachable than when the pin was set (S 100 → 45; the report's
    manual path 504 → 204). Adopting it is a new model identity with goldens generated by py-fsrs 6.3.2 itself, which
    needs installing it (a download).
  - **Kept:** original predictions beside replayed ones (the declined DB v11; corrections are logged and left out of
    the evidence); a planner layer or a shadow queue (pilot data first); the 64-step fit window (rarely reached).
  - **Citations:** Eglington & Pavlik 2020 (npj Science of Learning) is real; Price et al. is real (Academic Medicine
    2025;100(1):94–102), but its "26,258 physicians" could not be confirmed (a related ABFM study reports 16,751). Nothing
    in the app depends on either.
- Exact alarms: ONLY `SCHEDULE_EXACT_ALARM` is declared (user-grantable; inexact
  fallback + Reminder Health + permission-regrant receiver handle denial).
  `USE_EXACT_ALARM` was removed 2026-07 per Play policy (declare one, not both).
- Snooze is REAL: `reminder_snoozed_until` pref suppresses the whole chain until
  the target; colliding primary/secondary slots (±5 min) are coalesced to one.

## Testing

Unit tests: `app/src/test/java/com/example/...` (JUnit; Robolectric where a
Context/Room is needed). The scheduler math, replay==live, backup round-trip,
and Persian date conversion are covered. When changing `MedScheduler` or `Fsrs`,
run the suite — those invariants are load-bearing.

## Reference

- `DESIGN.md` — full product/design write-up and rationale.
- `plans/` — advisor-generated implementation plans (if present).


### Revalidate the active personal model at a due refit (2026-10-09)

At each due/forced personal refit, the active set is tested against the same conservative guard as a
candidate: valid weights, ordered initial grades and geometric mean interval lengthening <= 1 against
the published defaults on the captured histories at the actual retention target. A rejected replacement
no longer leaves a baseline that fails this guard ACTIVE. Retirement and its `PERSONAL_MODEL_RETIRED`
event occur only after the full fit identity and enabled switch are rechecked in the commit transaction
(PR #24); stale fits cannot retire a current model. Retired weights remain available for historical
replay, existing topic dates are not rewritten, and the scheduler refreshes at the next session.
This is checked at a due refit, not continuously or on every app launch; it is an average model ceiling,
not a promise that each topic's interval is shorter or that exam scores improve. The default ceiling is
intentionally distinct from the previously calibrated intervals. `ActiveModelSafetyTest` reproduces the
rejected-candidate gap with real Room and tests safe baselines and discard paths.
