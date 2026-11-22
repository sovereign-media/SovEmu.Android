package media.sovereign.sovemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameLatencyTest {
    @Test
    fun `nothing is measured before an ack`() {
        val latency = FrameLatency()
        latency.frameDelivered(100)
        assertNull(latency.medianMs())
        assertEquals(0, latency.count)
    }

    @Test
    fun `the first frame after an ack measures from the input's send time`() {
        val latency = FrameLatency()
        latency.ackArrived(1_000)
        latency.frameDelivered(1_039)
        // Later frames show nothing new: no second sample.
        latency.frameDelivered(1_056)
        assertEquals(39L, latency.medianMs())
        assertEquals(1, latency.count)
    }

    @Test
    fun `a newer ack replaces one no frame has shown yet`() {
        val latency = FrameLatency()
        latency.ackArrived(1_000)
        latency.ackArrived(1_010)
        latency.frameDelivered(1_040)
        assertEquals(30L, latency.medianMs())
    }

    @Test
    fun `the clock wraps at 32 bits`() {
        val latency = FrameLatency()
        latency.ackArrived(0xffff_fff0L)
        latency.frameDelivered(0x10)
        assertEquals(32L, latency.medianMs())
    }

    @Test
    fun `an implausible time is dropped`() {
        val latency = FrameLatency()
        latency.ackArrived(50_000)
        latency.frameDelivered(10)
        assertNull(latency.medianMs())
    }

    @Test
    fun `only the last samples count, and the median is the middle one`() {
        val latency = FrameLatency(capacity = 3)
        for (ms in listOf(500L, 10, 30, 20)) {
            latency.ackArrived(0)
            latency.frameDelivered(ms)
        }
        assertEquals(3, latency.count)
        assertEquals(20L, latency.medianMs())
    }
}
