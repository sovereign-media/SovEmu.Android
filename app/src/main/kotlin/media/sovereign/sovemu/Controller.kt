package media.sovereign.sovemu

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import media.sovereign.sovemu.core.Button
import media.sovereign.sovemu.core.Protocol
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Port 0's RetroPad state from physical gamepads, keyboards and the touch controller, combined.
 * The activity feeds key and motion events in; the player reads [buttons] and [hotkeys].
 *
 * Hotkeys follow console frontends: hold Select with R (fast-forward) or L (rewind); those
 * buttons are kept from the game while the combo is held.
 */
class Controller {
    private val keys = AtomicInteger(0)
    private val axes = AtomicInteger(0)
    private val touch = AtomicInteger(0)
    private val analog = AtomicReference(Protocol.Analog.CENTRED)

    /**
     * The game reads analog sticks (a 3D system): the left stick moves the stick, not the D-pad.
     * Otherwise it doubles as a D-pad, as in 2D frontends.
     */
    @Volatile var analogSticks = false

    private fun keyButton(code: Int): Button? = when (code) {
        // By position, as the web client does: Android names face buttons after the Xbox layout
        // (BUTTON_A is the bottom face), while the RetroPad follows the SNES (bottom = B).
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_Z -> Button.B
        KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_X -> Button.A
        KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_A -> Button.Y
        KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_S -> Button.X
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_Q -> Button.L
        KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_W -> Button.R
        KeyEvent.KEYCODE_BUTTON_L2 -> Button.L2
        KeyEvent.KEYCODE_BUTTON_R2 -> Button.R2
        KeyEvent.KEYCODE_BUTTON_THUMBL -> Button.L3
        KeyEvent.KEYCODE_BUTTON_THUMBR -> Button.R3
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_ENTER -> Button.START
        KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_SHIFT_LEFT -> Button.SELECT
        KeyEvent.KEYCODE_DPAD_UP -> Button.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> Button.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> Button.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Button.RIGHT
        else -> null
    }

    /** Returns true if the key was a controller button (and so consumed). */
    fun onKey(event: KeyEvent): Boolean {
        val button = keyButton(event.keyCode) ?: return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> keys.updateAndGet { it or button.bit }
            KeyEvent.ACTION_UP -> keys.updateAndGet { it and button.bit.inv() }
        }
        return true
    }

    /** Analogue stick and hat (D-pad) from a joystick. Returns true if consumed. */
    fun onMotion(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false
        val sx = event.getAxisValue(MotionEvent.AXIS_X)
        val sy = event.getAxisValue(MotionEvent.AXIS_Y)
        analog.set(
            Protocol.Analog(
                lx = stick(sx), ly = stick(sy),
                rx = stick(event.getAxisValue(MotionEvent.AXIS_Z)), ry = stick(event.getAxisValue(MotionEvent.AXIS_RZ)),
                l2 = trigger(maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))),
                r2 = trigger(maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))),
            ),
        )
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val x = if (analogSticks) hatX else sx + hatX
        val y = if (analogSticks) hatY else sy + hatY
        var state = 0
        if (x < -0.5f) state = state or Button.LEFT.bit
        if (x > 0.5f) state = state or Button.RIGHT.bit
        if (y < -0.5f) state = state or Button.UP.bit
        if (y > 0.5f) state = state or Button.DOWN.bit
        axes.set(state)
        return true
    }

    fun setTouch(button: Button, pressed: Boolean) {
        touch.updateAndGet { if (pressed) it or button.bit else it and button.bit.inv() }
    }

    fun clear() {
        keys.set(0)
        axes.set(0)
        touch.set(0)
        analog.set(Protocol.Analog.CENTRED)
    }

    private val raw: Int get() = keys.get() or axes.get() or touch.get()

    data class Reading(
        val buttons: Int,
        val fastForward: Boolean,
        val rewind: Boolean,
        val analog: Protocol.Analog = Protocol.Analog.CENTRED,
    )

    fun read(): Reading {
        val state = raw
        val select = state and Button.SELECT.bit != 0
        val ff = select && state and Button.R.bit != 0
        val rewind = select && state and Button.L.bit != 0
        val masked = if (ff || rewind) state and (Button.SELECT.bit or Button.L.bit or Button.R.bit).inv() else state
        return Reading(masked, ff, rewind, analog.get())
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

    /** Select+Start together opens the in-game menu. */
    val menuCombo: Boolean
        get() = raw and (Button.SELECT.bit or Button.START.bit) == (Button.SELECT.bit or Button.START.bit)
}
