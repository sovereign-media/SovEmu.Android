package media.sovereign.sovemu.core

/**
 * How *Identify* (the controllers screen) shows which controller is which: it rumbles, and
 * blinks its lights where Android can drive them, once for each player number, so player four's
 * pad pulses four times and a player can tell theirs without looking at the screen.
 */
object Identify {
    /** One rumble pulse, and the pause between two. */
    const val RUMBLE_MS = 180L
    const val RUMBLE_GAP_MS = 140L

    /** One blink of the lights, lit then dark. */
    const val BLINK_MS = 250L

    /**
     * What *Identify* says when [controller] can neither rumble nor light up on this device: the
     * device's kernel has no rumble support for it, so no app can make it vibrate (an Xbox pad
     * over Bluetooth on a Fire TV Stick, which rumbles on a TV with a newer kernel).
     */
    fun unsupported(controller: String): String =
        "This streaming device doesn't support rumble features on $controller"

    /** The pulses for [port] (0-3): its player number. A pad on no player pulses once. */
    fun pulses(port: Int?): Int = (port ?: 0).coerceIn(0, Protocol.MAX_PORTS - 1) + 1

    /**
     * The rumble as Android's waveform takes it: a wait of 0, then on and off in turn, [pulses]
     * times on, the last without a pause after it.
     */
    fun rumble(pulses: Int): LongArray = LongArray(pulses * 2) { i ->
        when {
            i == 0 -> 0L
            i % 2 == 1 -> RUMBLE_MS
            else -> RUMBLE_GAP_MS
        }
    }
}
