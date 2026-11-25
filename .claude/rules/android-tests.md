---
paths:
  - "**/src/test/**"
---

# SovEmu.Android: tests

Rules from the official Android testing guidance (tags point at the sources at the end);
"(judgement)" marks ours.

- **A pyramid**: most tests are fast JVM tests in `core` and of plain classes in `app`, fewer
  Compose UI tests, very few end to end. Test at the lowest layer that gives the answer. [TS]
- **Hand-written fakes** implementing the real interface, in the test source set (`FakeSealer`),
  and `mockwebserver3` for HTTP in `core`; no mocks or spies. Shared fixtures are `TestData.kt`
  (`core`) and `ui/Fixtures.kt` (`app`). [TD]
- **Coroutines**: `runBlocking` is fine for driving a suspend call against a fake or
  mockwebserver. A test that involves delays, timeouts or Main uses `runTest` with injected test
  dispatchers sharing one scheduler (add `kotlinx-coroutines-test` with the first one), never a
  real sleep. Assert a `StateFlow` on `.value`; use `first()` / `take(n).toList()` for finite
  checks. [CT][FT]
- **Compose UI tests** (`createComposeRule`) render the stateless composable with fake state and
  find nodes by text or semantics as a user would; `testTag` is a last resort. They run on the JVM
  under Robolectric (`app/src/test`, `TestApp` supplies the composition locals), so CI needs no
  emulator. Test D-pad traversal, initial focus and focus restoration this way. [CUI][SEM][FOC]
- **Shared vectors**: `ProtocolTest` and `ControllersTest` use the same cases as the server's and
  the other clients' tests; add a case to all of them together.
- Every fix for a crash or a regression lands with a test that would have caught it. Never
  measure performance in a unit test. (judgement)

## Sources

[TS] developer.android.com/training/testing/fundamentals/strategies ·
[TD] …/training/testing/fundamentals/test-doubles ·
[CT] developer.android.com/kotlin/coroutines/test · [FT] developer.android.com/kotlin/flow/test ·
[CUI] developer.android.com/develop/ui/compose/testing · [SEM] …/compose/testing/semantics ·
[FOC] …/compose/touch-input/focus
