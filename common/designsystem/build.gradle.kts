// The look shared by every screen and both forms (TV and touch): colours and the theme
// (`Ui.kt`), the Lucide icons, focus, and the widgets that know nothing of the server (buttons,
// chips, filter fields, the side panel, the TV keyboard and text field, the quality chip, the
// pad diagram). Material 3 only; no API, no WebRTC.
plugins {
    id("sovemu.android.library")
    id("sovemu.android.compose")
}

android {
    namespace = "media.sovereign.sovemu.designsystem"
}

dependencies {
    api(project(":common:core"))
    // The BOM goes with Compose's unversioned artifacts to every module above.
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    testImplementation(libs.junit)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    debugImplementation(libs.compose.ui.test.manifest)
}
