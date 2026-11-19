package media.sovereign.sovemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/** Controller IDs, families, input names, mappings, hotkeys and players (docs/CONTROLLERS.md). */
class ControllersTest {
    // --- Identification ---------------------------------------------------------------------

    @Test
    fun recognisesTheKnownModelsByVendorAndProduct() {
        assertEquals(ControllerModel("xbox", Family.XBOX), Controllers.identify(0x045e, 0x0b13, "Xbox Wireless Controller"))
        assertEquals("xbox", Controllers.identify(0x045e, 0x028e, "Microsoft X-Box 360 pad").id)
        assertEquals("xbox", Controllers.identify(0x045e, 0x0b22, "").id)
        assertEquals(ControllerModel("dualsense", Family.PLAYSTATION), Controllers.identify(0x054c, 0x0ce6, "DualSense Wireless Controller"))
        assertEquals("dualsense", Controllers.identify(0x054c, 0x0df2, "DualSense Edge").id)
        assertEquals("dualshock4", Controllers.identify(0x054c, 0x05c4, "Wireless Controller").id)
        assertEquals("dualshock4", Controllers.identify(0x054c, 0x09cc, "Wireless Controller").id)
        assertEquals("dualshock4", Controllers.identify(0x054c, 0x0ba0, "").id)
        assertEquals(ControllerModel("switch-pro", Family.NINTENDO), Controllers.identify(0x057e, 0x2009, "Pro Controller"))
    }

    @Test
    fun anUnknownControllerIsItsZeroPaddedHexIds() {
        val m = Controllers.identify(0x2dc8, 0x6101, "8BitDo SN30 Pro")
        assertEquals("pad:2dc8:6101", m.id)
        assertEquals(Family.GENERIC, m.family)
        assertEquals("pad:0001:000a", Controllers.identify(1, 10, "").id)
        // Only the vendor or only the product still counts as IDs.
        assertEquals("pad:0000:0042", Controllers.identify(0, 0x42, "x").id)
        // An unlisted product of a known vendor takes the vendor's family.
        assertEquals(ControllerModel("pad:054c:0268", Family.PLAYSTATION), Controllers.identify(0x054c, 0x0268, "PLAYSTATION(R)3 Controller"))
        assertEquals(Family.XBOX, Controllers.identify(0x045e, 0x0001, "").family)
        assertEquals(Family.NINTENDO, Controllers.identify(0x057e, 0x2006, "Joy-Con (L)").family)
    }

    @Test
    fun aControllerWithoutIdsIsItsNameSlug() {
        assertEquals("pad:name:8bitdo-sn30-pro", Controllers.identify(0, 0, "8BitDo SN30 Pro+").id)
        assertEquals("pad:name:gamepad-1", Controllers.identify(0, 0, "  --GamePad   (1)--  ").id)
        assertEquals("pad:name:unknown", Controllers.identify(0, 0, "★★★").id)
        // At most 48 characters, never ending in '-'.
        val long = Controllers.identify(0, 0, "a".repeat(47) + " b" + "c".repeat(20)).id
        assertEquals("pad:name:" + "a".repeat(47), long)
        assertTrue(long.length <= 64)
        assertTrue(long.matches(Regex("[a-z0-9:-]+")))
        // The family then comes from the name.
        assertEquals(Family.XBOX, Controllers.identify(0, 0, "Generic X-Box pad (XInput)").family)
        assertEquals(Family.PLAYSTATION, Controllers.identify(0, 0, "Sony DualShock 3").family)
        assertEquals(Family.NINTENDO, Controllers.identify(0, 0, "Nintendo Switch Joy-Con").family)
        assertEquals(Family.GENERIC, Controllers.identify(0, 0, "USB Gamepad").family)
    }

    @Test
    fun slugsFollowTheSpec() {
        assertEquals("ab-c", Controllers.slug("AB c"))
        assertEquals("a-b", Controllers.slug("--a__b--"))
        assertEquals("", Controllers.slug(""))
        assertEquals(48, Controllers.slug("x".repeat(100)).length)
    }

    // --- Families ---------------------------------------------------------------------------

    @Test
    fun familiesLabelTheirButtons() {
        val inputs = listOf("south", "east", "west", "north", "l1", "r1", "l2", "r2", "select", "start", "l3", "r3")
        assertEquals(
            listOf("A", "B", "X", "Y", "LB", "RB", "LT", "RT", "View", "Menu", "LS", "RS"),
            inputs.map(Family.XBOX::label),
        )
        assertEquals(inputs.map(Family.XBOX::label), inputs.map(Family.GENERIC::label))
        assertEquals(
            listOf("✕", "○", "□", "△", "L1", "R1", "L2", "R2", "Create", "Options", "L3", "R3"),
            inputs.map(Family.PLAYSTATION::label),
        )
        assertEquals(
            listOf("B", "A", "Y", "X", "L", "R", "ZL", "ZR", "−", "+", "LS", "RS"),
            inputs.map(Family.NINTENDO::label),
        )
        for (f in Family.entries) assertEquals("D-pad ↑", f.label("up"))
        assertEquals("Left Shift", Controllers.inputLabel(Controllers.KEYBOARD, Family.GENERIC, "ShiftLeft"))
        assertEquals("✕", Controllers.inputLabel("dualsense", Family.PLAYSTATION, "south"))
    }

    // --- Inputs -----------------------------------------------------------------------------

    @Test
    fun padKeycodesAreNamedByPosition() {
        val k = Keys.Android
        val codes = listOf(
            k.BUTTON_A, k.BUTTON_B, k.BUTTON_X, k.BUTTON_Y, k.BUTTON_L1, k.BUTTON_R1, k.BUTTON_L2, k.BUTTON_R2,
            k.BUTTON_SELECT, k.BUTTON_START, k.BUTTON_THUMBL, k.BUTTON_THUMBR, k.DPAD_UP, k.DPAD_DOWN, k.DPAD_LEFT, k.DPAD_RIGHT,
        )
        assertEquals(Controllers.PAD_INPUTS, codes.map(Keys::padInput))
        assertNull(Keys.padInput(4)) // KEYCODE_BACK
        assertNull(Keys.padInput(110)) // KEYCODE_BUTTON_MODE: the guide button is never bound
    }

    @Test
    fun keyboardKeycodesAreW3cCodes() {
        val k = Keys.Android
        assertEquals("KeyA", Keys.keyboardCode(k.A))
        assertEquals("KeyZ", Keys.keyboardCode(k.Z))
        assertEquals("KeyQ", Keys.keyboardCode(45))
        assertEquals("Digit0", Keys.keyboardCode(k.DIGIT_0))
        assertEquals("Digit9", Keys.keyboardCode(k.DIGIT_9))
        assertEquals("ArrowUp", Keys.keyboardCode(k.DPAD_UP))
        assertEquals("ArrowRight", Keys.keyboardCode(k.DPAD_RIGHT))
        assertEquals("Enter", Keys.keyboardCode(k.ENTER))
        assertEquals("Space", Keys.keyboardCode(k.SPACE))
        assertEquals("ShiftLeft", Keys.keyboardCode(k.SHIFT_LEFT))
        assertEquals("ShiftRight", Keys.keyboardCode(k.SHIFT_RIGHT))
        assertEquals("ControlLeft", Keys.keyboardCode(k.CTRL_LEFT))
        assertEquals("ControlRight", Keys.keyboardCode(k.CTRL_RIGHT))
        assertEquals("AltLeft", Keys.keyboardCode(k.ALT_LEFT))
        assertEquals("AltRight", Keys.keyboardCode(k.ALT_RIGHT))
        assertEquals("Escape", Keys.keyboardCode(k.ESCAPE))
        assertEquals("Tab", Keys.keyboardCode(k.TAB))
        assertEquals("Backspace", Keys.keyboardCode(k.DEL))
        assertEquals("F12", Keys.keyboardCode(k.F12))
        assertEquals("Numpad5", Keys.keyboardCode(149))
        assertNull(Keys.keyboardCode(24)) // KEYCODE_VOLUME_UP
        // Every name is one the server accepts for a keyboard.
        for (code in 0..300) Keys.keyboardCode(code)?.let { assertTrue(it, it.matches(Regex("[A-Za-z0-9]{1,32}"))) }
    }

    @Test
    fun tabAndBackspaceAreNotBindable() {
        assertFalse(Keys.bindable("Tab"))
        assertFalse(Keys.bindable("Backspace"))
        assertTrue(Keys.bindable("Escape"))
        assertEquals("Z", Keys.label("KeyZ"))
        assertEquals("7", Keys.label("Digit7"))
        assertEquals("↑", Keys.label("ArrowUp"))
        assertEquals("Right Ctrl", Keys.label("ControlRight"))
        assertEquals("Enter", Keys.label("Enter"))
    }

    // --- Mappings ---------------------------------------------------------------------------

    @Test
    fun defaultMappingsFollowTheSpec() {
        val pad = Mapping.defaults("xbox")
        assertEquals(Button.B, pad.button("south"))
        assertEquals(Button.A, pad.button("east"))
        assertEquals(Button.Y, pad.button("west"))
        assertEquals(Button.X, pad.button("north"))
        assertEquals(Button.L, pad.button("l1"))
        assertEquals(Button.R2, pad.button("r2"))
        assertEquals(Button.SELECT, pad.button("select"))
        assertEquals(Button.L3, pad.button("l3"))
        assertEquals(Button.RIGHT, pad.button("right"))
        assertEquals(16, pad.bindings.size)
        assertEquals(Controllers.PAD_INPUTS.toSet(), pad.bindings.map { it.input }.toSet())

        val kb = Mapping.defaults(Controllers.KEYBOARD)
        assertEquals(Button.UP, kb.button("ArrowUp"))
        assertEquals(Button.B, kb.button("KeyZ"))
        assertEquals(Button.A, kb.button("KeyX"))
        assertEquals(Button.Y, kb.button("KeyA"))
        assertEquals(Button.X, kb.button("KeyS"))
        assertEquals(Button.L, kb.button("KeyQ"))
        assertEquals(Button.R, kb.button("KeyW"))
        assertEquals(Button.START, kb.button("Enter"))
        assertEquals(listOf("ShiftLeft", "ShiftRight"), kb.inputs(Button.SELECT))
        assertNull(kb.button("Tab"))
    }

    @Test
    fun rebindingMovesTheInputAndReplacesTheButtonsInputs() {
        val m = Mapping.KEYBOARD_DEFAULT.rebind(Button.SELECT, "KeyZ")
        assertEquals(listOf("KeyZ"), m.inputs(Button.SELECT))
        assertEquals(emptyList<String>(), m.inputs(Button.B))
        assertNull(m.button("ShiftLeft"))
        assertEquals(Mapping.of(m.bindings), m)
        // A saved mapping replaces the defaults entirely.
        val saved = Mapping.of(listOf(Binding("east", Button.B)))
        assertEquals(Button.B.bit, saved.buttons(listOf("east", "south")))
    }

    @Test
    fun hotkeysAreReadAfterMapping() {
        val mixer = InputMixer()
        mixer.connect(5, "Pad", ControllerModel("xbox", Family.XBOX))
        // L1 is now Select and R1 is Start: holding both opens the menu, and the game sees neither.
        mixer.setMappings(listOf(ControllerMapping("xbox", "Pad", listOf(Binding("l1", Button.SELECT), Binding("r1", Button.START), Binding("east", Button.R)))))
        mixer.pad(5, "l1", true)
        mixer.pad(5, "r1", true)
        var r = mixer.read()
        assertTrue(r.menu)
        assertEquals(0, r.ports[0])
        // The physical Select is unbound now: it opens nothing.
        mixer.release()
        mixer.pad(5, "select", true)
        mixer.pad(5, "start", true)
        r = mixer.read()
        assertFalse(r.menu)
        assertEquals(0, r.ports[0])
        // Select (l1) + R (east) fast-forwards.
        mixer.release()
        mixer.pad(5, "l1", true)
        mixer.pad(5, "east", true)
        mixer.pad(5, "south", true) // unbound in the saved mapping
        r = mixer.read()
        assertTrue(r.fastForward)
        assertFalse(r.rewind)
        assertEquals(0, r.ports[0])
    }

    @Test
    fun hotkeyCombosAreKeptFromTheGame() {
        val sel = Button.SELECT.bit
        val r = Hotkeys.apply(listOf(sel or Button.L.bit or Button.B.bit, sel or Button.START.bit, sel, Button.R.bit))
        assertTrue(r.rewind)
        assertTrue(r.menu)
        assertFalse(r.fastForward)
        assertEquals(listOf(Button.B.bit, 0, sel, Button.R.bit), r.ports)
    }

    @Test
    fun keyboardHotkeysAndUnusedKeys() {
        val mixer = InputMixer()
        mixer.key("Tab", true)
        mixer.key("KeyZ", true)
        val r = mixer.read()
        assertTrue(r.fastForward)
        assertEquals(Button.B.bit, r.ports[0])
        assertTrue(mixer.keyUsed("Backspace"))
        assertTrue(mixer.keyUsed("Enter"))
        assertFalse(mixer.keyUsed("Escape")) // unbound: falls through to Back (the menu)
    }

    // --- Players ----------------------------------------------------------------------------

    @Test
    fun aNewControllerTakesTheLowestFreePlayer() {
        val p = Players()
        assertEquals(0, p.connect(10))
        assertEquals(1, p.connect(11))
        assertEquals(2, p.connect(12))
        assertEquals(1, p.connect(11)) // already connected
        p.disconnect(11)
        assertNull(p.port(11))
        assertEquals(1, p.connect(13)) // takes the freed player
        assertEquals(3, p.connect(14))
        assertNull(p.connect(15)) // four players taken
        p.disconnect(10)
        assertEquals(0, p.connect(16))
        assertEquals(listOf(12, 13, 14, 15, 16), p.devices)
    }

    @Test
    fun reassignedPlayersFreeTheirOldOne() {
        val p = Players()
        p.connect(1)
        p.assign(1, 2)
        assertEquals(2, p.port(1))
        assertEquals(0, p.connect(2))
        assertEquals(1, p.connect(3))
        assertEquals(3, p.connect(4))
    }

    @Test
    fun eachControllerDrivesItsOwnPort() {
        val mixer = InputMixer()
        mixer.connect(1, "One", ControllerModel("xbox", Family.XBOX))
        mixer.connect(2, "Two", ControllerModel("dualsense", Family.PLAYSTATION))
        mixer.pad(1, "south", true)
        mixer.pad(2, "east", true)
        mixer.padAxes(2, setOf("r2"), 0f, -1f, Protocol.Analog(lx = 100))
        mixer.touch(Button.START, true)
        var r = mixer.read()
        assertEquals(2, r.count)
        assertEquals(Button.B.bit or Button.START.bit, r.ports[0])
        // The left stick doubles as the D-pad...
        assertEquals(Button.A.bit or Button.R2.bit or Button.UP.bit, r.ports[1])
        assertEquals(100, r.analog[1].lx)
        assertEquals(Protocol.Analog.CENTRED, r.analog[0])
        // ...unless the system reads analog sticks.
        mixer.analogSticks = true
        assertEquals(Button.A.bit or Button.R2.bit, mixer.read().ports[1])

        // The keyboard is player 1, and can be moved.
        mixer.key("KeyX", true)
        assertEquals(Button.A.bit, mixer.read().ports[0] and Button.A.bit)
        mixer.assign(InputMixer.KEYBOARD_DEVICE, 3)
        r = mixer.read()
        assertEquals(4, r.count)
        assertEquals(Button.A.bit, r.ports[3])
        assertEquals(listOf(InputMixer.KEYBOARD_DEVICE, 1, 2), mixer.devices().map { it.device })
        assertEquals(listOf(3, 0, 1), mixer.devices().map { it.port })

        // A disconnected controller frees its player for the next one.
        mixer.disconnect(1)
        assertEquals(0, mixer.connect(3, "Three", ControllerModel("pad:2dc8:6101", Family.GENERIC)))
    }

    // --- Reset ------------------------------------------------------------------------------

    /** Runs a suspend function that never really suspends (core has no coroutines library). */
    private fun <T> run(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
        return result!!.getOrThrow()
    }

    private fun mine(system: String?, vararg b: Binding) = ControllerMapping("xbox", "Xbox", b.toList(), MappingSource.USER, system)

    @Test
    fun theBookSavesAndResetsEachScopeWithItsSystemArgument() {
        val book = MappingBook("megadrive")
        assertEquals("megadrive", book.systemArg(MappingScope.SYSTEM))
        assertNull(book.systemArg(MappingScope.ALL))
    }

    @Test
    fun resettingRefetchesBothScopesSoTheNextLayerApplies() {
        val admin = ControllerMapping("xbox", "Xbox", listOf(Binding("south", Button.A)), MappingSource.ADMIN, "megadrive")
        val forMd = mine("megadrive", Binding("south", Button.Y))
        val forAll = mine(null, Binding("south", Button.START))
        // What the server has in force, per `system` argument.
        var server = mapOf<String?, List<ControllerMapping>>("megadrive" to listOf(forMd), null to listOf(forAll))
        val calls = mutableListOf<String>()
        val book = MappingBook("megadrive")
        run { book.refresh { calls += "fetch $it"; server.getValue(it) } }
        assertEquals(Button.Y, book.mapping("xbox", MappingScope.SYSTEM).button("south"))
        assertEquals(Button.START, book.mapping("xbox", MappingScope.ALL).button("south"))
        assertTrue(book.canReset("xbox", MappingScope.SYSTEM))
        assertTrue(book.canReset("xbox", MappingScope.ALL))

        // Resetting this system's: the every-system mapping of the user's own is now in force.
        calls.clear()
        run {
            book.reset(
                "xbox", MappingScope.SYSTEM,
                reset = { c, sys -> calls += "reset $c $sys"; server = server + ("megadrive" to listOf(forAll)); true },
                fetch = { calls += "fetch $it"; server.getValue(it) },
            )
        }
        assertEquals(listOf("reset xbox megadrive", "fetch megadrive", "fetch null"), calls)
        assertEquals(Button.START, book.mapping("xbox", MappingScope.SYSTEM).button("south"))
        assertFalse(book.canReset("xbox", MappingScope.SYSTEM)) // nothing of their own for this system
        assertEquals("Yours for all systems", MappingBook.sourceText(book.entry("xbox", MappingScope.SYSTEM)))

        // Resetting every system's: the admin's default for the system, not the built-in one.
        run {
            book.reset(
                "xbox", MappingScope.ALL,
                reset = { _, sys -> assertNull(sys); server = mapOf("megadrive" to listOf(admin), null to emptyList()); true },
                fetch = { server.getValue(it) },
            )
        }
        assertEquals(Button.A, book.mapping("xbox", MappingScope.SYSTEM).button("south"))
        assertEquals("Default", MappingBook.sourceText(book.entry("xbox", MappingScope.SYSTEM)))
        assertEquals(Mapping.PAD_DEFAULT, book.mapping("xbox", MappingScope.ALL))
        assertFalse(book.canReset("xbox", MappingScope.ALL))
    }

    @Test
    fun aFailedResetChangesNothing() {
        val book = MappingBook("snes")
        run { book.refresh { listOf(mine(it, Binding("south", Button.START))) } }
        val e = runCatching {
            run { book.reset("xbox", MappingScope.SYSTEM, reset = { _, _ -> throw ApiException("BAD_REQUEST", "no") }, fetch = { emptyList() }) }
        }.exceptionOrNull()
        assertEquals("BAD_REQUEST", (e as ApiException).code)
        assertEquals(Button.START, book.mapping("xbox", MappingScope.SYSTEM).button("south"))
    }

    @Test
    fun sourceTextSaysWhereTheMappingComesFrom() {
        assertEquals("Yours for this system", MappingBook.sourceText(mine("snes")))
        assertEquals("Yours for all systems", MappingBook.sourceText(mine(null)))
        assertEquals("Default", MappingBook.sourceText(mine("snes").copy(source = MappingSource.ENVIRONMENT)))
        assertEquals("Default", MappingBook.sourceText(mine(null).copy(source = MappingSource.ADMIN)))
        assertEquals("Default", MappingBook.sourceText(null))
    }

    // --- Diagrams ---------------------------------------------------------------------------

    @Test
    fun aButtonLightsItsSystemKeyAndEveryInputMappedToIt() {
        // l3 also drives B; Select drives Start; nothing drives Select.
        val m = Mapping.of(Mapping.PAD_DEFAULT.rebind(Button.START, "select").bindings.filter { it.input != "l3" } + Binding("l3", Button.B))
        assertEquals(Highlight("B", setOf("south", "l3")), highlight(Button.B, m))
        assertEquals(Highlight("START", setOf("select")), highlight(Button.START, m))
        assertEquals(Highlight("SELECT", emptySet()), highlight(Button.SELECT, m))
        // A keyboard: key names, which a client lists instead of drawing.
        assertEquals(setOf("ShiftLeft", "ShiftRight"), highlight(Button.SELECT, Mapping.KEYBOARD_DEFAULT).controllerKeys)
    }

    @Test
    fun diagramsAreCroppedToTheirShapesPlusThreeUnits() {
        // A flat pad: a body 30..130 × 40..60 and a key poking out above it.
        val flat = PadLayout(
            "nes", "NES", 160.0, 100.0,
            body = listOf(Shape(30.0, 40.0, 100.0, 20.0, 4.0)),
            keys = listOf(PadKey("START", "Start", 70.0, 35.0, 12.0, 6.0, 3.0)),
        )
        assertEquals(Bounds(27.0, 32.0, 106.0, 31.0), flat.bounds())
        assertEquals(Bounds(30.0, 35.0, 100.0, 25.0), flat.bounds(padding = 0.0))
        // Nothing to draw: the whole canvas.
        assertEquals(Bounds(0.0, 0.0, 160.0, 100.0), PadLayout("x", "X", 160.0, 100.0).bounds())
    }

    @Test
    fun layoutsFitTheirBoxCentred() {
        assertEquals(Fit(2f, 0f, 50f), Fit.of(160.0, 100.0, 320f, 300f))
        assertEquals(Fit(1f, 20f, 0f), Fit.of(160.0, 100.0, 200f, 100f))
        assertEquals(Fit(1f, 0f, 0f), Fit.of(0.0, 0.0, 10f, 10f))
        assertEquals("playstation", Family.PLAYSTATION.layoutId)
        assertEquals("generic", Family.GENERIC.layoutId)
    }

    // --- Rebinding --------------------------------------------------------------------------

    private val bind = RebindCapture.Outcome::Bind
    private val waiting = RebindCapture.Outcome.Waiting

    @Test
    fun aQuickTapOfBackCancels() {
        val c = RebindCapture("east", emptySet(), 0)
        assertEquals(waiting, c.update(setOf("east"), 100))
        assertEquals(waiting, c.update(setOf("east"), 900))
        assertEquals(RebindCapture.Outcome.Cancel(timedOut = false), c.update(emptySet(), 950))
    }

    @Test
    fun holdingBackForASecondBindsItWithoutWaitingForRelease() {
        val c = RebindCapture("Escape", emptySet(), 0)
        assertEquals(waiting, c.update(setOf("Escape"), 500))
        assertEquals(waiting, c.update(setOf("Escape"), 1499))
        assertEquals(bind("Escape"), c.update(setOf("Escape"), 1500))
    }

    @Test
    fun anyOtherNewInputBindsAtOnce() {
        assertEquals(bind("south"), RebindCapture("east", emptySet(), 0).update(setOf("south"), 10))
        // Even while Back is being held.
        val c = RebindCapture("east", emptySet(), 0)
        c.update(setOf("east"), 10)
        assertEquals(bind("l2"), c.update(setOf("east", "l2"), 200))
    }

    @Test
    fun inputsHeldAtTheStartAreIgnoredUntilReleased() {
        val c = RebindCapture("east", setOf("south", "east"), 0)
        assertEquals(waiting, c.update(setOf("south", "east"), 2000)) // a held Back neither binds nor cancels
        assertEquals(waiting, c.update(emptySet(), 2100))
        assertEquals(bind("south"), c.update(setOf("south"), 2200))
    }

    @Test
    fun guideCancels() {
        val c = RebindCapture("east", emptySet(), 0)
        c.update(setOf("east"), 10)
        assertEquals(RebindCapture.Outcome.Cancel(timedOut = false), c.update(setOf("east"), 20, guide = true))
    }

    @Test
    fun eightSecondsWithoutANewInputCancels() {
        val c = RebindCapture("east", setOf("north"), 1000)
        assertEquals(waiting, c.update(setOf("north"), 8999))
        assertEquals(RebindCapture.Outcome.Cancel(timedOut = true), c.update(setOf("north"), 9000))
        assertEquals("Escape", RebindCapture.backInput(Controllers.KEYBOARD))
        assertEquals("east", RebindCapture.backInput("xbox"))
    }

    @Test
    fun touchControlsOnlyWithNothingElseToPlayWith() {
        val pad = InputDeviceInfo(7, "Pad", Controllers.identify(0x045e, 0x0b13, "Xbox Wireless Controller"), 0)
        val keyboard = InputDeviceInfo(InputMixer.KEYBOARD_DEVICE, "Keyboard", Controllers.identify(0, 0, "Keyboard"), 0)
        assertTrue(touchControlsWanted(touchscreen = true, devices = emptyList()))
        assertFalse(touchControlsWanted(touchscreen = true, devices = listOf(pad)))
        assertFalse(touchControlsWanted(touchscreen = true, devices = listOf(keyboard)))
        assertFalse(touchControlsWanted(touchscreen = false, devices = emptyList()))
    }

    @Test
    fun aPlayerIsNeverTwoDevices() {
        val players = Players()
        assertEquals(0, players.connect(10))
        assertEquals(1, players.connect(11))
        assertEquals(2, players.connect(12))
        // 12 takes player 1: 10 (who had it) takes 12's old player 3.
        assertEquals(10, players.assign(12, 0))
        assertEquals(listOf(2, 1, 0), listOf(10, 11, 12).map { players.port(it) })
        // To a free player: nobody moves.
        assertEquals(null, players.assign(11, 3))
        assertEquals(setOf(0, 2, 3), listOf(10, 11, 12).mapNotNull { players.port(it) }.toSet())
    }

    @Test
    fun movingTheKeyboardSwapsWithTheControllerThere() {
        val mixer = InputMixer()
        mixer.setKeyboardPresent(true)
        mixer.connect(7, "Pad", Controllers.identify(0x045e, 0x0b13, "Xbox Wireless Controller"))
        mixer.connect(8, "Pad 2", Controllers.identify(0x045e, 0x0b13, "Xbox Wireless Controller"))
        mixer.assign(InputMixer.KEYBOARD_DEVICE, 1)
        val ports = mixer.devices().associate { it.device to it.port }
        // Pad 8 had player 2; the keyboard's old player 1 is pad 7's, so it takes the lowest free.
        assertEquals(1, ports[InputMixer.KEYBOARD_DEVICE])
        assertEquals(0, ports[7])
        assertEquals(2, ports[8])
        // The controller on player 2 moving to the keyboard's player sends the keyboard to its old one.
        mixer.assign(7, 1)
        val after = mixer.devices().associate { it.device to it.port }
        assertEquals(1, after[7])
        assertEquals(0, after[InputMixer.KEYBOARD_DEVICE])
    }

    @Test
    fun tvRemotesAreNotControllers() {
        assertTrue(Controllers.isRemote("virtual-remote", 0x18d1, 0x0100, faceButtons = true))
        assertTrue(Controllers.isRemote("Xiaomi RC Consumer Control", 0x2717, 0x32b9, faceButtons = true))
        assertTrue(Controllers.isRemote("ir_keypad0_0", 0, 0, faceButtons = true))
        assertTrue(Controllers.isRemote("Some Pad", 0x1234, 0x5678, faceButtons = false))
        assertFalse(Controllers.isRemote("Xbox Wireless Controller", 0x045e, 0x0b13, faceButtons = true))
        assertFalse(Controllers.isRemote("8BitDo Pro 2", 0x2dc8, 0x6006, faceButtons = true))
    }
}
