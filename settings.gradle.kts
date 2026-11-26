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
//   core ◄── designsystem ◄── ui-tv, ui-mobile
//   { data, stream, designsystem } ◄── feature
//   { feature, ui-tv } ◄── feature-tv ◄── apps:androidtv / apps:firetv
//   { feature, ui-mobile } ◄── feature-mobile ◄── apps:mobile
include(":common:core") //         plain Kotlin/JVM: protocol, models, sign-in, game lists
include(":common:data") //         the API, settings, the library's state
include(":common:stream") //       WebRTC and controllers
include(":common:designsystem") // colours, icons, widgets
include(":common:ui-tv") //        the TV's own widgets: its keyboard and text field
include(":common:ui-mobile") //    the phone's own widgets
include(":common:feature") //      ViewModels, the shared app and the activity
include(":common:feature-tv") //   the TV apps' screens
include(":common:feature-mobile") // the phone and tablet app's screens

// One app per surface, as Sovereign.Android has.
include(":apps:mobile") //    phones and tablets
include(":apps:androidtv") // Android TV / Google TV
include(":apps:firetv") //    Amazon Fire TV

// Not shipped: generate the baseline profiles, one module per app driven (a test module's
// target is one project): the TV apps' from :apps:androidtv, the phone's from :apps:mobile.
include(":baselineprofile")
include(":baselineprofile-mobile")
