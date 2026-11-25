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
  never on a debug build. The app ships no Baseline Profile of its own yet; one (Baseline Profile
  plugin + Macrobenchmark, covering startup, scrolling a system's games and starting a game) is
  the next step when startup or scrolling needs work.
- The WebRTC library is a pinned binary dependency: move its version deliberately, with an
  emulator check of a game session.
