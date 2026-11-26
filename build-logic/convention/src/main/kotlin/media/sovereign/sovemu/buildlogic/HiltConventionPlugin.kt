package media.sovereign.sovemu.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * A module with injected classes, Hilt modules or entry points, as Sovereign.Android's
 * `com.sovereign.android.hilt`: KSP, the Hilt Gradle plugin, the runtime and the compiler. Apply
 * it after `sovemu.android.library` or `.application` (the Hilt plugin looks for the Android
 * plugin as it is applied). A module with nothing injected must not: it would pay for a KSP pass
 * on every build for nothing.
 */
class HiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.google.devtools.ksp")
            pluginManager.apply("com.google.dagger.hilt.android")
            dependencies.add("implementation", libs.findLibrary("hilt-android").get())
            dependencies.add("ksp", libs.findLibrary("hilt-compiler").get())
        }
    }
}
