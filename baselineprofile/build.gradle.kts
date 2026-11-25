// The baseline-profile generator, and nothing else: a test module that drives the release-shaped
// app (R8 on, so the profile names the classes that ship) and records what its most travelled
// screens run. Nothing depends on it and CI does not run it.
//
// `./gradlew :app:generateBaselineProfile`, with an Android TV emulator of API 33 or later
// attached (no root needed there) and the app signed in on it to a server with games (see
// BaselineProfileGenerator), writes `app/src/release/generated/baselineProfiles/`. Commit those
// files; release builds compile them in, and profileinstaller installs them on first run.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "media.sovereign.sovemu.baselineprofile"
    compileSdk = 37

    defaultConfig {
        // Macrobenchmark cannot read ART profiles below 28; the app's floor is 28 anyway.
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    targetProjectPath = ":app"
}

baselineProfile {
    // Whatever device is attached, rather than a managed one.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.junit)
    implementation(libs.androidx.test.runner)
}
