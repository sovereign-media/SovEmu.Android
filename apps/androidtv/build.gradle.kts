// Android TV and Google TV: the D-pad, the TV keyboard, sign-in with a code.
// A thin shell: its manifest and a MainActivity that says which form it is; the rest is in
// common/.
plugins {
    id("sovemu.android.application")
    // Folds the profile :baselineprofile generates into release builds.
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "media.sovereign.sovemu.tv"
    defaultConfig {
        applicationId = "media.sovereign.sovemu.tv"
    }
}

dependencies {
    implementation(project(":common:feature"))
    // Installs the shipped baseline profile on first run, so a sideloaded app starts compiled.
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
