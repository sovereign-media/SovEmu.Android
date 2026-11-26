package media.sovereign.sovemu.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType

/** An Android library under `common/`. AGP 9 compiles its Kotlin itself (built-in Kotlin). */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            configureSovEmuAndroid(extensions.getByType<LibraryExtension>())
        }
    }
}
