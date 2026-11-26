// The phone and tablet app's baseline-profile generator, and nothing else: a test module that
// drives the release-shaped app (R8 on, so the profile names the classes that ship) and records
// what its most travelled screens run. A second module because `targetProjectPath` names one
// project, and the phone draws its own screens (:common:feature-mobile), so the TV's profile
// misses them. Nothing depends on it and CI does not run it.
//
// `./gradlew :apps:mobile:generateBaselineProfile`, with a phone emulator of API 33 or later
// attached (no root needed there; set ANDROID_SERIAL when other devices are) and the app signed
// in on it to a server with games (see BaselineProfileGenerator), writes
// `apps/mobile/src/release/generated/baselineProfiles/`. Commit those files; profileinstaller
// installs them on first run.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "media.sovereign.sovemu.baselineprofile.mobile"
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

    targetProjectPath = ":apps:mobile"
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
