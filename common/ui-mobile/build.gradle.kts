// The phone and tablet's own widgets, as Sovereign.Android's `:common:ui-mobile`: drawn for
// touch. Only the phone app's screens use it.
plugins {
    id("sovemu.android.library")
    id("sovemu.android.compose")
}

android {
    namespace = "media.sovereign.sovemu.ui.mobile"
}

dependencies {
    api(project(":common:designsystem"))
}
