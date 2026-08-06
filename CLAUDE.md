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
- **Fidelity to FSRS-5 is verified, not assumed.** `FsrsSpecComplianceTest` re-implements the
  published FSRS-5 equations independently and asserts `Fsrs.kt` agrees to ~1e-9 across a
  sweep of stabilities, difficulties, grades and elapsed times — plus no NaN/∞ from any
  reachable input, and the monotonicity properties (a later successful recall never yields
  less stability; a lapse never strengthens memory). `SchedulerInvariantsTest` does the same
  for the product layer. If you change `Fsrs.kt` or `MedScheduler.kt`, these two are the
  tests that matter: a wrong exponent would not crash and would not fail a relational test,
  it would silently mis-time every review for years.
- **The live memory model is FSRS-6** (`MedScheduler.CURRENT_MODEL`), policy `YADORA-4`.
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
  - **`ReplayEqualsLiveTest` must mirror the real commit path** (project + `CURRENT_MODEL`).
    When FSRS-6 went live it kept passing while production disagreed with itself, because the
    test's own helper still defaulted to FSRS-5. If you change the commit path, change it there.
- **Understanding uses a SECOND CLOCK, not a multiplier** (DB v6). `modelDueAt` holds the pure
  memory prediction; `understandingDueAt` holds a short repair deadline (Confused or any lapse:
  1 day; Partial: 2/3/4 by recall strength; Clear: none); `nextReviewAt` is the earlier of the
  two. The old ×0.8/×0.9 was incoherent at long intervals — a topic the user said they did NOT
  understand still vanished for 80 days after a 100-day prediction. FSRS-5 keeps the multiplier
  so legacy replay reproduces what users actually experienced.
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
  (slider 0.85–0.95) sits in the workload-optimal band from FSRS's own retention simulations
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
- **Merging keeps the review-count-weighted average** rather than replaying the combined history
  chronologically. Replay is arguably more principled (stability/difficulty are nonlinear
  summaries, so averaging them is not a real memory state), and the machinery exists — but the
  averaging behaviour is conservative, tested, and easy to explain. User-confirmed 2026-08;
  revisit only with evidence, not on theory alone.
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
