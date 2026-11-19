---
paths:
  - "**/*.kt"
  - "**/*.kts"
---

# SovEmu.Android

- **`core` stays plain Kotlin/JVM.** Anything testable without Android (the wire protocol, API
  models, parsing) goes there, with JUnit tests. `app` only wires it to Android and Compose.
- **The wire protocol is shared.** `core/.../Protocol.kt` mirrors `SovEmu.Protocol/src/input.rs`
  and `SovEmu.Web/src/lib/protocol.ts` byte for byte; `ProtocolTest` uses the same vectors.
  Change all three in the same release.
- **Controllers follow SovEmu's `docs/CONTROLLERS.md`** (IDs, families, positional input names,
  keyboard `KeyboardEvent.code` names, defaults, hotkeys after mapping, one player per
  controller), implemented in `core/.../Controllers.kt`. Change it with the web and iOS clients.
  By default gamepad buttons map by position (bottom face = RetroPad B, right = A); Android's
  `KEYCODE_BUTTON_A` is the bottom face.
- **While a game is playing, controller events go to the game**, not to Compose focus
  (`MainActivity.dispatchKeyEvent`), except while the in-game menu or the controllers screen is
  open (`Controller.toGame`), which must stay usable with a D-pad. Back always opens the
  in-game menu.
- Signalling is one POST of the complete offer (wait for ICE gathering), as on the web.
- **GraphQL through Apollo.** Every operation is written once in
  `core/src/main/graphql/operations.graphql` and generated into typed Kotlin against
  `schema.graphqls`, a copy of SovEmu's `SovEmu.Server/schema.graphql`: refresh it with
  `./gradlew :core:syncSchema` when the server's schema changes. Browsing models are the generated
  fragments (`Game` = `GameFields` …); the controller models stay hand-written and convert in
  `Models.kt`. Never write a query string or decode API JSON by hand.
- **Navigation 3.** The back stack is a list of `Route`s in `MainActivity`, shown by `NavDisplay`
  (which keeps each entry's saved state); system Back pops it. Screens get callbacks, not the stack.
- Versions live in `gradle/libs.versions.toml`. Before a PR: `./gradlew :core:test :app:assembleDebug`.
