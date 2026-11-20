# SovEmu.Android

The Android client for [SovEmu](https://github.com/sovereign-media/SovEmu): sign in to a SovEmu
server, pick a system and a game, and play it streamed over WebRTC with a gamepad or the touch
controller.

Checked out as a submodule of `SovEmu` (unpinned, `branch = main`); it builds on its own.

## Modules

| Module | What |
|---|---|
| `core` | Plain Kotlin/JVM: the input wire protocol (`Protocol.kt`), controller IDs, input names, mappings, hotkeys and players (`Controllers.kt`, after SovEmu's `docs/CONTROLLERS.md`), the GraphQL operations (`src/main/graphql/operations.graphql`, generated into typed Kotlin by Apollo from a copy of the server's `schema.graphql`), the sign-in (`Auth.kt`: OAuth 2 password and refresh-token grants as the public client `sovemu-native`, one refresh at a time, the bearer interceptor), and the game-list logic (`GameList.kt`). Its tests use the same byte vectors as `SovEmu.Protocol` (Rust) and `SovEmu.Web` (TypeScript). |
| `app` | Navigation 3 over a back stack of routes, and Jetpack Compose UI after the web client's (sign-in; home with the featured hero and rows of games to continue, systems and new arrivals; a system's cover grid with sort, filters, A–Z jump and a game panel; the player), Apollo Kotlin over OkHttp for the API (`POST /graphql`), Coil for artwork, and Stream's libwebrtc build for the stream. |

## Building

Needs JDK 21 and the Android SDK (platform 37). Put the SDK path in `local.properties`
(`sdk.dir=…`), then:

```bash
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug   # what CI runs
```

To use the app, install the release build: R8 makes Compose several times faster than a debug
build, which matters on a TV. It is signed with the debug key so it sideloads over a debug
install. A sideloaded app starts out interpreted; compile it with its bundled profiles once:

```bash
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell cmd package compile -f -m speed-profile media.sovereign.sovemu
```

### The API schema

`core/src/main/graphql/schema.graphqls` is a copy of SovEmu's `SovEmu.Server/schema.graphql`
(this repository builds on its own). When the server's schema changes, refresh it from the SovEmu
checkout this is a submodule of, and the build tells you which operations no longer fit:

```bash
./gradlew :core:syncSchema :core:test
```

## Using it

1. First-run setup (the admin account) happens in a browser at the server's address.
2. In the app, enter the server address (for example `http://192.168.1.20:8080`) and sign in. The app keeps
   the sign-in's refresh token in its private preferences (not backed up) and the access token in
   memory; signing out revokes the sign-in on the server.
3. The server must advertise an address the phone can reach (`SOVEMU_ADVERTISE_IP`, the host's
   LAN address), with UDP 50000-50009 open.

**Controls:**
- **Gamepads:** each controller is its own player (the lowest free of 1-4, in the order they
  connect; a disconnected one frees its player). Buttons map by position by default, as on the
  web client (bottom face = B, right face = A); the left stick doubles as the D-pad on 2D systems.
- **Keyboard:** arrows, Z/X (B/A), A/S (Y/X), Q/W (L/R), Enter (Start), Shift (Select); Tab
  fast-forwards and Backspace rewinds. The keyboard and the touch controls are player 1.
- **Controllers** (in-game menu): move any device to another player for the session, and
  rebind a device's buttons by pressing an input on it: a tap of B (Escape on a keyboard)
  cancels and holding it for a second binds it; home/guide or 8 s without input also cancel.
  A mapping is for the game's system ("This system", the default, listing the console's own
  button names) or for all systems, and the page draws the console's pad next to your
  controller, lighting the button being remapped on both. Mappings are saved on the server per
  user, controller model and system, so they follow you to the web and iOS clients
  (`docs/CONTROLLERS.md` in SovEmu). In force is yours for the system, else yours for all
  systems, else the server's default (set by an admin), else the built-in one; "Reset to
  defaults" removes yours in that scope and goes back to the next.
- **In-game menu:** Back, or Select+Start.
- **Fast-forward:** hold Select+R.
- **Rewind:** hold Select+L.
- **Touch controller:** shown by default; hide it from the menu.


**Menus:**
- **Music:** every MP3 in `app/src/main/assets/music/` plays in the menus, shuffled; it stops in a
  game and in the screensaver. Switch it off in the account menu.
- **Screensaver:** after 5 minutes without input (3, 5, 10 or off, in the account menu), random
  games' video snaps play with their name, system, release date and players along the bottom.
  Any button ends it. The app keeps the screen on while it is open.
- **Scan lines:** a CRT look over the menus (account menu).
