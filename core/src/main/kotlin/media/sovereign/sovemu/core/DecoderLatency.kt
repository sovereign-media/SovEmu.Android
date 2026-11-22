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
     * The keys to set to 1, empty when [requested] is off or the decoder supports none.
     * [vendorParameters]: what the decoder lists, with or without the `vendor.` prefix.
     */
    fun keys(requested: Boolean, featureLowLatency: Boolean, vendorParameters: Collection<String>): List<String> {
        if (!requested) return emptyList()
        if (featureLowLatency) return listOf(KEY_LOW_LATENCY)
        val listed = vendorParameters.mapTo(HashSet()) { it.removePrefix("vendor.") }
        return VENDOR_KEYS.filter { it.removePrefix("vendor.") in listed }
    }
}
