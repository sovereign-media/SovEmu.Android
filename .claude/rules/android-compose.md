---
paths:
  - "app/src/main/**/*.kt"
---

# SovEmu.Android: Compose

Rules from the official Compose guidance (tags point at the sources at the end), with a few
learned in Sovereign.Android; "(judgement)" marks ours. The app has no ViewModel and no DI
framework on purpose: `Store` and each screen's remembered state stand in for a ViewModel, and
constructor defaults for DI.

## Naming and shape

- Composables that emit UI are PascalCase nouns (`CoverCard`, not `RenderCover`); composables that
  return a value are camelCase; factories of remembered objects start with `remember`. A
  composable either emits UI or returns a value, never both. [API][KS]
- **Every public composable that emits UI takes one `modifier: Modifier = Modifier`**, the first
  optional parameter, applied once, first in the chain, on the root. Order: required parameters,
  `modifier`, optional parameters, then a trailing `content` slot. Prefer slots over
  `String`/icon parameters where callers vary the content. [API][CAPI]
- Don't add parameters for what a modifier already does (click, clip, padding). [CAPI]
- Stateless by default: a value plus an `onXChange` callback; never take `MutableState<T>` or
  `State<T>` parameters (take a value or `() -> T`). [API][CAPI]
- One component, one job; behaviour that fits any element is a modifier. [CAPI]

## State and data flow

- Unidirectional data flow: `Store` and a screen's remembered state produce state and handle
  events; composables render it and forward events. [UI][HOIST]
- **Only the screen-level composable touches `Store`, `SovEmuApi` or `Controller`**; children get
  plain values and lambdas. Several screens still call `SovEmuApi` from effects and pass `api`
  into children (`Cheats`, `Collections`, `Login`): move a call into `Store` or a plain class when
  you change it, never add one. [UI][HOIST]
- Hoist state to the lowest common ancestor that reads and writes it; keep purely visual state
  (expanded, animation) inside. Involved UI logic is a remembered plain class (`FocusMemory`),
  saved with `rememberSaveable` + `Saver` when it must survive. [HOIST]
- Composables are idempotent and side-effect free. Effects go in `LaunchedEffect` /
  `DisposableEffect`, keyed on every value they use (or `rememberUpdatedState`);
  `LaunchedEffect(Unit)` needs a comment saying why. [FX][MM]
- No backwards writes: never write state that was read earlier in the same composition. [PB]

## Performance

- Judge performance only on the R8 release build; debug Compose is several times slower. [PERF]
- No I/O, JSON decoding, sorting or filtering in a composable body: do it off Main (`Store`,
  `GameIndex`) and pass the result in, or `remember(keys)` with every input as a key. [PB][MM]
- **Never read an animated or fast-changing value in a body.** `Modifier.alpha(a)` / `scale` /
  `offset(Dp)` subscribe the composition: a recompose per frame. Read `.value` in a lambda
  modifier (`graphicsLayer { }`, `offset { }`, `drawBehind { }`) or pass `() -> T`. One
  `graphicsLayer` beats `scale().alpha()`. [PB] (Sovereign)
- **Anything that ticks on a timer gets its own flow**, collected only by what shows it, not a
  field of a screen's state. (Sovereign)
- `remember { derivedStateOf { } }` only to turn a fast-changing state into a slow one. [PB][FX]
- Every lazy `items` call has a stable server key (never an index or `hashCode`) and a
  `contentType` when kinds are mixed. Collect flows and read CompositionLocals above the list. [PB]
- A composable returning a fresh `List` stops its consumer skipping: constant sets are a top-level
  `val`. Don't annotate or wrap for stability by reflex: strong skipping compares unstable `core`
  classes by identity, so don't re-create equal instances on every emission. [STAB] (Sovereign)
- **TVs are fill-rate bound**: no background on a container its children cover, no window
  background under Compose, no placeholder drawn under a loaded image, no zero-width or
  transparent borders (`border(0.dp)` draws a hairline), no `Modifier.blur` or `RenderEffect` on
  TV paths. Size images to their box (Coil does from constraints). [OD]
- Layout traps: a `Box` in a `Column` wraps to its widest child, so centring a child needs
  `fillMaxWidth()`; `Modifier.size` is a proposal and `requiredSize` a demand. (Sovereign)

## TV focus

- Every screen sets initial focus (a remembered `FocusRequester`, requested from an effect, never
  from the body), restores it when returned to (`FocusMemory` / `focusRestorer` on lazy rows and
  grids), and gives it back when an overlay closes. [RF][FR]
- **Overlays** (drawers, strips, menus, side panels) trap focus (`focusGroup` + `focusProperties
  { onExit }`) and close on Back via `BackHandler`. Nothing behind an open overlay may have focus:
  focus that lands behind it is brought back in, as `SidePanel` does. Closing a game's panel lands
  on its card. Overlays are drawn in the activity window, never a `Dialog` window (Back was lost
  in one on a Mi TV). [FG] (judgement)
- Every focusable custom element shows its focus visibly through its `interactionSource`, not
  only by changing state in `onFocusChanged`. [FS][FOC]

## Sources

[PB] developer.android.com/develop/ui/compose/performance/bestpractices ·
[PERF] …/compose/performance · [STAB] …/compose/performance/stability ·
[OD] developer.android.com/topic/performance/rendering/overdraw ·
[MM] …/compose/mental-model · [FX] …/compose/side-effects · [HOIST] …/compose/state-hoisting ·
[UI] developer.android.com/topic/architecture/ui-layer ·
[API] github.com/androidx/androidx/blob/androidx-main/compose/docs/compose-api-guidelines.md ·
[CAPI] …/compose/docs/compose-component-api-guidelines.md ·
[KS] developer.android.com/kotlin/style-guide · [FOC] …/compose/touch-input/focus ·
[FR] …/focus/focus-restoration · [RF] …/focus/request-focus · [FS] …/focus/focused-state ·
[FG] …/focus/focus-group
