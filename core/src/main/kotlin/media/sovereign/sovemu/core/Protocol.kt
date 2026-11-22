package media.sovereign.sovemu.core

import com.apollographql.apollo.api.json.JsonReader
import com.apollographql.apollo.api.json.jsonReader
import okio.Buffer
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
    private const val TYPE_PIPELINE: Byte = 0x82.toByte()

    /** The longest pipeline message the worker sends (and the longest decoded). */
    const val MAX_PIPELINE_BYTES = 4096

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

    /** An encoder the worker tried before the one it streams with, and why it gave up on it. */
    data class Fallback(val encoder: String = "", val reason: String = "")

    data class PipelineVideo(
        /** `h264` or `vp8`. */
        val codec: String = "",
        /** `vaapi`, `nvenc`, `x264` or `libvpx`. */
        val encoder: String = "",
        val hardware: Boolean = false,
        val fallbacks: List<Fallback> = emptyList(),
        val width: Int = 0,
        val height: Int = 0,
        val fps: Double = 0.0,
    )

    data class PipelineAudio(val codec: String = "", val sourceHz: Double = 0.0, val kbps: Int = 0)

    /**
     * How the worker makes the stream (`0x82`, the version, then JSON): what draws the picture,
     * the video codec and encoder (and any it fell back from), the audio and run-ahead. Sent
     * when the channel opens, on any change and every 2 s; every key may be missing.
     */
    data class Pipeline(
        /** The OpenGL renderer a 3D core draws with; null for a core that draws on the CPU. */
        val renderer: String? = null,
        /** The renderer is the software one (llvmpipe). */
        val rendererSoftware: Boolean = false,
        val video: PipelineVideo = PipelineVideo(),
        val audio: PipelineAudio = PipelineAudio(),
        val runAhead: Int = 0,
    )

    /** Decodes a pipeline message, or returns null for anything else. */
    fun decodePipeline(bytes: ByteArray): Pipeline? {
        if (bytes.size < 3 || bytes.size > MAX_PIPELINE_BYTES) return null
        if (bytes[0] != TYPE_PIPELINE || bytes[1] != INPUT_VERSION) return null
        val p = try {
            val reader = Buffer().write(bytes, 2, bytes.size - 2).jsonReader()
            readJson(reader).also { if (reader.peek() != JsonReader.Token.END_DOCUMENT) return null }
        } catch (_: Exception) {
            return null
        } as? Map<*, *> ?: return null
        val video = p["video"].obj()
        val audio = p["audio"].obj()
        return Pipeline(
            renderer = p["renderer"] as? String,
            rendererSoftware = p["renderer_software"] == true,
            video = PipelineVideo(
                codec = video["codec"].str(),
                encoder = video["encoder"].str(),
                hardware = video["hardware"] == true,
                fallbacks = (video["fallbacks"] as? List<*>).orEmpty().map {
                    Fallback(it.obj()["encoder"].str(), it.obj()["reason"].str())
                },
                width = video["width"].num().toInt(),
                height = video["height"].num().toInt(),
                fps = video["fps"].num(),
            ),
            audio = PipelineAudio(
                codec = audio["codec"].str(),
                sourceHz = audio["source_hz"].num(),
                kbps = audio["kbps"].num().toInt(),
            ),
            runAhead = p["run_ahead"].num().toInt(),
        )
    }

    private fun Any?.str() = this as? String ?: ""
    private fun Any?.num() = (this as? Double)?.takeIf { it.isFinite() } ?: 0.0
    private fun Any?.obj(): Map<*, *> = this as? Map<*, *> ?: emptyMap<String, Any?>()

    /** Any JSON value: objects as maps, arrays as lists, every number as a Double. */
    private fun readJson(reader: JsonReader): Any? = when (reader.peek()) {
        JsonReader.Token.BEGIN_OBJECT -> {
            val members = HashMap<String, Any?>()
            reader.beginObject()
            while (reader.hasNext()) members[reader.nextName()] = readJson(reader)
            reader.endObject()
            members
        }
        JsonReader.Token.BEGIN_ARRAY -> {
            val items = ArrayList<Any?>()
            reader.beginArray()
            while (reader.hasNext()) items.add(readJson(reader))
            reader.endArray()
            items
        }
        JsonReader.Token.STRING -> reader.nextString()
        JsonReader.Token.NUMBER, JsonReader.Token.LONG -> reader.nextDouble()
        JsonReader.Token.BOOLEAN -> reader.nextBoolean()
        JsonReader.Token.NULL -> reader.nextNull()
        else -> throw IllegalArgumentException("unexpected JSON token ${reader.peek()}")
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
