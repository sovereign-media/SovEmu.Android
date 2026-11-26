package media.sovereign.sovemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameLatencyTest {
    /** Frame [n] (1-based): planned to render at 5000 + 16n ms, captured at 900 + 16n ms. */
    private fun render(n: Int) = 5_000L + 16 * n
    private fun captureNs(n: Int) = (900L + 16 * n) * 1_000_000

    /** WebRTC's output timestamp for frame [n], by its render time. */
    private fun out(n: Int) = render(n) * 1_000_000

    /** Frames 1..[n] in and out, so the match is learned (by render time). */
    private fun FrameLatency.warmUp(n: Int, nowMs: Long) {
        for (i in 1..n) {
            frameEntered(render(i), captureNs(i), nowMs)
            frameDelivered(out(i), nowMs)
        }
    }

    @Test
    fun `nothing is measured before an ack`() {
        val latency = FrameLatency()
        latency.frameDelivered(0, 100)
        assertNull(latency.medianMs())
        assertEquals(0, latency.count)
    }

    @Test
    fun `the frame that entered the decoder after the ack counts, not older ones still inside`() {
        val latency = FrameLatency()
        latency.warmUp(3, nowMs = 900)
        assertEquals(FrameLatency.Match.RENDER_TIME, latency.match)
        // Frames 4 and 5 are inside a slow decoder when the ack for an input sent at 1000 arrives.
        latency.frameEntered(render(4), captureNs(4), 1_005)
        latency.frameEntered(render(5), captureNs(5), 1_010)
        latency.ackArrived(1_000, nowMs = 1_020)
        latency.frameEntered(render(6), captureNs(6), 1_025)
        // The two older frames come out first: no sample yet.
        latency.frameDelivered(out(4), 1_030)
        latency.frameDelivered(out(5), 1_040)
        assertNull(latency.medianMs())
        latency.frameDelivered(out(6), 1_052)
        assertEquals(52L, latency.medianMs())
        assertEquals(1, latency.count)
    }

    @Test
    fun `a later frame counts when the bound one was dropped`() {
        val latency = FrameLatency()
        latency.warmUp(3, nowMs = 900)
        latency.ackArrived(1_000, nowMs = 1_010)
        latency.frameEntered(render(4), captureNs(4), 1_015)
        latency.frameEntered(render(5), captureNs(5), 1_030)
        // Frame 4 never comes out.
        latency.frameDelivered(out(5), 1_045)
        assertEquals(45L, latency.medianMs())
    }

    @Test
    fun `capture times match when the output carries them`() {
        val latency = FrameLatency()
        for (i in 1..2) {
            latency.frameEntered(render(i), captureNs(i), 900)
            latency.frameDelivered(captureNs(i), 900)
        }
        assertEquals(FrameLatency.Match.CAPTURE_TIME, latency.match)
        latency.frameEntered(render(3), captureNs(3), 1_000)
        latency.ackArrived(1_000, nowMs = 1_005)
        latency.frameEntered(render(4), captureNs(4), 1_010)
        latency.frameDelivered(captureNs(3), 1_020)
        assertNull(latency.medianMs())
        latency.frameDelivered(captureNs(4), 1_036)
        assertEquals(36L, latency.medianMs())
    }

    @Test
    fun `without a matching timestamp the nth frame in is the nth frame out`() {
        val latency = FrameLatency()
        // Output timestamps that match nothing that went in.
        for (i in 1..FrameLatency.LEARN_FRAMES.toInt()) {
            latency.frameEntered(render(i), captureNs(i), 900)
            latency.frameDelivered(77_000_000L * i, 900)
        }
        assertEquals(FrameLatency.Match.COUNT, latency.match)
        val n = FrameLatency.LEARN_FRAMES.toInt()
        latency.frameEntered(render(n + 1), captureNs(n + 1), 1_000)
        latency.ackArrived(1_000, nowMs = 1_001)
        latency.frameEntered(render(n + 2), captureNs(n + 2), 1_002)
        latency.frameDelivered(1L, 1_020) // frame n + 1
        assertNull(latency.medianMs())
        latency.frameDelivered(2L, 1_040) // frame n + 2: the bound one
        assertEquals(40L, latency.medianMs())
    }

    @Test
    fun `the oldest pending ack is kept`() {
        val latency = FrameLatency()
        latency.warmUp(1, nowMs = 900)
        latency.ackArrived(1_000, nowMs = 1_010)
        latency.ackArrived(1_008, nowMs = 1_012)
        latency.frameEntered(render(2), captureNs(2), 1_015)
        latency.frameDelivered(out(2), 1_050)
        assertEquals(50L, latency.medianMs())
    }

    @Test
    fun `a pending ack no frame shows within two seconds is dropped`() {
        val latency = FrameLatency()
        latency.warmUp(1, nowMs = 900)
        latency.ackArrived(1_000, nowMs = 1_010)
        latency.frameEntered(render(2), captureNs(2), 1_015)
        latency.frameDelivered(out(2), 3_100)
        assertNull(latency.medianMs())
        // And a newer ack is taken in its place.
        latency.ackArrived(4_000, nowMs = 4_010)
        latency.frameEntered(render(3), captureNs(3), 4_015)
        latency.frameDelivered(out(3), 4_030)
        assertEquals(30L, latency.medianMs())
    }

    @Test
    fun `when no frames are seen going in, the first frame out after the ack counts`() {
        val latency = FrameLatency()
        latency.ackArrived(1_000, nowMs = 1_010)
        latency.frameDelivered(123, 1_039)
        latency.frameDelivered(456, 1_056)
        assertEquals(39L, latency.medianMs())
        assertEquals(1, latency.count)
    }

    @Test
    fun `the clock wraps at 32 bits`() {
        val latency = FrameLatency()
        latency.ackArrived(0xffff_fff0L, nowMs = 0xffff_fff8L)
        latency.frameDelivered(1, 0x10)
        assertEquals(32L, latency.medianMs())
    }

    @Test
    fun `only the last samples count, and the median is the middle one`() {
        val latency = FrameLatency(capacity = 3)
        for ((i, ms) in listOf(500L, 10, 30, 20).withIndex()) {
            val t = i * 3_000L
            latency.ackArrived(t, nowMs = t)
            latency.frameDelivered(1, t + ms)
        }
        assertEquals(3, latency.count)
        assertEquals(20L, latency.medianMs())
    }
}
