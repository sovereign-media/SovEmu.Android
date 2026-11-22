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
     * The keys to set to 1, empty when the decoder supports none.
     * [vendorParameters]: what the decoder lists, with or without the `vendor.` prefix.
     */
    fun keys(featureLowLatency: Boolean, vendorParameters: Collection<String>): List<String> {
        if (featureLowLatency) return listOf(KEY_LOW_LATENCY)
        val listed = vendorParameters.mapTo(HashSet()) { it.removePrefix("vendor.") }
        return VENDOR_KEYS.filter { it.removePrefix("vendor.") in listed }
    }
}

/**
 * How this stream's hardware video decoder was configured: its MediaCodec name ([codec],
 * `c2.amlogic.avc.decoder`), its [mime] type and the low-latency [keys] set on it.
 */
data class DecoderSetup(val codec: String, val mime: String, val keys: List<String>) {
    /** Low latency was applied. Keys are set whenever the decoder supports one, so also: supported. */
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
    fun logfmt(): String =
        "decoder=$codec mime=$mime low_latency_supported=$lowLatency low_latency_applied=$lowLatency " +
            "low_latency_key=${keyName ?: "none"}"
}
