package media.sovereign.sovemu.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType

/** One of the apps under `apps/`: a thin shell over `:common:feature`. */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")
            val android = extensions.getByType<ApplicationExtension>()
            configureSovEmuAndroid(android)
            android.defaultConfig.targetSdk = SovEmuSdk.TARGET
            android.defaultConfig.versionCode = 1
            android.defaultConfig.versionName = "0.1.0"

            // Native code (WebRTC's, ~12 MB an ABI) only for 64-bit ARM and the x86_64
            // emulator, so a debug build merges and packages two ABIs rather than four.
            // `-Pabi=armeabi-v7a` (a 32-bit device such as a Fire TV Stick; a comma-separated
            // list for several) or `-Pabi=all` builds others. Android Studio's Run already
            // packages only the ABI of the device it installs on. Release keeps every ABI.
            val abi = providers.gradleProperty("abi").getOrElse("arm64-v8a,x86_64")
            android.buildTypes.getByName("debug") {
                if (abi != "all") ndk.abiFilters += abi.split(',').map(String::trim)
            }
            android.buildTypes.getByName("release") {
                // Compose is much faster shrunk and optimised by R8 than in a debug build:
                // install this one to use the app (assembleRelease), the debug build to debug
                // it. Signed with the debug key so it sideloads; a store build would bring its
                // own key. The keep rules travel with the libraries that need them.
                isMinifyEnabled = true
                isShrinkResources = true
                proguardFiles(android.getDefaultProguardFile("proguard-android-optimize.txt"))
                signingConfig = android.signingConfigs.getByName("debug")
            }
        }
    }
}
