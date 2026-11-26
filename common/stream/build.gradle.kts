// A game's stream: the WebRTC session (`Stream`), its video view (`StreamView`), the decoder
// factory that asks for low latency, and the controllers and their input (`Controller`,
// `InputPresence`). The only module that pulls in WebRTC.
plugins {
    id("sovemu.android.library")
}

android {
    namespace = "media.sovereign.sovemu.stream"
    defaultConfig {
        // WebRTC is called from native code (JNI) by name: kept in every app that shrinks.
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    api(project(":common:data"))
    // A pinned binary dependency: move it deliberately, with an emulator check of a session.
    api(libs.webrtc)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
