package media.sovereign.sovemu.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The input channel's wire format, mirroring SovEmu.Protocol (`src/input.rs`) byte for byte.
 * ProtocolTest checks it against the same vectors as the Rust and TypeScript tests; change all
 * three together.
 */
object Protocol {
    const val INPUT_VERSION: Byte = 1
    const val INPUT_CHANNEL = "input"
    const val MAX_PORTS = 4

    private const val TYPE_INPUT: Byte = 0x01
    private const val TYPE_HOTKEYS: Byte = 0x02
    private const val TYPE_ACK: Byte = 0x81.toByte()

    fun encodeInput(seq: Int, clientTimeMs: Int, ports: IntArray): ByteArray {
        val n = minOf(ports.size, MAX_PORTS)
        val buf = ByteBuffer.allocate(11 + 2 * n).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(TYPE_INPUT).put(INPUT_VERSION).putInt(seq).putInt(clientTimeMs).put(n.toByte())
        for (i in 0 until n) buf.putShort(ports[i].toShort())
        return buf.array()
    }

    fun encodeHotkeys(fastForward: Boolean, rewind: Boolean): ByteArray {
        val flags = (if (fastForward) 1 else 0) or (if (rewind) 2 else 0)
        return byteArrayOf(TYPE_HOTKEYS, INPUT_VERSION, flags.toByte())
    }

    data class Ack(val seq: Long, val clientTimeMs: Long, val frame: Long)

    /** Decodes an ack, or returns null for anything else. */
    fun decodeAck(bytes: ByteArray): Ack? {
        if (bytes.size < 14 || bytes[0] != TYPE_ACK || bytes[1] != INPUT_VERSION) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun u32(at: Int) = buf.getInt(at).toLong() and 0xffff_ffffL
        return Ack(u32(2), u32(6), u32(10))
    }
}

/** RetroPad buttons (libretro `RETRO_DEVICE_ID_JOYPAD_*`): bit `id` of a port's state. */
enum class Button(val id: Int) {
    B(0), Y(1), SELECT(2), START(3), UP(4), DOWN(5), LEFT(6), RIGHT(7),
    A(8), X(9), L(10), R(11), L2(12), R2(13), L3(14), R3(15);

    val bit: Int get() = 1 shl id
}
