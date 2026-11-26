---
paths:
  - "**/*.gradle.kts"
  - "gradle/**"
  - "gradle.properties"
  - ".github/**"
  - "**/*.pro"
---

# SovEmu.Android: build and CI

- **Versions live in `gradle/libs.versions.toml`**, never inline in a build file. Remove a
  dependency, and its catalog entry, once nothing uses it.
- **Modules are configured by the convention plugins in `build-logic/`** (`sovemu.jvm.library`,
  `sovemu.android.library`, `sovemu.android.application`, `sovemu.android.compose`,
  `sovemu.android.hilt`): SDK levels,
  Java 21, the release build and the debug ABIs are set there once, never in a module's build
  file. A module's build file names its namespace and its dependencies.
- **`sovemu.android.hilt` only where something is injected** (today `:common:data`,
  `:common:feature` and the apps), after the Android plugin: each costs a KSP pass on every build.
- `:common:core` applies only `sovemu.jvm.library` and Apollo: never give it an Android or
  Compose dependency.
- **CI** (`.github/workflows/ci.yml`, JDK 21) runs exactly the PR command:
  `./gradlew spotlessCheck :common:core:test testDebugUnitTest` and `assembleDebug` of the three
  apps, written out in android.md's *Before a PR*. Keep the two in step.
- **The pre-push hook** (`.githooks/pre-push`; once per clone, `git config core.hooksPath
  .githooks`) runs `spotlessCheck` on any pushed commit that changes Kotlin or Gradle, so the
  format never fails CI. Never skip it with `--no-verify` to get a misformatted change up.
- **`gradle.properties` keeps the build fast**: the build cache, parallel modules and the
  configuration cache are on; check a new property against AGP's deprecations before adding it.
  Debug builds package native code only for `arm64-v8a` and `x86_64` (`-Pabi=` for others, or
  `all`); release keeps every ABI. Iterate on debug, never on release.
- **Performance is judged on the R8 release build** (`assembleRelease`), compiled with its profile
  (`adb shell cmd package compile -m speed-profile`), or with Macrobenchmark / `dumpsys gfxinfo`;
  never on a debug build.
- **The release builds ship Baseline Profiles**, one per surface, committed:
  `apps/androidtv/src/release/generated/baselineProfiles/` (the Fire TV app compiles in the same
  files) and `apps/mobile/src/release/generated/baselineProfiles/`; `profileinstaller` installs
  them on first run. `:baselineprofile` generates the TV's and `:baselineprofile-mobile` the
  phone's (a test module drives one app). Sign the app's debug build in on an emulator of API 33+
  of its kind (no root needed) to a server with games, then
  `ANDROID_SERIAL=<emulator> ./gradlew :apps:androidtv:generateBaselineProfile` (or
  `:apps:mobile:`): without `ANDROID_SERIAL` it runs on every attached device, Fire TVs included.
  The run uninstalls the app, so sign in again before the next one. A green run can still be a
  thin walk: check the file names `HomeKt`, `SystemKt` and `SystemPartsKt`. Regenerate when the
  walk's screens change a lot, or classes move between files.
- The WebRTC library is a pinned binary dependency: move its version deliberately, with an
  emulator check of a game session.
