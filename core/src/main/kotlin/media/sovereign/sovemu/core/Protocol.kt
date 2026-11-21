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
    private const val TYPE_ANALOG: Byte = 0x03
    private const val TYPE_STATS: Byte = 0x04
    private const val TYPE_ACK: Byte = 0x81.toByte()

    /**
     * The stats overlay's numbers for the worker's log (`client stats`): `0x04`, the version,
     * then a JSON object of the known values. [fields] are `ClientStats`'s keys in its order;
     * nulls and non-finite numbers are left out, numbers rounded to one decimal.
     */
    fun encodeStats(fields: List<Pair<String, Any?>>): ByteArray {
        val body = fields.mapNotNull { (key, value) ->
            val json = when (value) {
                null -> null
                is Boolean -> value.toString()
                is Int, is Long -> value.toString()
                is Double -> value.takeIf { it.isFinite() }?.let { number(it) }
                is Float -> value.toDouble().takeIf { it.isFinite() }?.let { number(it) }
                else -> quote(value.toString())
            }
            json?.let { "${quote(key)}:$it" }
        }.joinToString(",", "{", "}")
        return byteArrayOf(TYPE_STATS, INPUT_VERSION) + body.toByteArray(Charsets.UTF_8)
    }

    /** One decimal, no trailing zeros: as JavaScript's JSON.stringify writes `Math.round(x * 10) / 10`. */
    private fun number(x: Double): String =
        java.math.BigDecimal(x).setScale(1, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' || c == '\\' -> append('\\').append(c)
            c < ' ' -> append(String.format(java.util.Locale.ROOT, "\\u%04x", c.code))
            else -> append(c)
        }
        append('"')
    }

    fun encodeInput(seq: Int, clientTimeMs: Int, ports: IntArray): ByteArray {
        val n = minOf(ports.size, MAX_PORTS)
        val buf = ByteBuffer.allocate(11 + 2 * n).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(TYPE_INPUT).put(INPUT_VERSION).putInt(seq).putInt(clientTimeMs).put(n.toByte())
        for (i in 0 until n) buf.putShort(ports[i].toShort())
        return buf.array()
    }

    /** Flags: bit 0 fast-forward, bit 1 rewind, bit 2 pause (the in-game menu is open: no frames run). */
    fun encodeHotkeys(fastForward: Boolean, rewind: Boolean, pause: Boolean = false): ByteArray {
        val flags = (if (fastForward) 1 else 0) or (if (rewind) 2 else 0) or (if (pause) 4 else 0)
        return byteArrayOf(TYPE_HOTKEYS, INPUT_VERSION, flags.toByte())
    }

    /**
     * One port's analog controls: sticks −32768..32767 (x right, y down, libretro's convention),
     * triggers 0..32767.
     */
    data class Analog(val lx: Int = 0, val ly: Int = 0, val rx: Int = 0, val ry: Int = 0, val l2: Int = 0, val r2: Int = 0) {
        val centred: Boolean get() = this == CENTRED

        companion object {
            val CENTRED = Analog()
        }
    }

    /** Sticks and triggers (`0x03`); sent alongside the button packets, with its own sequence. */
    fun encodeAnalog(seq: Int, ports: List<Analog>): ByteArray {
        val n = minOf(ports.size, MAX_PORTS)
        val buf = ByteBuffer.allocate(7 + 12 * n).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(TYPE_ANALOG).put(INPUT_VERSION).putInt(seq).put(n.toByte())
        for (i in 0 until n) {
            val a = ports[i]
            for (v in intArrayOf(a.lx, a.ly, a.rx, a.ry, a.l2, a.r2)) buf.putShort(v.coerceIn(-32768, 32767).toShort())
        }
        return buf.array()
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

/**
 * RetroPad buttons (libretro `RETRO_DEVICE_ID_JOYPAD_*`): bit `id` of a port's state. The names
 * are GraphQL's `PadButton`.
 */
enum class Button(val id: Int) {
    B(0), Y(1), SELECT(2), START(3), UP(4), DOWN(5), LEFT(6), RIGHT(7),
    A(8), X(9), L(10), R(11), L2(12), R2(13), L3(14), R3(15);

    val bit: Int get() = 1 shl id
}
