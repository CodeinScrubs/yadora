# Plan 002: A single tested `MedScheduler.priorityScore()` replaces the duplicated queue-ordering formula

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md`.
>
> **Drift check (run first)**: `git diff --stat <001 SHA>..HEAD -- app/src/main/java/com/example/ui/review/ReviewSessionScreen.kt app/src/main/java/com/example/ui/today/TodayScreen.kt app/src/main/java/com/example/domain/srs/MedScheduler.kt`
> If any of these changed since plan 001's commit, compare the "Current state"
> excerpts below against the live code before proceeding; on a mismatch, treat it
> as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW
- **Depends on**: plans/001 (needs a git baseline for the drift check; the code
  change itself is independent)
- **Category**: tech-debt (with new test coverage)
- **Planned at**: against plan 001's initial commit.

## Why this matters

The formula that ranks due topics by importance is **copy-pasted in two places**.
The review session uses it to decide which topics survive the daily cap; the
Today screen uses it to decide which overdue topics get recovered on day 1 of a
3-day redistribution. The code comment in the Today copy literally claims it is
"the same score the review queue uses" — but nothing enforces that. If someone
tunes the weights in one place (say, raise the high-yield boost), the two screens
silently disagree: the queue shows one order, the redistribution promises another.
The logic is also completely untested. Extracting one pure function, calling it
from both sites, and unit-testing it removes the drift risk and adds the first
real coverage of the prioritization behavior.

## Current state

Two byte-identical scoring blocks:

**`app/src/main/java/com/example/ui/review/ReviewSessionScreen.kt`, lines ~134–147**
(inside `ReviewSessionViewModel.loadNext`, building the capped due queue):

```kotlin
val prioritized = units.sortedByDescending { u ->
    var score = 0.0
    if (u.highYield) score += 100.0
    score += when (u.state) {
        "NeedsRelearn" -> 80.0
        "Learning" -> 40.0
        "Building" -> 20.0
        else -> 0.0
    }
    score += u.lapseCount * 10.0
    val overdueDays = (now - u.nextReviewAt) / 86400000.0
    if (overdueDays > 0) score += overdueDays * 5.0
    score
}
dueUnits.addAll(prioritized.take(limit))
```
Here `now` is a `val now = System.currentTimeMillis()` declared a few lines above
(line ~129), and `units` is `List<StudyUnitEntity>`.

**`app/src/main/java/com/example/ui/today/TodayScreen.kt`, lines ~114–127**
(inside `TodayViewModel.redistributeOverdueUnits`):

```kotlin
val prioritized = overdueList.sortedByDescending { u ->
    var score = 0.0
    if (u.highYield) score += 100.0
    score += when (u.state) {
        "NeedsRelearn" -> 80.0
        "Learning" -> 40.0
        "Building" -> 20.0
        else -> 0.0
    }
    score += u.lapseCount * 10.0
    val overdueDays = (now - u.nextReviewAt) / 86400000.0
    if (overdueDays > 0) score += overdueDays * 5.0
    score
}
```
Here `now` is `val now = System.currentTimeMillis()` at line ~111, and
`overdueList` is `List<StudyUnitEntity>`.

**Where the function will live** —
`app/src/main/java/com/example/domain/srs/MedScheduler.kt` is an `object` holding
all scheduling logic. It already exposes pure functions like `masteryState(...)`
(lines ~278–283) and `effectiveReviewNumber(...)` (lines ~291–294). Add the new
function next to them, at the same indentation (4 spaces, inside `object MedScheduler`).

**The entity fields used** (from `StudyUnitEntity`): `highYield: Boolean`,
`state: String`, `lapseCount: Int`, `nextReviewAt: Long`. To keep the domain
layer free of any dependency on the persistence entity, the function takes these
as primitive parameters, not the entity.

**Test convention** — `app/src/test/java/com/example/domain/srs/MedSchedulerTest.kt`
is a plain JUnit test (no Robolectric): `class MedSchedulerTest` with
`@Test fun name() { ... assertEquals/assertTrue }`, importing
`org.junit.Assert.assertEquals`, `org.junit.Assert.assertTrue`, `org.junit.Test`.
Match this exactly for the new test file.

## Commands you will need

| Purpose   | Command                                                                                                          | Expected           |
|-----------|------------------------------------------------------------------------------------------------------------------|--------------------|
| Tests     | `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --console=plain`        | `BUILD SUCCESSFUL` |
| Assemble  | `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug --console=plain`            | `BUILD SUCCESSFUL` |

(Git Bash from the repo root. PowerShell: set `$env:JAVA_HOME=...` first, then
`./gradlew ...`.)

## Scope

**In scope**:
- `app/src/main/java/com/example/domain/srs/MedScheduler.kt` (add function)
- `app/src/main/java/com/example/ui/review/ReviewSessionScreen.kt` (call it)
- `app/src/main/java/com/example/ui/today/TodayScreen.kt` (call it)
- `app/src/test/java/com/example/domain/srs/PriorityScoreTest.kt` (create)

**Out of scope** (do NOT touch):
- The `.take(limit)` capping, the `perDay`/redistribution math, or any DB write
  around these blocks — only the `sortedByDescending { ... }` lambda body changes.
- The `now` declarations — keep them; pass `now` into the new function.
- Any weight VALUE — this is a pure extraction; the numbers must stay identical
  (100 / 80 / 40 / 20 / 0 / ×10 / ×5), so behavior is provably unchanged.

## Git workflow

- Branch: `advisor/002-priority-score`
- One commit is fine; message style (match repo):
  `Extract duplicated queue-priority formula into MedScheduler.priorityScore`
  with the `Co-Authored-By: Claude <noreply@anthropic.com>` trailer.
- Do NOT push or open a PR unless the operator instructs it.

## Steps

### Step 1: Add the pure function to `MedScheduler`

In `app/src/main/java/com/example/domain/srs/MedScheduler.kt`, add this function
inside the `object MedScheduler` body (place it directly ABOVE
`fun masteryState(` near line ~278):

```kotlin
/**
 * Ordering score for the due queue and the overdue-redistribution plan. Higher = review sooner /
 * recover first. SINGLE source of truth for "which items matter most": the review-session daily cap
 * and the Today redistribution both call this, so their notion of priority can never drift apart.
 * Weights are deliberately coarse and additive: importance dominates, then how weak/overdue it is.
 */
fun priorityScore(
    highYield: Boolean,
    state: String,
    lapseCount: Int,
    nextReviewAt: Long,
    now: Long,
): Double {
    var score = 0.0
    if (highYield) score += 100.0
    score += when (state) {
        "NeedsRelearn" -> 80.0
        "Learning" -> 40.0
        "Building" -> 20.0
        else -> 0.0
    }
    score += lapseCount * 10.0
    val overdueDays = (now - nextReviewAt) / 86400000.0
    if (overdueDays > 0) score += overdueDays * 5.0
    return score
}
```

**Verify**: `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug --console=plain` → `BUILD SUCCESSFUL` (compiles with the new function unused so far).

### Step 2: Call it from the review queue

In `app/src/main/java/com/example/ui/review/ReviewSessionScreen.kt`, replace the
`sortedByDescending { u -> ... score }` lambda body (the block shown in Current
state) with a single call. The result must read:

```kotlin
val prioritized = units.sortedByDescending { u ->
    com.example.domain.srs.MedScheduler.priorityScore(u.highYield, u.state, u.lapseCount, u.nextReviewAt, now)
}
dueUnits.addAll(prioritized.take(limit))
```

Use the fully-qualified `com.example.domain.srs.MedScheduler` (it compiles whether
or not the file already imports the class). Do NOT change the `now` line or
`.take(limit)`.

**Verify**: `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug --console=plain` → `BUILD SUCCESSFUL`

### Step 3: Call it from the Today redistribution

In `app/src/main/java/com/example/ui/today/TodayScreen.kt`, replace the
`sortedByDescending { u -> ... score }` lambda body with:

```kotlin
val prioritized = overdueList.sortedByDescending { u ->
    com.example.domain.srs.MedScheduler.priorityScore(u.highYield, u.state, u.lapseCount, u.nextReviewAt, now)
}
```

Leave everything after it (the `perDay`, `mapIndexed`, `updateUnitsAtomic`) exactly
as is.

**Verify**: `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug --console=plain` → `BUILD SUCCESSFUL`

### Step 4: Write the unit test

Create `app/src/test/java/com/example/domain/srs/PriorityScoreTest.kt`:

```kotlin
package com.example.domain.srs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the single source-of-truth queue/redistribution ordering score. */
class PriorityScoreTest {

    private val now = 1_000_000_000_000L
    private val day = 86_400_000L

    @Test fun high_yield_dominates_ordinary_items() {
        val hy = MedScheduler.priorityScore(highYield = true, state = "New", lapseCount = 0, nextReviewAt = now, now = now)
        val normal = MedScheduler.priorityScore(highYield = false, state = "Building", lapseCount = 3, nextReviewAt = now - 2 * day, now = now)
        assertTrue("high-yield ($hy) outranks a non-high-yield item ($normal)", hy > normal)
    }

    @Test fun state_weights_are_ordered_relearn_gt_learning_gt_building_gt_other() {
        fun s(state: String) = MedScheduler.priorityScore(false, state, 0, now, now)
        assertTrue(s("NeedsRelearn") > s("Learning"))
        assertTrue(s("Learning") > s("Building"))
        assertTrue(s("Building") > s("Strong"))
        assertEquals("unknown/Strong state adds nothing", 0.0, s("Strong"), 1e-9)
    }

    @Test fun more_lapses_raise_the_score() {
        val few = MedScheduler.priorityScore(false, "New", 1, now, now)
        val many = MedScheduler.priorityScore(false, "New", 5, now, now)
        assertEquals("each lapse adds 10", 40.0, many - few, 1e-9)
    }

    @Test fun more_overdue_raises_the_score_but_future_due_does_not() {
        val onTime = MedScheduler.priorityScore(false, "New", 0, now, now)
        val overdue5 = MedScheduler.priorityScore(false, "New", 0, now - 5 * day, now)
        val future = MedScheduler.priorityScore(false, "New", 0, now + 5 * day, now)
        assertEquals("5 days overdue adds 25", 25.0, overdue5 - onTime, 1e-9)
        assertEquals("not-yet-due items get no overdue bonus", 0.0, future, 1e-9)
    }

    @Test fun score_is_pure_and_deterministic() {
        val a = MedScheduler.priorityScore(true, "Learning", 2, now - day, now)
        val b = MedScheduler.priorityScore(true, "Learning", 2, now - day, now)
        assertEquals(a, b, 0.0)
    }
}
```

**Verify**: `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --console=plain` → `BUILD SUCCESSFUL`, and the test report at `app/build/test-results/testDebugUnitTest/` includes `PriorityScoreTest` with 5 passing cases.

## Test plan

- New file `PriorityScoreTest.kt`, 5 cases: high-yield dominance, state-weight
  ordering, lapse contribution, overdue-vs-future behavior, determinism.
- Modeled structurally after `MedSchedulerTest.kt` (plain JUnit, no Robolectric).
- Verification: `./gradlew :app:testDebugUnitTest` → all pass, 5 new tests present.

## Done criteria

Machine-checkable. ALL must hold:

- [ ] `grep -c "var score = 0.0" app/src/main/java/com/example/ui/review/ReviewSessionScreen.kt app/src/main/java/com/example/ui/today/TodayScreen.kt` → `0` in both files (the inline formula is gone)
- [ ] `grep -c "fun priorityScore" app/src/main/java/com/example/domain/srs/MedScheduler.kt` → `1`
- [ ] `grep -rc "priorityScore" app/src/main/java/com/example/ui/` → the call appears in both screen files
- [ ] `JAVA_HOME=... ./gradlew :app:testDebugUnitTest` → `BUILD SUCCESSFUL`, `PriorityScoreTest` has 5 passing cases
- [ ] `JAVA_HOME=... ./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL`
- [ ] `git status --short` shows only the 4 in-scope files changed/created
- [ ] `plans/README.md` status row for 002 updated

## STOP conditions

Stop and report back (do not improvise) if:

- The two scoring blocks in Current state do NOT match the live code (someone
  already changed a weight — the two copies may have already diverged, which
  changes this from a pure extraction into a behavior decision the maintainer
  must make).
- After extraction, any existing test fails — the extraction was supposed to be
  behavior-preserving, so a failure means the values were transcribed wrong.
- `now` is not in scope at one of the call sites (the surrounding code changed) —
  report rather than inventing a new time source.

## Maintenance notes

- Anyone tuning prioritization now edits ONE function. If a new mastery `state`
  value is added, add its weight to the `when` here and to `PriorityScoreTest`.
- A reviewer should confirm the extracted weights are identical to the originals
  (this PR must not change behavior) and that no call site kept a stray copy.
- Plan 005 (product-scheduling tests) builds on this: its redistribution planner
  will call `MedScheduler.priorityScore`, so this must land first.
