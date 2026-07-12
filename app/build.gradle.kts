plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.kotlin.serialization)
}

// Emit Room schema JSON per version so migrations can be validated/tested and reviewed in git.
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    // The install/store identity. PERMANENT once anyone installs — changing it later = a different app
    // = total data loss for existing users. Set to the real brand now, before any friend installs.
    // (The `namespace` above is only the R/BuildConfig package; it can stay `com.example` harmlessly.)
    applicationId = "com.yadora.app"
    minSdk = 26
    targetSdk = 36
    // versionName = the human-readable version users see ("v1.0"). Bump it however you like.
    // versionCode = the machine version; MUST strictly increase by at least 1 on EVERY release you
    // hand to anyone, or Android refuses to install the update over the old one.
    versionCode = 3
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // Only wire release signing when the credentials actually exist. Otherwise assembleRelease/bundleRelease
  // fails EARLY with a clear message instead of dying mid-build on a null password or a missing keystore
  // (or, worse, quietly depending on a file that only exists on one machine).
  val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
  val hasReleaseSigning = file(keystorePath).exists() &&
    System.getenv("STORE_PASSWORD") != null && System.getenv("KEY_PASSWORD") != null

  signingConfigs {
    if (hasReleaseSigning) {
      create("release") {
        storeFile = file(keystorePath)
        storePassword = System.getenv("STORE_PASSWORD")
        keyAlias = "upload"
        keyPassword = System.getenv("KEY_PASSWORD")
      }
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      if (hasReleaseSigning) {
        signingConfig = signingConfigs.getByName("release")
      }
      // No signing config otherwise: the release artifact builds UNSIGNED and Android Studio's own
      // "Generate Signed Bundle/APK" wizard (or env vars KEYSTORE_PATH/STORE_PASSWORD/KEY_PASSWORD)
      // provides the identity at release time.
    }
    debug {
      // Uses Android's default debug signing (~/.android/debug.keystore), auto-created by Android Studio.
      // No custom keystore needed, so the app builds and runs out of the box.
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

// (The template's Secrets Gradle Plugin was removed: this app is fully offline and has no API keys.)

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.vico.compose)
  implementation(libs.vico.compose.m3)
  implementation(libs.vico.core)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
}
