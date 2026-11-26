// The TV apps' baseline-profile generator, and nothing else: a test module that drives the
// release-shaped app (R8 on, so the profile names the classes that ship) and records what its
// most travelled screens run. The phone's is :baselineprofile-mobile. Nothing depends on it and
// CI does not run it.
//
// `./gradlew :apps:androidtv:generateBaselineProfile`, with an Android TV emulator of API 33 or
// later attached (no root needed there; set ANDROID_SERIAL when other devices are) and the app
// signed in on it to a server with games (see BaselineProfileGenerator), writes
// `apps/androidtv/src/release/generated/baselineProfiles/`. Commit those files; the Android TV
// and Fire TV release builds compile them in, and profileinstaller installs them on first run.
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

    targetProjectPath = ":apps:androidtv"
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
