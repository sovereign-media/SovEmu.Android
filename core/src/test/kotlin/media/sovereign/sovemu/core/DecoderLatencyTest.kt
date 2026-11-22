package media.sovereign.sovemu.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DecoderLatencyTest {
    @Test
    fun `off asks for nothing`() {
        assertEquals(emptyList<String>(), DecoderLatency.keys(false, true, listOf("vendor.low-latency.enable")))
    }

    @Test
    fun `the standard key alone when the decoder advertises low latency`() {
        assertEquals(listOf("low-latency"), DecoderLatency.keys(true, true, listOf("vendor.low-latency.enable")))
    }

    @Test
    fun `vendor keys only when the decoder lists them`() {
        assertEquals(emptyList<String>(), DecoderLatency.keys(true, false, listOf("vendor.mtk.something")))
        assertEquals(listOf("vendor.low-latency.enable"), DecoderLatency.keys(true, false, listOf("low-latency.enable")))
        assertEquals(
            listOf("vendor.qti-ext-dec-low-latency.enable"),
            DecoderLatency.keys(true, false, listOf("vendor.qti-ext-dec-low-latency.enable", "vendor.qti-ext-dec-picture-order.enable")),
        )
    }
}
