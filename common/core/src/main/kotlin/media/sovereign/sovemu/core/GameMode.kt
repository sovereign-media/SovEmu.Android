package media.sovereign.sovemu.core

/**
 * Whether the TV's game picture mode is in effect, as far as the device can tell: the "TV game
 * mode" setting only asks for it, and not every display reports back (a streaming stick's TV
 * over HDMI may switch without saying so).
 */
enum class GameModeState(val label: String) {
    /** The setting is off: nothing is asked of the display. */
    OFF("off"),

    /** Asked for, and the display says it can switch. */
    ON("on"),

    /**
     * Asked for, but the display does not say whether it can switch (a streaming stick's TV
     * over HDMI), so the device cannot tell what became of the request. The TV may well be in
     * its game mode all the same: a Mi TV switches and shows its own banner.
     */
    UNSUPPORTED("Asked - Display does not report status"),

    /** Asked for on an Android too old to pass the request on (before 11). */
    UNAVAILABLE("needs Android 11"),
    ;

    /** The display is being asked, and says it can: the only state known to be in effect. */
    val applied: Boolean get() = this == ON

    companion object {
        /**
         * [setting]: the "TV game mode" setting. [supported]: what the display says; null before
         * Android 11.
         */
        fun of(setting: Boolean, supported: Boolean?): GameModeState = when {
            !setting -> OFF
            supported == null -> UNAVAILABLE
            supported -> ON
            else -> UNSUPPORTED
        }
    }
}
