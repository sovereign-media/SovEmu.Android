package media.sovereign.sovemu.core

import com.apollographql.apollo.api.Optional
import media.sovereign.sovemu.core.graphql.type.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CapabilitiesTest {
    private val screen = ScreenMode(3840, 2160, 59.94)

    /** A Mi TV (Amlogic S7D): every codec but VP8 in hardware, with a low-latency mode. */
    private val miTv = listOf(
        DecoderChoice("VP8", hardware = false, lowLatency = false, name = "c2.amlogic.vp8.decoder.sw"),
        DecoderChoice("VP9", hardware = true, lowLatency = true, name = "c2.amlogic.vp9.decoder"),
        DecoderChoice("H264", hardware = true, lowLatency = true, name = "c2.amlogic.avc.decoder"),
        DecoderChoice("AV1", hardware = true, lowLatency = true, name = "c2.amlogic.av1.decoder"),
    )

    @Test
    fun theDocumentIsWholeAndOfThisVersion() {
        val document = Capabilities.document(miTv, screen)
        assertEquals(1, document.version)
        assertEquals(listOf(VideoCodec.VP8, VideoCodec.VP9, VideoCodec.H264, VideoCodec.AV1), document.videoDecoders.map { it.codec })
        val h264 = document.videoDecoders[2]
        assertEquals(true to true, h264.hardware to h264.lowLatency)
        assertEquals(Optional.present("c2.amlogic.avc.decoder"), h264.name)
        assertEquals(Triple(3840, 2160, 59.94), Triple(document.display.width, document.display.height, document.display.refreshHz))
        assertEquals(
            "vp8 software, vp9 hardware low-latency, h264 hardware low-latency, av1 hardware low-latency",
            Capabilities.summary(document),
        )
    }

    @Test
    fun eachCodecHasOneRowAndUnknownCodecsAreLeftOut() {
        // WebRTC lists H.264 once per profile, and may list codecs the server has no name for.
        val decoders = listOf(
            DecoderChoice("H264", hardware = true, lowLatency = true, name = "c2.vendor.avc.decoder"),
            DecoderChoice("H264", hardware = false, lowLatency = false, name = "another"),
            DecoderChoice("h265", hardware = true, lowLatency = false, name = null),
            DecoderChoice("VP10", hardware = true, lowLatency = true, name = "c2.vendor.vp10"),
        )
        val rows = Capabilities.document(decoders, screen).videoDecoders
        assertEquals(listOf(VideoCodec.H264, VideoCodec.H265), rows.map { it.codec })
        assertEquals(Optional.present("c2.vendor.avc.decoder"), rows[0].name)
        assertEquals(Optional.Absent, rows[1].name)
        assertNull(Capabilities.codec("VP10"))
    }

    @Test
    fun decoderNamesAreKeptShort() {
        val long = DecoderChoice("VP8", hardware = false, lowLatency = true, name = "  " + "x".repeat(200))
        val blank = DecoderChoice("VP9", hardware = false, lowLatency = true, name = "   ")
        val rows = Capabilities.document(listOf(long, blank), screen).videoDecoders
        assertEquals(Optional.present("x".repeat(80)), rows[0].name)
        assertEquals(Optional.Absent, rows[1].name)
    }
}
