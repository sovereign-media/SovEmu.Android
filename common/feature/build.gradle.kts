// The screens, shared by every app: sign-in, home, a system's games, search, the player and its
// menus, controllers, the screensaver; Navigation 3 over the back stack (`Nav`, `Route`), and
// `SovEmuActivity`, which each app's MainActivity extends. Draws with `:common:designsystem`.
plugins {
    id("sovemu.android.library")
    id("sovemu.android.compose")
    id("sovemu.android.hilt")
}

android {
    namespace = "media.sovereign.sovemu.feature"
}

dependencies {
    api(project(":common:data"))
    api(project(":common:stream"))
    api(project(":common:designsystem"))
    // Until each surface has its own screens: the shared ones type with the TV's keyboard.
    api(project(":common:ui-tv"))
    implementation(libs.androidx.core.ktx)
    api(libs.androidx.activity.compose)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.lifecycle.viewmodel.navigation3)
    implementation(libs.hilt.lifecycle.viewmodel.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    debugImplementation(libs.compose.ui.test.manifest)
}
