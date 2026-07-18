# Yadora

A spaced-review **topic scheduler** for students — Android, Kotlin + Jetpack Compose, with no app backend or login.

You log a topic after studying it. On its study date, Yadora asks for an initial difficulty and understanding rating; later reviews ask how well you recalled it. An FSRS-5-derived model uses those ratings to choose an adaptive next review date. The model estimates a useful schedule; it does not know the exact moment an individual topic will be forgotten.

Data is stored in the on-device Room database. The current Android configuration also opts the database and preferences into the operating system's encrypted Auto Backup / device-transfer mechanisms; manual JSON backup and analytics export are available from Settings.

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
