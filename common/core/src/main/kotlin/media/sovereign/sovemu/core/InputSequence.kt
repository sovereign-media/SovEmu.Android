package media.sovereign.sovemu.core

import java.util.concurrent.atomic.AtomicInteger

/**
 * The sequence numbers of one game's input (`0x01`) and stick (`0x03`) packets, kept across its
 * connections. The worker drops a packet no newer than the last it took, and remembers that one
 * when a reconnect replaces the client: numbers started again from 1 would all be dropped, and
 * the game would take no input after the reconnect.
 */
class InputSequence {
    private val input = AtomicInteger()
    private val analog = AtomicInteger()

    /** The next input packet's number (wrapping as the worker's comparison expects). */
    fun nextInput(): Int = input.incrementAndGet()

    /** The next stick packet's number. */
    fun nextAnalog(): Int = analog.incrementAndGet()
}
