package media.sovereign.sovemu.core

import com.apollographql.apollo.api.Optional
import media.sovereign.sovemu.core.graphql.type.ClientCapabilitiesInput
import media.sovereign.sovemu.core.graphql.type.DisplayInput
import media.sovereign.sovemu.core.graphql.type.VideoCodec
import media.sovereign.sovemu.core.graphql.type.VideoDecoderInput

/**
 * The decoder this device would use for one of WebRTC's codecs ([codec]: its name there, `VP8`,
 * `H264`…): whether the device's [hardware] decodes it, whether it hands over each frame as soon
 * as it is decoded ([lowLatency]), and the decoder's own [name] (`c2.amlogic.avc.decoder`,
 * `libvpx`).
 */
data class DecoderChoice(val codec: String, val hardware: Boolean, val lowLatency: Boolean, val name: String?)

/** The screen games are shown on: physical pixels, and pictures a second. */
data class ScreenMode(val width: Int, val height: Int, val refreshHz: Double)

/**
 * What this device can decode and show, as SovEmu's server takes it with every offer
 * (`sessionOffer`'s `capabilities`): one complete document of [VERSION], sent whole each time.
 * The server keeps it against this sign-in and will pick each session's codec from it.
 */
object Capabilities {
    /** The document's shape. The server refuses any other, asking the player to update. */
    const val VERSION = 1

    /** Longest decoder name the server keeps. */
    private const val MAX_NAME = 80

    /**
     * The document for [decoders] and [screen]. A codec the server has no name for is left out,
     * and a codec listed more than once (WebRTC lists H.264 once per profile) keeps its first
     * row: the server takes one decoder for each codec.
     */
    fun document(decoders: List<DecoderChoice>, screen: ScreenMode): ClientCapabilitiesInput {
        val rows = LinkedHashMap<VideoCodec, VideoDecoderInput>()
        for (decoder in decoders) {
            val codec = codec(decoder.codec) ?: continue
            rows.getOrPut(codec) {
                VideoDecoderInput(
                    codec = codec,
                    hardware = decoder.hardware,
                    lowLatency = decoder.lowLatency,
                    name = Optional.presentIfNotNull(decoder.name?.trim()?.take(MAX_NAME)?.ifEmpty { null }),
                )
            }
        }
        return ClientCapabilitiesInput(
            version = VERSION,
            videoDecoders = rows.values.toList(),
            display = DisplayInput(width = screen.width, height = screen.height, refreshHz = screen.refreshHz),
        )
    }

    /** The server's name for one of WebRTC's codec names; null for one it does not know. */
    fun codec(webRtcName: String): VideoCodec? = when (webRtcName.uppercase()) {
        "VP8" -> VideoCodec.VP8
        "H264" -> VideoCodec.H264
        "VP9" -> VideoCodec.VP9
        "AV1" -> VideoCodec.AV1
        "H265" -> VideoCodec.H265
        else -> null
    }

    /** The document in a line, for the log: `vp8 software, h264 hardware low-latency`. */
    fun summary(document: ClientCapabilitiesInput): String = document.videoDecoders.joinToString(", ") {
        val how = if (it.hardware) "hardware" else "software"
        "${it.codec.rawValue.lowercase()} $how${if (it.lowLatency) " low-latency" else ""}"
    }
}
