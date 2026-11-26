package media.sovereign.sovemu.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The same vectors as `input_layout_is_stable` in SovEmu.Protocol and protocol.test.ts. */
class ProtocolTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun encodesInputLikeTheRustReference() {
        val encoded = Protocol.encodeInput(0x04030201, 0x08070605, intArrayOf(0x0a09))
        assertArrayEquals(bytes(0x01, 1, 1, 2, 3, 4, 5, 6, 7, 8, 1, 0x09, 0x0a), encoded)
    }

    @Test
    fun encodesHotkeysLikeTheRustReference() {
        assertArrayEquals(
            bytes(0x02, 1, 0b11),
            Protocol.encodeHotkeys(fastForward = true, rewind = true),
        )
        assertArrayEquals(
            bytes(0x02, 1, 0b01),
            Protocol.encodeHotkeys(fastForward = true, rewind = false),
        )
        assertArrayEquals(
            bytes(0x02, 1, 0b10),
            Protocol.encodeHotkeys(fastForward = false, rewind = true),
        )
        assertArrayEquals(
            bytes(0x02, 1, 0b100),
            Protocol.encodeHotkeys(fastForward = false, rewind = false, pause = true),
        )
    }

    @Test
    fun encodesAnalogLikeTheRustReference() {
        // `analog_layout_is_stable_and_round_trips` in SovEmu.Protocol.
        val encoded = Protocol.encodeAnalog(
            0x04030201,
            listOf(Protocol.Analog(-32768, 32767, 1, -2, 0, 0x1234)),
        )
        assertArrayEquals(
            bytes(
                0x03, 1, 1, 2, 3, 4, 1, 0x00, 0x80, 0xff, 0x7f, 1, 0, 0xfe, 0xff, 0, 0, 0x34, 0x12,
            ),
            encoded,
        )
        // Out-of-range values clamp rather than wrap.
        val clamped = Protocol.encodeAnalog(0, listOf(Protocol.Analog(lx = 40000)))
        assertArrayEquals(bytes(0xff, 0x7f), clamped.copyOfRange(7, 9))
    }

    @Test
    fun decodesTheRustReferenceAck() {
        val ack = Protocol.decodeAck(bytes(0x81, 1, 1, 0, 0, 0, 2, 0, 0, 0, 3, 0, 0, 0))
        assertEquals(Protocol.Ack(1, 2, 3), ack)
        // Unsigned: a sequence number past 2^31 stays positive.
        val big = Protocol.decodeAck(bytes(0x81, 1, 0xff, 0xff, 0xff, 0xff, 0, 0, 0, 0, 0, 0, 0, 0))
        assertEquals(0xffff_ffffL, big?.seq)
    }

    @Test
    fun rejectsOtherMessages() {
        assertNull(Protocol.decodeAck(bytes(0x81, 1)))
        assertNull(Protocol.decodeAck(bytes(0x01, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))
        assertNull(Protocol.decodeAck(bytes(0x81, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))
    }

    @Test
    fun capsPortsAtFourAndNumbersButtonsLikeLibretro() {
        assertEquals(19, Protocol.encodeInput(1, 0, intArrayOf(1, 2, 3, 4, 5)).size)
        assertEquals(1, Button.B.bit)
        assertEquals(1 shl 3, Button.START.bit)
        assertEquals(1 shl 8, Button.A.bit)
    }

    @Test
    fun encodesStatsLikeTheRustReference() {
        // `stats_are_json_after_the_header` in SovEmu.Protocol: only the known values, in order.
        val encoded = Protocol.encodeStats(
            listOf(
                "jitter_ms" to 1.5,
                "kbps" to null,
                "fps" to Double.NaN,
                "packets_lost" to 2L,
                "frames_dropped" to 3L,
            ),
        )
        val json = """{"jitter_ms":1.5,"packets_lost":2,"frames_dropped":3}"""
        assertArrayEquals(bytes(0x04, 1) + json.toByteArray(), encoded)
    }

    @Test
    fun statsRoundToOneDecimalAndQuoteText() {
        val encoded = Protocol.encodeStats(
            listOf(
                "client" to "android \"tv\"",
                "fps" to 59.94,
                "estimate_ms" to 40.0,
                "game_mode" to true,
            ),
        )
        assertEquals(
            """{"client":"android \"tv\"","fps":59.9,"estimate_ms":40,"game_mode":true}""",
            String(encoded.copyOfRange(2, encoded.size)),
        )
    }

    private fun pipeline(json: String) =
        byteArrayOf(0x82.toByte(), 1) + json.toByteArray(Charsets.UTF_8)

    /** The same message as `pipeline_layout_is_stable` in SovEmu.Protocol and protocol.test.ts. */
    @Test
    fun decodesTheRustReferencePipeline() {
        val json = """{"renderer":"llvmpipe","renderer_software":true,"video":{"codec":"h264",""" +
            """"encoder":"x264","hardware":false,""" +
            """"fallbacks":[{"encoder":"vaapi","reason":"no device"}],""" +
            """"width":256,"height":224,"fps":60.0,"kbps":3500,"limited_by":"client"},""" +
            """"audio":{"codec":"opus","source_hz":32040.0,"kbps":128},""" +
            """"run_ahead":1}"""
        assertEquals(
            Protocol.Pipeline(
                renderer = "llvmpipe",
                rendererSoftware = true,
                video = Protocol.PipelineVideo(
                    codec = "h264",
                    encoder = "x264",
                    hardware = false,
                    fallbacks = listOf(Protocol.Fallback("vaapi", "no device")),
                    width = 256,
                    height = 224,
                    fps = 60.0,
                    kbps = 3500,
                    limitedBy = "client",
                ),
                audio = Protocol.PipelineAudio(codec = "opus", sourceHz = 32040.0, kbps = 128),
                runAhead = 1,
            ),
            Protocol.decodePipeline(pipeline(json)),
        )
    }

    @Test
    fun pipelineToleratesMissingAndUnknownKeys() {
        assertEquals(Protocol.Pipeline(), Protocol.decodePipeline(pipeline("{}")))
        val decoded = Protocol.decodePipeline(
            pipeline(
                """{"future":{"a":[1,2,null]},"renderer":null,""" +
                    """"video":{"codec":"vp8","extra":true},"run_ahead":"two"}""",
            ),
        )
        assertEquals(Protocol.Pipeline(video = Protocol.PipelineVideo(codec = "vp8")), decoded)
    }

    @Test
    fun rejectsWhatIsNotAPipeline() {
        assertNull(Protocol.decodePipeline(bytes(0x82, 1)))
        assertNull(Protocol.decodePipeline(byteArrayOf(0x81.toByte(), 1) + "{}".toByteArray()))
        assertNull(Protocol.decodePipeline(byteArrayOf(0x82.toByte(), 2) + "{}".toByteArray()))
        assertNull(Protocol.decodePipeline(pipeline("[]")))
        assertNull(Protocol.decodePipeline(pipeline("\"h264\"")))
        assertNull(Protocol.decodePipeline(pipeline("{\"renderer\":")))
        assertNull(
            Protocol.decodePipeline(
                pipeline("{\"a\":\"" + "x".repeat(Protocol.MAX_PIPELINE_BYTES) + "\"}"),
            ),
        )
        // An ack's decoder leaves a pipeline alone, so the app can try each in turn.
        assertNull(Protocol.decodeAck(pipeline("{\"renderer\":\"llvmpipe\",\"run_ahead\":0}")))
    }
}
