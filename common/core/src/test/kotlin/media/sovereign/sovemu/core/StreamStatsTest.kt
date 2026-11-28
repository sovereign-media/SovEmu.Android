package media.sovereign.sovemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamStatsTest {
    private fun reading(
        atMs: Long,
        bytes: Double = 0.0,
        jitter: Double = 0.0,
        emitted: Double = 0.0,
        decode: Double = 0.0,
        decoded: Double = 0.0,
    ) = StatsReading(
        atMs = atMs,
        bytesReceived = bytes,
        jitterBufferDelay = jitter,
        jitterBufferEmittedCount = emitted,
        totalDecodeTime = decode,
        framesDecoded = decoded,
        framesPerSecond = 60.0,
        width = 256,
        height = 240,
        packetsLost = 3,
        networkRttMs = 4.25,
    )

    @Test
    fun `the audio's delays are per sample played between two readings`() {
        val sampler = StatsSampler()
        val first = reading(1_000).copy(
            audioJitterBufferDelay = 100.0,
            audioJitterBufferTargetDelay = 80.0,
            audioJitterBufferEmittedCount = 2_000.0,
            audioPlayoutDelay = 50.0,
            audioPlayoutSamples = 1_000.0,
        )
        // Nothing is known from one reading: no line.
        assertNull(sampler.sample(first, emptyList()).audioLogfmt())
        // A second later 48000 more samples were played, having waited 60 ms each in the
        // buffer (aiming for 40 ms) and 25 ms in the output.
        val second = first.copy(
            atMs = 2_000,
            audioJitterBufferDelay = 100.0 + 48_000 * 0.060,
            audioJitterBufferTargetDelay = 80.0 + 48_000 * 0.040,
            audioJitterBufferEmittedCount = 50_000.0,
            audioPlayoutDelay = 50.0 + 48_000 * 0.025,
            audioPlayoutSamples = 49_000.0,
        )
        val stats = sampler.sample(second, emptyList())
        assertEquals(60.0, stats.audioBufferMs!!, 0.001)
        assertEquals(40.0, stats.audioTargetMs!!, 0.001)
        assertEquals(25.0, stats.audioPlayoutMs!!, 0.001)
        assertEquals(
            "audio_buffer_ms=60.0 audio_target_ms=40.0 audio_playout_ms=25.0",
            stats.audioLogfmt(),
        )
        // The overlay shows the wait and what it is aiming for, and a dash before either is known.
        assertEquals("60 ms · target 40", stats.rows().toMap()["Audio delay"])
        assertEquals("–", StatsSampler().sample(first, emptyList()).rows().toMap()["Audio delay"])
        // A platform that reports no output delay leaves it out of the line.
        val third = second.copy(
            atMs = 3_000,
            audioJitterBufferDelay = second.audioJitterBufferDelay + 480.0,
            audioJitterBufferTargetDelay = second.audioJitterBufferTargetDelay + 480.0,
            audioJitterBufferEmittedCount = 98_000.0,
        )
        assertEquals(
            "audio_buffer_ms=10.0 audio_target_ms=10.0",
            sampler.sample(third, emptyList()).audioLogfmt(),
        )
    }

    @Test
    fun `the first reading knows no rates`() {
        val stats = StatsSampler()
            .sample(reading(1_000, bytes = 5_000.0, emitted = 60.0, decoded = 60.0), emptyList())
        assertNull(stats.kbps)
        assertNull(stats.jitterBufferMs)
        assertNull(stats.decodeMs)
        assertNull(stats.inputRttP50Ms)
        assertNull(stats.estimateMs)
        assertEquals(4.25, stats.networkRttMs)
    }

    @Test
    fun `rates and per-frame averages come from the change since the last reading`() {
        val sampler = StatsSampler()
        sampler.sample(
            reading(
                1_000,
                bytes = 10_000.0,
                jitter = 0.6,
                emitted = 60.0,
                decode = 0.12,
                decoded = 60.0,
            ),
            emptyList(),
        )
        // 125 kB in 1 s; 60 more frames that waited 0.6 s and decoded in 0.12 s between them.
        val stats = sampler.sample(
            reading(
                2_000,
                bytes = 135_000.0,
                jitter = 1.2,
                emitted = 120.0,
                decode = 0.24,
                decoded = 120.0,
            ),
            listOf(30L, 10L, 20L),
        )
        assertEquals(1000.0, stats.kbps!!, 1e-9)
        assertEquals(10.0, stats.jitterBufferMs!!, 1e-9)
        assertEquals(2.0, stats.decodeMs!!, 1e-9)
        assertEquals(20L, stats.inputRttP50Ms)
        assertEquals(30L, stats.inputRttP95Ms)
        assertEquals(3, stats.inputSamples)
        assertEquals(20.0 + 8 + 2 + 10 + 2 + 8, stats.estimateMs!!, 1e-9)
    }

    @Test
    fun `percentiles are nearest rank`() {
        val values = (1L..100L).toList()
        assertEquals(50L, percentile(values, 50))
        assertEquals(95L, percentile(values, 95))
        assertEquals(7L, percentile(listOf(7L), 95))
        assertNull(percentile(emptyList(), 50))
    }

    @Test
    fun `the log line is logfmt and leaves out what is unknown`() {
        val stats = StreamStats(
            inputRttP50Ms = 21, inputRttP95Ms = 34, inputSamples = 60, networkRttMs = 4.25,
            jitterBufferMs = null, decodeMs = 1.5, width = 256, height = 240, fps = 60.0,
            kbps = 1234.4, packetsLost = 0,
        )
        assertEquals(
            "estimate_ms=41 input_rtt_p50_ms=21 input_rtt_p95_ms=34 input_samples=60 " +
                "net_rtt_ms=4.3 " +
                "decode_ms=1.5 width=256 height=240 fps=60 kbps=1234 packets_lost=0",
            stats.logfmt(),
        )
    }

    @Test
    fun `the overlay shows a dash for what is unknown`() {
        val rows = StatsSampler().sample(reading(0), emptyList()).rows().toMap()
        assertEquals("–", rows["Input round trip"])
        assertEquals("–", rows["Bitrate"])
        assertEquals("256×240 @ 60 fps", rows["Video"])
        assertEquals("4.3 ms", rows["Network RTT"])
    }

    private val pipeline = Protocol.Pipeline(
        renderer = null,
        video = Protocol.PipelineVideo(
            codec = "h264",
            encoder = "vaapi",
            hardware = true,
            width = 640,
            height = 480,
            fps = 60.0,
        ),
        audio = Protocol.PipelineAudio(codec = "opus", sourceHz = 32040.0, kbps = 128),
    )

    private fun at(kbps: Int, limitedBy: String = "") =
        pipeline.copy(video = pipeline.video.copy(kbps = kbps, limitedBy = limitedBy))

    @Test
    fun `a change in quality is said as Sovereign words it, with why it fell`() {
        assertEquals(
            QualityNotice("Connection improved. Playing at a higher quality.", "wifi-up"),
            qualityNotice(at(5000), at(8400)),
        )
        assertEquals(
            QualityNotice("Slow connection. Playing at a lower quality.", "wifi-alert"),
            qualityNotice(at(5000), at(3300, "connection")),
        )
        assertEquals(
            QualityNotice("This device is falling behind. Playing at a lower quality.", "gauge"),
            qualityNotice(at(5000), at(800, "client")),
        )
        // Up again after the device fell behind: the connection did not improve.
        assertEquals(
            QualityNotice("Playing at a higher quality.", "gauge"),
            qualityNotice(at(800, "client"), at(5000)),
        )
    }

    @Test
    fun `nothing is said of the first message, an unchanged one, or a server that does not tell`() {
        assertEquals(null, qualityNotice(null, at(5000)))
        assertEquals(null, qualityNotice(at(5000), at(5000)))
        assertEquals(null, qualityNotice(at(0), at(5000)))
        assertEquals(null, qualityNotice(at(5000), at(0)))
    }

    @Test
    fun `without a pipeline there are no stream rows`() {
        assertEquals(
            emptyList<Pair<String, String>>(),
            pipelineRows(null, VideoDecoder("libvpx", false)),
        )
    }

    @Test
    fun `the stream rows name the codec, encoder, renderer, audio and run-ahead`() {
        assertEquals(
            listOf(
                "Stream" to "H.264 · VA-API (GPU)",
                "Rendering" to "CPU",
                "Audio" to "Opus 128 kbps · from 32,040 Hz",
                "Run-ahead" to "Off",
                "Decoder" to "MediaCodecVideoDecoder (hardware)",
            ),
            pipelineRows(pipeline, VideoDecoder("MediaCodecVideoDecoder", true)),
        )
    }

    @Test
    fun `fallbacks, software rendering and run-ahead frames`() {
        val rows = pipelineRows(
            Protocol.Pipeline(
                renderer = "llvmpipe (LLVM 19.1.7, 256 bits)",
                rendererSoftware = true,
                video = Protocol.PipelineVideo(
                    codec = "vp8",
                    encoder = "libvpx",
                    fallbacks = listOf(
                        Protocol.Fallback("nvenc", "no device"),
                        Protocol.Fallback("x264", "failed"),
                    ),
                ),
                runAhead = 2,
            ),
            null,
        ).toMap()
        assertEquals("VP8 · libvpx (CPU)", rows["Stream"])
        assertEquals("NVENC: no device; x264: failed", rows["Fell back from"])
        assertEquals("Software · llvmpipe (LLVM 19.1.7, 256 bits)", rows["Rendering"])
        assertEquals("2 frames", rows["Run-ahead"])
        assertNull(rows["Decoder"])
        assertEquals(
            "GPU · AMD Radeon 780M",
            pipelineRows(Protocol.Pipeline(renderer = "AMD Radeon 780M"), null)
                .toMap()["Rendering"],
        )
        assertEquals(
            "1 frame",
            pipelineRows(Protocol.Pipeline(runAhead = 1), null).toMap()["Run-ahead"],
        )
    }

    @Test
    fun `the decoder says hardware or software only when the stats do`() {
        assertEquals("libvpx (software)", VideoDecoder("libvpx", false).label)
        assertEquals("ExternalDecoder", VideoDecoder("ExternalDecoder", null).label)
    }

    @Test
    fun `the measured row follows the estimate and shows a dash until a sample`() {
        val none = StatsSampler().sample(reading(0), emptyList())
        assertEquals(
            listOf("Estimated button→pixel", "Measured button→frame"),
            none.rows().take(2).map { it.first },
        )
        assertEquals("–", none.rows().toMap()["Measured button→frame"])
        val some = StatsSampler().sample(reading(0), emptyList(), inputToFrameMs = 39)
        assertEquals("39 ms", some.rows().toMap()["Measured button→frame"])
        assertEquals(true, some.logfmt().contains("input_to_frame_ms=39"))
    }

    @Test
    fun `the stats message carries the measurement and the decoder only when known`() {
        fun json(stats: StreamStats, decoder: DecoderSetup?) =
            String(stats.encode("app", true, true, decoder).let { it.copyOfRange(2, it.size) })
        val unknown = StatsSampler().sample(reading(0), emptyList())
        assertEquals(false, json(unknown, null).contains("input_to_frame_ms"))
        assertEquals(false, json(unknown, null).contains("decoder"))
        assertEquals(false, json(unknown, null).contains("low_latency_decode"))
        val known = StatsSampler().sample(reading(0), emptyList(), inputToFrameMs = 41)
        assertEquals(
            """{"client":"app","input_to_frame_ms":41,"net_rtt_ms":4.3,"width":256,""" +
                """"height":240,"fps":60,"packets_lost":3,"game_mode":true,"wifi_lock":true,""" +
                """"low_latency_decode":false,"decoder":"c2.amlogic.avc.decoder"}""",
            json(known, DecoderSetup("c2.amlogic.avc.decoder", "video/avc", emptyList())),
        )
        assertEquals(
            true,
            json(known, DecoderSetup("c2.qti.avc.decoder", "video/avc", listOf("low-latency")))
                .contains(""""low_latency_decode":true"""),
        )
    }

    @Test
    fun `a software decoder is reported by its own name, from the start or after a fallback`() {
        fun json(reading: StatsReading, setup: DecoderSetup?): String {
            val bytes = StatsSampler().sample(reading, emptyList()).encode("app", null, null, setup)
            return String(bytes.copyOfRange(2, bytes.size))
        }
        val libvpx = reading(0).copy(decoder = VideoDecoder("libvpx", false))
        // Never a hardware decoder: no setup, the stats' name.
        assertEquals(true, json(libvpx, null).endsWith(""""decoder":"libvpx"}"""))
        // The hardware decoder failed mid-stream: its setup stays, but the stats know better.
        val hardware = DecoderSetup("c2.qti.vp8.decoder", "video/x-vnd.on2.vp8", listOf("x"))
        val fellBack = json(libvpx, hardware)
        assertEquals(true, fellBack.endsWith(""""decoder":"libvpx"}"""))
        assertEquals(false, fellBack.contains("low_latency_decode"))
        // Still on the hardware decoder: its own name and mode.
        val onHardware = reading(0).copy(decoder = VideoDecoder("MediaCodecVideoDecoder", true))
        assertEquals(
            true,
            json(onHardware, hardware)
                .endsWith(""""low_latency_decode":true,"decoder":"c2.qti.vp8.decoder"}"""),
        )
    }

    @Test
    fun `a negative packet count is sent as none lost`() {
        // WebRTC's packetsLost goes negative when duplicates outnumber losses.
        val sample = StatsSampler().sample(reading(0).copy(packetsLost = -4), emptyList())
        assertEquals(0L, sample.packetsLost)
    }

    @Test
    fun `the decoder row says whether it decodes in low-latency mode once known`() {
        val hardware = VideoDecoder("MediaCodecVideoDecoder", true)
        fun decoderRow(setup: DecoderSetup?) =
            pipelineRows(pipeline, hardware, setup).toMap()["Decoder"]
        assertEquals("MediaCodecVideoDecoder (hardware)", decoderRow(null))
        assertEquals(
            "MediaCodecVideoDecoder (hardware) · low latency: on",
            decoderRow(DecoderSetup("c2.qti.avc.decoder", "video/avc", listOf("low-latency"))),
        )
        assertEquals(
            "MediaCodecVideoDecoder (hardware) · low latency: not supported",
            decoderRow(DecoderSetup("c2.amlogic.avc.decoder", "video/avc", emptyList())),
        )
    }

    private val host = Candidate("host", "udp")
    private fun relay(protocol: String?) = Candidate("relay", "udp", protocol)
    private val measured = Protocol.Link(
        state = Protocol.Link.DONE,
        elapsedMs = 640,
        budgetMs = 2000,
        path = "relay-udp",
        estimateKbps = 18200,
        startKbps = 12000,
        rttMs = 24.5,
        stoppedBy = "ceiling",
    )

    @Test
    fun `the path this client reports says which of the relay's transports it uses`() {
        // The relay forwards UDP to the worker whatever the client used: without this the
        // worker could not start a TCP-relayed player lower, as it must. (link.test.ts)
        assertEquals("relay-tcp", clientPath(relay("tcp")))
        assertEquals("relay-tls", clientPath(relay("tls")))
        assertEquals("relay-udp", clientPath(relay("udp")))
        assertEquals("relay-udp", clientPath(relay(null)))
        assertEquals("direct", clientPath(host))
        assertEquals("direct", clientPath(Candidate("srflx")))
        assertNull(clientPath(null))
    }

    @Test
    fun `before any video the stats still carry the round trip and the path`() {
        // #243: the worker sends no video while it checks the connection, and judges the link
        // by the round trip and the relay's transport, which only this client can tell.
        val probing = StatsReading(
            atMs = 0,
            bytesReceived = 0.0,
            jitterBufferDelay = 0.0,
            jitterBufferEmittedCount = 0.0,
            totalDecodeTime = 0.0,
            framesDecoded = 0.0,
            framesPerSecond = null,
            width = null,
            height = null,
            packetsLost = 0,
            networkRttMs = 24.0,
            local = relay("tcp"),
            remote = host,
        )
        val sample = StatsSampler().sample(probing, emptyList())
        val json = String(sample.encode("app", null, null).let { it.copyOfRange(2, it.size) })
        assertEquals(
            """{"client":"app","net_rtt_ms":24,"packets_lost":0,"path":"relay-tcp"}""",
            json,
        )
    }

    @Test
    fun `the Network rows name the pair, the transport and the network`() {
        assertEquals("STUN → host", pairLabel(Candidate("srflx"), host))
        assertEquals("relay → host", pairLabel(relay("udp"), host))
        assertNull(pairLabel(host, null))
        assertEquals("TCP to the relay", transportLabel(relay("tcp")))
        assertEquals("UDP", transportLabel(host))
        // The loading screen says only where the player is; the details are in the overlay.
        assertEquals("Home network", networkLabel("lan"))
        assertEquals("Remote", networkLabel("relay-tcp"))
        assertNull(networkLabel(""))
        assertEquals("18.2 Mbit/s", rateLabel(18200))
        assertEquals("850 kbit/s", rateLabel(850))
    }

    @Test
    fun `measured at start says what was measured, and when nothing was`() {
        assertEquals("18.2 Mbit/s in 0.6 s (reached the ceiling)", measuredLabel(measured))
        assertEquals(
            "not measured (turned off)",
            measuredLabel(measured.copy(stoppedBy = "off", estimateKbps = 0)),
        )
        assertEquals("not measured", measuredLabel(null))
        assertEquals(
            "not measured",
            measuredLabel(measured.copy(state = Protocol.Link.PROBING)),
        )
    }

    @Test
    fun `this app's own Network rows show even with an older worker`() {
        // TURN server, transport and path come from getStats: an older worker, or the check
        // turned off, must not hide them.
        assertEquals(
            listOf(
                "TURN server" to "Yes",
                "Transport" to "TCP to the relay",
                "Path" to "relay → host",
                "Measured at start" to "not measured",
            ),
            networkRows(relay("tcp"), host, null, 0),
        )
        val full = networkRows(relay("udp"), host, measured, 15400)
        assertEquals("Network" to "Remote", full.first())
        assertEquals(
            listOf("Started at" to "12.0 Mbit/s", "Estimate now" to "15.4 Mbit/s"),
            full.takeLast(2),
        )
    }
}
