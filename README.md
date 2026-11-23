# SovEmu.Android

The Android client for [SovEmu](https://github.com/sovereign-media/SovEmu): sign in to a SovEmu
server, pick a system and a game, and play it streamed over WebRTC with a gamepad or the touch
controller.

Checked out as a submodule of `SovEmu` (unpinned, `branch = main`); it builds on its own.

## Modules

| Module | What |
|---|---|
| `core` | Plain Kotlin/JVM: the input wire protocol (`Protocol.kt`), controller IDs, input names, mappings, hotkeys and players (`Controllers.kt`, after SovEmu's `docs/CONTROLLERS.md`), the GraphQL operations (`src/main/graphql/operations.graphql`, generated into typed Kotlin by Apollo from a copy of the server's `schema.graphql`), the sign-in (`Auth.kt`: the server's `bootstrapConfig` first, then its password endpoint and the OAuth refresh-token grant at the URLs and client id it names, one refresh at a time, the bearer interceptor), and the game-list logic (`GameList.kt`). Its tests use the same byte vectors as `SovEmu.Protocol` (Rust) and `SovEmu.Web` (TypeScript). |
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
2. In the app, enter the server address (for example `http://192.168.1.20:8080`); the app asks it
   how to sign in (its address must be in the server's `SOVEMU_PUBLIC_URL`), then sign in. The app keeps
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
- **Starting a game:** a loading bar, as on the web client, follows the server's start
  (`startProgress`, polled four times a second): saving your last game, starting and loading the
  emulator, then loading the game, exact when the server knows the size ("32 of 64 MB") and
  easing forward while the megabytes read count up when it does not, with the seconds from 3 s.
  The bar never moves back; a failed start shows the server's error. Once the session is ready
  the bar is full and the screen says "Starting the game" until the game's first picture arrives
  (a console's BIOS can boot for seconds first, a PS2 for 5-6 s, and continuing from a save waits
  for the core's first frame); after 20 s it adds that Back opens the menu (to quit).
  Controllers already reach the game meanwhile.
- **In-game menu:** Back, or Select+Start.
- **Stats** (in-game menu, "Show stats"; remembered): latency and stream health in a top-left
  panel of two columns, as on the web client: estimated button→pixel, measured button→frame,
  input round trip (p50 and p95), network RTT, jitter buffer, audio delay (how long sound waits
  in its buffer, and the wait it is aiming for), decode, resolution and fps, bitrate, packets
  lost, and TV game mode (`on`, `off`, or `Asked - Display does not report status` on a device
  whose TV does not say whether it switched, a streaming stick's over HDMI); then, from a server that says how it makes the stream, the codec and
  encoder (GPU or CPU, and any it fell back from), what renders the picture, the audio, run-ahead
  and this device's video decoder, with whether it decodes in low-latency mode ("low latency:
  on" or "not supported"). Under them, a graph of the last minute's measured and
  estimated latency, with the audio's delay on a scale of its own (its marks beside the
  latency's, in amber); a jewel in each line's colour sits beside its value in the rows above. *Measured button→frame* is the median time from sending an input to the
  renderer getting the frame that shows it: the first frame to enter the decoder after the
  worker's ack (the worker sends the ack just before that frame's video), matched across the
  decoder by its timestamp (or by counting frames in and out), so a decoder slower than a frame
  cannot make it read low. It leaves out the display's scan-out and the TV's own
  processing. While it is on, each once-a-second sample is also logged as logfmt
  (`adb logcat -s SovEmu.Stats`, with one `decoder=…` line per stream naming the video decoder
  and its low-latency key); off, nothing is sampled. The audio's delays are logged every ten
  seconds either way (`audio_buffer_ms`, `audio_target_ms`).
- **Low-latency decoding** (automatic): the hardware video decoder is asked to output each frame
  as soon as it is decoded wherever it supports that: `KEY_LOW_LATENCY` when it advertises
  `FEATURE_LowLatency` (Android 11+), else a known vendor key the decoder itself lists
  (Android 12+). Nothing is forced on a decoder that lists neither. The stats send the
  decoder's name and whether low latency was applied to the server.
- **Software decoding where the hardware decoder holds frames back** (automatic): a hardware
  decoder with no low-latency mode gives way to WebRTC's own software decoder where it has one
  (VP8, VP9, AV1). Measured on a Fire TV Stick (MT8696): its hardware VP8 decoder dropped 3,867
  frames of one session and showed the picture 1.9 s late; in software, none dropped and 40 ms
  from button to frame. MediaTek's own `vdec-lowlatency` key was tried there and did not help. A
  decoder named as software (`c2.amlogic.vp8.decoder.sw`) is left as it is, and is reported to
  the server as software whatever the device calls it.
- **Sound close to the picture** (automatic): the game's sound plays as game audio, in stereo,
  on Android's low-latency output; its jitter buffer may hold at most 15 packets (150 ms) and
  drains quickly after a hiccup. With the library's defaults (a voice call's: mono, and a buffer
  free to grow) sound ran 175 to 390 ms behind the picture on a Mi TV and a Fire TV Stick; with
  these, about 90 to 100 ms on the stick.
- **Inputs as they happen:** a press is sent when it changes, not at the next look (every 4 ms).
- **Capabilities** (automatic): with every stream's offer the app tells the server what this
  device can decode and show, as one complete document (`Capabilities`, version 1): for each of
  WebRTC's video codecs the decoder it would use here (hardware or software, with a low-latency
  mode or not, and its name), and the screen's size and refresh rate. The server keeps it against
  this sign-in and picks each session's codec from it (sovereign-media/SovEmu#184). One line in
  logcat (`adb logcat -s SovEmu.Stats`, `capabilities decoders=…`) shows what was sent.
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
- **TV game mode** (account menu, on by default): asks the TV for its game picture mode (ALLM,
  minimal post-processing) for as long as the app is on screen, not per game, since some TVs
  blink at each switch. Off, the TV keeps its own picture mode. The low-latency Wi-Fi lock is
  separate and held only while a game plays.
- **Continue playing** (Home): a card opens the game's panel on its system's page, to Continue
  from the auto save or Start over; Back from the panel returns to Home on the same card. The
  hero's Play starts at once.

## Licence

Free software under the [GNU Affero General Public License v3.0 or later](LICENSE)
(`AGPL-3.0-or-later`), as the SovEmu server is. You may use, change and share it; if you give
others a modified version, or run one they use over a network, you must offer them its source.
Its dependencies keep their own licences.
