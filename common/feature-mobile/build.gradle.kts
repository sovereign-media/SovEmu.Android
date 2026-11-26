// The phone and tablet app's screens, as Sovereign.Android's `:common:feature-mobile`: drawn
// for touch, over `:common:feature`'s ViewModels and shared app.
plugins {
    id("sovemu.android.library")
    id("sovemu.android.compose")
}

android {
    namespace = "media.sovereign.sovemu.feature.mobile"
}

dependencies {
    api(project(":common:feature"))
    implementation(project(":common:ui-mobile"))
    // The browser sign-in: the server's own page in a Custom Tab.
    implementation(libs.androidx.browser)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.hilt.lifecycle.viewmodel.compose)
    implementation(libs.coil.compose)
    implementation(libs.navigation3.runtime)
    testImplementation(libs.junit)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    debugImplementation(libs.compose.ui.test.manifest)
}
