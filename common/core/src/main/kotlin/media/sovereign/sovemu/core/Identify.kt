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
     * What *Identify* says when [controller] can neither rumble nor light up on the device
     * [device] (its own name: `DEV-FIRESTICK`), with [details] after it in brackets (`Fire TV,
     * AFTMA08C15`; none when they would only repeat the name). It is the pair that fails, not
     * either alone: the device's kernel has no rumble driver for this controller, so Android sees
     * no vibrator and no app can reach one (an Xbox pad over Bluetooth on a Fire TV Stick, which
     * rumbles on a TV with a newer kernel). Both are named outright, never "it", so a player
     * with two pads and two TVs knows which.
     */
    fun unsupported(controller: String, device: String, details: String? = null): String {
        val named = if (details.isNullOrBlank()) device else "$device ($details)"
        return "$controller can't rumble on $named. $device's kernel has no rumble support " +
            "for $controller, so no app can make the controller vibrate on this device, and " +
            "the controller has no lights apps can use. It may rumble on other devices."
    }

    /**
     * The brackets after a device's name: [fireTv]'s `Fire TV`, then the [model], each left out
     * where it adds nothing (a model the name already is).
     */
    fun deviceDetails(name: String, model: String, fireTv: Boolean): String? = listOfNotNull(
        "Fire TV".takeIf { fireTv },
        model.takeIf { it.isNotBlank() && !it.equals(name, ignoreCase = true) },
    ).joinToString(", ").ifEmpty { null }

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
