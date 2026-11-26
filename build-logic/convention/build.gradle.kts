plugins {
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // compileOnly: the root build puts the plugins themselves on the classpath (`apply false`).
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("jvmLibrary") {
            id = "sovemu.jvm.library"
            implementationClass = "media.sovereign.sovemu.buildlogic.JvmLibraryConventionPlugin"
        }
        register("androidLibrary") {
            id = "sovemu.android.library"
            implementationClass = "media.sovereign.sovemu.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidApplication") {
            id = "sovemu.android.application"
            implementationClass =
                "media.sovereign.sovemu.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidCompose") {
            id = "sovemu.android.compose"
            implementationClass = "media.sovereign.sovemu.buildlogic.AndroidComposeConventionPlugin"
        }
    }
}
