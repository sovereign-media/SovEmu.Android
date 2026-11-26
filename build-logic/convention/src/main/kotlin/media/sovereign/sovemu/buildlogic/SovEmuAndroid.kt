package media.sovereign.sovemu.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/** The SDK levels and Java version every module builds with. */
internal object SovEmuSdk {
    const val COMPILE = 37

    // Android 9: the floor for the decoders and the low-latency APIs the stream uses.
    const val MIN = 28
    const val TARGET = 36
    val JAVA: JavaVersion = JavaVersion.VERSION_21
}

/** What an Android application and an Android library share. */
internal fun Project.configureSovEmuAndroid(android: CommonExtension) {
    android.compileSdk = SovEmuSdk.COMPILE
    android.defaultConfig.minSdk = SovEmuSdk.MIN
    android.compileOptions.sourceCompatibility = SovEmuSdk.JAVA
    android.compileOptions.targetCompatibility = SovEmuSdk.JAVA
    // Compose UI and focus tests run on the JVM under Robolectric (no emulator in CI).
    android.testOptions.unitTests.isIncludeAndroidResources = true
}

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")
