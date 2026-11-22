package media.sovereign.sovemu.core

/**
 * What the player's loading screen says while a game starts: [label], with a spinner while
 * [busy], and a [hint] under it.
 */
data class StartScreen(val label: String, val busy: Boolean, val hint: String? = null)

/**
 * The loading screen's phases. The server's session is ready as soon as the core has opened the
 * game, but a console's BIOS can boot for seconds before the core draws (a PS2 for 5-6 s), and
 * continuing from a save waits for the core's first frame too: so the screen stays up until the
 * first picture reaches the renderer, saying so.
 */
object GameStart {
    const val WAITING_LABEL = "Starting the game…"
    const val NO_PICTURE_HINT = "Still no picture. Back opens the menu."

    /** Waiting this long for the first picture adds [NO_PICTURE_HINT]. */
    const val NO_PICTURE_HINT_MS = 20_000L

    /**
     * The screen to show, or null once the game's picture is up. [error] (a failure to start or
     * a lost connection) wins; then the session starting ([ready] not yet); then the wait for the
     * first picture ([pictureShown]), with the hint once it has been [slow].
     */
    fun screen(title: String, error: String?, ready: Boolean, pictureShown: Boolean, slow: Boolean): StartScreen? = when {
        error != null -> StartScreen(error, busy = false)
        !ready -> StartScreen("Starting $title…", busy = true)
        pictureShown -> null
        else -> StartScreen(WAITING_LABEL, busy = true, hint = NO_PICTURE_HINT.takeIf { slow })
    }
}
