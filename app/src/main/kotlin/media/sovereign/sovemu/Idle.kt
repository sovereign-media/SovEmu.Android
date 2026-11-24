package media.sovereign.sovemu

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** How long the screensaver plays before it goes dark and lets the device sleep: Fire TV's own
 *  defaults are a screensaver at 5 minutes and sleep at 20. */
const val SCREENSAVER_SLEEP_MINUTES = 15

/**
 * When the screensaver shows: after a while without input, only while the app is in the
 * foreground. Stopping the app (Home, another app, a second launch on top) ends it, and the wait
 * starts again when the app comes back: a stopped activity's screensaver would otherwise go on
 * playing its videos' sound where no button can reach it.
 *
 * After [SCREENSAVER_SLEEP_MINUTES] it goes [asleep]: dark, silent, and no longer keeping the
 * device awake. A TV switched off often leaves the device thinking it is still shown (a sound bar
 * keeps the HDMI link up), and the videos would play on through the sound bar.
 */
class Idle(private val clock: () -> Long = SystemClock::uptimeMillis) {
    /** Showing the screensaver (input ends it, and goes no further). */
    var on by mutableStateOf(false)
        private set

    /** The screensaver has played long enough: dark, and the device may sleep (input wakes it). */
    var asleep by mutableStateOf(false)
        private set

    /** Between the activity's onStart and onStop. */
    var foreground by mutableStateOf(false)
        private set

    private var lastInput = clock()
    private var onSince = 0L

    /** A press, touch or stick: the wait starts again. True if it ended the screensaver. */
    fun input(): Boolean {
        lastInput = clock()
        if (!on) return false
        on = false
        asleep = false
        return true
    }

    /** The wait starts again (the screensaver became possible: back in the menus, a game paused). */
    fun restart() {
        lastInput = clock()
    }

    /** Shows the screensaver once [minutes] have passed without input (never for 0), and puts it
     *  to sleep once it has played for [sleepMinutes]. */
    fun check(minutes: Int, sleepMinutes: Int = SCREENSAVER_SLEEP_MINUTES) {
        if (!foreground || minutes <= 0) return
        if (on) {
            if (!asleep && clock() - onSince >= sleepMinutes * 60_000L) asleep = true
            return
        }
        if (clock() - lastInput >= minutes * 60_000L) {
            on = true
            onSince = clock()
        }
    }

    fun started() {
        foreground = true
        lastInput = clock()
    }

    fun stopped() {
        foreground = false
        on = false
        asleep = false
    }
}
