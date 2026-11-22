package media.sovereign.sovemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DecoderLatencyTest {
    @Test
    fun `the standard key alone when the decoder advertises low latency`() {
        assertEquals(listOf("low-latency"), DecoderLatency.keys(true, listOf("vendor.low-latency.enable")))
    }

    @Test
    fun `vendor keys only when the decoder lists them`() {
        // The Mi TV's c2.amlogic.avc.decoder: no FEATURE_LowLatency, no known vendor key listed.
        assertEquals(emptyList<String>(), DecoderLatency.keys(false, emptyList()))
        assertEquals(emptyList<String>(), DecoderLatency.keys(false, listOf("vendor.mtk.something")))
        assertEquals(listOf("vendor.low-latency.enable"), DecoderLatency.keys(false, listOf("low-latency.enable")))
        assertEquals(
            listOf("vendor.qti-ext-dec-low-latency.enable"),
            DecoderLatency.keys(false, listOf("vendor.qti-ext-dec-low-latency.enable", "vendor.qti-ext-dec-picture-order.enable")),
        )
    }

    @Test
    fun `a setup names its key and its mode`() {
        val standard = DecoderSetup("c2.qti.avc.decoder", "video/avc", listOf("low-latency"))
        assertEquals("KEY_LOW_LATENCY", standard.keyName)
        assertEquals("low latency: on", standard.label)
        assertEquals(
            "decoder=c2.qti.avc.decoder mime=video/avc low_latency_supported=true low_latency_applied=true " +
                "low_latency_key=KEY_LOW_LATENCY",
            standard.logfmt(),
        )
        assertEquals("vendor.low-latency.enable", DecoderSetup("c2.amlogic.avc.decoder", "video/avc", listOf("vendor.low-latency.enable")).keyName)
        val none = DecoderSetup("c2.amlogic.avc.decoder", "video/avc", emptyList())
        assertNull(none.keyName)
        assertEquals("low latency: not supported", none.label)
        assertEquals(false, none.lowLatency)
    }
}
