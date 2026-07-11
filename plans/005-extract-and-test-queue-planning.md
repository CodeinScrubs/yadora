# Plan 005: Today's due-bucket and overdue-redistribution logic is pure and unit-tested

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md`.
>
> **Drift check (run first)**: `git diff --stat <002 SHA>..HEAD -- app/src/main/java/com/example/ui/today/TodayScreen.kt`
> This plan assumes plan 002 has landed (the redistribution block calls
> `MedScheduler.priorityScore`). If `TodayScreen.kt`'s `redistributeOverdueUnits`
> still contains `var score = 0.0`, plan 002 was NOT applied — STOP and do 002 first.
> Compare the "Current state" excerpts below against the live code; on a mismatch,
> treat it as a STOP condition.

## Status

- **Priority**: P2
- **Effort**: M
- **Risk**: LOW–MED (touches the Today flows the main screen reads)
- **Depends on**: plans/002 (redistribution calls `MedScheduler.priorityScore`)
- **Category**: tests (with a small supporting extraction)
- **Planned at**: against plan 002's commit.

## Why this matters

The FSRS *math* is thoroughly tested, but the layer that decides **what the user
actually sees on the Today screen** — which topics count as overdue vs. due-today
vs. upcoming, and how the "recover overdue over 3 days" redistribution spreads
items — has zero tests. That logic lives as inline lambdas and arithmetic inside
`TodayViewModel`, coupled to the clock and Room flows, so it can't be tested where
it is. This plan lifts the pure decisions into two small testable objects
(`TodayBuckets` for the date predicates, `OverdueRedistributor` for the 3-day
spread math), wires the ViewModel to call them (no behavior change), and covers
them with unit tests. It makes the categorization boundaries and the spread math
regression-safe — and is the prerequisite that makes a future ViewModel split
(the god-object screens) safe to attempt.

## Current state

**`app/src/main/java/com/example/ui/today/TodayScreen.kt`** — `TodayViewModel`.

The four bucket flows (lines ~82–101) each filter `activeUnits` against
freshly-computed day boundaries (`startOfToday()`, `endOfToday()` are private
methods on the VM, lines ~49–61):

```kotlin
val dueUnits: StateFlow<List<StudyUnitEntity>> = repository.activeUnits.combine(dayTick) { units, _ ->
    val end = endOfToday()
    units.filter { it.nextReviewAt <= end }
}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

val overdueUnits = repository.activeUnits.combine(dayTick) { units, _ ->
    val start = startOfToday()
    units.filter { it.nextReviewAt < start }
}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

val dueTodayUnits = repository.activeUnits.combine(dayTick) { units, _ ->
    val start = startOfToday()
    val end = endOfToday()
    units.filter { it.nextReviewAt in start..end }
}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

val upcomingUnits: StateFlow<List<StudyUnitEntity>> = repository.activeUnits.combine(dayTick) { units, _ ->
    val end = endOfToday()
    units.filter { it.nextReviewAt > end }.sortedBy { it.nextReviewAt }.take(5)
}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
```

The redistribution (lines ~106–149, `redistributeOverdueUnits`). After plan 002
the ordering is already a `priorityScore` call; the SPREAD math is still inline:

```kotlin
val perDay = Math.ceil(prioritized.size / 3.0).toInt().coerceAtLeast(1)
val updated = prioritized.mapIndexed { index, unit ->
    val dayOffset = (index / perDay).coerceAtMost(2) + 1 // fill day 1, then 2, then 3
    val target = java.util.Calendar.getInstance().apply {
        timeInMillis = now
        add(java.util.Calendar.DAY_OF_YEAR, dayOffset)
        set(java.util.Calendar.HOUR_OF_DAY, 8)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    unit.copy(nextReviewAt = target, updatedAt = now)
}
```

**Convention** — plain-JUnit tests live under `app/src/test/java/com/example/...`
(see `MedSchedulerTest.kt`): no Robolectric, `org.junit.Test`,
`org.junit.Assert.*`. The new objects use only `java.util.Calendar` and
primitives, so plain JUnit works (no Android runtime needed).

## Commands you will need

| Purpose   | Command                                                                                                    | Expected           |
|-----------|------------------------------------------------------------------------------------------------------------|--------------------|
| Assemble  | `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug --console=plain`      | `BUILD SUCCESSFUL` |
| Tests     | `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --console=plain`  | `BUILD SUCCESSFUL` |

## Scope

**In scope**:
- `app/src/main/java/com/example/ui/today/QueuePlanning.kt` (create — two objects)
- `app/src/main/java/com/example/ui/today/TodayScreen.kt` (wire the four flow
  filters + the redistribution spread math to the new objects)
- `app/src/test/java/com/example/ui/today/QueuePlanningTest.kt` (create)

**Out of scope** (do NOT touch):
- The StateFlow plumbing: keep the four separate flows, `combine(dayTick)`,
  `stateIn(...)`. Only the `filter { ... }` predicate bodies change.
- `startOfToday()` / `endOfToday()` / `dayTick` — leave as is; keep calling them
  and pass their results into the predicates.
- The DB write (`updateUnitsAtomic`), event log, re-arm, widget update in
  `redistributeOverdueUnits` — unchanged.
- `ReviewSessionScreen.kt` — its ordering already uses `priorityScore` (plan 002).

## Git workflow

- Branch: `advisor/005-queue-planning`
- Commit per logical unit (add objects; wire; test) or one commit; message:
  `Extract & test Today due-bucket and overdue-redistribution logic`
  with the `Co-Authored-By: Claude <noreply@anthropic.com>` trailer.

## Steps

### Step 1: Create the pure helpers

Create `app/src/main/java/com/example/ui/today/QueuePlanning.kt`:

```kotlin
package com.example.ui.today

import java.util.Calendar

/**
 * Pure due-bucket predicates for the Today screen. Extracted from TodayViewModel's flow lambdas so
 * the overdue / due-today / upcoming boundaries are unit-testable and can't silently drift.
 */
object TodayBuckets {
    /** Due strictly before today's start = overdue. */
    fun isOverdue(nextReviewAt: Long, startOfToday: Long): Boolean = nextReviewAt < startOfToday

    /** Due at some point within today (inclusive of both ends). */
    fun isDueToday(nextReviewAt: Long, startOfToday: Long, endOfToday: Long): Boolean =
        nextReviewAt in startOfToday..endOfToday

    /** Anything due by the end of today (overdue + due-today) — the "load these now" set. */
    fun isDueByEndOfToday(nextReviewAt: Long, endOfToday: Long): Boolean = nextReviewAt <= endOfToday

    /** Not yet due today = upcoming. */
    fun isUpcoming(nextReviewAt: Long, endOfToday: Long): Boolean = nextReviewAt > endOfToday
}

/**
 * Pure planning math for the "recover overdue topics over 3 calm days" redistribution. Kept separate
 * from the DB write so the spread (how many per day, which day, what target time) can be tested.
 */
object OverdueRedistributor {
    const val RECOVERY_DAYS = 3

    /** How many items land on each recovery day so [total] items fit in RECOVERY_DAYS (min 1). */
    fun perDay(total: Int): Int =
        Math.ceil(total / RECOVERY_DAYS.toDouble()).toInt().coerceAtLeast(1)

    /** Recovery day (1..RECOVERY_DAYS) for the item at [index] in a priority-ordered list of [total]. */
    fun dayOffset(index: Int, total: Int): Int =
        (index / perDay(total)).coerceAtMost(RECOVERY_DAYS - 1) + 1

    /** Absolute due time: [dayOffset] days after [now], pinned to 08:00 local. */
    fun targetMillis(now: Long, dayOffset: Int): Long = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, dayOffset)
        set(Calendar.HOUR_OF_DAY, 8)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
```

**Verify**: `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug --console=plain` → `BUILD SUCCESSFUL`.

### Step 2: Wire the four bucket flows

In `TodayViewModel`, change ONLY the `filter { ... }` predicate bodies to call
`TodayBuckets` (keep the surrounding `combine`/`stateIn` exactly):

- `dueUnits`: `units.filter { TodayBuckets.isDueByEndOfToday(it.nextReviewAt, end) }`
- `overdueUnits`: `units.filter { TodayBuckets.isOverdue(it.nextReviewAt, start) }`
- `dueTodayUnits`: `units.filter { TodayBuckets.isDueToday(it.nextReviewAt, start, end) }`
- `upcomingUnits`: `units.filter { TodayBuckets.isUpcoming(it.nextReviewAt, end) }.sortedBy { it.nextReviewAt }.take(5)`

(`TodayBuckets` is in the same package, so no import is needed.)

**Verify**: `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug --console=plain` → `BUILD SUCCESSFUL`.

### Step 3: Wire the redistribution spread math

In `redistributeOverdueUnits`, replace the inline `perDay` + `dayOffset` +
`Calendar` target block (shown in Current state) with:

```kotlin
val total = prioritized.size
val updated = prioritized.mapIndexed { index, unit ->
    val target = OverdueRedistributor.targetMillis(now, OverdueRedistributor.dayOffset(index, total))
    unit.copy(nextReviewAt = target, updatedAt = now)
}
```

Leave the `prioritized` (priority-sorted) line above it and the
`updateUnitsAtomic(updated)` + logging/re-arm/widget lines below it unchanged.

**Verify**: `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug --console=plain` → `BUILD SUCCESSFUL`.

### Step 4: Write the tests

Create `app/src/test/java/com/example/ui/today/QueuePlanningTest.kt`:

```kotlin
package com.example.ui.today

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class QueuePlanningTest {

    private val start = 2_000_000_000_000L      // some "start of today"
    private val end = start + 86_400_000L - 1   // end of that day

    // --- TodayBuckets ---

    @Test fun overdue_is_strictly_before_start() {
        assertTrue(TodayBuckets.isOverdue(start - 1, start))
        assertFalse("exactly start is not overdue", TodayBuckets.isOverdue(start, start))
    }

    @Test fun due_today_is_inclusive_of_both_ends() {
        assertTrue(TodayBuckets.isDueToday(start, start, end))
        assertTrue(TodayBuckets.isDueToday(end, start, end))
        assertFalse(TodayBuckets.isDueToday(start - 1, start, end))
        assertFalse(TodayBuckets.isDueToday(end + 1, start, end))
    }

    @Test fun upcoming_and_due_by_end_partition_at_end_of_today() {
        assertTrue(TodayBuckets.isDueByEndOfToday(end, end))
        assertFalse(TodayBuckets.isUpcoming(end, end))
        assertTrue(TodayBuckets.isUpcoming(end + 1, end))
        assertFalse(TodayBuckets.isDueByEndOfToday(end + 1, end))
    }

    // --- OverdueRedistributor ---

    @Test fun perDay_spreads_over_three_days_and_is_at_least_one() {
        assertEquals(1, OverdueRedistributor.perDay(0))
        assertEquals(1, OverdueRedistributor.perDay(1))
        assertEquals(1, OverdueRedistributor.perDay(3))
        assertEquals(2, OverdueRedistributor.perDay(4))
        assertEquals(2, OverdueRedistributor.perDay(6))
        assertEquals(3, OverdueRedistributor.perDay(7))
    }

    @Test fun dayOffset_fills_day1_then_day2_then_day3() {
        // 6 items, 2 per day -> [1,1,2,2,3,3]
        val total = 6
        val offsets = (0 until total).map { OverdueRedistributor.dayOffset(it, total) }
        assertEquals(listOf(1, 1, 2, 2, 3, 3), offsets)
    }

    @Test fun dayOffset_never_exceeds_three() {
        assertEquals(3, OverdueRedistributor.dayOffset(1000, 6))
    }

    @Test fun targetMillis_is_dayOffset_days_ahead_at_0800_local() {
        val now = System.currentTimeMillis()
        val t = OverdueRedistributor.targetMillis(now, 2)
        val c = Calendar.getInstance().apply { timeInMillis = t }
        assertEquals(8, c.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, c.get(Calendar.MINUTE))
        assertTrue("target is in the future", t > now)
    }
}
```

**Verify**: `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --console=plain` → `BUILD SUCCESSFUL`, and `QueuePlanningTest` shows 8 passing cases in `app/build/test-results/testDebugUnitTest/`.

## Test plan

- New file `QueuePlanningTest.kt`, 8 cases: overdue/due-today/upcoming boundary
  behavior (inclusive/exclusive edges), `perDay` spread, `dayOffset` fill order,
  the 3-day cap, and `targetMillis` (08:00, future). Plain JUnit, modeled on
  `MedSchedulerTest.kt`.
- Verification: `./gradlew :app:testDebugUnitTest` → all pass, 8 new tests.
- Behavior-preservation check: the wired predicates/arithmetic reproduce the
  original inline logic exactly (`< start`, `in start..end`, `<= end`, `> end`;
  `ceil(total/3) min 1`; `(index/perDay) cap 2 +1`; 08:00 target), so existing
  behavior is unchanged.

## Done criteria

Machine-checkable. ALL must hold:

- [ ] `test -f app/src/main/java/com/example/ui/today/QueuePlanning.kt` → exists
- [ ] `grep -c "TodayBuckets\." app/src/main/java/com/example/ui/today/TodayScreen.kt` → at least `4` (all four flows wired)
- [ ] `grep -c "OverdueRedistributor\." app/src/main/java/com/example/ui/today/TodayScreen.kt` → at least `2` (dayOffset + targetMillis)
- [ ] `grep -c "Math.ceil(prioritized.size" app/src/main/java/com/example/ui/today/TodayScreen.kt` → `0` (inline spread math removed)
- [ ] `JAVA_HOME=... ./gradlew :app:testDebugUnitTest` → `BUILD SUCCESSFUL`, `QueuePlanningTest` has 8 passing cases
- [ ] `JAVA_HOME=... ./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL`
- [ ] `git status --short` shows only the 3 in-scope files changed/created
- [ ] `plans/README.md` status row for 005 updated

## STOP conditions

Stop and report back (do not improvise) if:

- `redistributeOverdueUnits` still contains `var score = 0.0` — plan 002 has not
  landed; do 002 first.
- The four flow lambdas or the redistribution block do not match the Current state
  excerpts (the ViewModel was restructured) — the wiring targets have moved.
- Any existing test fails after wiring — the extracted predicate/arithmetic does
  not reproduce the original (re-check the boundary operators: `<` vs `<=`,
  inclusive `in start..end`).

## Maintenance notes

- If the redistribution window ever changes from 3 days, edit
  `OverdueRedistributor.RECOVERY_DAYS` and update the `perDay`/`dayOffset` tests —
  the `.coerceAtMost(RECOVERY_DAYS - 1)` keeps the cap in sync automatically.
- These pure helpers are the seam that makes splitting `TodayViewModel` out of the
  700-line `TodayScreen.kt` safe later (the god-object-screens finding): the risky
  logic now has tests. A reviewer should confirm no flow changed its emitted set.
- `targetMillis` uses the device default timezone via `Calendar`; tests assert
  local 08:00, which is correct for this single-user offline app.
