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
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import media.sovereign.sovemu.core.DecoderChoice
import media.sovereign.sovemu.core.DecoderLatency
import media.sovereign.sovemu.core.DecoderSetup

/**
 * [DefaultVideoDecoderFactory]'s choice of decoders (software with the hardware one preferred,
 * falling back to software if it fails), except that the hardware decoder's MediaFormat gets
 * the low-latency keys the decoder supports ([DecoderLatency]), whenever it supports one.
 * [onConfigured] hears once per decoder how it went (on WebRTC's decoder thread).
 *
 * Every decoder whose frames pass through Java (MediaCodec ones, hardware or software) also
 * tells [onFrameEntered] of each encoded frame it is given, with the render time WebRTC planned
 * for it and its capture time: the measured latency matches frames across the decoder by them.
 * Decoders WebRTC runs in native code (libvpx) can't be watched; they are left as they are.
 */
class LowLatencyVideoDecoderFactory(
    private val egl: EglBase.Context?,
    private val onConfigured: (DecoderSetup) -> Unit,
    private val onFrameEntered: (renderTimeMs: Long, captureTimeNs: Long) -> Unit,
) : VideoDecoderFactory {
    private val defaults = DefaultVideoDecoderFactory(egl)
    private val hardware = HardwareVideoDecoderFactory(egl)
    private val software = SoftwareVideoDecoderFactory()
    private val platformSoftware = PlatformSoftwareVideoDecoderFactory(egl)

    override fun getSupportedCodecs(): Array<VideoCodecInfo> = defaults.supportedCodecs

    /**
     * The decoder [createDecoder] would give each supported codec, for the capabilities this
     * app reports to the server: the hardware one where there is one (it is tried first), else
     * the software one. Nothing is opened but, on a hardware decoder without the standard
     * low-latency feature, the codec itself for a moment, to read which vendor keys it knows.
     */
    fun describe(): List<DecoderChoice> = supportedCodecs.distinctBy {
        it.name
    }.mapNotNull { info ->
        runCatching { describe(info) }.getOrNull()
    }

    private fun describe(info: VideoCodecInfo): DecoderChoice? {
        val hw = hardware.createDecoder(info)
        // WebRTC's own software decoder, made once for every question below.
        val native = software.createDecoder(info)
        if (hw is AndroidVideoDecoder && !softwareInstead(info, hw, native)) {
            val name = hw.implementationName
            val codec = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .firstOrNull { it.name == name && !it.isEncoder }
            // A decoder named as software is one, whatever the platform calls it.
            val hardware = !DecoderLatency.softwareByName(name) &&
                (codec?.let(::hardwareAccelerated) ?: true)
            return DecoderChoice(
                info.name,
                hardware = hardware,
                lowLatency = lowLatency(info, name),
                name = name,
            )
        }
        if (hw is AndroidVideoDecoder) {
            // The hardware decoder holds frames back, so createDecoder gives WebRTC's own.
            val sw = native ?: return null
            return DecoderChoice(
                info.name,
                hardware = false,
                lowLatency = true,
                name = sw.javaClass.simpleName,
            )
        }
        if (hw != null) {
            return DecoderChoice(
                info.name,
                hardware = true,
                lowLatency = false,
                name = hw.javaClass.simpleName,
            )
        }
        val sw = native ?: platformSoftware.createDecoder(info) ?: return null
        // WebRTC's own decoders (libvpx, dav1d) run in native code and return each frame as it
        // is decoded; a platform software codec says nothing about holding frames.
        val inNativeCode = sw is WrappedNativeVideoDecoder
        val name = if (inNativeCode) {
            sw.javaClass.simpleName
        } else {
            runCatching { sw.implementationName }.getOrNull()
        }
        return DecoderChoice(info.name, hardware = false, lowLatency = inNativeCode, name = name)
    }

    /**
     * Whether the platform calls [codec] hardware; before Android 10, whether it is not one of
     * Google's software ones.
     */
    private fun hardwareAccelerated(codec: MediaCodecInfo): Boolean = if (Build.VERSION.SDK_INT >=
        Build.VERSION_CODES.Q
    ) {
        codec.isHardwareAccelerated
    } else {
        !codec.name.startsWith("OMX.google.") && !codec.name.startsWith("c2.android.")
    }

    /** The vendor parameters the decoder [name] lists (Android 12+), opening it only to ask. */
    private fun vendorParameters(name: String): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyList()
        return runCatching {
            val codec = MediaCodec.createByCodecName(name)
            try {
                codec.supportedVendorParameters
            } finally {
                codec.release()
            }
        }.getOrDefault(emptyList())
    }

    override fun createDecoder(info: VideoCodecInfo): VideoDecoder? {
        // WebRTC's own software decoder, made once: asked whether it is native, and used.
        val native = software.createDecoder(info)
        val chosen = hardware.createDecoder(info)
        // A hardware decoder that holds frames back gives way to WebRTC's own software one.
        if (native != null && softwareInstead(info, chosen, native)) return watched(native)
        // Otherwise as DefaultVideoDecoderFactory.createDecoder.
        val sw = (native ?: platformSoftware.createDecoder(info))?.let(::watched)
        val hw = chosen?.let { watched(configurable(info, it)) }
        return if (hw != null && sw != null) VideoDecoderFallback(sw, hw) else hw ?: sw
    }

    /** Whether each hardware decoder (by name and mime type) has a low-latency mode; asked once. */
    private val lowLatencyModes = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** Whether the hardware decoder [name] has a low-latency mode for [info]'s codec. */
    private fun lowLatency(info: VideoCodecInfo, name: String): Boolean {
        val mime = VideoCodecMimeType.valueOf(info.name).mimeType()
        return lowLatencyModes.getOrPut("$name $mime") {
            val codec = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .firstOrNull { it.name == name && !it.isEncoder }
            val feature = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                codec?.let { capabilities(it, mime) }?.isFeatureSupported(
                    MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency,
                ) == true
            DecoderLatency.keys(feature, if (feature) emptyList() else vendorParameters(name))
                .isNotEmpty()
        }
    }

    /**
     * [DecoderLatency.prefersSoftware] for the hardware decoder the library [chosen] for [info],
     * against WebRTC's own software decoder for it, [native] (made once by the caller).
     */
    private fun softwareInstead(
        info: VideoCodecInfo,
        chosen: VideoDecoder?,
        native: VideoDecoder?,
    ): Boolean {
        if (chosen !is AndroidVideoDecoder) return false
        val name = chosen.implementationName
        val nativeSoftware = native is WrappedNativeVideoDecoder
        return DecoderLatency.prefersSoftware(name, lowLatency(info, name), nativeSoftware)
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
        val colour = MediaCodecUtils.selectColorFormat(MediaCodecUtils.DECODER_COLOR_FORMATS, caps)
            ?: return chosen
        val feature = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
        return AndroidVideoDecoder(Codecs(type.mimeType(), feature), name, type, colour, egl)
    }

    /** [decoder], telling [onFrameEntered] of each frame it is given; a native one as it is. */
    private fun watched(decoder: VideoDecoder): VideoDecoder =
        if (decoder is WrappedNativeVideoDecoder) decoder else Watched(decoder, onFrameEntered)

    private fun capabilities(
        codec: MediaCodecInfo,
        mime: String,
    ): MediaCodecInfo.CodecCapabilities? = try {
        codec.getCapabilitiesForType(mime)
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Creates one decoder's MediaCodecs (again after a resolution change); reports the first. */
    private inner class Codecs(private val mime: String, private val featureLowLatency: Boolean) :
        MediaCodecWrapperFactory {
        private val reported = AtomicBoolean(false)

        override fun createByCodecName(name: String): MediaCodecWrapper =
            Codec(MediaCodec.createByCodecName(name)) {
                    codec,
                    format,
                ->
                val vendor = if (
                    !featureLowLatency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ) {
                    codec.supportedVendorParameters
                } else {
                    emptyList()
                }
                val keys = DecoderLatency.keys(featureLowLatency, vendor)
                for (key in keys) format.setInteger(key, 1)
                if (reported.compareAndSet(false, true)) {
                    onConfigured(DecoderSetup(name, mime, keys))
                }
            }
    }
}

/**
 * A MediaCodec as WebRTC drives it (MediaCodecWrapperImpl's), with [tune] applied to its format.
 */
private class Codec(
    private val codec: MediaCodec,
    private val tune: (MediaCodec, MediaFormat) -> Unit,
) : MediaCodecWrapper {
    override fun configure(
        format: MediaFormat,
        surface: Surface?,
        crypto: MediaCrypto?,
        flags: Int,
    ) {
        tune(codec, format)
        codec.configure(format, surface, crypto, flags)
    }
    override fun start() = codec.start()
    override fun flush() = codec.flush()
    override fun stop() = codec.stop()
    override fun release() = codec.release()
    override fun dequeueInputBuffer(timeoutUs: Long): Int = codec.dequeueInputBuffer(timeoutUs)
    override fun queueInputBuffer(
        index: Int,
        offset: Int,
        size: Int,
        presentationTimeUs: Long,
        flags: Int,
    ) = codec.queueInputBuffer(index, offset, size, presentationTimeUs, flags)
    override fun dequeueOutputBuffer(info: MediaCodec.BufferInfo, timeoutUs: Long): Int =
        codec.dequeueOutputBuffer(info, timeoutUs)
    override fun releaseOutputBuffer(index: Int, render: Boolean) =
        codec.releaseOutputBuffer(index, render)
    override fun getInputFormat(): MediaFormat = codec.inputFormat
    override fun getOutputFormat(): MediaFormat = codec.outputFormat
    override fun getOutputFormat(index: Int): MediaFormat = codec.getOutputFormat(index)
    override fun getInputBuffer(index: Int): ByteBuffer? = codec.getInputBuffer(index)
    override fun getOutputBuffer(index: Int): ByteBuffer? = codec.getOutputBuffer(index)
    override fun createInputSurface(): Surface = codec.createInputSurface()
    override fun setParameters(params: Bundle) = codec.setParameters(params)
    override fun getCodecInfo(): MediaCodecInfo = codec.codecInfo
}

/** The render time reported for a frame that came without a `DecodeInfo`. */
internal const val NO_RENDER_TIME = -1L

/** A Java decoder that reports each encoded frame on its way in, then decodes it as before. */
internal class Watched(
    private val decoder: VideoDecoder,
    private val onFrameEntered: (renderTimeMs: Long, captureTimeNs: Long) -> Unit,
) : VideoDecoder {
    override fun initDecode(
        settings: VideoDecoder.Settings,
        callback: VideoDecoder.Callback,
    ): VideoCodecStatus = decoder.initDecode(settings, callback)
    override fun release(): VideoCodecStatus = decoder.release()

    // WebRTC's native side passes a null DecodeInfo (it is `@Nullable` there in practice): a
    // non-null parameter here threw on the first frame, and an exception on the decoding thread
    // aborts the whole process. Without it the render time is unknown (-1, which matches no
    // frame), and frames are matched by capture time or by counting.
    override fun decode(frame: EncodedImage, info: VideoDecoder.DecodeInfo?): VideoCodecStatus {
        onFrameEntered(info?.renderTimeMs ?: NO_RENDER_TIME, frame.captureTimeNs)
        return decoder.decode(frame, info)
    }
    override fun getImplementationName(): String = decoder.implementationName
}
