// Amazon Fire TV: the Android TV app with a manifest for Fire OS.
// A thin shell: its manifest and a MainActivity that says which form it is; the rest is in
// common/.
plugins {
    id("sovemu.android.application")
    id("sovemu.android.hilt")
}

android {
    namespace = "media.sovereign.sovemu.firetv"
    defaultConfig {
        applicationId = "media.sovereign.sovemu.firetv"
    }

    // The Android TV app's profile: the same screens and classes. A generator of this app's
    // own would replace it.
    sourceSets.getByName("main").baselineProfiles.directories.add(
        "../androidtv/src/release/generated/baselineProfiles",
    )
}

dependencies {
    implementation(project(":common:feature"))
    // Installs the shipped baseline profile on first run, so a sideloaded app starts compiled.
    implementation(libs.androidx.profileinstaller)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
