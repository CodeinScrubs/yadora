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

### FSRS-5 → FSRS-6 migration (2026-08)

Adopted after an external audit's FSRS-6 arithmetic was independently reproduced to ten
significant figures (S=20, D=5, t=45 → Good 86.24141137779552, Forgot 2.1613332322199135),
and after a factual error here — the claim that FSRS-6 "behaves near-identically on default
weights" — was conceded. It does not: S₀(Easy) 15.69 → 8.30, and the curve exponent goes from
a fixed 0.5 to a trainable default of 0.1542.

**Why it was worth doing.** FSRS-6's fitted `S₀(Easy) = 8.2956` replaces the hand-chosen
YADORA-3 damping (7.06) — an *invented* constant swapped for a *fitted* one. That was the
single strongest criticism this project received, and the migration resolves it rather than
defending it.

**Shape of the migration** (details in CLAUDE.md):
- FSRS-5 is kept frozen; a stored stability only means something with its model.
- DB v6 records model identity per topic and the understanding clock. The migration itself is
  inert — upgrading moves no schedule.
- State is carried across by REPLAYING real rating history through FSRS-6, lazily at the next
  review. The stored FSRS-5 number is not portable and is never reinterpreted.
- Understanding became a second clock instead of a multiplier.

**What it cost, honestly.** Making FSRS-6 live broke `preview == commit == replay` in
production while the suite still passed, because `ReplayEqualsLiveTest`'s helper also defaulted
to FSRS-5 — it compared two FSRS-5 paths and agreed with itself. That is exactly the
"test mirrors the implementation, not the behaviour" failure this project warns about
elsewhere. Fixed, and CLAUDE.md now requires that test to mirror the real commit path.

**Deliberately NOT taken from the same patch:** removing the 365-day interval cap (→36,500)
and switching to rate-before-reveal. Neither is required by the migration; the second also drops
the "finalise after comparing" step that made it defensible. Both remain open product decisions.

### Adversarial audit OF THIS PROJECT'S OWN CLAIMS (2026-08)

A review not of the code but of the *confidence* attached to it. Largely fair; three of its
points were acted on.

**Conceded — a factual error.** It was claimed here that FSRS-6 "behaves near-identically to
FSRS-5 on default weights, so the benefit is ~zero until trained." That is wrong. The default
parameter sets differ materially: `S₀(Easy)` 15.69 → 8.30 (−47%), `S₀(Good)` 3.17 → 2.31,
`S₀(Again)` 0.40 → 0.21, and the forgetting-curve exponent goes from a fixed 0.5 to a trainable
default of 0.1542 — a much flatter curve. Adopting FSRS-6 defaults is a real behavioural change
and does NOT require personal training first. The migration-cost argument (keeping FSRS-5 alive
for faithful replay of existing logs, and changing every live user's schedule) still stands on
its own, but it must be argued on cost, not on a false claim of equivalence.

**Conceded — overstated confidence.** "The five-day cap is the single best design decision" and
similar phrasing treated policy constants as science. Tests here prove *implementation*
validity only. CLAUDE.md now says so explicitly and labels the policy constants as unvalidated.

**Conceded and FIXED — a real defect.** Rewriting a later `FIRST_STUDY` row as `RECALL` during
replay granted a re-study the stability growth of a successful delayed recall. That rewards
re-reading as remembering, and trusts completely the very signal YADORA-3 exists to distrust.
Such rows are now treated as re-encoding exposures: they re-anchor the clock, change no memory
state, are not counted as graded reviews, and keep their own `logType`.

**Not accepted:**
- *"Remove YADORA-3 damping as invented."* The direction is supported; the magnitude is a
  judgement call — but so is every alternative on offer, including using FSRS-5's raw `S₀(Easy)`,
  which was fitted on *delayed flashcard recall*, not immediate topic self-ratings. Notably
  FSRS-6's own refit moved that value 47% in the same direction. It is labelled as policy, not
  removed.
- *"Reverse weighted-average merging."* The theoretical objection is recorded and stands, but
  this was the user's explicit informed choice between two presented options. It is their call.
- The "contradiction" framing: revising a position on new evidence is correct behaviour. The
  fair version of the criticism is that conclusions were sometimes announced with more
  confidence than the evidence carried — which is conceded above.

### External code audit + patch (2026-08, `Yadora_suggestions.patch`, against `yadora1.zip`)

A 43-file patch (2,259 insertions / 1,015 deletions). **The patch was NOT applied**: it was built
against a snapshot predating this session's commits, so applying it would have reverted verified
fixes; it introduces a schema migration 5→6 (`rowVersion`, `mergedIntoId`, four log columns) and a
`FOREGROUND_SERVICE_SPECIAL_USE` declaration needing Play Console justification on a live app; it
rebuilds merge as alias-based, which was decided against; and its own author states Gradle, KSP,
Room, Compose, Lint and all tests could not be run against it. Findings were cherry-picked and
verified individually instead.

**Accepted (each independently reproduced first):**
- *First check-in could exceed its own ceiling.* `review()` capped the interval, but fuzz ran
  afterwards and added up to +5%, turning an advertised 5.0-day promise into 5.25. Now clamped
  after fuzz. NOTE: the cap is keyed on an explicit `isFirstStudy` flag, **not** `reviewCount == 0`
  — after a merge the earliest log in a combined history can be a RECALL, so the counter is 0 while
  the event is not a first study, and clamping that would corrupt a mature schedule.
- *Exam countdown was wrong across DST.* `(examMidnight - todayMidnight) / 86_400_000` truncates a
  167-hour spring-forward week to 6 days. A German user one week out was told six. Now
  `ChronoUnit.DAYS` between `LocalDate`s. (Iran dropped DST in 2022, so only European users were
  ever misled.) `ExamCountdownDstTest` pins both transitions — and must set the JVM default zone,
  since `ExamCountdown` reads `ZoneId.systemDefault()`.
- *`unarchiveUnit` could resurrect a soft-deleted row* into the active list while it still carried a
  `deletedAt` the 30-day purge would later act on. Now `AND deletedAt IS NULL`. Not reachable from
  today's UI, but a latent trap.
- *Merge dialog copy overstated recoverability.* Restoring an absorbed copy returns the title as a
  fresh topic; the history stays with the survivor. Copy now says exactly that.
- *Fuzz determinism rests on `kotlin.random.Random`*, which is not a documented cross-version
  contract. Rather than change the RNG (which would alter every future schedule and need a policy
  branch), golden-vector tests now pin its output so a Kotlin upgrade fails loudly here instead of
  silently rescheduling users.

**Rejected:**
- *Multiple `FIRST_STUDY` rows corrupt replay* — real, but already fixed this session (see the
  one-seed-per-history rule in CLAUDE.md). The audit's snapshot predates it.
- *Lifetime lapse count dominates priority* — already fixed (`MAX_SCORED_LAPSES`).
- *Optimistic concurrency / `rowVersion`* — the failure needs two live review screens for one topic.
  Android runs single-task, and `launchSingleTop` now prevents stacking the destination. A schema
  migration on a shipped DB is not justified by that reachability.
- *Foreign keys, composite index, enum columns, namespace rename* — all require migrations or churn
  on a published database; the invariants they would enforce are already held in code and re-checked
  by restore validation. Namespace is a settled decision.
- *Alarm as a foreground service so Home cannot silence it* — architecturally correct in the
  abstract, but it reverses behaviour added in direct response to the user's own report ("it keeps
  alarming and there is no option to shut it down"), and `FOREGROUND_SERVICE_SPECIAL_USE` invites
  Play review on a live app. Left as the user's call rather than changed unilaterally.
- *Merge invariant `nextReviewAt >= lastReviewedAt`* — a merged topic can be due before its latest
  review, which simply means "overdue". Harmless.

### External research review (2026-08, "Scientific and Algorithmic Design of Optimal Topic Review")

A long, unusually careful external document. Spot-checks passed: its FSRS-6 curve algebra is
correct (with w₂₀ = 0.1542, f ≈ 0.9804 gives R(S,S) = 0.9000), Cepeda et al. 2006's
839/317/184 figures are right, Brunmair & Richter's interleaving g = 0.42 and its similarity
moderator are right, and its sample-size claim reproduces (90 % → 91 % at 80 % power = ~13,480
per arm vs its "≈13,500"). Worth taking seriously as a result.

**Adopted:** damping the first-study prior (POLICY YADORA-3 — see CLAUDE.md).

**Rejected — "commit the recall rating BEFORE opening the source."** A grade given before you
can check yourself rates *confidence*, not *accuracy* — which is exactly the
judgment-of-learning illusion the same document warns about elsewhere. FSRS grades are
retrospective ("how did that retrieval go"), which requires seeing the material. Yadora's order
(prompt → attempt retrieval → reveal → grade) already matches FSRS semantics. The salvageable
part is UI copy making "attempt retrieval before revealing" explicit.

**Rejected for now — migrate to FSRS-6.** Its one meaningful gain over FSRS-5 is the *trainable*
decay w₂₀, and a trainable parameter only pays once trained, which needs thousands of reviews.
On default weights FSRS-6 behaves near-identically to FSRS-5, while migrating would require
keeping FSRS-5 alive anyway so existing logs still replay faithfully. Real cost, ~zero benefit
at current data volume. Revisit if the review corpus ever gets large enough to fit parameters.

**Rejected — merge by chronological replay** (user-confirmed; see CLAUDE.md).

**Not verifiable:** its FSRS-7 claims (35 parameters, benchmark table) are outside what can be
confirmed here, and its citation markers are internal tool references rather than resolvable
sources. Its own recommendation is shadow-mode-only, so nothing turns on it.

**Genuinely good v2 candidates, recorded not implemented:**
- **Content-currency guardrail** — a clock separate from memory for "you recall this perfectly,
  but the guideline changed." Uniquely apt for a medical app; independently proposed by the
  2026-07 review too (see the content-currency entry above), which strengthens it.
- **Explicit rating rubric**, especially "Hard is a SUCCESS, never a failure" — FSRS reads Hard
  as successful recall, so misuse silently lengthens intervals. Cheap copy change, directly
  protects input quality.
- **Recall anchors** (3–7 per topic) to stabilise what "remembering Appendicitis" means and vary
  the retrieval cue, without flashcard-scale authoring.
- **Capacity planning in MINUTES rather than topic count** — `reviewDurationMs` is already
  logged, so the data exists. The full min-cost planner in the document is over-engineered for
  this app; an EWMA per-topic cost plus a daily minute budget would capture most of the value.
- **Proper calibration scoring** (Brier / log-loss / ECE, reliability diagrams) instead of the
  single predicted-vs-actual line on Progress. This is what would make the 40-day field test
  actually measurable.
- **Interleaving in the daily queue** — avoid serving several near-identical topics
  consecutively. Affects queue order only, never the memory model.

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
