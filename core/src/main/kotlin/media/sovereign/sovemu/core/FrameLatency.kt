package media.sovereign.sovemu.core

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference

/**
 * Button to frame, measured: from sending an input to the renderer getting the frame that shows
 * it. The worker writes each ack just before the RTP video of the frame that consumed the input,
 * so that frame is the first to *enter the decoder* after the ack. It is not always the first to
 * leave it: a decoder slower than a frame still holds older frames, and those come out first.
 * So an ack is bound to the next frame in ([frameEntered]) and recorded when that frame, or a
 * later one if it was dropped, comes out ([frameDelivered]).
 *
 * Frames are matched across the decoder by an identity that survives it, found by watching the
 * first frames out ([match]): the render time WebRTC gives each frame, else its capture time,
 * else the count of frames in and out. When nothing reports frames going in (a decoder WebRTC
 * runs in native code, such as libvpx, can't be watched), the first frame out after the ack
 * counts. That is exact for a decoder faster than a frame.
 *
 * The oldest pending ack is kept: a newer one's frame comes later still. One that no frame has
 * shown within [PENDING_TIMEOUT_MS] is dropped. (An older worker acked after the video, which
 * makes the measurement up to a frame high.)
 *
 * Times are the app's 32-bit millisecond clock, the one `client_time_ms` carries. Acks arrive on
 * WebRTC's data channel thread, frames enter on its decoder thread and leave on the thread that
 * delivers them to the renderer. Everything is a lock-free atomic, so the frame path never waits,
 * and allocates only when it binds or records an ack (at most once per ack).
 */
class FrameLatency(private val capacity: Int = DEFAULT_CAPACITY) {
    /** How frames are told apart across the decoder. */
    enum class Match { UNKNOWN, RENDER_TIME, CAPTURE_TIME, COUNT }

    /**
     * An acked input not yet recorded; [frame] is null until a frame entering the decoder is bound.
     */
    private class Pending(val sentMs: Long, val arrivedMs: Long, val frame: Entered?)

    /** A frame going into the decoder: its render time (ms), capture time (ns) and its number. */
    private class Entered(val renderTimeMs: Long, val captureTimeNs: Long, val index: Long)

    private val pending = AtomicReference<Pending?>(null)
    private val framesIn = AtomicLong(0)
    private val framesOut = AtomicLong(0)
    private val lastEnteredMs = AtomicLong(NONE)

    /** The last frames in, for [match]: render times (ms) and capture times (ns). */
    private val recentRender = AtomicLongArray(RECENT)
    private val recentCapture = AtomicLongArray(RECENT)
    private val matchState = AtomicReference(Match.UNKNOWN)

    private val samples = AtomicLongArray(capacity)
    private val written = AtomicInteger(0)

    /** How frames are matched, once the first frames out have shown it. */
    val match: Match get() = matchState.get()

    /** An ack for the input sent at [clientTimeMs] arrived at [nowMs]. */
    fun ackArrived(clientTimeMs: Long, nowMs: Long) {
        while (true) {
            val current = pending.get()
            if (current != null && age(nowMs, current.arrivedMs) <= PENDING_TIMEOUT_MS) return
            if (pending.compareAndSet(
                    current,
                    Pending(clientTimeMs and MASK, nowMs and MASK, null),
                )
            ) {
                return
            }
        }
    }

    /**
     * An encoded frame went into the decoder at [nowMs], with the render time WebRTC planned for
     * it ([renderTimeMs]) and its capture time ([captureTimeNs]).
     */
    fun frameEntered(renderTimeMs: Long, captureTimeNs: Long, nowMs: Long) {
        val index = framesIn.incrementAndGet()
        lastEnteredMs.set(nowMs and MASK)
        if (matchState.get() == Match.UNKNOWN) {
            val slot = (index % RECENT).toInt()
            recentRender.set(slot, renderTimeMs)
            recentCapture.set(slot, captureTimeNs)
        }
        val current = pending.get() ?: return
        if (current.frame != null) return
        pending.compareAndSet(
            current,
            Pending(current.sentMs, current.arrivedMs, Entered(renderTimeMs, captureTimeNs, index)),
        )
    }

    /** A decoded frame with WebRTC's [timestampNs] was handed to the renderer at [nowMs]. */
    fun frameDelivered(timestampNs: Long, nowMs: Long) {
        val index = framesOut.incrementAndGet()
        if (matchState.get() == Match.UNKNOWN) learn(timestampNs, index)
        val current = pending.get() ?: return
        if (age(nowMs, current.arrivedMs) > PENDING_TIMEOUT_MS) {
            pending.compareAndSet(current, null)
            return
        }
        val frame = current.frame
        val shows = if (frame == null) {
            // Nothing is watching frames go in (or has, lately): the first frame out counts.
            val entered = lastEnteredMs.get()
            entered == NONE || age(nowMs, entered) > ENTRY_SILENCE_MS
        } else {
            when (matchState.get()) {
                Match.RENDER_TIME -> timestampNs / NS_PER_MS >= frame.renderTimeMs
                Match.CAPTURE_TIME -> timestampNs / NS_PER_MS >= frame.captureTimeNs / NS_PER_MS
                Match.COUNT, Match.UNKNOWN -> index >= frame.index
            }
        }
        if (!shows || !pending.compareAndSet(current, null)) return
        val latency = age(nowMs, current.sentMs)
        if (latency > MAX_PLAUSIBLE_MS) return
        val n = written.get()
        samples.set(n % capacity, latency)
        written.set(n + 1)
    }

    /**
     * Which identity the frames out carry: a timestamp equal to a recent frame's render time
     * or capture time, else (after [LEARN_FRAMES] frames out without one) their count.
     */
    private fun learn(timestampNs: Long, index: Long) {
        if (framesIn.get() == 0L) return
        val ms = timestampNs / NS_PER_MS
        for (i in 0 until RECENT) {
            if (recentRender.get(i) == ms && ms != 0L) {
                matchState.compareAndSet(Match.UNKNOWN, Match.RENDER_TIME)
                return
            }
            if (recentCapture.get(i) / NS_PER_MS == ms && ms != 0L) {
                matchState.compareAndSet(Match.UNKNOWN, Match.CAPTURE_TIME)
                return
            }
        }
        if (index >= LEARN_FRAMES) matchState.compareAndSet(Match.UNKNOWN, Match.COUNT)
    }

    /** Samples kept, up to the capacity. */
    val count: Int get() = minOf(written.get(), capacity)

    /** The median of the last samples (nearest rank, as the round trip's), or null before any. */
    fun medianMs(): Long? {
        val n = count
        val sorted = List(n) { samples.get(it) }.sorted()
        return percentile(sorted, 50)
    }

    private fun age(nowMs: Long, thenMs: Long) = (nowMs - thenMs) and MASK

    companion object {
        /** About the last second of acks (one per input packet). */
        const val DEFAULT_CAPACITY = 60

        /** Longer than any real button-to-frame time; anything above is a clock wrap. */
        const val MAX_PLAUSIBLE_MS = 10_000L

        /** A pending ack no frame has shown by now is dropped. */
        const val PENDING_TIMEOUT_MS = 2_000L

        /** Frames out, without a timestamp match, before matching falls back to counting. */
        const val LEARN_FRAMES = 30L

        /** No frame in for this long: frames are not being watched going in (any more). */
        const val ENTRY_SILENCE_MS = 500L

        private const val RECENT = 16
        private const val NONE = -1L
        private const val MASK = 0xffff_ffffL
        private const val NS_PER_MS = 1_000_000L
    }
}
