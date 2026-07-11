# Plan 001: The project is under git version control with a clean first commit

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md`.
>
> **Drift check (run first)**: there is no VCS yet, so there is no SHA to diff.
> Instead confirm the starting condition: run `git rev-parse --is-inside-work-tree`
> from the repo root. If it prints `true`, a git repo ALREADY exists — this plan
> is already partly done; STOP and report what `git status` and `git log --oneline -5`
> show instead of re-initializing.

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: dx
- **Planned at**: no VCS baseline yet — this plan establishes it.

## Why this matters

The project is ~8,500 lines of hand-tuned Kotlin refined across roughly ten
review rounds, and it is **not under version control** — a `.gitignore` exists
but there is no `.git/` directory. One bad edit, one accidental delete, or one
botched refactor is currently unrecoverable, and there is no way to diff, bisect,
or review changes. Establishing git is the single highest-leverage safety step
and a prerequisite for every future plan's drift check (which diffs against a
commit SHA). This plan creates the repo and one clean baseline commit; it does
NOT push anywhere or add a remote.

## Current state

- Repo root: `C:\Users\Shayan\Desktop\Work\12_review_app_like_anki`
- A `.gitignore` already exists at the root (253 bytes). **Read it first** — it
  may already cover the build dirs. If it does not exclude `.gradle/`, `build/`,
  `app/build/`, and `local.properties`, the commit could balloon with generated
  artifacts and secrets, so step 2 verifies this before committing.
- Build output lives in `build/`, `app/build/`, and `.gradle/` (all generated,
  must never be committed).
- `local.properties` (if present) holds the machine's SDK path — machine-specific,
  never committed.
- There is no `keystore`/`*.jks` file in the tree yet; if one appears later it
  must be gitignored (a signing key must never enter history).

## Commands you will need

| Purpose        | Command                                                                                              | Expected on success        |
|----------------|------------------------------------------------------------------------------------------------------|----------------------------|
| Is repo?       | `git rev-parse --is-inside-work-tree`                                                                 | `false`/error before, `true` after init |
| Init           | `git init`                                                                                            | `Initialized empty Git repository` |
| Status         | `git status --short`                                                                                  | lists tracked candidates   |
| Check ignores  | `git status --porcelain \| grep -E "build/\|\.gradle/\|local.properties" \| head`                    | **no output** (all ignored)|
| Tests          | `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --console=plain` | `BUILD SUCCESSFUL`         |

(The gradle command is the exact one used in this repo; run it from the repo
root in Git Bash. In PowerShell, set the env var first:
`$env:JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"; ./gradlew :app:testDebugUnitTest --console=plain`.)

## Scope

**In scope**:
- Creating `.git/` via `git init` (repo root).
- Appending to `.gitignore` ONLY if the verification in step 2 shows a generated
  path is not already ignored.
- Creating the first commit.

**Out of scope** (do NOT do):
- Do NOT add a remote, push, or create a GitHub repo.
- Do NOT change any source file, build config, or the app's behavior.
- Do NOT reformat `.gitignore` or reorder its existing lines — only append.
- Do NOT commit `local.properties`, any `*.jks`/`*.keystore`, `.gradle/`, or any
  `build/` directory.

## Git workflow

This plan *is* the git bootstrap. Use `main` as the initial branch. One commit.
Commit message (matches the co-author convention this project uses elsewhere):

```
Initial commit: Yadora study-review scheduler (v1.0 baseline)

Kotlin + Jetpack Compose + Room offline spaced-repetition app.
Baseline captured before further refactoring.

Co-Authored-By: Claude <noreply@anthropic.com>
```

## Steps

### Step 1: Confirm there is no repo yet, then initialize

Run `git rev-parse --is-inside-work-tree`. If it errors or prints `false`,
proceed. If it prints `true`, STOP (see Drift check).

Then run `git init` and `git branch -M main` (rename the initial branch to `main`
in case the git default differs).

**Verify**: `git rev-parse --is-inside-work-tree` → `true`

### Step 2: Verify generated files are ignored BEFORE staging

Read the existing `.gitignore`. Then run:

`git add -A --dry-run | grep -E "(^|/)(build|\.gradle)/|local\.properties|\.jks$|\.keystore$"`

**Expected**: no output — meaning none of these would be staged.

If any such path DOES appear, append the missing entries to `.gitignore` (append
only — do not touch existing lines). The standard Android set is:
```
.gradle/
build/
app/build/
local.properties
*.jks
*.keystore
```
Re-run the dry-run grep until it returns no output.

**Verify**: `git add -A --dry-run | grep -E "(^|/)(build|\.gradle)/|local\.properties|\.jks$|\.keystore$"` → no output

### Step 3: Stage and create the baseline commit

Run `git add -A`, then commit with the message from the Git workflow section
(use a heredoc or `-m` per line).

**Verify**: `git log --oneline -1` → shows one commit with the subject line
`Initial commit: Yadora study-review scheduler (v1.0 baseline)`

### Step 4: Confirm the working tree is clean and the build still passes

**Verify**:
- `git status --short` → no output (clean tree)
- `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --console=plain` → `BUILD SUCCESSFUL` (git added no source changes, so this must still pass)

## Test plan

No code changed, so no new tests. The verification is: tests still pass
(`BUILD SUCCESSFUL`), the tree is clean, and generated/secret paths are untracked
(`git ls-files | grep -E "build/|\.gradle/|local.properties|\.jks$"` returns
nothing).

## Done criteria

Machine-checkable. ALL must hold:

- [ ] `git rev-parse --is-inside-work-tree` exits 0 and prints `true`
- [ ] `git log --oneline` shows exactly one commit
- [ ] `git ls-files | grep -E "(^|/)build/|\.gradle/|local\.properties|\.jks$|\.keystore$"` returns no matches
- [ ] `git status --short` is empty
- [ ] `JAVA_HOME=... ./gradlew :app:testDebugUnitTest` still prints `BUILD SUCCESSFUL`
- [ ] `plans/README.md` status row for 001 updated

## STOP conditions

Stop and report back (do not improvise) if:

- A git repository already exists (`git rev-parse --is-inside-work-tree` is `true`
  before step 1).
- After `git add -A --dry-run`, a `*.jks`/`*.keystore` or `local.properties`
  would be staged and you cannot make `.gitignore` exclude it.
- `git ls-files` after committing shows any `build/` or `.gradle/` path (generated
  artifacts got committed) — the commit must be redone.

## Maintenance notes

- Every subsequent plan (002–005) uses `git diff --stat <SHA>..HEAD` for drift
  detection; that only works once this commit exists. Record this commit's SHA
  (`git rev-parse --short HEAD`) in `plans/README.md` so later plans can be
  stamped against it.
- When the user later creates a release keystore for Play Store signing, confirm
  `*.jks`/`*.keystore` is gitignored BEFORE the file is created — a signing key in
  history is a permanent leak.
- Consider a `.github/workflows/` CI job later that runs
  `./gradlew :app:testDebugUnitTest :app:lintDebug` on push; out of scope here.
