package media.sovereign.sovemu.core

/**
 * Which `MediaFormat` keys ask a hardware video decoder for low latency (output each frame as
 * soon as it is decoded, rather than holding some back).
 *
 * - [KEY_LOW_LATENCY] (`MediaFormat.KEY_LOW_LATENCY`, Android 11+), when the decoder advertises
 *   `FEATURE_LowLatency`. Alone: a decoder that honours the standard key needs nothing else.
 * - Otherwise the vendor keys in [VENDOR_KEYS], each only when the decoder itself lists it among
 *   its vendor parameters (`MediaCodec.getSupportedVendorParameters`, Android 12+), so a key is
 *   never set on a decoder that does not know it. The names are the ones Moonlight uses.
 */
object DecoderLatency {
    const val KEY_LOW_LATENCY = "low-latency"

    val VENDOR_KEYS = listOf(
        "vendor.qti-ext-dec-low-latency.enable", // Qualcomm
        "vendor.low-latency.enable", // Amlogic
        "vendor.rtc-ext-dec-low-latency.enable", // Exynos
        "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req", // HiSilicon
    )

    /**
     * Whether a MediaCodec decoder is a software one by its name, whatever the platform says of
     * it: Google's own (`OMX.google.`, `c2.android.`) and a vendor's named so (`.sw`, as
     * `c2.amlogic.vp8.decoder.sw`, which its TV lists as hardware accelerated).
     */
    fun softwareByName(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.endsWith(".sw") ||
            n.contains(".sw.")
    }

    /**
     * Whether to decode with WebRTC's own software decoder instead of the device's hardware
     * one: when there is one for the codec ([nativeSoftware]: libvpx for VP8 and VP9, dav1d for
     * AV1), and the hardware decoder is real hardware with no low-latency mode. Such a decoder
     * holds frames back: a Fire TV Stick's VP8 one dropped a quarter of a 60 fps stream and
     * showed its picture over a second late, where software decodes a console's picture in a
     * few milliseconds. A device with no hardware decoder, or one named as software, or one
     * with a low-latency mode, is left as it is.
     *
     * MediaCodec's realtime hints do not make such a decoder usable, so they are not set:
     * measured on a Fire TV Stick 4K Max (`OMX.MTK.VIDEO.DECODER.VPX`, Android 11) with the 240p
     * Test Suite, button→frame was 255 and 231 ms with the hardware decoder, 252 ms with
     * `KEY_PRIORITY` 0 (realtime), and 357 ms with `KEY_OPERATING_RATE` at its maximum as well,
     * a quarter to a third of the frames dropped each time; libvpx gave 22 and 21 ms with none
     * dropped. getStats' `decode_ms` read 3.3 ms throughout and does not show the hold.
     */
    fun prefersSoftware(
        hardwareName: String?,
        lowLatency: Boolean,
        nativeSoftware: Boolean,
    ): Boolean =
        hardwareName != null && nativeSoftware && !lowLatency && !softwareByName(hardwareName)

    /**
     * The keys to set to 1, empty when the decoder supports none.
     * [vendorParameters]: what the decoder lists, with or without the `vendor.` prefix.
     */
    fun keys(featureLowLatency: Boolean, vendorParameters: Collection<String>): List<String> {
        if (featureLowLatency) return listOf(KEY_LOW_LATENCY)
        val listed = vendorParameters.mapTo(HashSet()) { it.removePrefix("vendor.") }
        // MediaTek's `vdec-lowlatency`, which Moonlight sets by decoder name, was tried on a
        // Fire TV Stick (MT8696, Android 11) and is not set: its VP8 decoder still dropped a
        // fifth of the frames and showed the picture half a second late.
        return VENDOR_KEYS.filter { it.removePrefix("vendor.") in listed }
    }
}

/**
 * How this stream's hardware video decoder was configured: its MediaCodec name ([codec],
 * `c2.amlogic.avc.decoder`), its [mime] type and the low-latency [keys] set on it.
 */
data class DecoderSetup(val codec: String, val mime: String, val keys: List<String>) {
    /**
     * Low latency was applied. Keys are set whenever the decoder supports one, so also: supported.
     */
    val lowLatency: Boolean get() = keys.isNotEmpty()

    /** The key(s) in words: `KEY_LOW_LATENCY` for the standard one, else the vendor names. */
    val keyName: String?
        get() = when {
            keys.isEmpty() -> null
            keys == listOf(DecoderLatency.KEY_LOW_LATENCY) -> "KEY_LOW_LATENCY"
            else -> keys.joinToString(",")
        }

    /** The Decoder row's suffix. */
    val label: String get() = if (lowLatency) "low latency: on" else "low latency: not supported"

    /** The one logcat line per stream (logfmt, beside the stats lines). */
    fun logfmt(): String = "decoder=$codec mime=$mime low_latency_supported=$lowLatency " +
        "low_latency_applied=$lowLatency low_latency_key=${keyName ?: "none"}"
}
