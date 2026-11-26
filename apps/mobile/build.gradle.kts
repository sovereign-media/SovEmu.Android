// Phones and tablets: touch, the touch controller, the browser sign-in.
// A thin shell: its manifest and a MainActivity that says which form it is; the rest is in
// common/.
plugins {
    id("sovemu.android.application")
    id("sovemu.android.hilt")
    // Folds the profile :baselineprofile-mobile generates into release builds.
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "media.sovereign.sovemu.mobile"
    defaultConfig {
        applicationId = "media.sovereign.sovemu.mobile"
    }
}

dependencies {
    implementation(project(":common:feature-mobile"))
    // Installs the shipped baseline profile on first run, so a sideloaded app starts compiled.
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile-mobile"))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
