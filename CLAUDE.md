# Yadora — CLAUDE.md

Offline Android study-review **scheduler** (not a flashcard app) built around
FSRS-5 spaced repetition. Kotlin + Jetpack Compose + Material 3 + Room. No
backend. English + Persian (RTL, Persian digits, Jalali calendar).

## Build & verify

Requires Android Studio's bundled JDK:

```
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest   # unit tests
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug        # debug APK
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:lintDebug            # lint (keep 0 errors)
```

(PowerShell: `$env:JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"` first.)

## Identity (permanent — never change)

- `applicationId = "com.yadora.app"` — permanent once on Play; do NOT rename.
- `namespace = "com.example"` — deliberately kept; internal names
  (`medreview_db`, `medreview_settings`, channel ids) are intentionally NOT
  renamed. Cosmetic churn only; skip it.
- Version: `versionName` is the public string ("1.0"); bump `versionCode` by 1
  for every Play upload.

## Architecture

- `domain/srs/` — `Fsrs.kt` (pure FSRS-5) and `MedScheduler.kt` (product layer:
  understanding multipliers, high-yield retention, first-study window, interval
  fuzz, queue priority score). This is the tested core — keep it pure and covered.
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
- **"Forgot" is not red.** Ratings use a calm palette; red = destructive actions
  only. No emoji, confetti, or "Great job!" language (mature tone).
- **FSRS mean-reversion targets D0(Easy)** — this is canonical FSRS. "Revert to
  Good" is wrong; do not change it.
- **Interval fuzz** is deterministic per (unitId, reviewCount), multiplicative
  ±5%, and never applied when the BASE interval < 3 days. Preview == commit ==
  replay is an invariant; `ReplayEqualsLiveTest` guards it bit-for-bit.
- **Room migrations are additive only** (`MIGRATION_1_2/…/4_5`,
  `exportSchema=true`). Never `fallbackToDestructiveMigration`.
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
- **One memory seed per history.** `Fsrs.initialState` may only be re-applied for the
  chronologically FIRST review log of a topic. A merged topic legitimately carries
  several `logType = "FIRST_STUDY"` rows (one per absorbed copy), and treating each
  as a seed silently reset the merged FSRS state on any later rating correction or
  study-date edit. `editReviewRating` normalizes the extra rows to `RECALL` as it
  replays, so histories merged by older builds self-heal.
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
- **Transient reminder state lives in its own `medreview_transient` prefs file**,
  excluded from cloud backup and device transfer. Android backs up whole prefs FILES,
  so `last_notif_shown_at` / `reminder_snoozed_until` / `reminder_next_nudge_at`
  otherwise travel to a new phone and silence its first reminders.
- **Policy versioning**: bump `MedScheduler.POLICY_VERSION` whenever any
  product-layer number changes (understanding factors, relearn step, caps,
  fuzz, high-yield retention). Logs store the version + applied factor;
  replay honors the stored factor for untouched rows.
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
