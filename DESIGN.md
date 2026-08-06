# Yadora — Product & Technical Design

> MedReview is a **study-review scheduler** for medical students, not a flashcard app.
> The unit of value is *when* to review a studied topic, driven by forgetting-curve science,
> with reliable reminders and minimal cognitive load. Offline, no backend/login (for now).
> Languages: English + Persian (RTL).

---

## 1. The core loop

1. Student studies a topic for the **first time** (e.g. "Beta blockers — contraindications in asthma").
2. Logs the topic. It becomes due on the selected study date.
3. On the Review screen, review #0 records **initial difficulty** (Easy/Medium/Hard) and
   **understanding** (Confused/Partial/Clear). This row is tagged `FIRST_STUDY`, not counted as recall.
4. Yadora schedules the first delayed review using its FSRS-5-derived policy and arms reminders.
5. At later reviews, the student recalls from memory, rates **memory** (Forgot/Hard/Good/Easy),
   then rates understanding. These rows are tagged `RECALL`.
6. The interval adapts from the model state. Repeat to maintain the topic efficiently.

A study unit may be tiny or large; treated identically.

The app must answer, with minimum noise:
**What do I review today? · What did I miss? · What am I weak in? · What is high-yield? ·
What is coming before my exam? · Why did this get scheduled again?**

---

## 2. Scheduling engine — FSRS (DSR model)

**Decision:** adopt **FSRS** (Free Spaced Repetition Scheduler), the current state of the art,
validated on hundreds of millions of reviews and shown to beat SM-2 and Half-Life Regression.
**Do not hand-write the formulas** — port/adapt the open-source reference (py-fsrs / fsrs-rs /
fsrs4anki) to Kotlin and unit-test against reference vectors. This is how we satisfy both
"latest science" and "no calculation bugs."

### Memory model (three components)
- **Difficulty (D)** ∈ [1,10] — intrinsic hardness of the item.
- **Stability (S)** — days for recall probability to fall from 100% → 90%.
- **Retrievability (R)** — current probability of recall. Computed **on read**, never stored stale:

  `R(t,S) = (1 + FACTOR · t/S) ^ DECAY`, with `DECAY = -0.5`, `FACTOR = 19/81 ≈ 0.2345`
  (power-law forgetting curve; by construction R = 0.9 when t = S).

### Interval from stability
`interval(S, R_desired) = (S / FACTOR) · (R_desired^(1/DECAY) − 1)`
- Default **desired retention = 0.90** (med-student sweet spot; 0.97 explodes workload for
  marginal gain).
- **High-yield** → desired retention 0.92–0.95 (tighter, principled — replaces the arbitrary ×0.8).

### Ratings → grades
- `MemoryRating` Forgot/Hard/Good/Easy → FSRS grades 1/2/3/4 (clean mapping, already in the code).
- On each review: compute R **before** updating (fixes current bug), update D (mean-reversion + grade),
  update S (recall vs lapse formula consuming D, R, grade), derive new interval, clamp, log a reason.

### Understanding axis (honest stance — see §6)
FSRS models **recall**, not **comprehension**. There is no validated "understanding" scheduling
parameter. So understanding is used as:
- a **modifier on initial/ongoing Difficulty** (Confused → higher D → slower S growth → sooner reviews), and
- an **advisory** ("you said you didn't fully understand — restudy the source, don't just recall").
Not as a hard interval cap (the current app's approach corrupts the model).

### First-study event
The Add screen stores a neutral seed and makes the topic due on its study date. Review #0 then records
initial difficulty + understanding and establishes the first model interval. It is explicitly tagged
`FIRST_STUDY`; retention and calibration analytics exclude it. A back-dated topic is treated as a real
recall because time has elapsed since study.

### Procrastinate / snooze
`snoozedUntil` overrides the queue/alarm time but does **not** alter S/D — snoozing is not a memory
event. Elapsed time at the eventual review is still measured from `lastReviewedAt`, keeping the model honest.

### Exam handling
An `Exam` (date + linked subjects/systems or tags) raises desired retention as the date approaches
and forces a final review ~1–2 days before. "Coming before my exam" = linked units with
`nextReviewAt ≤ examDate`.

### Weak-topic detection
`weakness = f(lapseCount, recent Forgot/Hard rate, R below target, time overdue) × highYield weight` —
ranked list, not just per-subject bars.

---

## 3. Reliable reminders (the make-or-break requirement)

**Replace** the single daily `PeriodicWorkRequest` (Doze-batched, lost on reboot) with:

- **`AlarmManager.setAlarmClock()`** as the primary fire mechanism. It fires even in Doze, is
  treated as a user-facing alarm, and (key) is **exempt from the `SCHEDULE_EXACT_ALARM` permission**
  on Android 12+. Use one **daily digest** alarm at the user's chosen review time ("7 topics due,
  ~10 min") — calm + reliable — plus one-off alarms for snoozes.
- **`BootReceiver`** (`RECEIVE_BOOT_COMPLETED`) + `TIME_SET` / `TIMEZONE_CHANGED` /
  `MY_PACKAGE_REPLACED` receivers to **re-arm** alarms (alarms are cleared on reboot/update).
- **Battery-optimization exemption** prompt (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) +
  a one-time **OEM autostart guidance** screen (Xiaomi/Huawei/Oppo/Vivo/Samsung — see dontkillmyapp.com).
  Be honest: on hostile OEMs, 100% is impossible without user cooperation.
- **App-open re-arm**: every launch recomputes and re-arms all alarms (cheap insurance).
- **WorkManager** kept only as a redundant once-daily **safety-net re-check**, never the primary path.
- **Notification actions**: "Review now" (deep-link into the review session) and "Later"
  (snooze: +3h / tonight / tomorrow → writes `snoozedUntil`, re-arms a one-off alarm).
  Snoozed items show a "snoozed" tag in Today so nothing silently disappears.

Permissions to add: `SCHEDULE_EXACT_ALARM` (fallback path), `RECEIVE_BOOT_COMPLETED`,
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. (`POST_NOTIFICATIONS` already present.)

---

## 4. Data-model changes (Room migrations; bump version, enable schema export)

- **New `ExamEntity`** (name, examDate, linked subject/system or tag, color) + DAO queries
  ("units due before exam", "days until exam").
- **StudyUnitEntity**: keep `difficulty`/`stability`; stop storing stale `retrievability`
  (compute on read); add `firstStudiedAt` (distinct from `lastReviewedAt`),
  `initialDifficultyRating`/`initialUnderstandingRating`, `snoozedUntil`, `deferCount`,
  `schedulingOrigin` enum (Scheduled / Snoozed / OverdueRedistributed / ExamCompressed).
- **ReviewLogEntity**: add `elapsedDays`, `retrievabilityAtReview`, `difficultyBefore/After`,
  `stabilityBefore/After`, and a generated `reasonText` so "why scheduled again" renders from stored data.
- Replace `StudyState` review-count thresholds with a **stability/estimated-retention-derived** mastery
  so the badge/progress bar match the scheduler.

---

## 5. UX — Today screen & information architecture

**Governing rule:** ONE primary action visible without scrolling; everything else is progressive disclosure.

**Today (single LazyColumn, plain background — no competing stat-card grid):**
1. **Calm header** — localized weekday + date; one status line "X topics · about Y min" ("about", never false-precise); a single gear top-right.
2. **The one big answer** — a single hero **"Start review · X due"** button (the only elevated element). The count rides on the action.
3. **Thin summary strip** (text only): "● X due · ● Z missed · ● W high-yield" — each tappable to filter.
4. **The list**, ≤3 sections, calm headers:
   - **Missed** (only if non-empty) → quiet "You missed Z while away" card + one **"Catch up (spread over 3 days)"** action (reframed as relief, not red-alarm guilt) + top 3–5 by high-yield/weakness + "See all".
   - **Due today** → calm cards (title · subject dot + state · high-yield pill only when true · one-line "why" caption), ordered high-yield → weakest → rest, **capped at the daily limit** ("X more held for tomorrow").
   - **Coming up** → collapsed one-liner "Next: 4 tomorrow, 9 this week ›".
5. **Exam ribbon** (only if an exam is within ~30 days): "Cardiology exam in 12 days · 18 still weak ›".

**Empty states:** one warm sentence ("You're all caught up — next review Tuesday."), not emoji-heavy paragraphs.

**"Why was this scheduled again?"** — one plain cause→effect→principle sentence generated from the
**same** FSRS math that set the date (single source of truth):
*"You recalled it well last time, so we waited longer — that longer gap is what builds lasting memory."*
Variants per grade, with "+high-yield kept it tighter" / "+exam pulled it in" riders.

**Navigation (4 tabs, RTL-mirrored):** **Today** (decision screen) · **Plan** (exams + forecast +
full backlog) · **Library** (catalog/search/filter/archive + item detail) · **You/Progress**
(lead with "Weakest areas", then charts). Add/Edit, Review Session, Item Detail, Settings, Exam editor
are pushed routes.

**Anti-overwhelm principles:** one action per screen · numbers as quiet text not alarms · cap the day &
hide the backlog · progressive disclosure · reframe failure as relief · no false precision · conditional
UI (no hollow "no items" boxes) · every item self-explains in one sentence · full RTL + Persian numerals/dates.

---

## 6. Honesty / open items (not independently verified)

- The FSRS **formulas and parameter counts** here are from memory — at implementation time, take them
  from the **reference implementation**, not this doc.
- The **understanding-as-difficulty-modifier** choice is reasoned from general learning science
  (levels-of-processing, desirable difficulties), **not** a validated scheduling parameter. Treat as a
  design decision to validate, not settled science.
- Reliable-alarm claims (esp. `setAlarmClock` exempt from exact-alarm permission, OEM behavior) should be
  confirmed against current Android docs + on-device testing before shipping.

---

## 7. Build plan (evolve, don't rebuild)

- **Phase 1 — Real scheduler. ✅ code complete (pending a compile/test run on a machine with the SDK).**
  Added `domain/srs/Fsrs.kt` (FSRS-5 engine) + `domain/srs/MedScheduler.kt` (first-study seeding,
  understanding→difficulty, high-yield retention, relearn step, mastery-from-stability, "why" reason).
  Deleted the cosmetic `domain/scheduler/SpacedRepetitionScheduler.kt`; rewired `ReviewSessionScreen`
  (commit + button preview now share `MedScheduler.review`) and `AddUnitScreen` (seeds FSRS state).
  Added `FsrsTest` + `MedSchedulerTest` (invariants + exact curve identities).
  TODO follow-up: the Settings FSRS/SM-2 toggle and the `RetentionMode` enum are now dead (no longer
  read) — remove or repurpose; optionally seed demo data in `MedReviewApplication` via `firstStudy`.
- **Phase 2 — Reliable alarms. 🚧 core landed (needs on-device testing).** AlarmManager exact alarms
  (`setExactAndAllowWhileIdle` with inexact fallback + `canScheduleExactAlarms` check), `ReviewReminderReceiver`
  (fires only when something is due today, re-arms next day), `BootReceiver` (reboot/update/timezone re-arm),
  notification "Review now" + "Later" (snooze) actions, Settings "Enable exact alarms" prompt, due-count bug fixed.
  WorkManager removed. STILL TODO: OEM battery/autostart guidance, app-open re-arm refinement, real-device verification.
- **Phase 3 — Features.** Exam entity + Plan tab; procrastinate UI; "why scheduled" explainability;
  real weak-topic ranking; i18n cleanup (kill the `cancel == "لغو"` hack, move all literals into AppStrings).

Each phase behind a Room migration.

---

## 8. v2 candidate ideas (curated from external reviews, 2026-07)

Two long external reviews proposed full redesigns. Most of their content was either already
implemented, contradicted settled decisions (see CLAUDE.md), or v1 bloat — but these ideas are
genuinely worth considering for v2. Kept here so they aren't lost:

- ✅ **DONE (DB v5): `modelDueAt` + `deferredUntil` split.** Deferrals (Not today, redistribute,
  manual date edits) no longer overwrite the model's due date; a real review re-unifies the two.
- **Batch commitment + in-progress resume.** Commit to min(batchSize, dueCount) per session;
  closing the app preserves the batch and completed reviews; resume later. Better than the daily
  cap for habit psychology.
- **Capped lateness credit.** A successful very-late review currently gets full elapsed-time
  stability credit; capping the bonus (e.g. +25% of scheduled interval) is a defensible hypothesis.
  Must be versioned per log — breaks replay otherwise.
- ✅ **DONE: Split-topic suggestion.** After ≥2 strong↔Forgot reversals across ≥4 recalls, a
  dismissible hint appears on the review screen (suppressed 5 reviews after dismissal).
- ✅ **DONE (DB v5): Policy versioning per log** (`schedulerPolicyVersion` +
  `understandingFactorAtReview`); replay honors stored factors for untouched rows. This is the
  foundation for capped lateness credit, mature-Forgot flows, and personalization.
- **DB-level duplicate uniqueness** on (subjectId, NFKC-normalized title) instead of the current
  app-side check; add fuzzy-similarity warnings.
- **Scheduler interface extraction** (`TopicScheduler` with modelVersion) to slot FSRS-6 in when
  per-user data exists to justify it.
- **Content-currency status, separate from memory** (from the 2026-07 research review): a physician
  can perfectly remember an outdated guideline. A per-topic "source may need updating" flag (set
  manually, or by source date) is cheap, honest, and independent of the scheduler. Small.
- **Per-topic retention horizon** ("exam in 1 month" vs "lifelong"): the spacing literature's most
  consistent finding is that optimal gaps scale with the desired retention interval. Today one
  global retention target serves everyone; a per-topic horizon would feed
  `desiredRetentionOverride`, which the scheduler ALREADY accepts per review. Medium.
- **Risk-per-minute queue refinement**: divide `priorityScore` by expected review minutes (user's
  observed median) so limited time buys the most retention. Needs per-topic duration estimates —
  the `reviewDurationMs` data being logged since v4 is exactly this. Medium.
- **Validation note**: an external FSRS-6 workload analysis (unverified simulation, but consistent
  with known FSRS workload curves) puts the efficient retention band at ~0.88–0.92, with ~0.92 for
  critical items. Yadora's shipped defaults (0.90 standard / 0.93 important) sit essentially inside
  that band — keep them; revisit only with real user data.

Explicitly rejected for any version (re-litigated multiple times): first rating at Add time;
"Forgot" in red; removing the retention slider; ABORT on log insert (REPLACE is the replay
mechanism); raising minSdk above 26 (excludes older devices common among our users); strict
streaks with permanent-fail recovery challenges (stress-inducing, against the calm-tone rule).

### Post-publish audit round (2026-08, 14-dimension multi-agent audit)

A full adversarially-verified audit run after launch, prompted by the question "when a
topic goes overdue, does the scheduler behave correctly whether the user then performs
well or poorly?"

**Answer: the scheduling math is sound.** Traced with real numbers at 30/90/365 days
late. A large stability jump on a late-but-successful recall is canonical FSRS-5 (a
successful recall after a long gap IS evidence of durability), and it is capped at 365
days in every path — live, preview and replay all funnel through the same functions.
Forgot-while-overdue correctly relearns tomorrow regardless of lateness. Backlog
redistribution spreads a 40-item pile sensibly, and the overdue UI tone is already calm
("You were away", warm terracotta, never error-red). No change needed.

**What the audit actually caught** was elsewhere, and is fixed: the merge feature could
silently corrupt FSRS state (see the merge rules in CLAUDE.md), a restored backup could
permanently brick reviewing via an unvalidated `desired_retention`, the reliability
layers could override a user's snooze, transient reminder state leaked across devices
via cloud backup, and five background paths crashed the whole app on any exception.

**Rejected as wrong or not worth it:**
- *Composite `(archived, nextReviewAt)` index* — needs a migration on a published DB for
  a table of at most a few thousand rows, where the existing `nextReviewAt` index already
  carries the query. Cost exceeds benefit.
- *`ExactAlarmPermissionReceiver` `exported="false"` "never fires"* — false. That is the
  documented pattern for `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`; the system
  targets the package directly. `BootReceiver` needs `exported="true"` only because
  `BOOT_COMPLETED` is a true broadcast.
- *"First-study intervals should never be fuzzed"* — the behaviour is right and the
  comment was wrong; see CLAUDE.md.

**Coverage gap (not a finding):** the run hit account usage limits, so the `datetime`
dimension and five cross-cutting/product-completeness sweeps never executed. Worth a
follow-up, particularly: does Progress/analytics let a user see whether the scheduler's
overdue handling is working well *for them*?

### Pre-publish audit round (2026-07, `publish-app-yadora.txt`)

An external "think outside the box" review before keystore creation. Adjudication:

**Accepted & fixed (all in this pass):**
- **"Delete all data" was genuinely missing** despite the privacy policy promising it — added a
  red, double-confirmed Settings action (`BackupManager.deleteAllData`) that wipes every table in
  one transaction, cancels reminders, clears settings (keeps only the chosen language), and deletes
  the crash log + pre-restore safety copy. EN/FA/DE.
- **Backup/analytics snapshots weren't atomic** — each table was read from its own Flow at a
  different instant, so a concurrent write (receiver/purge) could produce an internally inconsistent
  export. Both now read through one-shot DAO queries inside a single `withTransaction`.
- **Emergency pre-restore backup was best-effort** (`runCatching`) — restore could delete the only
  copy of the data even if the safety copy failed to write. Now writes to a temp file, verifies
  non-empty, atomically renames, and ABORTS the restore if any step fails.
- **Settings restore used `apply()`** (async) though success was reported synchronously → `commit()`.
- **Migration coverage was v4→v5 only** — added real seeded-DB tests for the full **2→5** and
  **3→5** chains (identity hashes `cc66ba…`/`bd6124…`), proving every stacked ALTER lands with its
  documented default and pre-existing values (e.g. a real `logType`) are preserved.
- Minor: OFL font licenses now shipped in `NOTICES.md`; template `ExampleUnitTest` removed;
  PUBLISHING "99%+ of devices" softened to point at Play's live distribution dashboard.

**Rejected (with reasons the user can send back):**
- **"Default reminders OFF."** Contradicts the explicit bulletproof-reminders requirement: a
  due topic must notify even if the app is never opened. Reminders stay ON by default; the user can
  turn them off. This is a product decision, not an oversight.
- **"Remove alarm/full-screen-intent mode."** It's an opt-in feature (default off) with an
  API-34+ `canUseFullScreenIntent()` fallback and an inexact-alarm degradation path already in
  place. Kept.
- **"16KB page size will break on Android 15+."** Verified false: the only native libs in the AAB
  (`androidx.graphics.path`) have every LOAD segment 0x4000-aligned. Compatible as-is.
- **Namespace / channel-id / `medreview_*` renames.** Settled — cosmetic churn with migration risk
  (see CLAUDE.md Identity).
