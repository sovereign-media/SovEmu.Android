package media.sovereign.sovemu.core

import java.util.Locale

/*
 * Controllers as every SovEmu client names them (docs/CONTROLLERS.md in SovEmu): controller IDs,
 * families and their button labels, positional input names, and the default mappings. Change
 * that document, and all three clients, together.
 */

/** Which button labels a controller shows. `GENERIC` uses the Xbox labels. */
enum class Family(val title: String) {
    XBOX("Xbox"),
    PLAYSTATION("PlayStation"),
    NINTENDO("Nintendo"),
    GENERIC("Controller");

    /** What this family prints on [input] (a positional name: `south`, `l1`, `up` …). */
    fun label(input: String): String {
        val i = when (this) {
            PLAYSTATION -> 1
            NINTENDO -> 2
            else -> 0
        }
        return LABELS[input]?.get(i) ?: input
    }

    private companion object {
        val LABELS = mapOf(
            "south" to listOf("A", "✕", "B"),
            "east" to listOf("B", "○", "A"),
            "west" to listOf("X", "□", "Y"),
            "north" to listOf("Y", "△", "X"),
            "l1" to listOf("LB", "L1", "L"),
            "r1" to listOf("RB", "R1", "R"),
            "l2" to listOf("LT", "L2", "ZL"),
            "r2" to listOf("RT", "R2", "ZR"),
            "select" to listOf("View", "Create", "−"),
            "start" to listOf("Menu", "Options", "+"),
            "l3" to listOf("LS", "L3", "LS"),
            "r3" to listOf("RS", "R3", "RS"),
            "up" to List(3) { "D-pad ↑" },
            "down" to List(3) { "D-pad ↓" },
            "left" to List(3) { "D-pad ←" },
            "right" to List(3) { "D-pad →" },
        )
    }
}

/** A controller model: the ID its mapping is saved under, and its family. */
data class ControllerModel(val id: String, val family: Family)

object Controllers {
    /** Every keyboard shares this mapping. */
    const val KEYBOARD = "keyboard"

    /** A controller's inputs, by position: the W3C standard gamepad's buttons 0 to 15. */
    val PAD_INPUTS = listOf(
        "south", "east", "west", "north", "l1", "r1", "l2", "r2",
        "select", "start", "l3", "r3", "up", "down", "left", "right",
    )

    private const val MICROSOFT = 0x045e
    private const val SONY = 0x054c
    private const val NINTENDO = 0x057e

    private val KNOWN: Map<Int, String> = buildMap {
        for (p in intArrayOf(
            0x028e, 0x0291, 0x0719, 0x02d1, 0x02dd, 0x02e0, 0x02e3, 0x02ea, 0x02fd,
            0x0b00, 0x0b05, 0x0b12, 0x0b13, 0x0b20, 0x0b22,
        )) put(key(MICROSOFT, p), "xbox")
        put(key(SONY, 0x0ce6), "dualsense")
        put(key(SONY, 0x0df2), "dualsense")
        put(key(SONY, 0x05c4), "dualshock4")
        put(key(SONY, 0x09cc), "dualshock4")
        put(key(SONY, 0x0ba0), "dualshock4")
        put(key(NINTENDO, 0x2009), "switch-pro")
    }

    private fun key(vendor: Int, product: Int) = (vendor and 0xffff) shl 16 or (product and 0xffff)

    /**
     * Recognises a controller from Android's `InputDevice.vendorId`, `productId` and `name`: a
     * known model, else `pad:<vendor>:<product>`, else (both IDs 0) `pad:name:<slug>`.
     */
    fun identify(vendorId: Int, productId: Int, name: String): ControllerModel {
        val id = KNOWN[key(vendorId, productId)]
            ?: if (vendorId != 0 || productId != 0) {
                "pad:%04x:%04x".format(vendorId and 0xffff, productId and 0xffff)
            } else {
                "pad:name:" + slug(name).ifEmpty { "unknown" }
            }
        return ControllerModel(id, family(id, vendorId, name))
    }

    /** Lowercase; every run of other characters a `-`; trimmed of `-`; at most 48 characters. */
    fun slug(name: String): String =
        name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').take(48).trimEnd('-')

    /** From the model, else the vendor, else the name, else generic. */
    fun family(id: String, vendorId: Int, name: String): Family {
        when (id) {
            "xbox" -> return Family.XBOX
            "dualsense", "dualshock4" -> return Family.PLAYSTATION
            "switch-pro" -> return Family.NINTENDO
        }
        when (vendorId) {
            MICROSOFT -> return Family.XBOX
            SONY -> return Family.PLAYSTATION
            NINTENDO -> return Family.NINTENDO
        }
        val n = name.lowercase(Locale.ROOT)
        return when {
            "xbox" in n || "xinput" in n -> Family.XBOX
            "playstation" in n || "dualshock" in n || "dualsense" in n -> Family.PLAYSTATION
            "nintendo" in n || "joy-con" in n || "switch" in n -> Family.NINTENDO
            else -> Family.GENERIC
        }
    }

    /** The RetroPad buttons in the order a remap screen lists them. */
    val REMAP_ORDER = listOf(
        Button.UP, Button.DOWN, Button.LEFT, Button.RIGHT,
        Button.B, Button.A, Button.Y, Button.X,
        Button.L, Button.R, Button.L2, Button.R2, Button.L3, Button.R3,
        Button.SELECT, Button.START,
    )

    /** A RetroPad button's name for people. */
    fun buttonLabel(button: Button): String = when (button) {
        Button.UP -> "Up"
        Button.DOWN -> "Down"
        Button.LEFT -> "Left"
        Button.RIGHT -> "Right"
        Button.SELECT -> "Select"
        Button.START -> "Start"
        else -> button.name
    }

    /** How a remap screen shows an input of this controller (a keyboard key, or a pad input). */
    fun inputLabel(controllerId: String, family: Family, input: String): String =
        if (controllerId == KEYBOARD) Keys.label(input) else family.label(input)
}

/**
 * One controller model's bindings: each input drives one RetroPad button, and a button may
 * have several inputs. A saved mapping replaces the defaults.
 */
class Mapping private constructor(private val byInput: Map<String, Button>) {
    val bindings: List<Binding> get() = byInput.map { (input, button) -> Binding(input, button) }

    fun button(input: String): Button? = byInput[input]

    fun inputs(button: Button): List<String> = byInput.filterValues { it == button }.keys.toList()

    /** The RetroPad state (bits) for the inputs held. */
    fun buttons(held: Collection<String>): Int = held.fold(0) { state, input -> state or (byInput[input]?.bit ?: 0) }

    /**
     * [button] is now driven by [input] alone: its other inputs are unbound, and [input] leaves
     * whatever button it drove before.
     */
    fun rebind(button: Button, input: String): Mapping =
        Mapping(byInput.filter { (i, b) -> b != button && i != input } + (input to button))

    override fun equals(other: Any?) = other is Mapping && other.byInput == byInput
    override fun hashCode() = byInput.hashCode()
    override fun toString() = "Mapping($byInput)"

    companion object {
        /** A saved mapping; an input listed twice keeps its first button. */
        fun of(bindings: List<Binding>): Mapping {
            val map = LinkedHashMap<String, Button>()
            for (b in bindings) map.putIfAbsent(b.input, b.button)
            return Mapping(map)
        }

        /** By position, as libretro's SNES-shaped RetroPad expects (bottom face = B). */
        val PAD_DEFAULT = Mapping(
            linkedMapOf(
                "south" to Button.B, "east" to Button.A, "west" to Button.Y, "north" to Button.X,
                "l1" to Button.L, "r1" to Button.R, "l2" to Button.L2, "r2" to Button.R2,
                "select" to Button.SELECT, "start" to Button.START, "l3" to Button.L3, "r3" to Button.R3,
                "up" to Button.UP, "down" to Button.DOWN, "left" to Button.LEFT, "right" to Button.RIGHT,
            ),
        )

        val KEYBOARD_DEFAULT = Mapping(
            linkedMapOf(
                "ArrowUp" to Button.UP, "ArrowDown" to Button.DOWN, "ArrowLeft" to Button.LEFT, "ArrowRight" to Button.RIGHT,
                "KeyZ" to Button.B, "KeyX" to Button.A, "KeyA" to Button.Y, "KeyS" to Button.X,
                "KeyQ" to Button.L, "KeyW" to Button.R, "Enter" to Button.START,
                "ShiftLeft" to Button.SELECT, "ShiftRight" to Button.SELECT,
            ),
        )

        fun defaults(controllerId: String): Mapping =
            if (controllerId == Controllers.KEYBOARD) KEYBOARD_DEFAULT else PAD_DEFAULT
    }
}

/**
 * Android key codes as SovEmu input names: a controller's positional inputs, and a keyboard's
 * W3C `KeyboardEvent.code` values. The numbers are `android.view.KeyEvent.KEYCODE_*` (core is
 * plain JVM; the app's `KeycodesTest` checks them against the SDK).
 */
object Keys {
    /** The keyboard's fast-forward and rewind keys: never bound. */
    const val FAST_FORWARD = "Tab"
    const val REWIND = "Backspace"

    fun bindable(code: String) = code != FAST_FORWARD && code != REWIND

    /** `KEYCODE_BUTTON_*` and `KEYCODE_DPAD_*` from a controller; null for anything else. */
    fun padInput(keyCode: Int): String? = when (keyCode) {
        Android.BUTTON_A -> "south"
        Android.BUTTON_B -> "east"
        Android.BUTTON_X -> "west"
        Android.BUTTON_Y -> "north"
        Android.BUTTON_L1 -> "l1"
        Android.BUTTON_R1 -> "r1"
        Android.BUTTON_L2 -> "l2"
        Android.BUTTON_R2 -> "r2"
        Android.BUTTON_SELECT -> "select"
        Android.BUTTON_START -> "start"
        Android.BUTTON_THUMBL -> "l3"
        Android.BUTTON_THUMBR -> "r3"
        Android.DPAD_UP -> "up"
        Android.DPAD_DOWN -> "down"
        Android.DPAD_LEFT -> "left"
        Android.DPAD_RIGHT -> "right"
        else -> null
    }

    /** A keyboard key's `KeyboardEvent.code`, or null if it has none we know. */
    fun keyboardCode(keyCode: Int): String? = when (keyCode) {
        in Android.A..Android.Z -> "Key" + ('A' + (keyCode - Android.A))
        in Android.DIGIT_0..Android.DIGIT_9 -> "Digit" + (keyCode - Android.DIGIT_0)
        in Android.NUMPAD_0..Android.NUMPAD_9 -> "Numpad" + (keyCode - Android.NUMPAD_0)
        in Android.F1..Android.F12 -> "F" + (keyCode - Android.F1 + 1)
        else -> NAMED[keyCode]
    }

    private val NAMED = mapOf(
        Android.DPAD_UP to "ArrowUp", Android.DPAD_DOWN to "ArrowDown",
        Android.DPAD_LEFT to "ArrowLeft", Android.DPAD_RIGHT to "ArrowRight",
        Android.ENTER to "Enter", Android.NUMPAD_ENTER to "NumpadEnter", Android.SPACE to "Space",
        Android.SHIFT_LEFT to "ShiftLeft", Android.SHIFT_RIGHT to "ShiftRight",
        Android.CTRL_LEFT to "ControlLeft", Android.CTRL_RIGHT to "ControlRight",
        Android.ALT_LEFT to "AltLeft", Android.ALT_RIGHT to "AltRight",
        Android.META_LEFT to "MetaLeft", Android.META_RIGHT to "MetaRight",
        Android.ESCAPE to "Escape", Android.TAB to "Tab", Android.DEL to "Backspace",
        Android.FORWARD_DEL to "Delete", Android.INSERT to "Insert",
        Android.MOVE_HOME to "Home", Android.MOVE_END to "End",
        Android.PAGE_UP to "PageUp", Android.PAGE_DOWN to "PageDown", Android.CAPS_LOCK to "CapsLock",
        Android.MINUS to "Minus", Android.EQUALS to "Equal",
        Android.LEFT_BRACKET to "BracketLeft", Android.RIGHT_BRACKET to "BracketRight",
        Android.BACKSLASH to "Backslash", Android.SEMICOLON to "Semicolon", Android.APOSTROPHE to "Quote",
        Android.GRAVE to "Backquote", Android.COMMA to "Comma", Android.PERIOD to "Period", Android.SLASH to "Slash",
    )

    /** A `KeyboardEvent.code` for people: `KeyZ` is "Z", `ArrowUp` "↑", `ShiftLeft` "Left Shift". */
    fun label(code: String): String = when {
        code.startsWith("Key") && code.length == 4 -> code.substring(3)
        code.startsWith("Digit") -> code.substring(5)
        code == "ArrowUp" -> "↑"
        code == "ArrowDown" -> "↓"
        code == "ArrowLeft" -> "←"
        code == "ArrowRight" -> "→"
        code.endsWith("Left") -> "Left " + code.removeSuffix("Left").replace("Control", "Ctrl")
        code.endsWith("Right") -> "Right " + code.removeSuffix("Right").replace("Control", "Ctrl")
        else -> code
    }

    /** `android.view.KeyEvent` constants. */
    object Android {
        const val DPAD_UP = 19
        const val DPAD_DOWN = 20
        const val DPAD_LEFT = 21
        const val DPAD_RIGHT = 22
        const val DIGIT_0 = 7
        const val DIGIT_9 = 16
        const val A = 29
        const val Z = 54
        const val COMMA = 55
        const val PERIOD = 56
        const val ALT_LEFT = 57
        const val ALT_RIGHT = 58
        const val SHIFT_LEFT = 59
        const val SHIFT_RIGHT = 60
        const val TAB = 61
        const val SPACE = 62
        const val ENTER = 66
        const val DEL = 67
        const val GRAVE = 68
        const val MINUS = 69
        const val EQUALS = 70
        const val LEFT_BRACKET = 71
        const val RIGHT_BRACKET = 72
        const val BACKSLASH = 73
        const val SEMICOLON = 74
        const val APOSTROPHE = 75
        const val SLASH = 76
        const val PAGE_UP = 92
        const val PAGE_DOWN = 93
        const val BUTTON_A = 96
        const val BUTTON_B = 97
        const val BUTTON_X = 99
        const val BUTTON_Y = 100
        const val BUTTON_L1 = 102
        const val BUTTON_R1 = 103
        const val BUTTON_L2 = 104
        const val BUTTON_R2 = 105
        const val BUTTON_THUMBL = 106
        const val BUTTON_THUMBR = 107
        const val BUTTON_START = 108
        const val BUTTON_SELECT = 109
        const val ESCAPE = 111
        const val FORWARD_DEL = 112
        const val CTRL_LEFT = 113
        const val CTRL_RIGHT = 114
        const val CAPS_LOCK = 115
        const val META_LEFT = 117
        const val META_RIGHT = 118
        const val MOVE_HOME = 122
        const val MOVE_END = 123
        const val INSERT = 124
        const val F1 = 131
        const val F12 = 142
        const val NUMPAD_0 = 144
        const val NUMPAD_9 = 153
        const val NUMPAD_ENTER = 160
    }
}

/**
 * Hotkeys, read after mapping from each port's RetroPad buttons: Select+R fast-forwards,
 * Select+L rewinds, Select+Start opens the menu. A combo's buttons are kept from the game while
 * it is held.
 */
object Hotkeys {
    data class Result(val ports: List<Int>, val fastForward: Boolean, val rewind: Boolean, val menu: Boolean)

    fun apply(ports: List<Int>): Result {
        var ff = false
        var rewind = false
        var menu = false
        val masked = ports.map { state ->
            if (state and Button.SELECT.bit == 0) return@map state
            var mask = 0
            if (state and Button.R.bit != 0) { ff = true; mask = mask or Button.SELECT.bit or Button.R.bit }
            if (state and Button.L.bit != 0) { rewind = true; mask = mask or Button.SELECT.bit or Button.L.bit }
            if (state and Button.START.bit != 0) { menu = true; mask = mask or Button.SELECT.bit or Button.START.bit }
            state and mask.inv()
        }
        return Result(masked, ff, rewind, menu)
    }
}

/**
 * Which player each controller is. A new controller takes the lowest free player, in the order
 * controllers connect; a disconnected one frees its player. Players are ports 0-3 (shown 1-4).
 * Not thread-safe: [InputMixer] guards it.
 */
class Players(private val count: Int = Protocol.MAX_PORTS) {
    private val byDevice = LinkedHashMap<Int, Int?>()

    /** The device's player (its existing one if already connected); null when all are taken. */
    fun connect(device: Int): Int? {
        if (device in byDevice) return byDevice[device]
        val taken = byDevice.values.filterNotNull().toSet()
        val port = (0 until count).firstOrNull { it !in taken }
        byDevice[device] = port
        return port
    }

    fun disconnect(device: Int) {
        byDevice.remove(device)
    }

    /** Moves a connected device to [port], for this session only. */
    fun assign(device: Int, port: Int) {
        require(port in 0 until count) { "no player ${port + 1}" }
        if (device in byDevice) byDevice[device] = port
    }

    fun port(device: Int): Int? = byDevice[device]

    val devices: List<Int> get() = byDevice.keys.toList()
}

/** The left stick (or a hat) as D-pad bits: past half-way on an axis presses that direction. */
fun dpadBits(x: Float, y: Float): Int {
    var state = 0
    if (x < -0.5f) state = state or Button.LEFT.bit
    if (x > 0.5f) state = state or Button.RIGHT.bit
    if (y < -0.5f) state = state or Button.UP.bit
    if (y > 0.5f) state = state or Button.DOWN.bit
    return state
}

/** The positional inputs a controller's hat and analog triggers are pressing. */
fun axisInputs(hatX: Float, hatY: Float, leftTrigger: Float, rightTrigger: Float): Set<String> = buildSet {
    if (hatX < -0.5f) add("left")
    if (hatX > 0.5f) add("right")
    if (hatY < -0.5f) add("up")
    if (hatY > 0.5f) add("down")
    if (leftTrigger > 0.5f) add("l2")
    if (rightTrigger > 0.5f) add("r2")
}

/** Two devices on one port: each axis takes whichever is further from centre. */
fun mixAnalog(a: Protocol.Analog, b: Protocol.Analog): Protocol.Analog {
    fun pick(x: Int, y: Int) = if (kotlin.math.abs(y) > kotlin.math.abs(x)) y else x
    return Protocol.Analog(pick(a.lx, b.lx), pick(a.ly, b.ly), pick(a.rx, b.rx), pick(a.ry, b.ry), pick(a.l2, b.l2), pick(a.r2, b.r2))
}

/** A connected device as a remap screen lists it. */
data class InputDeviceInfo(
    /** Android's device ID; [InputMixer.KEYBOARD_DEVICE] for the keyboard(s). */
    val device: Int,
    val name: String,
    val model: ControllerModel,
    /** 0-3, or null when four players are already taken. */
    val port: Int?,
) {
    val isKeyboard: Boolean get() = device == InputMixer.KEYBOARD_DEVICE
}

/**
 * Every device's RetroPad state, per player: each controller through its model's mapping into
 * its own port; the keyboard (all keyboards) through the keyboard mapping into its port (player
 * 1 unless reassigned); the touch controller into port 0. Thread-safe: input events arrive on
 * the main thread and the stream reads from another.
 */
class InputMixer {
    private class Pad(val name: String, val model: ControllerModel) {
        val held = HashSet<String>()
        var axes: Set<String> = emptySet()
        var stick = 0
        var analog = Protocol.Analog.CENTRED
    }

    data class Reading(
        /** One RetroPad state per port (always [Protocol.MAX_PORTS]), hotkey combos removed. */
        val ports: List<Int>,
        val analog: List<Protocol.Analog>,
        /** Ports in use: the highest player any device is on, plus one. */
        val count: Int,
        val fastForward: Boolean,
        val rewind: Boolean,
        val menu: Boolean,
    )

    private val lock = Any()
    private val pads = LinkedHashMap<Int, Pad>()
    private val players = Players()
    private val mappings = HashMap<String, Mapping>()
    private val sources = HashMap<String, String>()
    private val keys = HashSet<String>()
    private var touch = 0
    private var keyboardPresent = false
    private var keyboardPort = 0
    private val keyboardModel = ControllerModel(Controllers.KEYBOARD, Family.GENERIC)

    /** The game reads analog sticks: the left stick no longer doubles as the D-pad. */
    @Volatile var analogSticks = false

    /** Connects a controller (again: no change); returns its player. */
    fun connect(device: Int, name: String, model: ControllerModel): Int? = synchronized(lock) {
        if (device !in pads) pads[device] = Pad(name, model)
        players.connect(device)
    }

    fun disconnect(device: Int) = synchronized(lock) {
        pads.remove(device)
        players.disconnect(device)
    }

    fun isPad(device: Int): Boolean = synchronized(lock) { device in pads }

    fun model(device: Int): ControllerModel? = synchronized(lock) {
        if (device == KEYBOARD_DEVICE) keyboardModel else pads[device]?.model
    }

    fun setKeyboardPresent(present: Boolean) = synchronized(lock) { keyboardPresent = present }

    /** Moves a device (or the keyboard) to another player for this session. */
    fun assign(device: Int, port: Int) = synchronized(lock) {
        require(port in 0 until Protocol.MAX_PORTS)
        if (device == KEYBOARD_DEVICE) keyboardPort = port else players.assign(device, port)
    }

    /**
     * The mappings in force for this user (`controllerMappings`: theirs, else the server's
     * default), replacing whatever was loaded before. A model not listed uses the built-in defaults.
     */
    fun setMappings(inForce: List<ControllerMapping>) = synchronized(lock) {
        mappings.clear()
        sources.clear()
        for (m in inForce) {
            mappings[m.controller] = Mapping.of(m.bindings)
            sources[m.controller] = m.source
        }
    }

    /** One model's mapping and where it comes from; null puts it on the built-in defaults. */
    fun setMapping(controllerId: String, mapping: Mapping?, source: String = MappingSource.USER) = synchronized(lock) {
        if (mapping == null) {
            mappings.remove(controllerId)
            sources.remove(controllerId)
        } else {
            mappings[controllerId] = mapping
            sources[controllerId] = source
        }
    }

    /** Where a model's mapping comes from (a [MappingSource]); null for the built-in defaults. */
    fun source(controllerId: String): String? = synchronized(lock) { sources[controllerId] }

    /**
     * Puts a model back on the defaults: [reset] (`resetControllerMapping`), then [fetch]
     * (`controllerMappings`) and use whatever the server now has in force for it (an admin's
     * default), or the built-in defaults if nothing. Returns the mapping now in force.
     */
    suspend fun resetMapping(
        controllerId: String,
        reset: suspend (String) -> Boolean,
        fetch: suspend () -> List<ControllerMapping>,
    ): Mapping {
        reset(controllerId)
        setMappings(fetch())
        return mapping(controllerId)
    }

    fun mapping(controllerId: String): Mapping = synchronized(lock) {
        mappings[controllerId] ?: Mapping.defaults(controllerId)
    }

    /** A controller input (`south`, `up` …) pressed or released. */
    fun pad(device: Int, input: String, down: Boolean) = synchronized(lock) {
        val pad = pads[device] ?: return@synchronized
        if (down) pad.held += input else pad.held -= input
    }

    /** A controller's sticks, triggers and hat. */
    fun padAxes(device: Int, axes: Set<String>, stickX: Float, stickY: Float, analog: Protocol.Analog) = synchronized(lock) {
        val pad = pads[device] ?: return@synchronized
        pad.axes = axes
        pad.stick = dpadBits(stickX, stickY)
        pad.analog = analog
    }

    /** A keyboard key, by `KeyboardEvent.code`, pressed or released. */
    fun key(code: String, down: Boolean) = synchronized(lock) {
        keyboardPresent = true
        if (down) keys += code else keys -= code
    }

    /** Whether the keyboard mapping (or a hotkey) uses this key, so the game should get it. */
    fun keyUsed(code: String): Boolean = !Keys.bindable(code) || mapping(Controllers.KEYBOARD).button(code) != null

    fun touch(button: Button, down: Boolean) = synchronized(lock) {
        touch = if (down) touch or button.bit else touch and button.bit.inv()
    }

    /** Lets go of everything held (the game lost the input: menu, pause, quit). */
    fun release() = synchronized(lock) {
        for (pad in pads.values) {
            pad.held.clear()
            pad.axes = emptySet()
            pad.stick = 0
            pad.analog = Protocol.Analog.CENTRED
        }
        keys.clear()
        touch = 0
    }

    fun read(): Reading = synchronized(lock) {
        val ports = IntArray(Protocol.MAX_PORTS)
        val analog = MutableList(Protocol.MAX_PORTS) { Protocol.Analog.CENTRED }
        var count = 1
        ports[0] = touch
        for ((device, pad) in pads) {
            val port = players.port(device) ?: continue
            val map = mappings[pad.model.id] ?: Mapping.PAD_DEFAULT
            var state = map.buttons(pad.held) or map.buttons(pad.axes)
            if (!analogSticks) state = state or pad.stick
            ports[port] = ports[port] or state
            analog[port] = mixAnalog(analog[port], pad.analog)
            count = maxOf(count, port + 1)
        }
        if (keyboardPresent) {
            val map = mappings[Controllers.KEYBOARD] ?: Mapping.KEYBOARD_DEFAULT
            ports[keyboardPort] = ports[keyboardPort] or map.buttons(keys)
            count = maxOf(count, keyboardPort + 1)
        }
        val hot = Hotkeys.apply(ports.toList())
        Reading(
            ports = hot.ports,
            analog = analog,
            count = count,
            fastForward = hot.fastForward || Keys.FAST_FORWARD in keys,
            rewind = hot.rewind || Keys.REWIND in keys,
            menu = hot.menu,
        )
    }

    /** The keyboard (if any) first, then the controllers in the order they connected. */
    fun devices(): List<InputDeviceInfo> = synchronized(lock) {
        buildList {
            if (keyboardPresent) add(InputDeviceInfo(KEYBOARD_DEVICE, "Keyboard", keyboardModel, keyboardPort))
            for ((device, pad) in pads) add(InputDeviceInfo(device, pad.name, pad.model, players.port(device)))
        }
    }

    companion object {
        /** The device ID [devices] gives the keyboard (Android's device IDs are never this). */
        const val KEYBOARD_DEVICE = Int.MIN_VALUE
    }
}
