package media.sovereign.sovemu.core

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Button to frame, measured: from sending an input to the first video frame handed to the
 * renderer after the worker acknowledged it. The worker writes each ack just before the RTP video
 * of the frame that consumed the input, so that frame is the first to show it. (An older worker
 * acked after the video, which makes the measurement up to a frame high.)
 *
 * Times are the app's 32-bit millisecond clock, the one `client_time_ms` carries. Acks arrive on
 * WebRTC's data channel thread and frames on its decoder thread: everything here is a lock-free
 * atomic, so the frame path never waits. One thread calls [frameDelivered] (WebRTC's decoder
 * delivers frames from one thread); any thread may read [medianMs].
 */
class FrameLatency(private val capacity: Int = DEFAULT_CAPACITY) {
    /** The newest acked input's send time not yet seen on a frame, or [NONE]. */
    private val pending = AtomicLong(NONE)
    private val samples = AtomicLongArray(capacity)
    private val written = AtomicInteger(0)

    /** An ack for the input sent at [clientTimeMs] arrived: the next frame shows it. */
    fun ackArrived(clientTimeMs: Long) {
        pending.set(clientTimeMs and MASK)
    }

    /** A decoded frame was handed to the renderer at [nowMs] (the same clock as the acks). */
    fun frameDelivered(nowMs: Long) {
        // A plain read first: most frames follow no ack, and they should not write.
        if (pending.get() == NONE) return
        val sent = pending.getAndSet(NONE)
        if (sent == NONE) return
        val latency = (nowMs - sent) and MASK
        // A wrapped or stale time (the clock is 32-bit) is not a measurement.
        if (latency > MAX_PLAUSIBLE_MS) return
        val n = written.get()
        samples.set(n % capacity, latency)
        written.set(n + 1)
    }

    /** Samples kept, up to the capacity. */
    val count: Int get() = minOf(written.get(), capacity)

    /** The median of the last samples (nearest rank, as the round trip's), or null before any. */
    fun medianMs(): Long? {
        val n = count
        val sorted = List(n) { samples.get(it) }.sorted()
        return percentile(sorted, 50)
    }

    companion object {
        /** About the last second of acks (one per input packet). */
        const val DEFAULT_CAPACITY = 60

        /** Longer than any real button-to-frame time; anything above is a clock wrap. */
        const val MAX_PLAUSIBLE_MS = 10_000L

        private const val NONE = -1L
        private const val MASK = 0xffff_ffffL
    }
}
