package media.sovereign.sovemu.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType

/** Compose in an Android module: the compiler plugin, and the BOM for every configuration. */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            pluginManager.withPlugin("com.android.base") {
                extensions.getByType<CommonExtension>().buildFeatures.compose = true
            }
            val bom = libs.findLibrary("compose-bom").get().get()
            listOf("implementation", "testImplementation", "debugImplementation").forEach {
                dependencies.add(it, dependencies.platform(bom))
            }
        }
    }
}
