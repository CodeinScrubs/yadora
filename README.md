# Yadora

A spaced-review **topic scheduler** for students (Persian: یادورا) — Android, Kotlin + Jetpack Compose, fully offline, no account or backend.

You log a topic after studying it. On its study date, Yadora asks how difficult it was and how well you understood it; later reviews ask how well you recalled it. An **FSRS-6** memory model — conformance-tested against the reference implementation, py-fsrs 6.3.1 — turns those ratings into an adaptive next review date. Understanding runs on a separate, shorter repair clock, so a shaky understanding brings a topic back sooner without distorting the memory estimate. The model estimates a useful schedule; it cannot know the exact moment an individual topic will be forgotten.

Data lives in the on-device Room database. Android's phone-to-phone transfer carries the full study history to a new device; Android cloud backup carries settings only. A full JSON backup and a diagnostics/research export are available from Settings.

Languages: English, فارسی (right-to-left, Persian digits, Jalali calendar), Deutsch.

- **[CLAUDE.md](CLAUDE.md)** — current engineering state and every settled design decision. Start here.
- **[DESIGN.md](DESIGN.md)** — the original product and technical design write-up (partly historical).
- **[PUBLISHING.md](PUBLISHING.md)** — how to sign and ship a release.

## Run locally

**Prerequisites:** [Android Studio](https://developer.android.com/studio)

1. Open Android Studio → **Open** → choose this project directory.
2. Let Gradle sync finish (it downloads dependencies on first run; this can take a few minutes once).
3. Run the app on an emulator or device with the green ▶ **app** button.

No API keys or signing setup required — it builds and runs out of the box using Android's default debug signing.

## Tests and checks

JVM unit tests cover the scheduler math, the py-fsrs golden vectors, replay == live, database migrations, backup round-trip, and Persian/German date and text handling:

```
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

From a plain terminal on Windows, point `JAVA_HOME` at Android Studio's bundled JDK first (see [CLAUDE.md](CLAUDE.md)).

Every push and pull request to `main` runs the same gate on GitHub Actions ([`.github/workflows/android-ci.yml`](.github/workflows/android-ci.yml)): unit tests, lint, and debug + R8-minified release builds.
