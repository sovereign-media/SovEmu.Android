package media.sovereign.sovemu.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The same vectors as `input_layout_is_stable` in SovEmu.Protocol and protocol.test.ts. */
class ProtocolTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun encodesInputLikeTheRustReference() {
        val encoded = Protocol.encodeInput(0x04030201, 0x08070605, intArrayOf(0x0a09))
        assertArrayEquals(bytes(0x01, 1, 1, 2, 3, 4, 5, 6, 7, 8, 1, 0x09, 0x0a), encoded)
    }

    @Test
    fun encodesHotkeysLikeTheRustReference() {
        assertArrayEquals(bytes(0x02, 1, 0b11), Protocol.encodeHotkeys(fastForward = true, rewind = true))
        assertArrayEquals(bytes(0x02, 1, 0b01), Protocol.encodeHotkeys(fastForward = true, rewind = false))
        assertArrayEquals(bytes(0x02, 1, 0b10), Protocol.encodeHotkeys(fastForward = false, rewind = true))
    }

    @Test
    fun decodesTheRustReferenceAck() {
        val ack = Protocol.decodeAck(bytes(0x81, 1, 1, 0, 0, 0, 2, 0, 0, 0, 3, 0, 0, 0))
        assertEquals(Protocol.Ack(1, 2, 3), ack)
        // Unsigned: a sequence number past 2^31 stays positive.
        val big = Protocol.decodeAck(bytes(0x81, 1, 0xff, 0xff, 0xff, 0xff, 0, 0, 0, 0, 0, 0, 0, 0))
        assertEquals(0xffff_ffffL, big?.seq)
    }

    @Test
    fun rejectsOtherMessages() {
        assertNull(Protocol.decodeAck(bytes(0x81, 1)))
        assertNull(Protocol.decodeAck(bytes(0x01, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))
        assertNull(Protocol.decodeAck(bytes(0x81, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))
    }

    @Test
    fun capsPortsAtFourAndNumbersButtonsLikeLibretro() {
        assertEquals(19, Protocol.encodeInput(1, 0, intArrayOf(1, 2, 3, 4, 5)).size)
        assertEquals(1, Button.B.bit)
        assertEquals(1 shl 3, Button.START.bit)
        assertEquals(1 shl 8, Button.A.bit)
    }

    @Test
    fun parsesApiJson() {
        val s = ApiJson.decodeFromString<SystemSummary>(
            """{"id":"nes","name":"Nintendo Entertainment System","short":"NES","manufacturer":"Nintendo",
               "year":1983,"color":"#c0392b","game_count":2,"playable":true,"extra":1}""",
        )
        assertEquals("NES", s.short)
        assertEquals(0xffc0392bL, parseColor(s.color))
    }
}
