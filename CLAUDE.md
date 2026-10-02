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

**CI:** private repo `github.com/CodeinScrubs/yadora`. `.github/workflows/android-ci.yml` runs unit
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
delivery over real time, Samsung battery management over days, the full-screen alarm.

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
(exact vs inexact), channels and jobs; `tools/device/test_reminder.sh <serial>` fires Settings → "Send a
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
  `docs/PILOT_GUIDE_FA.md` the participant guide, and `docs/COMPETITOR_REVIEWS.md` what 271 users of the closest
  comparable app valued and suffered, with Yadora's answer to each.

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
  usually sticks better than rereading alone.
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
  on the old code).
- **Digits follow the interface language, dates the calendar setting** (2026-09-27). `AppDate.date/dateTime/weekdayDate`
  take a REQUIRED `persianDigits` (the Persian interface): Jalali dates used to print Latin digits beside Persian ones.
  An English interface on the Jalali calendar keeps Latin digits. `PersianDate.faDigits` also turns a decimal point
  between two digits into the Persian separator "٫" ("۱۱٫۲ روز", "×۰٫۸۱").
- **Screens with text fields take the keyboard's height** (`.consumeWindowInsets(padding).imePadding()` on the
  Review, Add/Edit and Settings content, 2026-09-26). The activity is edge-to-edge, so the keyboard covers the
  window instead of resizing it, and without this a focused field near the bottom (a question score, the notes)
  sat under the keyboard. Consume the Scaffold padding first, or the navigation bar is counted twice.
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
  do not propose exam-horizon capping again.
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
  soak's 24,673 reviews contain none. What remains is a deliberate second review from the Library and the
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
  history; the complete fix is a per-log day or zone record (a schema change), not taken yet.
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
  beat the memory date is dropped (`remediationDays` returns null). A two-year simulation showed
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
  predictions with outcomes; the corrected predictions agree with them by construction.
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
  independent; reviews of one topic are not, and the fit is repeated, so the real rate of adopting a
  worse set is NOT established — 0 in 40 below has a 95% upper bound of about 7%). Measured by `Fsrs6OptimizerGateTest`:
  a learner the defaults describe was adopted 0 times in 40 refits; moderate departures 0 in 10 (once
  real reviews correct the state, the defaults' predictions differ too little); a strong departure 9 in
  10 at ~9,000 reviews and not yet at ~4,000. Only then
  is it refitted on everything and stored ACTIVE (the previous set RETIRED); otherwise the attempt is
  stored REJECTED with its scores. Model identity is now (model, weight set):
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
  `reviewDurationMs` is still logged as research data.
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
  the emulator), and the card counted it among "reviews waiting".
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
  them, because every log already stores the scale it was scheduled with. Currently `YADORA-6`.
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
  validates the WHOLE file before it writes the safety copy or touches the database. Settings runs the
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
  - **Measured:** exam-day recall 96.9% against 90.6% for a random-review twin at equal time (the twin's time
    matches Yadora's to within one review since 2026-09-27; before, it got a few percent more and scored 90.1%);
    100% of topics at 90%+; weakest tenth 93.1%; 24,673 reviews. (Before the calibration stopped lengthening
    intervals, 2026-09-28: 96.6%, 92.7% and 22,804 reviews. The 2026-09-29 queue order changed neither figure at
    this limit, which rarely binds; it changed which random draw each review gets, and the count, from 24,335.)
  - **CI:** `analyze.py` replays the export (27,105 logs, all exact) in the "Pilot toolkit agrees with the app"
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
  question score, once the pilot shows what it means). The personal weight set learns from the same ratings
  and can still lengthen intervals after its gate; that risk is known and not yet addressed.
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
    which topics are asked (about 8% of 100-question exams against the disciplined no-app twin), and a different
    student can study more, start ahead or review better. Never claim a guaranteed score; MARKETING.md applies.
- **Five more outside reports, 2026-09-28: what was real, what changed, what did not** (each item checked against
  the code; every fix has a test that fails without it).
  - **Fixed:**
    - A backup whose topics were reviewed but that carries no review history (`reviewLogs` missing or empty) is
      refused before anything is replaced; an unreviewed library without the section still restores
      (`BackupRoundTripTest`). A missing section used to restore topics that claimed reviews and had none.
    - The personal fit's "did the history change while it ran?" check is a SHA-256 over every log the fit learned
      from (id, topic, time, both ratings, type, model, set) plus the active set (`MedReviewRepository.fitIdentity`).
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
    (a simulation is not proof; the twin loses about 8% of 100-question exams to the disciplined no-app twin by
    which topics are asked); "the core sits exactly on the Pareto frontier, no inefficient formula exists" (the
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
