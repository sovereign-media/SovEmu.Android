package media.sovereign.sovemu.core

/** The input pump's timings ([InputPump]). */
object InputPumpDefaults {
    /** Held input is sent again this often, so the worker knows it is still held. */
    const val HEARTBEAT_MS = 100L

    /**
     * A change is sent again this long after, and again after twice this (about a frame each):
     * the channel neither orders nor retransmits, and a lost change would otherwise wait for the
     * next heartbeat. The worker drops the copies, which carry the same number.
     */
    const val RESEND_MS = 16L

    /** Copies of each change after the first. */
    const val RESENDS = 2
}

/**
 * Decides what the input channel carries ([Protocol]'s `0x01` buttons, `0x02` hotkeys, `0x03`
 * sticks) from each reading of the controllers, and when to look again: plain logic, driven by
 * the stream's pump thread ([tick]) and tested on the JVM.
 *
 * Buttons and hotkeys go on any change and every [InputPumpDefaults.HEARTBEAT_MS]; sticks on
 * any change and, while one is off-centre, at the same rate. A change is sent again
 * [InputPumpDefaults.RESENDS] times, a frame apart, with the same number (a stick coming back to
 * centre included), so one lost packet costs a frame rather than a heartbeat. A change is
 * stamped with the time of the input event that made it, so the round trip measured from its
 * ack includes the hop from the event to the pump. Ports in use only grow during a session, so
 * a player who disconnects is sent released rather than left holding what they last pressed.
 */
class InputPump(private val sequence: InputSequence, private val send: (ByteArray) -> Unit) {
    private var count = 1
    private val sent = IntArray(Protocol.MAX_PORTS)
    private var sentCount = 0
    private val buttons = IntArray(Protocol.MAX_PORTS)
    private var sentHotkeys = 0
    private var lastSend = Long.MIN_VALUE / 2
    private var sentAnalog = List(1) { Protocol.Analog.CENTRED }
    private var lastAnalogSend = Long.MIN_VALUE / 2

    /** The last change's packets and when to send them again. */
    private var resend: List<ByteArray> = emptyList()
    private var resendsLeft = 0
    private var resendAt = 0L
    private var analogResend: ByteArray? = null
    private var analogResendsLeft = 0
    private var analogResendAt = 0L

    /**
     * Sends what [reading] calls for at [nowMs] (a monotonic clock), and returns how long until
     * the pump should look again, unless an input changes first. [paused]: the in-game menu is
     * open, so the game gets nothing held and is told to pause. [stampMs] is now on the clock acks
     * are measured on (the 32-bit `client_time_ms`), and [eventMs] the time on that clock of the
     * input event that last changed something, when known.
     */
    fun tick(
        reading: InputMixer.Snapshot,
        paused: Boolean,
        nowMs: Long,
        stampMs: Long,
        eventMs: Long? = null,
    ): Long {
        count = maxOf(count, if (paused) 1 else reading.count)
        for (i in 0 until count) buttons[i] = if (paused) 0 else reading.ports[i]
        val fastForward = !paused && reading.fastForward
        val rewind = !paused && reading.rewind
        val hotkeys = (if (fastForward) 1 else 0) or (if (rewind) 2 else 0) or
            (if (paused) 4 else 0)
        val changed = hotkeys != sentHotkeys || count != sentCount || !buttons.sameAs(sent, count)
        if (changed || nowMs - lastSend >= InputPumpDefaults.HEARTBEAT_MS) {
            // A change carries its event's time; a heartbeat, now. An event older than a
            // heartbeat is not this change's.
            val stamp = eventMs
                ?.takeIf {
                    changed && it <= stampMs && stampMs - it < InputPumpDefaults.HEARTBEAT_MS
                }
                ?: stampMs
            val packets = buildList {
                add(
                    Protocol.encodeInput(
                        sequence.nextInput(),
                        stamp.toInt(),
                        buttons.copyOf(count),
                    ),
                )
                if (hotkeys != sentHotkeys || hotkeys != 0) {
                    add(Protocol.encodeHotkeys(fastForward, rewind, paused))
                }
            }
            packets.forEach(send)
            buttons.copyInto(sent, 0, 0, count)
            sentCount = count
            sentHotkeys = hotkeys
            lastSend = nowMs
            if (changed) {
                resend = packets
                resendsLeft = InputPumpDefaults.RESENDS
                resendAt = nowMs + InputPumpDefaults.RESEND_MS
            }
        } else if (resendsLeft > 0 && nowMs >= resendAt) {
            resend.forEach(send)
            resendsLeft--
            resendAt = nowMs + InputPumpDefaults.RESEND_MS
        }

        val analogChanged = !analogSame(reading, paused)
        var offCentre = false
        if (!paused) for (i in 0 until count) if (!reading.analog[i].centred) offCentre = true
        if (analogChanged ||
            (offCentre && nowMs - lastAnalogSend >= InputPumpDefaults.HEARTBEAT_MS)
        ) {
            val now = List(count) { if (paused) Protocol.Analog.CENTRED else reading.analog[it] }
            val packet = Protocol.encodeAnalog(sequence.nextAnalog(), now)
            send(packet)
            sentAnalog = now
            lastAnalogSend = nowMs
            if (analogChanged) {
                analogResend = packet
                analogResendsLeft = InputPumpDefaults.RESENDS
                analogResendAt = nowMs + InputPumpDefaults.RESEND_MS
            }
        } else if (analogResendsLeft > 0 && nowMs >= analogResendAt) {
            analogResend?.let(send)
            analogResendsLeft--
            analogResendAt = nowMs + InputPumpDefaults.RESEND_MS
        }

        var next = lastSend + InputPumpDefaults.HEARTBEAT_MS
        if (offCentre) next = minOf(next, lastAnalogSend + InputPumpDefaults.HEARTBEAT_MS)
        if (resendsLeft > 0) next = minOf(next, resendAt)
        if (analogResendsLeft > 0) next = minOf(next, analogResendAt)
        return (next - nowMs).coerceAtLeast(1)
    }

    private fun analogSame(reading: InputMixer.Snapshot, paused: Boolean): Boolean {
        if (sentAnalog.size != count) return false
        for (i in 0 until count) {
            val now = if (paused) Protocol.Analog.CENTRED else reading.analog[i]
            if (now != sentAnalog[i]) return false
        }
        return true
    }

    private fun IntArray.sameAs(other: IntArray, n: Int): Boolean {
        for (i in 0 until n) if (this[i] != other[i]) return false
        return true
    }
}
