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
    implementation(libs.navigation3.runtime)
}
