# SovEmu.Android

The Android client for [SovEmu](https://github.com/sovereign-media/SovEmu): sign in to a SovEmu
server, pick a system and a game, and play it streamed over WebRTC with a gamepad or the touch
controller.

Checked out as a submodule of `SovEmu` (unpinned, `branch = main`); it builds on its own.

## Modules

| Module | What |
|---|---|
| `core` | Plain Kotlin/JVM: the input wire protocol (`Protocol.kt`), controller IDs, input names, mappings, hotkeys and players (`Controllers.kt`, after SovEmu's `docs/CONTROLLERS.md`), the GraphQL query documents and response decoding (`GraphQL.kt`, against the server's `schema.graphql`) and the API models. Its tests use the same byte vectors as `SovEmu.Protocol` (Rust) and `SovEmu.Web` (TypeScript). |
| `app` | Jetpack Compose UI (sign-in, system carousel, game list, player), OkHttp for the API (`POST /graphql`), and Stream's libwebrtc build for the stream. |

## Building

Needs JDK 21 and the Android SDK (platform 37). Put the SDK path in `local.properties`
(`sdk.dir=…`), then:

```bash
./gradlew :core:test :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Using it

1. First-run setup (the admin account) happens in a browser at the server's address.
2. In the app, enter the server address (for example `http://192.168.1.20:8080`) and sign in.
3. The server must advertise an address the phone can reach (`SOVEMU_ADVERTISE_IP`, the host's
   LAN address), with UDP 50000-50009 open.

**Controls:**
- **Gamepads:** each controller is its own player (the lowest free of 1-4, in the order they
  connect; a disconnected one frees its player). Buttons map by position by default, as on the
  web client (bottom face = B, right face = A); the left stick doubles as the D-pad on 2D systems.
- **Keyboard:** arrows, Z/X (B/A), A/S (Y/X), Q/W (L/R), Enter (Start), Shift (Select); Tab
  fast-forwards and Backspace rewinds. The keyboard and the touch controls are player 1.
- **Controllers** (in-game menu): move any device to another player for the session, and
  rebind a device's buttons by pressing an input on it (Back or Escape cancels). Mappings are
  saved on the server per user and controller model, so they follow you to the web and iOS
  clients (`docs/CONTROLLERS.md` in SovEmu). Without one of your own, a controller uses the
  server's default for its model (set by an admin) or the built-in one; "Reset to defaults"
  removes yours and goes back to whichever the server has in force.
- **In-game menu:** Back, or Select+Start.
- **Fast-forward:** hold Select+R.
- **Rewind:** hold Select+L.
- **Touch controller:** shown by default; hide it from the menu.
