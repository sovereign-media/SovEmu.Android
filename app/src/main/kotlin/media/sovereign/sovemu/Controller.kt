package media.sovereign.sovemu

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import media.sovereign.sovemu.core.Button
import media.sovereign.sovemu.core.ControllerModel
import media.sovereign.sovemu.core.Controllers
import media.sovereign.sovemu.core.InputDeviceInfo
import media.sovereign.sovemu.core.InputMixer
import media.sovereign.sovemu.core.Keys
import media.sovereign.sovemu.core.Protocol
import media.sovereign.sovemu.core.RebindCapture
import media.sovereign.sovemu.core.axisInputs
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

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

    private fun isPad(d: InputDevice): Boolean =
        !d.isVirtual && (d.supportsSource(InputDevice.SOURCE_GAMEPAD) || d.supportsSource(InputDevice.SOURCE_JOYSTICK))

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
        mixer.disconnect(id)
        if (keyboards.remove(id) && keyboards.isEmpty()) mixer.setKeyboardPresent(false)
        publish()
    }

    private fun connectPad(id: Int, d: InputDevice?) {
        val name = d?.name ?: "Controller"
        val model = Controllers.identify(d?.vendorId ?: 0, d?.productId ?: 0, name)
        mixer.connect(id, name, model)
    }

    /** A device we did not see connect (or that hides its gamepad source) sends a pad button. */
    private fun padDevice(id: Int, keyCode: Int): Boolean {
        if (mixer.isPad(id)) return true
        if (!KeyEvent.isGamepadButton(keyCode)) return false
        connectPad(id, InputDevice.getDevice(id))
        publish()
        return true
    }

    private fun publish() {
        _devices.value = mixer.devices()
    }

    /** Moves a device (or [InputMixer.KEYBOARD_DEVICE]) to another player, for this session. */
    fun assign(device: Int, port: Int) {
        mixer.assign(device, port)
        publish()
    }

    /** A key from any device while playing. Returns true if the game took it. */
    fun onKey(event: KeyEvent): Boolean {
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
    fun onMotion(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK)) return false
        val id = event.deviceId
        if (!mixer.isPad(id)) {
            connectPad(id, InputDevice.getDevice(id))
            publish()
        }
        val sx = event.getAxisValue(MotionEvent.AXIS_X)
        val sy = event.getAxisValue(MotionEvent.AXIS_Y)
        val analog = Protocol.Analog(
            lx = stick(sx), ly = stick(sy),
            rx = stick(event.getAxisValue(MotionEvent.AXIS_Z)), ry = stick(event.getAxisValue(MotionEvent.AXIS_RZ)),
            l2 = trigger(leftTrigger(event)), r2 = trigger(rightTrigger(event)),
        )
        mixer.padAxes(id, axes(event), sx, sy, analog)
        return true
    }

    private fun leftTrigger(e: MotionEvent) = maxOf(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE))
    private fun rightTrigger(e: MotionEvent) = maxOf(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS))

    /** The hat as `up`/`down`/`left`/`right`, the analog triggers as `l2`/`r2`. */
    private fun axes(e: MotionEvent): Set<String> = axisInputs(
        e.getAxisValue(MotionEvent.AXIS_HAT_X), e.getAxisValue(MotionEvent.AXIS_HAT_Y), leftTrigger(e), rightTrigger(e),
    )

    fun setTouch(button: Button, pressed: Boolean) = mixer.touch(button, pressed)

    /** Lets go of everything (leaving the game). */
    fun clear() {
        cancelCapture()
        mixer.release()
        toGame = true
    }

    fun read(): InputMixer.Reading = mixer.read()

    /** Select+Start (after mapping) opens the in-game menu. */
    val menuCombo: Boolean get() = mixer.read().menu

    // --- Capturing an input for the remap screen ---------------------------------------------

    private class Capture(
        val model: ControllerModel,
        val machine: RebindCapture,
        val onInput: (String) -> Unit,
        val onCancel: (timedOut: Boolean) -> Unit,
        val onRejected: (String) -> Unit,
    )

    @Volatile private var capture: Capture? = null
    /** Every device's held keys and axis inputs, tracked always so a capture knows what was already held. */
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
    fun startCapture(model: ControllerModel, onInput: (String) -> Unit, onCancel: (timedOut: Boolean) -> Unit, onRejected: (String) -> Unit) {
        val back = RebindCapture.backInput(model.id)
        capture = Capture(model, RebindCapture(back, held(model), now()), onInput, onCancel, onRejected)
        main.removeCallbacks(tick)
        main.postDelayed(tick, 50)
    }

    fun cancelCapture() {
        capture = null
        main.removeCallbacks(tick)
    }

    private fun now() = android.os.SystemClock.uptimeMillis()

    /** The inputs held now on devices of [model]: bindable keyboard codes, or a pad's positional inputs. */
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
                !Keys.bindable(code) -> c.onRejected("$code is the ${if (code == Keys.FAST_FORWARD) "fast-forward" else "rewind"} key")
            }
        }
        val guide = !keyboard && event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE && mixer.model(id)?.id == c.model.id
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
