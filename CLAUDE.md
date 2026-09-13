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
delivery over real time, clock/time-zone changes, Samsung battery management over days, the
full-screen alarm.

Device-testing gotchas: in Git Bash set `MSYS_NO_PATHCONV=1` before adb commands — otherwise a device
path like `/sdcard/ui.xml` is silently rewritten into a Windows path and the command "succeeds" doing
nothing. After a reboot wait at least ~60 s past `sys.boot_completed` before judging whether reminders
were re-armed: under load the boot broadcast reached Yadora ~40 s late, and a 25 s check reported a
re-arm bug that did not exist. Drive the UI with `uiautomator dump` + `input tap` on the node bounds,
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

## Identity (permanent — never change)

- `applicationId = "com.yadora.app"` — permanent once on Play; do NOT rename.
- `namespace = "com.example"` — deliberately kept; internal names
  (`medreview_db`, `medreview_settings`, channel ids) are intentionally NOT
  renamed. Cosmetic churn only; skip it.
- Version: `versionName` is the public string (currently "1.1"); bump
  `versionCode` by 1 for every Play upload (currently 4).

## Architecture

- `domain/srs/` — `Fsrs6.kt` (the LIVE model), `Fsrs.kt` (FSRS-5, frozen for
  replay) and `MedScheduler.kt` (product layer: the understanding clock,
  high-yield retention, first-study window, interval fuzz, queue priority score).
  This is the tested core — keep it pure and covered.
- `data/` — Room (`AppDatabase`, DAOs, entities), `MedReviewRepository`,
  `BackupManager` (versioned JSON export/import).
- `ui/<screen>/` — each screen file holds its ViewModel + factory + composables.
- `notifications/` — exact-alarm reminder stack + boot catch-up + WorkManager
  safety net.
- Manual DI: `MedReviewApplication` builds the DB + repository; the repository is
  passed down through composables.

## Settled decisions — do NOT re-propose these

These were decided deliberately. Re-suggesting them wastes a session:

- **First rating happens on the REVIEW screen, not the Add screen.** The Add
  screen intentionally has no confidence/difficulty section. A topic is due on
  its study date; the first rating there is review #0.
- **Day-granularity due model** (date-only). Not a bug; intervals are whole days.
  Due dates are `reviewedAt + intervalDays * 86_400_000` — ELAPSED milliseconds, not calendar
  addition. That is required: forgetting is physical, so FSRS must be fed true elapsed time, and a
  calendar-based due date would make preview/commit/replay depend on the device time zone at the
  moment each ran. The cost, measured and pinned by `DueDateDstTest`, is that a review between 23:00
  and midnight on a DST spring-forward night slips one day. Accepted; do NOT "fix" it by switching
  to calendar addition.
- **The recall prompt is an OPTIONAL per-topic field** (restored 2026-09 by user decision, after
  being cut as v1 bloat). A bare title like "Appendicitis" leaves Good vs Forgot undefined, and that
  noise sits under every interval FSRS computes; one optional line fixes most of it without turning
  Yadora into a flashcard app. It shows on the review screen under the title, before the notes. A
  merge keeps a prompt (survivor's, else the first absorbed copy's). Analytics exports only
  `hasRecallPrompt`, never the text — it is user content, like titles and notes.
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
- **Room migrations are additive only** (`MIGRATION_1_2/…/6_7`, currently DB v7,
  `exportSchema=true`, schemas 2–7 committed; every builder adds `AppDatabase.ALL_MIGRATIONS`).
  Never `fallbackToDestructiveMigration`.
- **DB v5 honest-scheduling model**: `nextReviewAt` = the effective date every
  query uses; `modelDueAt` = the memory model's own date; `deferredUntil` = set
  only by user deferrals (Not today / redistribute / manual edit) and cleared by
  a real review. Deferrals must NEVER write `modelDueAt`.
- **Merging topics never discards work.** The same material often gets added
  twice (frequently in two languages). `MedReviewRepository.mergeUnits` keeps one
  survivor and, in ONE transaction: re-points every review log at it (logs are
  never deleted), sums `reviewCount`/`lapseCount`, sets stability and difficulty
  to a **review-count-weighted average** so the result sits nearer the copy with
  more iterations (an unrated copy still weighs 1, never 0), takes the
  **earliest** `nextReviewAt` (a merge must never push material further away than
  the schedule already had), unions `highYield`, clears `deferredUntil`, and
  SOFT-deletes the absorbed copies so a mistaken merge is recoverable. No schema
  change — it is a re-pointing of existing rows. `MergeUnitsTest` pins all of it.
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
  `tools/generate_fsrs6_goldens.py` CALLING py-fsrs 6.3.1's own methods over 2160 transitions.
  This exists because a hand-written spec test cannot catch a misread equation: it shares an
  author with the code, so the same mistake lands in both and the suite stays green. Four real
  deviations survived exactly that way and were only found by running the reference:
  mean reversion must target the **unclamped** `D₀(Easy)` (−4.77, not the clamped 1.0 — py-fsrs
  passes `clamp=False` there and `clamp=True` only when seeding a new card); a lapse is bounded
  by the **short-term branch** `S / e^(w17·w18)` ≈ 0.9518·S, not by `S`; the same-day multiplier
  is floored at 1 for **Good and Easy only** (6.3.1 lists just those two — an unreleased `main`
  commit adds Hard; we pin the release); and the stability floor is 0.001, not 0.01.
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
  last 600 real recall reviews on the live model that came at least 3 calendar days after the
  previous review: the stability scale at which the model's predicted recall count equals the
  observed count (method of moments on the RAW stored predictions, so it never chases the corrected
  schedule), shrunk toward 1 in log space by `n / (n + 120)` and clamped to 0.5–2. The 3-day
  evidence rule is load-bearing: FSRS-6 is fed whole calendar days, so a 2.5-day gap is predicted at
  t = 3, the stored prediction runs pessimistic at short intervals, and fed those rows the estimator
  drove a perfectly average simulated learner to 1.58× within three months. Reviews that came
  before half of their scheduled memory interval (a repair deadline, an on-demand review) are left
  out too: they sit at ~0.97 predicted recall, where an outcome says nothing about the curve, and
  they are a selected set (`EARLY_REVIEW_FRACTION`). `RecallCalibrationEvidenceTest` pins the rule.
- **On-demand review from the Library is allowed; interval compression by exam date is not.**
  Long-press a topic → play. It opens the single-topic session whether or not the topic is due; an
  early review is ordinary FSRS (high predicted recall, a small stability gain, a lapse is a lapse)
  and the schedule is recomputed from it honestly. This is the learner choosing to check a topic
  before a test. It does not read the exam date, and the exam date still feeds nothing.
- **The Today estimate uses the user's own review time**: the median of their last 50 measured
  durations (≥ 5 s), two minutes until ten exist (`MedReviewRepository.typicalReviewMinutes`). It multiplies the memory interval only, before the caps —
  equivalent to a per-user retention adjustment — and never touches stability or difficulty. It
  is read at app start and at the start of every review session, each log stores the scale it was
  scheduled with (`calibrationScaleAtReview`), and replay uses the stored one for untouched rows.
  The shrinkage is deliberately strong: the FSRS-6 curve is so flat that a one-point recall gap
  is a ~15 % stability change, so 100 reviews alone would swing the scale by ~1.5× on noise. This
  is the honest first-order correction until an FSRS optimizer (all 21 weights, ~1,000+ reviews)
  exists; do not present it as one. Constants are POLICY. `RecallCalibrationTest` pins recovery
  of a planted scale, shrinkage, bounds and reach.
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
  (slider 0.85–0.97; 0.90 is the workload optimum, 0.95–0.97 buys exam-readiness at roughly
  1.4–2× the reviews) sits in the workload-optimal band from FSRS's own retention simulations
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
  months out. Keep the Settings copy honest about it.
- **Duplicate detection normalizes Persian/Arabic** (`TopicTitle`). SQL `lower(trim(title))` is byte
  equality with an ASCII-only lowercase, so Farsi yeh vs Arabic yeh and keheh vs Arabic kaf —
  chosen by the keyboard, not the writer, and visually identical — produced two topics with no
  duplicate warning. That is precisely the "same material added twice, often in two languages" case
  merge exists for. Comparison only; stored titles stay exactly as typed.
- **One memory seed per history.** `Fsrs.initialState` may only be re-applied for the
  chronologically FIRST review log of a topic. A merged topic legitimately carries
  several `logType = "FIRST_STUDY"` rows (one per absorbed copy), and treating each
  as a seed silently reset the merged FSRS state on any later rating correction or
  study-date edit. `editReviewRating` normalizes the extra rows to `RECALL` as it
  replays, so histories merged by older builds self-heal.
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
- **`priorityScore`'s lapse term is capped** at `MAX_SCORED_LAPSES` (5). `lapseCount`
  only ever grows, so uncapped it eventually outweighed high-yield (100) and let an
  old struggle permanently outrank a genuinely important topic. The overdue term is
  deliberately left uncapped so nothing can starve.
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
- **Policy versioning**: bump `MedScheduler.POLICY_VERSION` whenever any
  product-layer number changes (understanding factors, relearn step, caps,
  fuzz, high-yield retention, repair backoff, calibration constants). Logs store the version,
  the applied understanding factor and the calibration scale; replay honors the stored values
  and the stamped policy's rules for untouched rows. Currently `YADORA-6`.
- **Behaviour changes are simulated before they are argued.** A two-year simulated student
  (honest ratings drawn from a true memory that may forget faster or slower than the defaults,
  a daily limit, a holiday, the Spread-Out button) found the Partial loop and the leech spiral
  that code reading missed. The simulator lives with the independent Python reference used for
  the differential test; rebuild it from `MedScheduler`'s rules rather than reasoning from one
  worked example when a policy number is on the table.
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
