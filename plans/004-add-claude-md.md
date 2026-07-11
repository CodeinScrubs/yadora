# Plan 004: A `CLAUDE.md` captures build commands and the project's settled decisions

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md`.
>
> **Drift check (run first)**: `git diff --stat <001 SHA>..HEAD -- app/build.gradle.kts DESIGN.md`
> If `app/build.gradle.kts` changed since plan 001, re-read the `applicationId`,
> `versionName`, and `versionCode` from it and use the live values in the file you
> write (do not trust the excerpts below if they conflict with the live file).

## Status

- **Priority**: P2
- **Effort**: S
- **Risk**: NONE (new doc file; touches no code)
- **Depends on**: plans/001 (git baseline for drift check)
- **Category**: docs / dx
- **Planned at**: against plan 001's initial commit.

## Why this matters

The maintainer develops this app almost entirely with AI assistants, session
after session. Each new session re-derives the same facts — how to build, why the
package is `com.example` despite the app id being `com.yadora.app`, why "Forgot"
isn't red, why the due model is day-granular — and repeatedly re-proposes changes
the maintainer has already deliberately rejected (moving the first rating to the
Add screen, making mean-reversion target "Good", etc.). A `CLAUDE.md` at the repo
root is loaded automatically into every Claude Code session; capturing the build
commands and the *settled* decisions there stops the rework and the re-litigation.
This is the highest-leverage doc for this specific workflow.

## Current state

- No `CLAUDE.md` exists at the repo root.
- `DESIGN.md` (root, ~11 KB) holds the product/design write-up — read it to
  confirm the design facts below, but `CLAUDE.md` is the short operational index,
  not a copy of `DESIGN.md`.
- Key build facts, verified this session:
  - JDK: `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"`
  - Tests: `./gradlew :app:testDebugUnitTest` (currently 54 passing)
  - APK: `./gradlew :app:assembleDebug`
  - Lint: `./gradlew :app:lintDebug` (0 errors)
- From `app/build.gradle.kts` (confirm live values in the drift check):
  `applicationId = "com.yadora.app"`, `namespace = "com.example"`,
  `versionName = "1.0"`, `versionCode = 3`.
- Test layout: unit tests in `app/src/test/java/com/example/...` (JUnit +
  Robolectric); the FSRS/scheduler math is heavily covered
  (`FsrsTest`, `MedSchedulerTest`, `RegressionTest`, `ReplayEqualsLiveTest`).

## Commands you will need

| Purpose | Command | Expected |
|---------|---------|----------|
| Tests   | `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --console=plain` | `BUILD SUCCESSFUL` |

(No code is compiled by this plan; the command is only to confirm the documented
build instruction actually works on this machine.)

## Scope

**In scope**: create `CLAUDE.md` at the repo root.

**Out of scope**: any source or config file; `DESIGN.md` (leave as is); the memory
files under `.claude/`.

## Git workflow

- Branch: `advisor/004-claude-md`
- One commit; message: `Add CLAUDE.md with build commands and settled decisions`
  plus the `Co-Authored-By: Claude <noreply@anthropic.com>` trailer.

## Steps

### Step 1: Verify the build command actually works

Run `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --console=plain`.

**Verify**: `BUILD SUCCESSFUL`. (If it fails, STOP — the documented command would
be wrong; report the actual failure.)

### Step 2: Confirm the identity values from the live build file

Open `app/build.gradle.kts` and read the actual `applicationId`, `namespace`,
`versionName`, `versionCode`. Use those live values in the file below if they
differ from the Current state excerpt.

### Step 3: Write `CLAUDE.md`

Create `CLAUDE.md` at the repo root with exactly this content (substituting the
live identity values from step 2 if they changed):

```markdown
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
  fuzz). This is the tested core — keep it pure and covered.
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
- **Room migrations are additive only** (`MIGRATION_1_2/2_3/3_4`,
  `exportSchema=true`). Never `fallbackToDestructiveMigration`.
- `USE_EXACT_ALARM` is intended; only strip it if publishing rules require it.

## Testing

Unit tests: `app/src/test/java/com/example/...` (JUnit; Robolectric where a
Context/Room is needed). The scheduler math, replay==live, backup round-trip,
and Persian date conversion are covered. When changing `MedScheduler` or `Fsrs`,
run the suite — those invariants are load-bearing.

## Reference

- `DESIGN.md` — full product/design write-up and rationale.
- `plans/` — advisor-generated implementation plans (if present).
```

**Verify**: the file exists — `test -f CLAUDE.md && echo ok` → `ok`.

## Test plan

No automated test. Verification is: the file exists, is valid Markdown, and the
build command it documents was confirmed to work in step 1.

## Done criteria

Machine-checkable. ALL must hold:

- [ ] `test -f CLAUDE.md && echo ok` → `ok`
- [ ] `grep -c "com.yadora.app" CLAUDE.md` → at least `1`
- [ ] `grep -c "REVIEW screen, not the Add screen" CLAUDE.md` → `1` (the settled-decisions section is present)
- [ ] `JAVA_HOME=... ./gradlew :app:testDebugUnitTest` still `BUILD SUCCESSFUL` (documented command verified)
- [ ] `git status --short` shows only `CLAUDE.md` added
- [ ] `plans/README.md` status row for 004 updated

## STOP conditions

Stop and report back if:

- The documented test command does NOT produce `BUILD SUCCESSFUL` on this machine
  (the JDK path or task name has changed) — report the real command/output so the
  doc is correct.
- The live `applicationId` in `app/build.gradle.kts` is NOT `com.yadora.app` —
  report it; the identity section must reflect reality.

## Maintenance notes

- Keep the "Settled decisions" list in sync with reality: if the maintainer ever
  reverses one of these, update or remove the bullet so the doc doesn't fossilize
  a stale rule.
- When new invariants get a regression test, add a one-line pointer under Testing.
- This file is loaded into every Claude Code session automatically; keep it short
  and operational — deep rationale belongs in `DESIGN.md`.
