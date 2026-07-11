# MedReview

A spaced-repetition **study-review scheduler** for medical students — Android, Kotlin + Jetpack Compose, fully offline (no backend, no login).

You log a topic after you study it and rate how hard it was and how well you understood it. The app uses the **FSRS** memory model to schedule when to review it next, so you revisit each topic right before you'd forget it — and reminds you when it's due.

See **[DESIGN.md](DESIGN.md)** for the full product/technical design and roadmap.

## Run locally

**Prerequisites:** [Android Studio](https://developer.android.com/studio)

1. Open Android Studio → **Open** → choose this project directory.
2. Let Gradle sync finish (it downloads dependencies on first run; this can take a few minutes once).
3. Run the app on an emulator or device with the green ▶ **app** button.

No API keys or signing setup required — it builds and runs out of the box using Android's default debug signing.

## Run the tests

The scheduling engine is covered by JVM unit tests:

```
./gradlew :app:testDebugUnitTest
```

Or in Android Studio: right-click `app/src/test/java/com/example/domain/srs` → **Run**.
