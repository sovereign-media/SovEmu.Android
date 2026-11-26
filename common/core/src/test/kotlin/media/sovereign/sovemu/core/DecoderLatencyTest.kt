package media.sovereign.sovemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderLatencyTest {
    @Test
    fun `the standard key alone when the decoder advertises low latency`() {
        assertEquals(
            listOf("low-latency"),
            DecoderLatency.keys(true, listOf("vendor.low-latency.enable")),
        )
    }

    @Test
    fun `vendor keys only when the decoder lists them`() {
        // The Mi TV's c2.amlogic.avc.decoder: no FEATURE_LowLatency, no known vendor key listed.
        assertEquals(emptyList<String>(), DecoderLatency.keys(false, emptyList()))
        assertEquals(
            emptyList<String>(),
            DecoderLatency.keys(false, listOf("vendor.mtk.something")),
        )
        assertEquals(
            listOf("vendor.low-latency.enable"),
            DecoderLatency.keys(false, listOf("low-latency.enable")),
        )
        assertEquals(
            listOf("vendor.qti-ext-dec-low-latency.enable"),
            DecoderLatency.keys(
                false,
                listOf(
                    "vendor.qti-ext-dec-low-latency.enable",
                    "vendor.qti-ext-dec-picture-order.enable",
                ),
            ),
        )
    }

    @Test
    fun `a setup names its key and its mode`() {
        val standard = DecoderSetup("c2.qti.avc.decoder", "video/avc", listOf("low-latency"))
        assertEquals("KEY_LOW_LATENCY", standard.keyName)
        assertEquals("low latency: on", standard.label)
        assertEquals(
            "decoder=c2.qti.avc.decoder mime=video/avc low_latency_supported=true " +
                "low_latency_applied=true " +
                "low_latency_key=KEY_LOW_LATENCY",
            standard.logfmt(),
        )
        assertEquals(
            "vendor.low-latency.enable",
            DecoderSetup("c2.amlogic.avc.decoder", "video/avc", listOf("vendor.low-latency.enable"))
                .keyName,
        )
        val none = DecoderSetup("c2.amlogic.avc.decoder", "video/avc", emptyList())
        assertNull(none.keyName)
        assertEquals("low latency: not supported", none.label)
        assertEquals(false, none.lowLatency)
    }

    @Test
    fun `a hardware decoder with no low-latency mode gives way to WebRTC's software one`() {
        // A Fire TV Stick's VP8 decoder: hardware, no low-latency mode, and libvpx is there.
        assertTrue(
            DecoderLatency.prefersSoftware(
                "OMX.MTK.VIDEO.DECODER.VPX",
                lowLatency = false,
                nativeSoftware = true,
            ),
        )
        // Its H.264 decoder stays: WebRTC has no software H.264 on Android.
        assertFalse(
            DecoderLatency.prefersSoftware(
                "OMX.MTK.VIDEO.DECODER.AVC",
                lowLatency = false,
                nativeSoftware = false,
            ),
        )
        // A decoder with a low-latency mode stays.
        assertFalse(
            DecoderLatency.prefersSoftware(
                "c2.amlogic.vp9.decoder",
                lowLatency = true,
                nativeSoftware = true,
            ),
        )
        // A Mi TV's VP8 decoder is software already, whatever the TV calls it.
        assertFalse(
            DecoderLatency.prefersSoftware(
                "c2.amlogic.vp8.decoder.sw",
                lowLatency = false,
                nativeSoftware = true,
            ),
        )
        assertFalse(DecoderLatency.prefersSoftware(null, lowLatency = false, nativeSoftware = true))
        assertTrue(DecoderLatency.softwareByName("OMX.google.vp8.decoder"))
        assertTrue(DecoderLatency.softwareByName("c2.android.vp9.decoder"))
        assertFalse(DecoderLatency.softwareByName("OMX.MTK.VIDEO.DECODER.VPX"))
    }
}
