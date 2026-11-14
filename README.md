# SovEmu.Android

The Android client for [SovEmu](https://github.com/sovereign-media/SovEmu): sign in to a SovEmu
server, pick a system and a game, and play it streamed over WebRTC with a gamepad or the touch
controller.

Checked out as a submodule of `SovEmu` (unpinned, `branch = main`); it builds on its own.

## Modules

| Module | What |
|---|---|
| `core` | Plain Kotlin/JVM: the input wire protocol (`Protocol.kt`) and the API's JSON models. Its tests use the same byte vectors as `SovEmu.Protocol` (Rust) and `SovEmu.Web` (TypeScript). |
| `app` | Jetpack Compose UI (sign-in, system carousel, game list, player), OkHttp for the API, and Stream's libwebrtc build for the stream. |

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
- **Gamepad:** buttons are mapped by position, as on the web client (bottom face = B, right face = A).
- **In-game menu:** Back, or Select+Start.
- **Fast-forward:** hold Select+R.
- **Rewind:** hold Select+L.
- **Touch controller:** shown by default; hide it from the menu.
