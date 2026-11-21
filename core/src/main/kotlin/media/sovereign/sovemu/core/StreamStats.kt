package media.sovereign.sovemu.core

import java.util.Locale
import kotlin.math.ceil

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
)

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
     * (Pixel 7, Android 14)`) and whether TV game mode was asked for.
     */
    fun encode(client: String, gameMode: Boolean?): ByteArray = Protocol.encodeStats(
        listOf(
            "client" to client,
            "estimate_ms" to estimateMs,
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

    /** [inputRtts]: the recent input round trips in ms, in any order. */
    fun sample(now: StatsReading, inputRtts: List<Long>): StreamStats {
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
        )
    }
}

/** Nearest-rank percentile of an ascending list; null when it is empty. */
internal fun percentile(sorted: List<Long>, p: Int): Long? {
    if (sorted.isEmpty()) return null
    val rank = ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
    return sorted[rank - 1]
}
