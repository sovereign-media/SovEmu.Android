package media.sovereign.sovemu.core

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * One reading of a peer connection's stats (WebRTC `getStats()`): the inbound video's counters,
 * cumulative as WebRTC reports them, and the selected candidate pair's round trip.
 */
data class StatsReading(
    val atMs: Long,
    val bytesReceived: Double,
    /** Seconds, summed over every emitted frame. */
    val jitterBufferDelay: Double,
    val jitterBufferEmittedCount: Double,
    /** Seconds, summed over every decoded frame. */
    val totalDecodeTime: Double,
    val framesDecoded: Double,
    val framesPerSecond: Double?,
    val width: Int?,
    val height: Int?,
    /** Video and audio together. */
    val packetsLost: Long,
    val networkRttMs: Double?,
    /** The inbound video's decoder (`decoderImplementation`, `powerEfficientDecoder`). */
    val decoder: VideoDecoder? = null,
)

/**
 * The app's own video decoder, as WebRTC names it: [implementation] (`MediaCodecVideoDecoder`,
 * `libvpx`…) and whether it is a power-efficient (hardware) one, when the stats say.
 */
data class VideoDecoder(val implementation: String, val powerEfficient: Boolean?) {
    /** `MediaCodecVideoDecoder (hardware)`, or just the name when power efficiency is unknown. */
    val label: String
        get() = when (powerEfficient) {
            true -> "$implementation (hardware)"
            false -> "$implementation (software)"
            null -> implementation
        }
}

/** What the stats overlay shows, and the log line it echoes. Null: not known yet. */
data class StreamStats(
    val inputRttP50Ms: Long?,
    val inputRttP95Ms: Long?,
    val inputSamples: Int,
    val networkRttMs: Double?,
    val jitterBufferMs: Double?,
    val decodeMs: Double?,
    val width: Int?,
    val height: Int?,
    val fps: Double?,
    val kbps: Double?,
    val packetsLost: Long,
    val decoder: VideoDecoder? = null,
    /**
     * Button to frame, measured ([FrameLatency]): the median ms from sending an input to its
     * frame reaching the renderer. Leaves out the display's scan-out and the TV's processing.
     */
    val inputToFrameMs: Long? = null,
) {
    /**
     * Button to pixel, roughly, as the web client estimates it: the input round trip (the
     * input reaches the core and the frame comes back), plus half a frame of alignment, ~2 ms
     * encode, the jitter buffer, decode and half a display refresh at 60 Hz.
     */
    val estimateMs: Double?
        get() = inputRttP50Ms?.let { it + 8.0 + 2.0 + (jitterBufferMs ?: 0.0) + (decodeMs ?: 0.0) + 8.0 }

    /** Label and value, in the order the overlay shows them. */
    fun rows(): List<Pair<String, String>> = listOf(
        "Estimated button→pixel" to ms(estimateMs, 0),
        "Measured button→frame" to ms(inputToFrameMs?.toDouble(), 0),
        "Input round trip" to (inputRttP50Ms?.let { p50 -> "$p50 ms · p95 ${inputRttP95Ms ?: "–"}" } ?: "–"),
        "Network RTT" to ms(networkRttMs, 1),
        "Jitter buffer" to ms(jitterBufferMs, 1),
        "Decode" to ms(decodeMs, 1),
        "Video" to "${width ?: "?"}×${height ?: "?"} @ ${fps?.let { num(it, 0) } ?: "–"} fps",
        "Bitrate" to (kbps?.let { "${num(it, 0)} kbps" } ?: "–"),
        "Packets lost" to packetsLost.toString(),
    )

    /**
     * The same values as logfmt (`key=value`, unknown ones left out), so a capture parses with
     * one `grep`/`awk`: `adb logcat -s SovEmu.Stats`.
     */
    fun logfmt(): String = listOfNotNull(
        estimateMs?.let { "estimate_ms=${num(it, 0)}" },
        inputToFrameMs?.let { "input_to_frame_ms=$it" },
        inputRttP50Ms?.let { "input_rtt_p50_ms=$it" },
        inputRttP95Ms?.let { "input_rtt_p95_ms=$it" },
        "input_samples=$inputSamples",
        networkRttMs?.let { "net_rtt_ms=${num(it, 1)}" },
        jitterBufferMs?.let { "jitter_ms=${num(it, 1)}" },
        decodeMs?.let { "decode_ms=${num(it, 1)}" },
        width?.let { "width=$it" },
        height?.let { "height=$it" },
        fps?.let { "fps=${num(it, 0)}" },
        kbps?.let { "kbps=${num(it, 0)}" },
        "packets_lost=$packetsLost",
    ).joinToString(" ")

    /**
     * The worker's `client stats` message: these values, the client ([client], `android 0.1.0
     * (Pixel 7, Android 14)`), whether TV game mode was asked for and whether low-latency
     * decoding was (the setting, whatever the decoder made of it).
     */
    fun encode(client: String, gameMode: Boolean?, lowLatencyDecode: Boolean? = null): ByteArray = Protocol.encodeStats(
        listOf(
            "client" to client,
            "estimate_ms" to estimateMs,
            "input_to_frame_ms" to inputToFrameMs,
            "input_rtt_p50_ms" to inputRttP50Ms,
            "input_rtt_p95_ms" to inputRttP95Ms,
            "net_rtt_ms" to networkRttMs,
            "jitter_ms" to jitterBufferMs,
            "decode_ms" to decodeMs,
            "width" to width,
            "height" to height,
            "fps" to fps,
            "kbps" to kbps,
            "packets_lost" to packetsLost,
            "game_mode" to gameMode,
            "low_latency_decode" to lowLatencyDecode,
        ),
    )

    private fun ms(x: Double?, digits: Int) = x?.let { "${num(it, digits)} ms" } ?: "–"
    private fun num(x: Double, digits: Int) = String.format(Locale.ROOT, "%.${digits}f", x)
}

/**
 * Turns successive [StatsReading]s into [StreamStats]: rates and per-frame averages come from
 * the change since the previous reading (so the first reading knows none of them), as the web
 * client's `StatsSampler` does.
 */
class StatsSampler {
    private var last: StatsReading? = null

    /**
     * [inputRtts]: the recent input round trips in ms, in any order; [inputToFrameMs]: the
     * measured button→frame ([FrameLatency.medianMs]).
     */
    fun sample(now: StatsReading, inputRtts: List<Long>, inputToFrameMs: Long? = null): StreamStats {
        val prev = last
        last = now
        val sorted = inputRtts.sorted()
        fun perFrame(total: (StatsReading) -> Double, count: (StatsReading) -> Double): Double? {
            prev ?: return null
            val n = count(now) - count(prev)
            return if (n > 0) (total(now) - total(prev)) / n * 1000 else null
        }
        val seconds = prev?.let { (now.atMs - it.atMs) / 1000.0 }?.takeIf { it > 0 }
        return StreamStats(
            inputRttP50Ms = percentile(sorted, 50),
            inputRttP95Ms = percentile(sorted, 95),
            inputSamples = sorted.size,
            networkRttMs = now.networkRttMs,
            jitterBufferMs = perFrame(StatsReading::jitterBufferDelay, StatsReading::jitterBufferEmittedCount),
            decodeMs = perFrame(StatsReading::totalDecodeTime, StatsReading::framesDecoded),
            width = now.width,
            height = now.height,
            fps = now.framesPerSecond,
            kbps = seconds?.let { (now.bytesReceived - prev!!.bytesReceived) * 8 / 1000 / it },
            packetsLost = now.packetsLost,
            decoder = now.decoder,
            inputToFrameMs = inputToFrameMs,
        )
    }
}

/**
 * The overlay's rows on how the stream is made, after [StreamStats.rows]: the worker's
 * [pipeline] (none from a server that predates it, so no rows) and this app's [decoder]. The
 * labels and formats are the web client's.
 */
fun pipelineRows(pipeline: Protocol.Pipeline?, decoder: VideoDecoder?): List<Pair<String, String>> {
    pipeline ?: return emptyList()
    val video = pipeline.video
    return buildList {
        add("Stream" to "${codecName(video.codec)} · ${encoderName(video.encoder)} (${if (video.hardware) "GPU" else "CPU"})")
        if (video.fallbacks.isNotEmpty()) {
            add("Fell back from" to video.fallbacks.joinToString("; ") { "${encoderName(it.encoder)}: ${it.reason}" })
        }
        add(
            "Rendering" to when {
                pipeline.renderer == null -> "CPU"
                pipeline.rendererSoftware -> "Software · ${pipeline.renderer}"
                else -> "GPU · ${pipeline.renderer}"
            },
        )
        val audio = pipeline.audio
        val hz = String.format(Locale.ROOT, "%,d", audio.sourceHz.roundToLong())
        add("Audio" to "${codecName(audio.codec)} ${audio.kbps} kbps · from $hz Hz")
        add(
            "Run-ahead" to when (pipeline.runAhead) {
                0 -> "Off"
                1 -> "1 frame"
                else -> "${pipeline.runAhead} frames"
            },
        )
        decoder?.let { add("Decoder" to it.label) }
    }
}

/** A codec's display name; one this app does not know shows as the worker names it. */
private fun codecName(codec: String) = when (codec) {
    "h264" -> "H.264"
    "vp8" -> "VP8"
    "opus" -> "Opus"
    else -> codec
}

/** An encoder's display name; one this app does not know shows as the worker names it. */
private fun encoderName(encoder: String) = when (encoder) {
    "vaapi" -> "VA-API"
    "nvenc" -> "NVENC"
    else -> encoder
}

/** Nearest-rank percentile of an ascending list; null when it is empty. */
internal fun percentile(sorted: List<Long>, p: Int): Long? {
    if (sorted.isEmpty()) return null
    val rank = ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
    return sorted[rank - 1]
}
