// Phones and tablets: touch, the touch controller, the browser sign-in.
// A thin shell: its manifest and a MainActivity that says which form it is; the rest is in
// common/.
plugins {
    id("sovemu.android.application")
    id("sovemu.android.hilt")
}

android {
    namespace = "media.sovereign.sovemu.mobile"
    defaultConfig {
        applicationId = "media.sovereign.sovemu.mobile"
    }

    // The Android TV app's profile: the same screens and classes. A generator of this app's
    // own would replace it.
    sourceSets.getByName("main").baselineProfiles.directories.add(
        "../androidtv/src/release/generated/baselineProfiles",
    )
}

dependencies {
    implementation(project(":common:feature-mobile"))
    // Installs the shipped baseline profile on first run, so a sideloaded app starts compiled.
    implementation(libs.androidx.profileinstaller)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
