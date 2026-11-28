package media.sovereign.sovemu

import android.content.Context
import android.hardware.input.InputManager
import android.hardware.lights.Light
import android.hardware.lights.LightState
import android.hardware.lights.LightsRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.annotation.RequiresApi
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import media.sovereign.sovemu.core.Button
import media.sovereign.sovemu.core.ControllerMapping
import media.sovereign.sovemu.core.ControllerModel
import media.sovereign.sovemu.core.Controllers
import media.sovereign.sovemu.core.InputDeviceInfo
import media.sovereign.sovemu.core.InputMixer
import media.sovereign.sovemu.core.Keys
import media.sovereign.sovemu.core.Protocol
import media.sovereign.sovemu.core.RebindCapture
import media.sovereign.sovemu.core.axisInputs

/**
 * Android's input devices, fed into an [InputMixer] (core): each controller is identified by
 * model (docs/CONTROLLERS.md), mapped through its user's mapping, and drives its own player's
 * port; keyboards share the keyboard mapping; the touch controller is player 1. The activity
 * feeds key and motion events in; the stream reads [read].
 *
 * A remap screen can [startCapture] the input to bind on one controller model instead.
 */
class Controller {
    val mixer = InputMixer()

    private val _devices = MutableStateFlow<List<InputDeviceInfo>>(emptyList())

    /** The connected keyboard and controllers, with their players, for the remap screen. */
    val devices: StateFlow<List<InputDeviceInfo>> = _devices.asStateFlow()

    private var inputManager: InputManager? = null
    private val keyboards = HashSet<Int>()

    /** The game reads analog sticks (a 3D system): the left stick no longer drives the D-pad. */
    var analogSticks: Boolean
        get() = mixer.analogSticks
        set(value) {
            mixer.analogSticks = value
        }

    /**
     * Input goes to the game. False while the in-game menu or the remap screen is open, so the
     * system's focus navigation gets it instead; turning it off lets go of everything held.
     */
    @Volatile var toGame = true
        set(value) {
            if (!value) mixer.release()
            field = value
            menuHeld = false
            wake()
        }

    private val listener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = add(deviceId)
        override fun onInputDeviceRemoved(deviceId: Int) = remove(deviceId)
        override fun onInputDeviceChanged(deviceId: Int) {
            remove(deviceId)
            add(deviceId)
        }
    }

    /** Starts tracking devices; the ones already connected join in device-ID order. */
    fun start(context: Context) {
        val im = context.getSystemService(InputManager::class.java) ?: return
        inputManager = im
        im.registerInputDeviceListener(listener, Handler(Looper.getMainLooper()))
        for (id in InputDevice.getDeviceIds().sorted()) add(id)
    }

    fun stop() {
        inputManager?.unregisterInputDeviceListener(listener)
        inputManager = null
    }

    /**
     * A controller: a gamepad or joystick with A/B face buttons, and not a TV remote (remotes
     * often claim gamepad sources for their D-pad; theirs goes through the keyboard path, so a
     * real controller is player 1 without being moved).
     */
    private fun isPad(d: InputDevice): Boolean {
        if (d.isVirtual) return false
        if (!d.supportsSource(InputDevice.SOURCE_GAMEPAD) &&
            !d.supportsSource(InputDevice.SOURCE_JOYSTICK)
        ) {
            return false
        }
        val faceButtons = d.hasKeys(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B).all { it }
        return !Controllers.isRemote(d.name, d.vendorId, d.productId, faceButtons)
    }

    private fun isKeyboard(d: InputDevice): Boolean =
        !d.isVirtual && !isPad(d) && d.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC

    private fun add(id: Int) {
        val d = InputDevice.getDevice(id) ?: return
        when {
            isPad(d) -> connectPad(id, d)

            isKeyboard(d) -> {
                keyboards += id
                mixer.setKeyboardPresent(true)
            }
        }
        publish()
    }

    private fun remove(id: Int) {
        if (mixer.isPad(id)) Log.i(TAG, "controller $id removed")
        mixer.disconnect(id)
        if (keyboards.remove(id) && keyboards.isEmpty()) mixer.setKeyboardPresent(false)
        publish()
    }

    private fun connectPad(id: Int, d: InputDevice) {
        val name = d.name ?: "Controller"
        val model = Controllers.identify(d.vendorId, d.productId, name)
        val port = mixer.connect(id, name, model, physical = d.descriptor)
        Log.i(TAG, "controller $id ($name, ${model.id}) connected as player ${port?.plus(1)}")
        // A model docs/CONTROLLERS.md does not know yet: worth adding.
        if (model.id.startsWith(
                "pad:",
            )
        ) {
            ClientEvents.report("CONTROLLER_UNKNOWN", "$name → ${model.id}")
        }
    }

    /**
     * A device we did not see connect (or that hides its gamepad source) sends a pad button. One
     * Android no longer knows is not a controller: a pad that reconnects (Bluetooth) comes back
     * under a new ID, and the last events of its old one would otherwise add a nameless
     * "Controller" that nothing ever removes, in a player's seat.
     */
    private fun padDevice(id: Int, keyCode: Int): Boolean {
        if (mixer.isPad(id)) return true
        if (!KeyEvent.isGamepadButton(keyCode)) return false
        val d = InputDevice.getDevice(id) ?: return false
        if (!isPad(d)) return false
        connectPad(id, d)
        publish()
        return true
    }

    private fun publish() {
        _devices.value = mixer.devices()
    }

    /**
     * Shows which physical controller [device] is: rumbles it and blinks its lights, each where
     * Android can drive them (a PlayStation pad's light bar, a Switch pad's player LEDs; an Xbox
     * pad over Bluetooth only rumbles). Needs Android 12; false when it can do neither.
     */
    fun identify(device: Int, scope: CoroutineScope): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val d = InputDevice.getDevice(device) ?: return false
        var did = false
        val vibrator = d.vibratorManager.defaultVibrator
        if (vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 180, 140, 180), -1))
            did = true
        }
        val lights = d.lightsManager.lights.filter {
            it.hasRgbControl() || it.type == Light.LIGHT_TYPE_PLAYER_ID ||
                it.type == Light.LIGHT_TYPE_INPUT
        }
        if (lights.isNotEmpty()) {
            scope.launch {
                val session = d.lightsManager.openSession()
                try {
                    repeat(3) {
                        session.requestLights(lightsRequest(lights, on = true))
                        delay(250)
                        session.requestLights(lightsRequest(lights, on = false))
                        delay(250)
                    }
                } finally {
                    // Closing hands the lights back to the system (its own colour, player number).
                    session.close()
                }
            }
            did = true
        }
        return did
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun lightsRequest(lights: List<Light>, on: Boolean): LightsRequest =
        LightsRequest.Builder().apply {
            for (light in lights) {
                val state = LightState.Builder()
                if (light.hasRgbControl()) {
                    state.setColor(
                        if (on) 0xFFFFFFFF.toInt() else 0xFF000000.toInt(),
                    )
                }
                if (light.type == Light.LIGHT_TYPE_PLAYER_ID) state.setPlayerId(if (on) 4 else 0)
                addLight(light, state.build())
            }
        }.build()

    /** Moves a device (or [InputMixer.KEYBOARD_DEVICE]) to another player, for this session. */
    fun assign(device: Int, port: Int) {
        mixer.assign(device, port)
        publish()
        wake()
    }

    /** The mappings in force ([InputMixer.setMappings]), sent to the game at once. */
    fun setMappings(inForce: List<ControllerMapping>) {
        mixer.setMappings(inForce)
        wake()
    }

    /**
     * Told (at most once per wait) whenever anything the game is sent changes: a key, a stick, a
     * trigger or a touch button, the player a device is on, the mappings, input going to the game
     * or not. The stream's pump sleeps until then or its next heartbeat, so every change must
     * say so ([wake]); one that did not would wait for the heartbeat.
     */
    val changes = Channel<Unit>(Channel.CONFLATED)

    /** Wakes the stream's pump to look at the input now. */
    fun wake() {
        changes.trySend(Unit)
    }

    /**
     * When the input event behind the last change happened, on [android.os.SystemClock]'s
     * uptime clock (`KeyEvent.eventTime`, the clock acks are measured on): the pump stamps the
     * change with it, so the round trip includes the hop from the event to the pump.
     */
    @Volatile var changedAtMs = 0L
        private set

    /** Select+Start is held (after mapping), as last read; its press opens the menu. */
    private var menuHeld = false
    private val menuCheck = InputMixer.Snapshot()

    /** An input changed at [atMs]: Select+Start's press asks for the menu, and the pump wakes. */
    private fun changed(atMs: Long) {
        changedAtMs = atMs
        mixer.read(menuCheck)
        if (menuCheck.menu && !menuHeld) requestMenu()
        menuHeld = menuCheck.menu
        wake()
    }

    /** A key from any device while playing. Returns true if the game took it. */
    fun onKey(event: KeyEvent): Boolean = key(event).also { if (it) changed(event.eventTime) }

    private fun key(event: KeyEvent): Boolean {
        val down = when (event.action) {
            KeyEvent.ACTION_DOWN -> true
            KeyEvent.ACTION_UP -> false
            else -> return false
        }
        val id = event.deviceId
        if (padDevice(id, event.keyCode)) {
            // BACK, the guide button and anything else unnamed stay the system's.
            val input = Keys.padInput(event.keyCode) ?: return false
            mixer.pad(id, input, down)
            return true
        }
        val code = Keys.keyboardCode(event.keyCode) ?: return false
        // An unbound key (Escape, say) falls through: Escape then works as Back (the menu).
        if (!mixer.keyUsed(code)) return false
        val wasPresent = _devices.value.any { it.isKeyboard }
        mixer.key(code, down)
        if (!wasPresent) publish()
        return true
    }

    /** Sticks, triggers and the hat from a joystick. Returns true if consumed. */
    fun onMotion(event: MotionEvent): Boolean =
        motion(event).also { if (it) changed(event.eventTime) }

    private fun motion(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK)) return false
        val id = event.deviceId
        if (!mixer.isPad(id)) {
            val d = InputDevice.getDevice(id) ?: return false
            if (!isPad(d)) return false
            connectPad(id, d)
            publish()
        }
        val sx = event.getAxisValue(MotionEvent.AXIS_X)
        val sy = event.getAxisValue(MotionEvent.AXIS_Y)
        val analog = Protocol.Analog(
            lx = stick(sx),
            ly = stick(sy),
            rx = stick(event.getAxisValue(MotionEvent.AXIS_Z)),
            ry = stick(event.getAxisValue(MotionEvent.AXIS_RZ)),
            l2 = trigger(leftTrigger(event)),
            r2 = trigger(rightTrigger(event)),
        )
        mixer.padAxes(id, axes(event), sx, sy, analog)
        return true
    }

    private fun leftTrigger(e: MotionEvent) =
        maxOf(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE))
    private fun rightTrigger(e: MotionEvent) =
        maxOf(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS))

    /**
     * The hat as `up`/`down`/`left`/`right`, the analog triggers as `l2`/`r2`. Asked of each
     * motion event twice (the remap screen's capture keeps what is held, then the game), which
     * costs a few comparisons: nothing is made unless something is over the line.
     */
    private fun axes(e: MotionEvent): Set<String> = axisInputs(
        e.getAxisValue(MotionEvent.AXIS_HAT_X),
        e.getAxisValue(MotionEvent.AXIS_HAT_Y),
        leftTrigger(e),
        rightTrigger(e),
        e.getAxisValue(MotionEvent.AXIS_X),
        e.getAxisValue(MotionEvent.AXIS_Y),
        e.getAxisValue(MotionEvent.AXIS_Z),
        e.getAxisValue(MotionEvent.AXIS_RZ),
    )

    fun setTouch(button: Button, pressed: Boolean) {
        mixer.touch(button, pressed)
        changed(now())
    }

    /**
     * The app lost its window (a system overlay, a notification panel, another app): Android
     * stops sending it events, so a held button's release would never arrive.
     */
    fun lostFocus() {
        Log.w(TAG, "window lost focus: releasing held input")
        mixer.release()
        menuHeld = false
        wake()
    }

    /** Lets go of everything (leaving the game). */
    fun clear() {
        cancelCapture()
        mixer.release()
        toGame = true
    }

    /** The controllers' state, into the reader's own [into]. */
    fun read(into: InputMixer.Snapshot) = mixer.read(into)

    private val _menuRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Asks for the in-game menu: the guide/home button, the touch controller's menu button, or
     * Select+Start's press. With Back, the ways out of a game.
     */
    val menuRequests: SharedFlow<Unit> = _menuRequests.asSharedFlow()

    fun requestMenu() {
        _menuRequests.tryEmit(Unit)
    }

    // --- Capturing an input for the remap screen ---------------------------------------------

    private class Capture(
        val model: ControllerModel,
        val machine: RebindCapture,
        val onInput: (String) -> Unit,
        val onCancel: (timedOut: Boolean) -> Unit,
        val onRejected: (String) -> Unit,
    )

    @Volatile private var capture: Capture? = null

    /**
     * Every device's held keys and axis inputs, tracked always so a capture knows what was already
     * held.
     */
    private val heldKeys = HashMap<Int, MutableSet<Int>>()
    private val heldAxes = HashMap<Int, Set<String>>()

    /** Keys whose press a capture consumed: their release is consumed too. */
    private val swallow = HashSet<Int>()
    private val main = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (capture == null) return
            evaluate()
            if (capture != null) main.postDelayed(this, 50)
        }
    }

    val capturing: Boolean get() = capture != null

    /**
     * Waits for the input to bind on a device of [model] (any keyboard, for the keyboard), as
     * docs/CONTROLLERS.md's "Remapping" says ([RebindCapture]): a new input binds; a tap of Back
     * (the pad's B/east, Escape) cancels and holding it 1 s binds it; home/guide, the system Back
     * or 8 s without input cancel. Callbacks run on the main thread.
     */
    fun startCapture(
        model: ControllerModel,
        onInput: (String) -> Unit,
        onCancel: (timedOut: Boolean) -> Unit,
        onRejected: (String) -> Unit,
    ) {
        val back = RebindCapture.backInput(model.id)
        capture =
            Capture(model, RebindCapture(back, held(model), now()), onInput, onCancel, onRejected)
        main.removeCallbacks(tick)
        main.postDelayed(tick, 50)
    }

    fun cancelCapture() {
        capture = null
        main.removeCallbacks(tick)
    }

    private fun now() = android.os.SystemClock.uptimeMillis()

    /**
     * The inputs held now on devices of [model]: bindable keyboard codes, or a pad's positional
     * inputs.
     */
    private fun held(model: ControllerModel): Set<String> = buildSet {
        for ((id, codes) in heldKeys) {
            val pad = mixer.isPad(id)
            if (model.id == Controllers.KEYBOARD) {
                if (!pad) codes.mapNotNull(Keys::keyboardCode).filter(Keys::bindable).forEach(::add)
            } else if (mixer.model(id)?.id == model.id) {
                codes.mapNotNull(Keys::padInput).forEach(::add)
            }
        }
        if (model.id != Controllers.KEYBOARD) {
            for ((id, axes) in heldAxes) if (mixer.model(id)?.id == model.id) addAll(axes)
        }
    }

    private fun evaluate(guide: Boolean = false) {
        val c = capture ?: return
        when (val outcome = c.machine.update(held(c.model), now(), guide)) {
            is RebindCapture.Outcome.Bind -> {
                cancelCapture()
                c.onInput(outcome.input)
            }

            is RebindCapture.Outcome.Cancel -> {
                cancelCapture()
                c.onCancel(outcome.timedOut)
            }

            RebindCapture.Outcome.Waiting -> {}
        }
    }

    /** Called first for every key event. Returns true if a capture (or its aftermath) took it. */
    fun captureKey(event: KeyEvent): Boolean {
        val id = event.deviceId
        when (event.action) {
            KeyEvent.ACTION_DOWN -> heldKeys.getOrPut(id) { HashSet() } += event.keyCode
            KeyEvent.ACTION_UP -> heldKeys[id]?.remove(event.keyCode)
        }
        val c = capture
        if (event.action == KeyEvent.ACTION_UP && swallow.remove(event.keyCode)) {
            if (c != null) evaluate()
            return true
        }
        if (c == null) return false
        if (event.action != KeyEvent.ACTION_DOWN) return true
        swallow += event.keyCode
        if (event.repeatCount > 0) return true
        // The system's own Back (a phone's gesture or a pad's Back button) always cancels.
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            cancelCapture()
            c.onCancel(false)
            return true
        }
        val keyboard = c.model.id == Controllers.KEYBOARD
        val pad = mixer.isPad(id) || KeyEvent.isGamepadButton(event.keyCode)
        if (keyboard && !pad) {
            val code = Keys.keyboardCode(event.keyCode)
            when {
                code == null -> c.onRejected("That key can't be bound")

                !Keys.bindable(
                    code,
                ) -> c.onRejected(
                    "$code is the " +
                        "${if (code == Keys.FAST_FORWARD) "fast-forward" else "rewind"} key",
                )
            }
        }
        val guide =
            !keyboard && event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE &&
                mixer.model(id)?.id == c.model.id
        evaluate(guide)
        return true
    }

    /** Called first for every motion event: a pad's trigger or hat can be captured too. */
    fun captureMotion(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK)) return false
        heldAxes[event.deviceId] = axes(event)
        if (capture == null) return false
        evaluate()
        return true
    }

    companion object {
        private const val TAG = "SovEmu.Input"
        private const val DEADZONE = 0.15f

        /** A stick axis (−1..1) in libretro's range, with the dead zone taken out. */
        fun stick(v: Float): Int {
            val m = abs(v)
            if (m < DEADZONE) return 0
            val scaled = ((m - DEADZONE) / (1 - DEADZONE)).coerceAtMost(1f)
            return (sign(v) * scaled * 32767).roundToInt()
        }

        fun trigger(v: Float): Int = (v.coerceIn(0f, 1f) * 32767).roundToInt()
    }
}
