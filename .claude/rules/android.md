---
paths:
  - "**/*.kt"
  - "**/*.graphql"
  - "**/*.graphqls"
---

# SovEmu.Android

One app for phones, tablets and Android TV (Compose, `androidx.compose.material3` only, minSdk
28). How code is written is in [android-kotlin.md](android-kotlin.md) and
[android-compose.md](android-compose.md); tests in [android-tests.md](android-tests.md); the
build in [android-build.md](android-build.md). Paths into SovEmu below are from the parent
repository's root.

## Modules

- **`core` stays plain Kotlin/JVM** (no Android, no Compose). Anything testable without Android
  goes there with JUnit tests: the wire protocol, API models and client, sign-in, parsing,
  controllers, game lists, latency maths. `app` only wires it to Android and Compose.
- In `app`: `Store` holds the library's state (`StateFlow`s of `Load<T>`: `Loading`, `Ready`,
  `Failed`), `Stream` and `StreamView` the WebRTC session, `Controller` the pads, and `ui/` the
  screens.

## Shared with the other clients

- **The wire protocol** (`core/.../Protocol.kt`) mirrors `SovEmu.Backend/SovEmu.Protocol/src/input.rs`,
  `SovEmu.Web/src/lib/protocol.ts` and SovEmu.iOS's `Protocol.swift` byte for byte; `ProtocolTest`
  uses the same vectors. Change them all in the same release.
- **Controllers follow SovEmu's `docs/CONTROLLERS.md`** (IDs, families, positional input names,
  keyboard `KeyboardEvent.code` names, defaults, hotkeys after mapping, one player per
  controller), implemented in `core/.../Controllers.kt`. Change it with the web and iOS clients.
  Gamepad buttons map by position (bottom face = RetroPad B, right = A); Android's
  `KEYCODE_BUTTON_A` is the bottom face.
- **Signalling** is one `sessionOffer` mutation carrying the complete offer (non-trickle: wait for
  ICE gathering), as on the other clients.
- **Sign-in URLs and client ids come from `bootstrapConfig`**, never hard-coded.

## GraphQL through Apollo

Every operation is written once in `core/src/main/graphql/operations.graphql` and generated into
typed Kotlin against `schema.graphqls`, a copy of `SovEmu.Backend/SovEmu.Server/schema.graphql`:
refresh it with `./gradlew :core:syncSchema` when the server's schema changes. Browsing models are
the generated fragments, named by typealias in `core/.../Models.kt` (`Game` = `GameFields` …): a
new fragment gets one there. The controller models stay hand-written and convert in `Models.kt`.
Never write a query string or decode GraphQL JSON by hand (the OAuth token response in `Auth.kt`
is not GraphQL, and is read with Apollo's `JsonReader`).

## Navigation and input

- **The back stack is ours**: a `mutableStateListOf<Route>` in `MainActivity`, shown by
  Navigation 3's `NavDisplay` (which keeps each entry's saved state under `Route.key`). There is
  no NavController or Navigation-Compose. System Back pops it, and pushing a route already on
  the stack pops back to it. Screens get a `Nav` of callbacks, never the stack.
- **While a game is playing, controller events go to the game**, not to Compose focus
  (`MainActivity.dispatchKeyEvent`), except while the in-game menu or the controllers screen is
  open (`Controller.toGame`), which must stay usable with a D-pad. Once the game is up, Back opens
  the in-game menu; before then there is nothing to resume, and Back leaves.

## Before a PR

`./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug`, which is what CI runs. Check
TV and controller behaviour on an emulator.
