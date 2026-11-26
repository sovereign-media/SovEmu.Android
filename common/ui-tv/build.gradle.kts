// The TV's own widgets, as Sovereign.Android's `:common:ui-tv`: the app's on-screen keyboard
// along the bottom of the screen (Fire TV's covers all of it) and the text field that types with
// it. Only the TV apps' screens use it.
plugins {
    id("sovemu.android.library")
    id("sovemu.android.compose")
}

android {
    namespace = "media.sovereign.sovemu.ui.tv"
}

dependencies {
    api(project(":common:designsystem"))
    implementation(libs.androidx.activity.compose)
    testImplementation(libs.junit)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    debugImplementation(libs.compose.ui.test.manifest)
}
