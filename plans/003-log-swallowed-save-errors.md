# Plan 003: A failed topic save is logged to `crash.log` instead of vanishing silently

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md`.
>
> **Drift check (run first)**: `git diff --stat <001 SHA>..HEAD -- app/src/main/java/com/example/ui/add/AddUnitScreen.kt`
> If it changed since plan 001's commit, compare the "Current state" excerpt below
> against the live code; on a mismatch, treat it as a STOP condition.

## Status

- **Priority**: P2
- **Effort**: S
- **Risk**: LOW
- **Depends on**: plans/001 (git baseline for drift check)
- **Category**: correctness
- **Planned at**: against plan 001's initial commit.

## Why this matters

This app has no backend, so its entire debugging story for problems on a friend's
or user's phone is the local `crash.log` file (written by the uncaught-exception
handler in `MedReviewApplication`) plus the analytics export that ships it. The
"Add / Edit topic" save path defeats that: it wraps the whole DB write in
`try { ... true } catch (e: Exception) { false }` and, on failure, returns `false`
which shows the user a generic error toast — but the exception itself is
**swallowed with no logging**. A save that fails (disk full, a constraint
violation, a migration edge) produces zero diagnostic trace anywhere. Writing the
throwable to the same `crash.log` before returning `false` makes these failures
visible without changing the user-facing behavior.

## Current state

**`app/src/main/java/com/example/ui/add/AddUnitScreen.kt`, lines ~89–158**
(inside `AddUnitViewModel.saveUnit`). The relevant wrapper:

```kotlin
val ok = try {
kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
    val current = existingUnit
    if (current != null) {
        // ... update / replay path ...
    } else {
        // ... insert path ...
    }
}
    true
} catch (e: Exception) {
    false
}
if (ok) onSaved() else onError()
```

The `catch (e: Exception) { false }` is the swallow — `e` is captured but never
logged.

**The existing crash-log mechanism** to mirror —
`app/src/main/java/com/example/MedReviewApplication.kt`, lines ~20–36, appends
crashes to `files/crash.log`, bounded to ~200 KB, with a timestamp + version +
device header:

```kotlin
val f = java.io.File(filesDir, "crash.log")
if (f.length() > 200_000) {
    val tail = f.readText().takeLast(100_000)
    f.writeText(tail)
}
f.appendText(
    "=== ${java.util.Date()} · v${com.example.BuildConfig.VERSION_NAME} · " +
        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · SDK ${android.os.Build.VERSION.SDK_INT}\n" +
        android.util.Log.getStackTraceString(throwable) + "\n"
)
```

**How the ViewModel reaches a Context**: `AddUnitViewModel` extends
`androidx.lifecycle.AndroidViewModel` OR takes the repository only — you MUST
check which. Open the class declaration near the top of `AddUnitScreen.kt`
(search for `class AddUnitViewModel`). If it is `AndroidViewModel(app)`, use
`getApplication<android.app.Application>().filesDir`. If it only holds a
`repository` and has NO Application, do NOT fabricate one — see STOP conditions;
the fallback is to log via `android.util.Log.e` instead of the file.

## Commands you will need

| Purpose   | Command                                                                                                    | Expected           |
|-----------|------------------------------------------------------------------------------------------------------------|--------------------|
| Assemble  | `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug --console=plain`      | `BUILD SUCCESSFUL` |
| Tests     | `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --console=plain`  | `BUILD SUCCESSFUL` |

## Scope

**In scope**:
- `app/src/main/java/com/example/ui/add/AddUnitScreen.kt` — only the
  `catch (e: Exception)` block in `saveUnit`.

**Out of scope** (do NOT touch):
- The `NonCancellable` block, the insert/update/replay logic inside `try`, or the
  `onSaved()`/`onError()` calls — behavior for the user is unchanged; only the
  catch gains logging.
- `MedReviewApplication.kt` — do not refactor the crash-log writer into a shared
  helper in this plan (tempting, but out of scope; note it in Maintenance).

## Git workflow

- Branch: `advisor/003-log-save-errors`
- One commit; message: `Log swallowed topic-save exceptions to crash.log`
  with the `Co-Authored-By: Claude <noreply@anthropic.com>` trailer.
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Determine how the ViewModel gets a Context

Open `app/src/main/java/com/example/ui/add/AddUnitScreen.kt`, find
`class AddUnitViewModel`. Note whether it extends `AndroidViewModel` (has an
`Application`) or plain `ViewModel` with just a repository.

- If `AndroidViewModel`: you can write to `files/crash.log` (step 2A).
- If plain `ViewModel` with no Application reachable: use `android.util.Log.e`
  only (step 2B) — do not add an Application dependency in this plan.

### Step 2A: Log the throwable to crash.log (AndroidViewModel case)

Replace the catch block so it appends the exception to the same file the crash
handler uses, then still returns `false`:

```kotlin
} catch (e: Exception) {
    // No backend: the only place a save failure can surface is the local crash log that ships in
    // the analytics export. Record it (best-effort) before showing the generic error, so a failed
    // save isn't invisible. User-facing behavior is unchanged: we still return false -> onError().
    runCatching {
        val f = java.io.File(getApplication<android.app.Application>().filesDir, "crash.log")
        if (f.length() > 200_000) f.writeText(f.readText().takeLast(100_000))
        f.appendText(
            "=== ${java.util.Date()} · SAVE FAILED · v${com.example.BuildConfig.VERSION_NAME}\n" +
                android.util.Log.getStackTraceString(e) + "\n"
        )
    }
    false
}
```

### Step 2B: Log via Logcat only (plain ViewModel case)

If there is no Application, use:

```kotlin
} catch (e: Exception) {
    // Surface the failure in Logcat at least; user-facing behavior unchanged (still onError()).
    android.util.Log.e("AddUnitViewModel", "Topic save failed", e)
    false
}
```

### Step 3: Build

**Verify**:
- `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug --console=plain` → `BUILD SUCCESSFUL`
- `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --console=plain` → `BUILD SUCCESSFUL`

## Test plan

No new automated test — the failure path (a DB write throwing) is not cheaply
reproducible in a unit test without heavy mocking of Room, and the change is
logging-only with unchanged control flow. Verification is: it compiles, existing
tests still pass, and the catch block no longer discards `e` (grep in Done
criteria). If the maintainer later wants coverage, a Robolectric test could
inject a repository stub whose `insertUnit` throws and assert `crash.log` grew;
note this in Maintenance rather than doing it here.

## Done criteria

Machine-checkable. ALL must hold:

- [ ] `grep -n "catch (e: Exception)" app/src/main/java/com/example/ui/add/AddUnitScreen.kt` shows the block, and the following lines reference `e` (either `getStackTraceString(e)` or `Log.e(..., e)`)
- [ ] `grep -A2 "catch (e: Exception)" app/src/main/java/com/example/ui/add/AddUnitScreen.kt` does NOT show a bare `false` on the immediately following line (i.e. logging was added)
- [ ] `JAVA_HOME=... ./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL`
- [ ] `JAVA_HOME=... ./gradlew :app:testDebugUnitTest` → `BUILD SUCCESSFUL`
- [ ] `git status --short` shows only `AddUnitScreen.kt` changed
- [ ] `plans/README.md` status row for 003 updated

## STOP conditions

Stop and report back (do not improvise) if:

- The `try { ... } catch (e: Exception) { false }` wrapper in `saveUnit` does not
  match the Current state excerpt (the save path was restructured).
- `AddUnitViewModel` is a plain `ViewModel` AND you cannot reach `android.util.Log`
  either (extremely unlikely) — report instead of adding new dependencies.
- There is more than one `catch (e: Exception) { false }` in the file and it is
  ambiguous which belongs to `saveUnit` — report the line numbers.

## Maintenance notes

- The crash-log writing code now appears in two places (`MedReviewApplication`
  and here). A future cleanup could extract a `CrashLog.append(context, header,
  throwable)` helper; deliberately deferred to keep this change minimal and
  low-risk.
- If a Robolectric test is later added for the failure path, model it after
  `ReplayEqualsLiveTest` (in-memory Room) with a repository whose write throws.
- Reviewer should confirm user-facing behavior is unchanged: the method still
  returns `false` and calls `onError()` on failure; only a best-effort log was
  added.
