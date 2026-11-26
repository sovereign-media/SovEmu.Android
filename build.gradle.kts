plugins {
    alias(libs.plugins.spotless)
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.apollo) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
}

// One format for every Kotlin file: ktlint in the Android Studio style, 100 columns, as
// .editorconfig says. `./gradlew spotlessApply` formats; CI runs `spotlessCheck`.
spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**", "build-logic/.kotlin/**")
        ktlint(libs.versions.ktlint.get())
            .setEditorConfigPath(rootProject.file(".editorconfig"))
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**", "build-logic/.kotlin/**")
        ktlint(libs.versions.ktlint.get())
            .setEditorConfigPath(rootProject.file(".editorconfig"))
    }
}
