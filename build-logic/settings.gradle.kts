// The convention plugins (`sovemu.android.library` and the rest), an included build: they must
// be compiled before the build that applies them is configured, which a subproject cannot be.
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
include(":convention")
