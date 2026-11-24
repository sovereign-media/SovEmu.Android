---
paths:
  - "**/*.kt"
  - "**/*.kts"
---

# SovEmu.Android: code quality

Common-sense rules drawn from the official Android, Compose and Kotlin guidance (sources at the
end, tagged per rule). They apply on top of `android.md`. The app has no ViewModel, DI framework
or navigation library on purpose: where the docs assume one, the rule says what stands in for it
here. "(judgement)" marks a rule that is ours rather than the docs'.

## Code cleanliness

- Composables that emit UI are PascalCase nouns (`CoverCard`, not `RenderCover`); composables
  that return a value are camelCase; factories of remembered objects start with `remember`. [API][KS]
- A composable either emits UI or returns a value, never both. [API]
- `val` and read-only types (`List`, `Map`) by default; `var` and `Mutable*` only for private,
  local state. Expose `StateFlow`, keep the `MutableStateFlow` private (`_x` / `x`). [KC][CO]
- Kotlin style: no wildcard imports, 100 columns where practical, braces on multi-line
  `if`/`when`/`for`, trailing commas in multi-line parameter and argument lists. [KS][KC]
- Never swallow `CancellationException`: catch specific exception types, or rethrow it when a
  broad catch is unavoidable (`runCatching` around a suspend call swallows it too). [CO]
- Defaults are public values, grouped in an `XDefaults` object when there are several; `null`
  never means "use my internal default". [CAPI]
- Every comment says why, not what; delete code rather than comment it out, and no unused
  imports, parameters or placeholder declarations. (judgement)

## Performance

- Judge performance only on the R8 release build (`assembleRelease`), compiled with its
  profile (`adb shell cmd package compile -m speed-profile`); debug builds of Compose are
  several times slower. [PERF]
- Ship an app Baseline Profile (Baseline Profile plugin + Macrobenchmark) covering startup,
  scrolling a system's games and starting a game. [BP]
- No I/O, JSON decoding, sorting or filtering in a composable body: do it off Main (the `Store`,
  `GameIndex`) and pass the result in. What must stay is `remember(keys)` with every input as a
  key. [PB][MM]
- Read fast-changing values (scroll, animation, focus scale) in lambda modifiers
  (`graphicsLayer { }`, `offset { }`, `drawBehind { }`) or pass `() -> T`, so the read
  happens in layout or draw, not composition. [PB]
- `remember { derivedStateOf { } }` only to turn a fast-changing state into a slow one (a
  threshold); not to combine states that change together. [PB][FX]
- Every lazy `items` call has a stable server key (never an index or `hashCode`) and a
  `contentType` when item kinds are mixed. Collect flows and read CompositionLocals above the
  list, not per item. [PB]
- No backwards writes: never write state that was read earlier in the same composition. Write
  from callbacks and effects only. [PB]
- TVs are fill-rate bound (Mali GPU): no background on a container its children cover, no
  window background under Compose, no placeholder drawn under a loaded image, no zero-width or
  transparent borders. `border(0.dp)` draws a hairline. Check with Debug GPU overdraw. [OD]
- Size images to their box (Coil does from constraints; pass `size()` for deliberate
  low-resolution blurs) and never use `Modifier.blur` or `RenderEffect` on TV paths. [OD] (judgement for the blur)
- Don't annotate or wrap collections for stability by reflex. Strong skipping compares
  unstable `core` classes by identity, so don't re-create equal instances on every emission. [STAB]

## Component composition

- Every composable that emits UI takes one `modifier: Modifier = Modifier`. It is the first
  optional parameter and is applied once, first in the chain, on the root. [API][CAPI]
- Parameter order: required parameters, `modifier`, optional parameters, then a trailing
  `content` slot. Prefer slots over `String`/icon parameters where callers vary the content. [CAPI]
- Don't add parameters for what a modifier already does (click, clip, padding). [CAPI]
- Stateless by default: a value plus an `onXChange` callback; never take `MutableState<T>` or
  `State<T>` parameters (take a value or `() -> T`). [API][CAPI]
- Hoist state to the lowest common ancestor that reads and writes it; keep purely visual
  element state (expanded, animation) inside. [HOIST]
- Composables are idempotent and side-effect free. Effects go in `LaunchedEffect` /
  `DisposableEffect`, keyed on every value they use (or `rememberUpdatedState`);
  `LaunchedEffect(Unit)` needs a reason. [FX][MM]
- TV focus: every screen sets initial focus (a remembered `FocusRequester`, requested from an
  effect, never from the body), restores focus when returned to (`FocusMemory` /
  `focusRestorer` on lazy rows and grids), and gives it back when an overlay closes. [RF][FR]
- Overlays (drawers, strips, menus, side panels) trap focus (`focusGroup` + `focusProperties
  { onExit }`) and close on Back via `BackHandler`. Nothing behind an open overlay may ever
  have the focus: focus that lands behind it (coming back from a game, a list restoring its
  card) is brought back in, as `SidePanel` does. Closing a game's panel lands on its card.
  They are drawn in the activity window, not a `Dialog` window (Back was lost in one on a Mi
  TV). [FG] (judgement for the window and for focus behind)
- Every focusable custom element shows its focus visibly through its `interactionSource`, not
  only by changing state in `onFocusChanged`. [FS][FOC]
- Don't mix `androidx.tv.material3` with `androidx.compose.material3` under one theme. [TV]

## Single responsibility

- One component, one job: split composables that do several things. Behaviour that fits any
  element is a modifier. [CAPI]
- Unidirectional data flow: a screen-level state holder (here, `Store` plus a screen's
  remembered state) produces state and handles events; composables render it and forward
  events. No business logic, parsing or network calls in the UI layer. [UI][HOIST]
- Only the screen-level composable touches the state holder; children get plain values and
  lambdas (prefer individual parameters to wrapper objects). [HOIST]
- Move involved UI logic out of composables into a remembered plain class (`FocusMemory`),
  saved with `rememberSaveable` + `Saver` when it must survive. [HOIST]
- Suspend functions are main-safe: the class doing blocking work (OkHttp, WebRTC, decoding)
  moves itself off Main with `withContext(dispatcher)`. [CO][UI]
- Inject dispatchers and long-lived scopes through constructors (default arguments stand in
  for DI); never `GlobalScope`. [CO]
- Collect flows in UI with `collectAsStateWithLifecycle()`. [UI]
- Native resources (PeerConnection, renderers, codecs) have one owner and an idempotent
  `close()`: closing a PeerConnection twice crashes. (judgement)

## Testing

- A pyramid: most tests are fast JVM tests in `core` and plain classes, fewer Compose UI tests,
  very few end-to-end. Test at the lowest layer that gives the answer. [TS]
- Hand-written fakes implementing the real interface, in the test source set; no mocks or
  spies. [TD][FT]
- Coroutine tests use `runTest` with injected test dispatchers sharing one scheduler; JVM tests
  replace Main with a `MainDispatcherRule`. [CT]
- Assert a `StateFlow` on `.value`; use `first()` / `take(n).toList()` for finite checks. [FT]
- Compose UI tests (`createComposeRule`) render the stateless composable with fake state and
  find nodes by text or semantics as a user would; `testTag` is a last resort. [CUI][SEM]
  They run on the JVM under Robolectric (`app/src/test`, `TestApp` supplies the composition
  locals), so CI needs no emulator. (judgement for Robolectric)
- Test D-pad traversal, initial focus and focus restoration with Compose focus tests. [FOC]
- Measure performance with Macrobenchmark (or `dumpsys gfxinfo` on a real TV) on the release
  build, never in unit tests. Every fix for a crash or a regression lands with a test that
  would have caught it. [TS][BP] (judgement for the last)

## Sources

[PB] developer.android.com/develop/ui/compose/performance/bestpractices ·
[PERF] …/compose/performance · [STAB] …/compose/performance/stability ·
[BP] developer.android.com/topic/performance/baselineprofiles/overview ·
[OD] developer.android.com/topic/performance/rendering/overdraw ·
[MM] …/compose/mental-model · [FX] …/compose/side-effects · [HOIST] …/compose/state-hoisting ·
[UI] developer.android.com/topic/architecture/ui-layer ·
[API] github.com/androidx/androidx/blob/androidx-main/compose/docs/compose-api-guidelines.md ·
[CAPI] …/compose/docs/compose-component-api-guidelines.md ·
[CO] developer.android.com/kotlin/coroutines/coroutines-best-practices ·
[CT] developer.android.com/kotlin/coroutines/test · [FT] developer.android.com/kotlin/flow/test ·
[TS] developer.android.com/training/testing/fundamentals/strategies ·
[TD] …/training/testing/fundamentals/test-doubles · [CUI] …/compose/testing ·
[SEM] …/compose/testing/semantics · [KC] kotlinlang.org/docs/coding-conventions.html ·
[KS] developer.android.com/kotlin/style-guide · [FOC] …/compose/touch-input/focus ·
[FR] …/focus/focus-restoration · [RF] …/focus/request-focus · [FS] …/focus/focused-state ·
[FG] …/focus/focus-group · [TV] developer.android.com/training/tv/playback/compose
