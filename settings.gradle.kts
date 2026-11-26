pluginManagement {
    // The convention plugins (sovemu.android.library and the rest): see build-logic/.
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SovEmu.Android"
// Layered; a module depends only on those above it:
//
//   core ◄── data ◄── stream
//   core ◄── designsystem
//   { data, stream, designsystem } ◄── feature ◄── apps:mobile / apps:androidtv / apps:firetv
include(":common:core") //         plain Kotlin/JVM: protocol, models, sign-in, game lists
include(":common:data") //         the API, settings, the library's state
include(":common:stream") //       WebRTC and controllers
include(":common:designsystem") // colours, icons, widgets
include(":common:feature") //      the screens and the activity

// One app per surface, as Sovereign.Android has.
include(":apps:mobile") //    phones and tablets
include(":apps:androidtv") // Android TV / Google TV
include(":apps:firetv") //    Amazon Fire TV

// Not shipped: generates the TV apps' baseline profile from :apps:androidtv.
include(":baselineprofile")
