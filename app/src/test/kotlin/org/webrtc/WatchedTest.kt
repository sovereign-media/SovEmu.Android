package org.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WatchedTest {
    private class Recording : VideoDecoder {
        var decoded = 0
        var lastInfo: VideoDecoder.DecodeInfo? = VideoDecoder.DecodeInfo(false, 1)
        override fun initDecode(settings: VideoDecoder.Settings, callback: VideoDecoder.Callback) = VideoCodecStatus.OK
        override fun release() = VideoCodecStatus.OK
        override fun decode(frame: EncodedImage, info: VideoDecoder.DecodeInfo?): VideoCodecStatus {
            decoded++
            lastInfo = info
            return VideoCodecStatus.OK
        }
        override fun getImplementationName() = "recording"
    }

    private fun image(captureTimeNs: Long): EncodedImage =
        EncodedImage.builder().setCaptureTimeNs(captureTimeNs).createEncodedImage()

    /** WebRTC's native decoding thread passes no DecodeInfo; that once crashed the app. */
    @Test
    fun aFrameWithoutDecodeInfoIsReportedAndDecoded() {
        val inner = Recording()
        val entered = mutableListOf<Pair<Long, Long>>()
        val watched = Watched(inner) { renderTimeMs, captureTimeNs -> entered += renderTimeMs to captureTimeNs }

        assertEquals(VideoCodecStatus.OK, watched.decode(image(42), null))

        assertEquals(listOf(NO_RENDER_TIME to 42L), entered)
        assertEquals(1, inner.decoded)
        assertNull(inner.lastInfo)
    }

    @Test
    fun aFrameWithDecodeInfoReportsItsRenderTime() {
        val entered = mutableListOf<Pair<Long, Long>>()
        val watched = Watched(Recording()) { renderTimeMs, captureTimeNs -> entered += renderTimeMs to captureTimeNs }

        watched.decode(image(7), VideoDecoder.DecodeInfo(false, 1234))

        assertEquals(listOf(1234L to 7L), entered)
    }
}
