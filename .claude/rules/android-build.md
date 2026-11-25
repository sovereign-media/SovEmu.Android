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
- `core` applies only `kotlin.jvm` and Apollo, with `jvmToolchain(21)`: never give it an Android
  or Compose dependency.
- **CI** (`.github/workflows/ci.yml`, JDK 21) runs exactly the PR command:
  `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug`. Keep the two in step.
- **Performance is judged on the R8 release build** (`assembleRelease`), compiled with its profile
  (`adb shell cmd package compile -m speed-profile`), or with Macrobenchmark / `dumpsys gfxinfo`;
  never on a debug build.
- **The release build ships a Baseline Profile** (`app/src/release/generated/baselineProfiles/`,
  committed; `profileinstaller` installs it on first run). `:baselineprofile` generates it:
  sign the debug build in on an Android TV emulator of API 33+ (no root needed) to a server with
  games, then `./gradlew :app:generateBaselineProfile`. The run uninstalls the app, so sign in
  again before the next one. A green run can still be a thin walk: check the file names
  `HomeKt` and `SystemKt`. Regenerate when the walk's screens change a lot.
- The WebRTC library is a pinned binary dependency: move its version deliberately, with an
  emulator check of a game session.
