// The TV apps' screens (Android TV and Fire TV), as Sovereign.Android's `:common:feature-tv`:
// drawn for a D-pad from the sofa, over `:common:feature`'s ViewModels and shared app.
plugins {
    id("sovemu.android.library")
    id("sovemu.android.compose")
}

android {
    namespace = "media.sovereign.sovemu.feature.tv"
}

dependencies {
    api(project(":common:feature"))
    implementation(project(":common:ui-tv"))
    implementation(libs.navigation3.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.hilt.lifecycle.viewmodel.compose)
    implementation(libs.coil.compose)
    testImplementation(libs.junit)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    debugImplementation(libs.compose.ui.test.manifest)
}
