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
    /** Video frames dropped since the stream began (`framesDropped`). */
    val framesDropped: Long? = null,
    /**
     * The audio's jitter buffer (`jitterBufferDelay`, `jitterBufferTargetDelay` and
     * `jitterBufferEmittedCount` of the inbound audio): seconds summed over every sample played.
     */
    val audioJitterBufferDelay: Double = 0.0,
    val audioJitterBufferTargetDelay: Double = 0.0,
    val audioJitterBufferEmittedCount: Double = 0.0,
    /**
     * The audio output's own delay (`media-playout`: `totalPlayoutDelay` over
     * `totalSamplesCount`), where the platform reports it.
     */
    val audioPlayoutDelay: Double = 0.0,
    val audioPlayoutSamples: Double = 0.0,
    /** The selected candidate pair's two ends; null before ICE has chosen one. */
    val local: Candidate? = null,
    val remote: Candidate? = null,
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

/**
 * This client's view of the stream: what the stats overlay shows, the log line it echoes, and
 * what the worker is sent every second to adapt the video bitrate by. Null: not known yet.
 */
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
    /** Video frames dropped since the stream began. */
    val framesDropped: Long? = null,
    /** How long sound waits in the audio jitter buffer, and the wait it is aiming for. */
    val audioBufferMs: Double? = null,
    val audioTargetMs: Double? = null,
    /** How long sound then takes to leave the audio output, where the platform says. */
    val audioPlayoutMs: Double? = null,
    /** How this client reaches the worker ([clientPath]); null before ICE has chosen a pair. */
    val path: String? = null,
) {
    /**
     * The audio's delays as one logfmt line (`adb logcat -s SovEmu.Stats`), null when none is
     * known yet. Logged every few seconds whether or not the stats overlay is open (its
     * "Audio delay" row shows the same): sound's delay is what a change to the audio path is
     * judged by, and a capture should not need the overlay opened.
     */
    fun audioLogfmt(): String? = listOfNotNull(
        audioBufferMs?.let { "audio_buffer_ms=${num(it, 1)}" },
        audioTargetMs?.let { "audio_target_ms=${num(it, 1)}" },
        audioPlayoutMs?.let { "audio_playout_ms=${num(it, 1)}" },
    ).takeIf { it.isNotEmpty() }?.joinToString(" ")

    /**
     * Button to pixel, roughly, as the web client estimates it: the input round trip (the
     * input reaches the core and the frame comes back), plus half a frame of alignment, ~2 ms
     * encode, the jitter buffer, decode and half a display refresh at 60 Hz.
     */
    val estimateMs: Double?
        get() = inputRttP50Ms?.let {
            it + 8.0 + 2.0 + (jitterBufferMs ?: 0.0) + (decodeMs ?: 0.0) +
                8.0
        }

    /** Label and value, in the order the overlay shows them. */
    fun rows(): List<Pair<String, String>> = listOf(
        "Estimated button→pixel" to ms(estimateMs, 0),
        "Measured button→frame" to ms(inputToFrameMs?.toDouble(), 0),
        "Input round trip" to
            (inputRttP50Ms?.let { p50 -> "$p50 ms · p95 ${inputRttP95Ms ?: "–"}" } ?: "–"),
        "Network RTT" to ms(networkRttMs, 1),
        "Jitter buffer" to ms(jitterBufferMs, 1),
        "Audio delay" to
            (
                audioBufferMs?.let {
                    "${num(it, 0)} ms" +
                        (audioTargetMs?.let { t -> " · target ${num(t, 0)}" } ?: "")
                }
                    ?: "–"
                ),
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
     * The worker's `client stats` message, sent every second while playing (the worker lowers
     * the bitrate for a client that is not keeping up, and logs it): these values, the client
     * ([client], `android 0.1.0 (Pixel 7, Android 14)`), whether TV game mode was asked for and
     * the low-latency Wi-Fi lock is held, the decoder ([decoderName]) and the path
     * ([clientPath]), in SovEmu.Protocol's `ClientStats` order. Sent from the moment the channel
     * opens, before any video: the worker checks the connection first (#243), and needs the
     * round trip and the relay's transport, which only this client can tell, to judge it.
     */
    fun encode(
        client: String,
        gameMode: Boolean?,
        wifiLock: Boolean?,
        setup: DecoderSetup? = null,
    ): ByteArray {
        val software = softwareDecoder(setup)
        return Protocol.encodeStats(
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
                "frames_dropped" to framesDropped,
                "game_mode" to gameMode,
                "wifi_lock" to wifiLock,
                // A software decoder has no low-latency mode to report.
                "low_latency_decode" to if (software != null) null else setup?.lowLatency,
                "decoder" to (software?.implementation ?: setup?.codec),
                "path" to path,
            ),
        )
    }

    /**
     * The decoder WebRTC's stats name, when it is decoding in software: from the start (no
     * hardware decoder was ever configured, [setup] is null), or after the hardware one failed
     * mid-stream and WebRTC fell back (the stats say it is not power-efficient, while [setup]
     * still names the hardware decoder configured first).
     */
    private fun softwareDecoder(setup: DecoderSetup?): VideoDecoder? =
        decoder?.takeIf { setup == null || it.powerEfficient == false }

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
    fun sample(
        now: StatsReading,
        inputRtts: List<Long>,
        inputToFrameMs: Long? = null,
    ): StreamStats {
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
            jitterBufferMs = perFrame(
                StatsReading::jitterBufferDelay,
                StatsReading::jitterBufferEmittedCount,
            ),
            decodeMs = perFrame(StatsReading::totalDecodeTime, StatsReading::framesDecoded),
            width = now.width,
            height = now.height,
            fps = now.framesPerSecond,
            kbps = seconds?.let { (now.bytesReceived - prev!!.bytesReceived) * 8 / 1000 / it },
            // WebRTC's count is signed: duplicates outnumbering losses make it negative.
            packetsLost = now.packetsLost.coerceAtLeast(0),
            decoder = now.decoder,
            inputToFrameMs = inputToFrameMs,
            framesDropped = now.framesDropped,
            audioBufferMs = perFrame(
                StatsReading::audioJitterBufferDelay,
                StatsReading::audioJitterBufferEmittedCount,
            ),
            audioTargetMs = perFrame(
                StatsReading::audioJitterBufferTargetDelay,
                StatsReading::audioJitterBufferEmittedCount,
            ),
            audioPlayoutMs = perFrame(
                StatsReading::audioPlayoutDelay,
                StatsReading::audioPlayoutSamples,
            ),
            path = clientPath(now.local),
        )
    }
}

/**
 * A change in the stream's quality, as the player is told of it: one [line], and the glyph
 * before it ([icon]: `wifi-alert` and `wifi-up`, Sovereign's own, or `gauge` for a device that
 * fell behind).
 */
data class QualityNotice(val line: String, val icon: String)

/**
 * The notice for a change in the stream's quality between two pipeline messages, worded and
 * marked as Sovereign's step-down and step-up notices and the web client's: the bitrate the
 * worker tells the player of moved (it only does in steps worth telling). Null when it has not,
 * or when either side does not know it (the first message, an older server).
 */
fun qualityNotice(before: Protocol.Pipeline?, now: Protocol.Pipeline): QualityNotice? {
    val was = before?.video?.kbps ?: 0
    val current = now.video.kbps
    if (was == 0 || current == 0 || was == current) return null
    if (current < was) {
        return if (now.video.limitedBy == "client") {
            QualityNotice("This device is falling behind. Playing at a lower quality.", "gauge")
        } else {
            QualityNotice("Slow connection. Playing at a lower quality.", "wifi-alert")
        }
    }
    // Back up from a fall that was the device's: the connection had nothing to do with it.
    return if (before?.video?.limitedBy == "client") {
        QualityNotice("Playing at a higher quality.", "gauge")
    } else {
        QualityNotice("Connection improved. Playing at a higher quality.", "wifi-up")
    }
}

/**
 * The overlay's rows on how the stream is made, after [StreamStats.rows]: the worker's
 * [pipeline] (none from a server that predates it, so no rows) and this app's [decoder], with
 * whether it decodes in low-latency mode once its [setup] is known. The labels and formats are
 * the web client's.
 */
fun pipelineRows(
    pipeline: Protocol.Pipeline?,
    decoder: VideoDecoder?,
    setup: DecoderSetup? = null,
): List<Pair<String, String>> {
    pipeline ?: return emptyList()
    val video = pipeline.video
    return buildList {
        add(
            "Stream" to
                "${codecName(
                    video.codec,
                )} · ${encoderName(video.encoder)} (${if (video.hardware) "GPU" else "CPU"})",
        )
        if (video.fallbacks.isNotEmpty()) {
            add(
                "Fell back from" to
                    video.fallbacks.joinToString("; ") {
                        "${encoderName(it.encoder)}: ${it.reason}"
                    },
            )
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
        decoder?.let {
            add("Decoder" to (setup?.let { s -> "${it.label} · ${s.label}" } ?: it.label))
        }
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
