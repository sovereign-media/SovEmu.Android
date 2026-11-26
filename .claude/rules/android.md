---
paths:
  - "**/*.kt"
  - "**/*.graphql"
  - "**/*.graphqls"
---

# SovEmu.Android

Three apps, as Sovereign.Android has: phones and tablets (`:apps:mobile`), Android TV
(`:apps:androidtv`) and Fire TV (`:apps:firetv`), over shared modules in `common/` (Compose,
`androidx.compose.material3` only, minSdk 28). How code is written is in
[android-kotlin.md](android-kotlin.md) and [android-compose.md](android-compose.md); tests in
[android-tests.md](android-tests.md); the build in [android-build.md](android-build.md). Paths into
SovEmu below are from the parent repository's root.

## Modules

A module depends only on those below it; the graph is in `settings.gradle.kts`.

- **`:common:core` stays plain Kotlin/JVM** (no Android, no Compose). Anything testable without
  Android goes there with JUnit tests: the wire protocol, API models and client, sign-in,
  parsing, controllers, game lists, latency maths.
- **`:common:data`**: the API on Android (`SovEmuApi`), `Settings` and the app's preferences
  (`Preferences.kt`), the sealed token (`KeystoreSealer`), `Store` (the library's state,
  `StateFlow`s of `Load<T>`: `Loading`, `Ready`, `Failed`), `Sessions` and `ClientEvents`. No
  Compose.
- **`:common:stream`**: `Stream` and `StreamView` (the WebRTC session), the low-latency decoder
  factory, `Controller` (the pads) and `InputPresence`. The only module with WebRTC.
- **`:common:designsystem`**: the theme and colours (`Ui.kt`), Lucide icons, focus, and widgets
  that know nothing of the server or of one surface. Anything that loads from the server (`Art`,
  `VideoSnap`, `Decorated`, `EsrbBadge`) is in `:common:feature`, with `LocalApi`.
- **`:common:ui-tv`**: the TV's own widgets (its on-screen keyboard and the text field that types
  with it), as Sovereign's `:common:ui-tv`.
- **`:common:feature`**: the ViewModels, the shared app (`Shell.kt`: `SovEmuApp`, the back stack,
  settings, screensaver, quit question; `Route`), and `SovEmuActivity`, the activity every app's
  `MainActivity` extends.
- **Each surface draws its own screens, as Sovereign's two UI stacks do**: `:common:feature-tv`
  (`TvSurface`, the Android TV and Fire TV apps) and `:common:feature-mobile` (`MobileSurface`, the
  phone and tablet app) each give a `Surface`: the signed-out screens and one Navigation 3 entry per
  `Route`, over the shared ViewModels. A screen being split still comes from `sharedRoutes`
  until both surfaces have their own; a new screen is written for each surface, never as one
  layout that reshapes itself for the other.
- **`apps/*` are thin shells**: a manifest, a `MainActivity` naming its `Surface`, and a
  `ClientInfoModule`. Only `:apps:mobile` registers the browser sign-in's redirect scheme. Kotlin
  packages stay `media.sovereign.sovemu` and `.ui` across the shared modules (the baseline
  profile names classes by them); each module's namespace is its own.

## Accounts, as Sovereign.Android keeps them

- **Several accounts stay signed in** (`AccountStore`, in `:common:core`): up to 5 per server on
  up to 3 servers, each keyed on the ID token's `sub` and server, each with its own refresh token
  sealed in the Keystore. A sign-in that would not fit is refused and its token revoked; nobody
  is signed out to make room. A sign-in of someone already stored replaces their session.
- **`Session` is the only owner of tokens**: never call `TokenClient` around it. One account is
  active and only it is refreshed; a launch with two or more accounts makes no token request
  until someone is chosen (`LaunchAccounts`). A token goes only to the server that issued it.
  A refused refresh removes only that account (`AuthState.Expired`); a network failure never
  signs anyone out. Sign-out revokes without waiting and lands as a launch does.
- **The account screens** (`ui/Launch.kt`, `ui/Accounts.kt`) are Sovereign's: *Who's watching?*
  (a tile per account here, `Add user`, *Change server*), *Choose a server* (remove asks first,
  naming who it signs out) and the sign-in form, one stack shown whenever nobody is active, and
  pushed over the app by the account menu's *Change user or server*. The picker returns after an
  hour in the background with two or more accounts (`IdlePicker`), never over a game. Their
  choices live in `:common:core` (`ServerChooser`, `pickerFocusMoves`), tested on the JVM.
- **Connecting is Sovereign's** (`Connection.kt`): a bare address is asked over https first, then
  http unless *Secure connections only* (on by default; off on an install already using http); an
  address that never answers puts the previous one back. Proxy headers (`CustomHeaderRules`: 5 at
  most, token names, never one the app sets, one per name in any case) go on every request to the
  server's host and port and nowhere else; a value is never logged or shown.
- **An update signs nobody out**: `AccountStore` moves a single-account install's sealed token,
  client and device name into the first account on first read, without unsealing the token.

## Shared with the other clients

- **The wire protocol** (`common/core/.../Protocol.kt`) mirrors
  `SovEmu.Backend/SovEmu.Protocol/src/input.rs`, `SovEmu.Web/src/lib/protocol.ts` and SovEmu.iOS's
  `Protocol.swift` byte for byte; `ProtocolTest` uses the same vectors. Change them all in the same
  release.
- **Controllers follow SovEmu's `docs/CONTROLLERS.md`** (IDs, families, positional input names,
  keyboard `KeyboardEvent.code` names, defaults, hotkeys after mapping, one player per controller),
  implemented in `common/core/.../Controllers.kt`. Change it with the web and iOS clients. Gamepad
  buttons map by position (bottom face = RetroPad B, right = A); Android's `KEYCODE_BUTTON_A` is the
  bottom face.
- **Signalling** is one `sessionOffer` mutation carrying the complete offer (non-trickle: wait for
  ICE gathering), as on the other clients.
- **On a phone or tablet, sign-in follows Sovereign's phones**: *Sign in* opens the server's own
  page in a Custom Tab (`BrowserSignIn.kt`: the code flow with PKCE as the **mobile client**),
  with a code as the other way in and no password typed into the app (the form only on a server
  with no browser sign-in). `BrowserSignInActivity` opens the tab and `BrowserRedirectActivity`
  takes `media.sovereign.sovemu:/oauth2redirect`, both declared only in `:apps:mobile`; the request
  in flight is kept in the app's preferences (`Settings.browserSignIn`), so a redirect that
  restarts an ended app is still exchanged. The redirect's `state` is checked before anything in
  it is believed, and every failure says the app's own sentence (`BrowserSignInText`), never an
  `error_description`. *Add user* asks the server's page who is signing in (`prompt=login`). A
  stored account remembers its client (`StoredAccount.clientId`): refresh and sign-out use it.
  *Sign in a TV* (account menu, not on a TV) approves a TV's code with the `approveDeviceCode`
  mutation; its field keeps the text as typed (rewriting it as it is typed drops keystrokes).
- **Sign-in on a TV follows Sovereign's TVs**: *Sign in with your phone* (the device grant: a code
  and its QR code, approved at the server's `/device` page, polled at the server's `interval`,
  `slow_down` widening it, waiting never shown as an error) with the password as the fallback, and
  only the code when the server has the password off. The app signs in as the **device client**; its
  id and every sign-in URL come from `bootstrapConfig` (`deviceClientId`), never hard-coded. The
  pairing loop is `:common:core`'s `awaitApproval`, tested on a fake clock.

## GraphQL through Apollo

Every operation is written once in `common/core/src/main/graphql/operations.graphql`, generated into
typed Kotlin against `schema.graphqls`, a copy of `SovEmu.Backend/SovEmu.Server/schema.graphql`:
refresh it with `./gradlew :common:core:syncSchema` when the server's schema changes. Browsing
models are the generated fragments, named by typealias in `common/core/.../Models.kt` (`Game` =
`GameFields` …): a new fragment gets one there. The controller models stay hand-written and convert
in `Models.kt`. Never write a query string or decode GraphQL JSON by hand (the OAuth token response
in `Auth.kt` is not GraphQL, and is read with Apollo's `JsonReader`).

## Navigation and input

- **The back stack is ours**: a `mutableStateListOf<Route>` in `SovEmuActivity`, shown by
  Navigation 3's `NavDisplay` (which keeps each entry's saved state under `Route.key`). There is
  no NavController or Navigation-Compose. System Back pops it, and pushing a route already on
  the stack pops back to it. Screens get a `Nav` of callbacks, never the stack.
- **While a game is playing, controller events go to the game**, not to Compose focus
  (`SovEmuActivity.dispatchKeyEvent`), except while the in-game menu or the controllers screen is
  open (`Controller.toGame`), which must stay usable with a D-pad. Once the game is up, Back opens
  the in-game menu; before then there is nothing to resume, and Back leaves.

## Before a PR

```bash
./gradlew spotlessCheck :common:core:test testDebugUnitTest \
  :apps:mobile:assembleDebug :apps:androidtv:assembleDebug :apps:firetv:assembleDebug
```

That is what CI runs (`./gradlew spotlessApply` fixes the format). Check TV and controller
behaviour on an emulator, in the TV app and the phone app.
