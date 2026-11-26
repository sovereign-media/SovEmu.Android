---
paths:
  - "**/*.kt"
---

# SovEmu.Android: Kotlin

Common-sense rules from the official Kotlin and coroutines guidance (tags point at the sources at
the end). "(judgement)" marks a rule that is ours rather than the docs'. Compose has its own file,
[android-compose.md](android-compose.md).

## Style

- **The format is ktlint's** (`android_studio` style, `.editorconfig`), through Spotless:
  `./gradlew spotlessApply` formats every Kotlin and Gradle file, and CI's `spotlessCheck` fails
  on anything it would change. Lines are at most 100 columns: ktlint can't wrap every line, so
  wrap the ones it reports. No wildcard imports; a KDoc sits on a declaration (a file's
  introduction is a `//` comment). [KS][KC]
- `val` and read-only types (`List`, `Map`) by default; `var` and `Mutable*` only for private,
  local state. Expose a `StateFlow`, keep the `MutableStateFlow` private (`_x` / `x`, as `Store`
  does). [KC][CO]
- Defaults are public values, grouped in an `XDefaults` object when there are several; `null`
  never means "use my internal default". [CAPI]
- KDoc (`/** */`) says why, not what; delete code rather than comment it out; no unused imports,
  parameters, dependencies or placeholder declarations. (judgement)

## Coroutines

- **Never swallow `CancellationException`.** Wrap a suspend call that may fail in `attempt { }`
  (`Async.kt`), which rethrows cancellation; `runCatching` around a suspend call swallows it.
  Catch specific exception types otherwise. [CO]
- **Suspend functions are main-safe**: the class doing blocking work (OkHttp, WebRTC, decoding)
  moves itself off Main with `withContext(dispatcher)`. [CO][UI]
- **What lives as long as the process comes from Hilt**, as in Sovereign.Android: `Settings`,
  `SovEmuApi` (and its `Session`, the one owner of every token) and `Store` are `@Singleton`s
  (`:common:data`'s `DataModule`, with the `@ApplicationScope` coroutine scope); each app has
  its `@HiltAndroidApp` application, its `ClientInfoModule` (which app it is) and an
  `@AndroidEntryPoint` `MainActivity`. Never build one of those singletons by hand outside a
  test. What lives with the window (controllers, game sessions, music) is made by the activity.
- **Inject dispatchers and scopes through constructors**, with a default argument where Hilt
  does not build the class (as `Store`'s `compute` and `TokenClient`'s `io`); never
  `GlobalScope`, and no new hard-coded `Dispatchers.*` in a class. [CO]
- Collect flows in UI with `collectAsStateWithLifecycle()`. [UI]

## Ownership

- **Native resources** (PeerConnection, renderers, codecs, audio) have one owner and an idempotent
  `close()`: closing a PeerConnection twice crashes. (judgement)
- **Business logic lives in `:common:core`** or a plain class in `:common:data` or
  `:common:stream`, not in a composable, so a JVM test can reach it. [UI]

## Sources

[KC] kotlinlang.org/docs/coding-conventions.html · [KS] developer.android.com/kotlin/style-guide ·
[CO] developer.android.com/kotlin/coroutines/coroutines-best-practices ·
[UI] developer.android.com/topic/architecture/ui-layer ·
[CAPI] github.com/androidx/androidx/blob/androidx-main/compose/docs/compose-component-api-guidelines.md
