// In WebRTC's package on purpose: the hardware decoder (`AndroidVideoDecoder`) and the hook it
// creates its MediaCodec through (`MediaCodecWrapperFactory`) are package-private, and the
// library has no other way to add keys to the decoder's MediaFormat.
package org.webrtc

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaCrypto
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.view.Surface
import media.sovereign.sovemu.core.DecoderLatency
import media.sovereign.sovemu.core.DecoderSetup
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [DefaultVideoDecoderFactory]'s choice of decoders (software with the hardware one preferred,
 * falling back to software if it fails), except that the hardware decoder's MediaFormat gets
 * the low-latency keys the decoder supports ([DecoderLatency]), whenever it supports one.
 * [onConfigured] hears once per decoder how it went (on WebRTC's decoder thread).
 */
class LowLatencyVideoDecoderFactory(
    private val egl: EglBase.Context?,
    private val onConfigured: (DecoderSetup) -> Unit,
) : VideoDecoderFactory {
    private val defaults = DefaultVideoDecoderFactory(egl)
    private val hardware = HardwareVideoDecoderFactory(egl)
    private val software = SoftwareVideoDecoderFactory()
    private val platformSoftware = PlatformSoftwareVideoDecoderFactory(egl)

    override fun getSupportedCodecs(): Array<VideoCodecInfo> = defaults.supportedCodecs

    override fun createDecoder(info: VideoCodecInfo): VideoDecoder? {
        // As DefaultVideoDecoderFactory.createDecoder.
        val sw = software.createDecoder(info) ?: platformSoftware.createDecoder(info)
        val hw = hardware.createDecoder(info)?.let { configurable(info, it) }
        return if (hw != null && sw != null) VideoDecoderFallback(sw, hw) else hw ?: sw
    }

    /**
     * The library's choice of hardware decoder, rebuilt with our codec hook. Constructing an
     * AndroidVideoDecoder only stores its arguments, so the library's own is simply dropped.
     */
    private fun configurable(info: VideoCodecInfo, chosen: VideoDecoder): VideoDecoder {
        if (chosen !is AndroidVideoDecoder) return chosen
        val type = VideoCodecMimeType.valueOf(info.name)
        val name = chosen.implementationName
        val caps = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .firstOrNull { it.name == name && !it.isEncoder }
            ?.let { capabilities(it, type.mimeType()) } ?: return chosen
        val colour = MediaCodecUtils.selectColorFormat(MediaCodecUtils.DECODER_COLOR_FORMATS, caps) ?: return chosen
        val feature = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
        return AndroidVideoDecoder(Codecs(type.mimeType(), feature), name, type, colour, egl)
    }

    private fun capabilities(codec: MediaCodecInfo, mime: String): MediaCodecInfo.CodecCapabilities? = try {
        codec.getCapabilitiesForType(mime)
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Creates one decoder's MediaCodecs (again after a resolution change); reports the first. */
    private inner class Codecs(private val mime: String, private val featureLowLatency: Boolean) : MediaCodecWrapperFactory {
        private val reported = AtomicBoolean(false)

        override fun createByCodecName(name: String): MediaCodecWrapper = Codec(MediaCodec.createByCodecName(name)) { codec, format ->
            val vendor = if (!featureLowLatency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                codec.supportedVendorParameters
            } else {
                emptyList()
            }
            val keys = DecoderLatency.keys(featureLowLatency, vendor)
            for (key in keys) format.setInteger(key, 1)
            if (reported.compareAndSet(false, true)) onConfigured(DecoderSetup(name, mime, keys))
        }
    }
}

/** A MediaCodec as WebRTC drives it (MediaCodecWrapperImpl's), with [tune] applied to its format. */
private class Codec(
    private val codec: MediaCodec,
    private val tune: (MediaCodec, MediaFormat) -> Unit,
) : MediaCodecWrapper {
    override fun configure(format: MediaFormat, surface: Surface?, crypto: MediaCrypto?, flags: Int) {
        tune(codec, format)
        codec.configure(format, surface, crypto, flags)
    }
    override fun start() = codec.start()
    override fun flush() = codec.flush()
    override fun stop() = codec.stop()
    override fun release() = codec.release()
    override fun dequeueInputBuffer(timeoutUs: Long): Int = codec.dequeueInputBuffer(timeoutUs)
    override fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) =
        codec.queueInputBuffer(index, offset, size, presentationTimeUs, flags)
    override fun dequeueOutputBuffer(info: MediaCodec.BufferInfo, timeoutUs: Long): Int = codec.dequeueOutputBuffer(info, timeoutUs)
    override fun releaseOutputBuffer(index: Int, render: Boolean) = codec.releaseOutputBuffer(index, render)
    override fun getInputFormat(): MediaFormat = codec.inputFormat
    override fun getOutputFormat(): MediaFormat = codec.outputFormat
    override fun getOutputFormat(index: Int): MediaFormat = codec.getOutputFormat(index)
    override fun getInputBuffer(index: Int): ByteBuffer? = codec.getInputBuffer(index)
    override fun getOutputBuffer(index: Int): ByteBuffer? = codec.getOutputBuffer(index)
    override fun createInputSurface(): Surface = codec.createInputSurface()
    override fun setParameters(params: Bundle) = codec.setParameters(params)
    override fun getCodecInfo(): MediaCodecInfo = codec.codecInfo
}
