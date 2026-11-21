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
    fun `the first reading knows no rates`() {
        val stats = StatsSampler().sample(reading(1_000, bytes = 5_000.0, emitted = 60.0, decoded = 60.0), emptyList())
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
        sampler.sample(reading(1_000, bytes = 10_000.0, jitter = 0.6, emitted = 60.0, decode = 0.12, decoded = 60.0), emptyList())
        // 125 kB in 1 s; 60 more frames that waited 0.6 s and decoded in 0.12 s between them.
        val stats = sampler.sample(
            reading(2_000, bytes = 135_000.0, jitter = 1.2, emitted = 120.0, decode = 0.24, decoded = 120.0),
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
            "estimate_ms=41 input_rtt_p50_ms=21 input_rtt_p95_ms=34 input_samples=60 net_rtt_ms=4.3 " +
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
}
