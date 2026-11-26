// The server, on Android: the Apollo client and its sign-in (`SovEmuApi`), the app's settings
// and the sealed refresh token (`Settings`, `KeystoreSealer`), the library's state (`Store`),
// game sessions (`Sessions`) and client events. No Compose and no WebRTC.
plugins {
    id("sovemu.android.library")
    id("sovemu.android.hilt")
}

android {
    namespace = "media.sovereign.sovemu.data"
}

dependencies {
    api(project(":common:core"))
    api(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
