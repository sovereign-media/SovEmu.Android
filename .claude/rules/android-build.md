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
  `sovemu.android.library`, `sovemu.android.application`, `sovemu.android.compose`): SDK levels,
  Java 21, the release build and the debug ABIs are set there once, never in a module's build
  file. A module's build file names its namespace and its dependencies.
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
- **The release builds ship a Baseline Profile**
  (`apps/androidtv/src/release/generated/baselineProfiles/`, committed; the Fire TV and phone
  apps compile in the same files; `profileinstaller` installs it on first run).
  `:baselineprofile` generates it: sign the Android TV app's debug build in on an Android TV
  emulator of API 33+ (no root needed) to a server with games, then
  `./gradlew :apps:androidtv:generateBaselineProfile`. The run uninstalls the app, so sign in
  again before the next one. A green run can still be a thin walk: check the file names
  `HomeKt` and `SystemKt`. Regenerate when the walk's screens change a lot.
- The WebRTC library is a pinned binary dependency: move its version deliberately, with an
  emulator check of a game session.
