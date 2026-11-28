package media.sovereign.sovemu.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the input channel carries, and when, from the controllers' readings. */
class InputPumpTest {
    private val sent = mutableListOf<ByteArray>()
    private val pump = InputPump(InputSequence()) { sent += it }
    private val reading = InputMixer.Snapshot()

    private fun type(b: ByteArray) = b[0].toInt() and 0xff
    private fun le(b: ByteArray) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
    private fun seq(b: ByteArray) = le(b).getInt(2)
    private fun stamp(b: ByteArray) = le(b).getInt(6)
    private fun inputs() = sent.filter { type(it) == 0x01 }
    private fun sticks() = sent.filter { type(it) == 0x03 }

    private fun tick(now: Long, eventMs: Long? = null, paused: Boolean = false) =
        pump.tick(reading, paused, nowMs = now, stampMs = now, eventMs = eventMs)

    @Test
    fun `a press is sent at once, stamped with its event, then twice more with its number`() {
        tick(1_000)
        sent.clear()
        reading.ports[0] = Button.A.bit
        val wait = tick(1_010, eventMs = 1_004)
        assertEquals(1, inputs().size)
        assertEquals("the event's time, not the send's", 1_004, stamp(inputs()[0]))
        assertEquals("looks again for the copy", InputPumpDefaults.RESEND_MS, wait)
        tick(1_010 + InputPumpDefaults.RESEND_MS)
        tick(1_010 + 2 * InputPumpDefaults.RESEND_MS)
        tick(1_010 + 3 * InputPumpDefaults.RESEND_MS)
        assertEquals(3, inputs().size)
        assertTrue("copies carry the same number", inputs().map(::seq).toSet().size == 1)
    }

    @Test
    fun `held input is sent again every heartbeat, stamped now`() {
        reading.ports[0] = Button.A.bit
        tick(0)
        sent.clear()
        // Nothing changed: no copies once they are done, then the heartbeat.
        val wait = tick(2 * InputPumpDefaults.RESEND_MS + 1)
        sent.clear()
        tick(InputPumpDefaults.HEARTBEAT_MS, eventMs = 1)
        assertEquals(1, inputs().size)
        assertEquals(InputPumpDefaults.HEARTBEAT_MS.toInt(), stamp(inputs()[0]))
        assertTrue(wait <= InputPumpDefaults.HEARTBEAT_MS)
    }

    @Test
    fun `with nothing to do it sleeps until the heartbeat`() {
        // The first reading is sent, and its copies; then nothing until the heartbeat.
        tick(0)
        tick(InputPumpDefaults.RESEND_MS)
        tick(2 * InputPumpDefaults.RESEND_MS)
        assertEquals(InputPumpDefaults.HEARTBEAT_MS - 50, tick(50))
    }

    @Test
    fun `a stick coming back to centre is sent three times`() {
        reading.analog[0] = Protocol.Analog(lx = 20_000)
        tick(0)
        reading.analog[0] = Protocol.Analog.CENTRED
        sent.clear()
        tick(10)
        tick(10 + InputPumpDefaults.RESEND_MS)
        tick(10 + 2 * InputPumpDefaults.RESEND_MS)
        assertEquals(3, sticks().size)
        assertTrue(sticks().map(::seq).toSet().size == 1)
        // Centred, no stick heartbeat.
        sent.clear()
        tick(10 + 10 * InputPumpDefaults.HEARTBEAT_MS)
        assertEquals(0, sticks().size)
    }

    @Test
    fun `a second player joining is a change`() {
        tick(0)
        sent.clear()
        reading.count = 2
        tick(5)
        assertEquals(1, inputs().size)
        assertEquals(2, inputs()[0][10].toInt())
    }

    @Test
    fun `paused, the game is sent nothing held and told to pause`() {
        reading.ports[0] = Button.A.bit
        reading.fastForward = true
        tick(0)
        sent.clear()
        tick(5, paused = true)
        assertEquals(0, le(inputs()[0]).getShort(11).toInt())
        val hotkeys = sent.first { type(it) == 0x02 }
        assertEquals(4, hotkeys[2].toInt())
    }
}
