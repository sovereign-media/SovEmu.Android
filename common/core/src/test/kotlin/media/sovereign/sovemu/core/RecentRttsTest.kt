package media.sovereign.sovemu.core

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentRttsTest {
    @Test
    fun `keeps the most recent, oldest first`() {
        val rtts = RecentRtts(capacity = 3)
        assertEquals(emptyList<Long>(), rtts.toList())
        (1L..5L).forEach(rtts::add)
        assertEquals(listOf(3L, 4L, 5L), rtts.toList())
    }

    @Test
    fun `an ack's time is read in place, and anything else is not an ack`() {
        // Seq 7, client time 1000, frame 42, two bytes into the buffer.
        val ack = byteArrayOf(0x81.toByte(), 1, 7, 0, 0, 0, 0xe8.toByte(), 3, 0, 0, 42, 0, 0, 0)
        val data = ByteBuffer.wrap(byteArrayOf(9, 9) + ack)
        data.position(2)
        assertEquals(1000L, Protocol.ackClientTimeMs(data))
        assertEquals("the position is left alone", 2, data.position())
        assertEquals(Protocol.decodeAck(ack)?.clientTimeMs, 1000L)
        assertEquals(-1L, Protocol.ackClientTimeMs(ByteBuffer.wrap(byteArrayOf(0x82.toByte(), 1))))
    }
}
