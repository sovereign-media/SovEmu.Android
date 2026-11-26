plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // Folds the profile :baselineprofile generates into release builds.
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "media.sovereign.sovemu"
    compileSdk = 37

    defaultConfig {
        applicationId = "media.sovereign.sovemu"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            // Native code (WebRTC's, ~12 MB an ABI) only for 64-bit ARM and the x86_64
            // emulator, so a debug build merges and packages two ABIs rather than four.
            // `-Pabi=armeabi-v7a` (a 32-bit device such as a Fire TV Stick; a comma-separated
            // list for several) or `-Pabi=all` builds others. Android Studio's Run already
            // packages only the ABI of the device it installs on. Release keeps every ABI.
            val abi = providers.gradleProperty("abi").getOrElse("arm64-v8a,x86_64")
            if (abi != "all") ndk.abiFilters += abi.split(',').map(String::trim)
        }
        release {
            // Compose is much faster shrunk and optimised by R8 than in a debug build: install
            // this one to use the app (assembleRelease), the debug build to debug it. Signed with
            // the debug key so it sideloads; a store build would bring its own key.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }

    // Compose UI and focus tests run on the JVM under Robolectric (no emulator in CI).
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":core"))
    // Installs the shipped baseline profile on first run, so a sideloaded app starts compiled.
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.webrtc)
    implementation(libs.apollo.runtime)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    testImplementation(libs.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    debugImplementation(platform(libs.compose.bom))
    debugImplementation(libs.compose.ui.test.manifest)
}
