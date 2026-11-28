package media.sovereign.sovemu.core

import java.util.Locale
import java.util.UUID
import kotlin.math.exp
import kotlin.math.floor
import media.sovereign.sovemu.core.graphql.StartProgressQuery
import media.sovereign.sovemu.core.graphql.type.StartPhase

// The loading screen while a game starts, as the web client's (SovEmu.Web/src/lib/launch.ts):
// the server's phases (saving the last session, starting the emulator, opening the core, reading
// the game) turned into one bar that only moves forward, then connecting, the worker's check of
// the connection (#243), and the wait for the first picture, which the server cannot see.
//
// Reading the game is most of a slow start. When the server knows how much it will read the bar
// is exact; when the core reads a disc itself it is not, and the bar eases towards the end of
// that phase instead (always moving, never arriving) while the megabytes read count up.

/** What the server says about a start (the `startProgress` query). */
data class StartStatus(
    val phase: StartPhase,
    val read: Double? = null,
    val total: Double? = null,
    val elapsedMs: Double = 0.0,
    val phaseMs: Double = 0.0,
    val error: String? = null,
)

fun StartProgressQuery.StartProgress.toStartStatus() =
    StartStatus(phase, read, total, elapsedMs, phaseMs, error)

/**
 * One line of the loading screen: [label], its [detail] (megabytes and seconds, or the error),
 * [fraction] 0..1 along the whole start.
 */
data class LaunchView(val label: String, val detail: String, val fraction: Double)

private class Phase(val from: Double, val to: Double, val label: String, val ease: Double)

/** Each phase's share of the bar, and what it is called. */
private val PHASES = mapOf(
    StartPhase.STOPPING to Phase(0.0, 0.08, "Saving your last game", 3000.0),
    StartPhase.LAUNCHING to Phase(0.08, 0.12, "Starting the emulator", 1000.0),
    StartPhase.CORE to Phase(0.12, 0.18, "Loading the emulator", 1500.0),
    StartPhase.CONTENT to Phase(0.18, 0.85, "Loading the game", 15000.0),
    StartPhase.READY to Phase(0.85, 0.85, "Ready", 1.0),
    StartPhase.FAILED to Phase(0.0, 0.0, "Could not start", 1.0),
)

/**
 * A phase a newer server knows and this app does not: named generically, the bar left where it
 * was.
 */
private val UNKNOWN_PHASE = Phase(0.0, 0.0, "Starting", 1.0)

private const val MB = 1024.0 * 1024.0

/** Megabytes as the web writes them: whole from 100 MB, else to one decimal (no trailing `.0`). */
private fun mb(bytes: Double): String {
    val x = if (bytes >= 100 * MB) floor(bytes / MB + 0.5) else floor(bytes / MB * 10 + 0.5) / 10
    return if (x == floor(x)) x.toLong().toString() else String.format(Locale.ROOT, "%.1f", x)
}

fun describeStart(s: StartStatus): LaunchView {
    val p = PHASES[s.phase] ?: UNKNOWN_PHASE
    if (s.phase == StartPhase.FAILED) return LaunchView(p.label, s.error ?: "", 0.0)
    val read = s.read
    val total = s.total
    val within = if (total != null && total > 0 && read != null) {
        minOf(1.0, read / total)
    } else {
        1 - exp(-s.phaseMs / p.ease)
    }
    val parts = mutableListOf<String>()
    if (s.phase == StartPhase.CONTENT && read != null && read > 0) {
        parts += if (total != null && total != 0.0) {
            "${mb(minOf(read, total))} of ${mb(total)} MB"
        } else {
            "${mb(read)} MB read"
        }
    }
    val seconds = floor(s.elapsedMs / 1000).toLong()
    if (seconds >= 3) parts += "$seconds s"
    return LaunchView(p.label, parts.joinToString(" · "), p.from + (p.to - p.from) * within)
}

/** After this long without a picture the wait says how to get out of it. */
const val NO_PICTURE_HINT_MS = 20_000L

/** Where the check of the connection takes the bar: from the session being ready to almost full. */
private const val NETWORK_FROM = 0.85
private const val NETWORK_TO = 0.97

/**
 * The session is ready and this app is reaching it (ICE, then DTLS): nothing to show of the
 * game yet, and the worker's check of the connection comes once connected. "Reconnecting" once
 * an earlier connection was given up on ([attempt] > 0, [Reconnect]).
 */
fun describeConnecting(waitedMs: Long, attempt: Int = 0): LaunchView {
    val seconds = waitedMs / 1000
    val label = if (attempt > 0) "Reconnecting" else "Connecting"
    val fraction = creep(NETWORK_FROM, CONNECTING_TO, waitedMs, CONNECTING_CREEP_MS)
    return LaunchView(label, if (seconds >= 3) "$seconds s" else "", fraction)
}

/**
 * How far the bar creeps while connecting, and how fast: a little, so it never looks stuck,
 * and never into the check of the connection's stretch, which starts again from [NETWORK_FROM].
 */
private const val CONNECTING_TO = 0.88
private const val CONNECTING_CREEP_MS = 3_000.0

/** Likewise while waiting for the first picture, which can take a BIOS's boot. */
private const val PICTURE_TO = 0.995
private const val PICTURE_CREEP_MS = 8_000.0

/**
 * [from], eased towards [to] over [waitedMs]: most of the way in a few [creepMs], never quite
 * there, so a stretch whose end cannot be known still moves.
 */
private fun creep(from: Double, to: Double, waitedMs: Long, creepMs: Double): Double =
    from + (to - from) * (1 - exp(-waitedMs.coerceAtLeast(0) / creepMs))

/** The loading screen once reconnecting has given up ([Reconnect]), as the web words it. */
fun describeConnectionFailed(): LaunchView = LaunchView(
    "Could not connect to the game",
    "The server's game ports may be unreachable from here",
    0.0,
)

/**
 * The check of the player's connection before the stream starts (`0x83`, #243): the worker
 * sends no video meanwhile, so it runs between the session being ready and the first picture.
 * The bar follows its time against its budget. Where the player is shows in a chip of its own
 * ([networkLabel]).
 */
fun describeLink(link: Protocol.Link): LaunchView {
    val within = if (link.budgetMs > 0) {
        minOf(1.0, link.elapsedMs.toDouble() / link.budgetMs)
    } else {
        1.0
    }
    return LaunchView(
        "Checking your connection",
        "",
        NETWORK_FROM + (NETWORK_TO - NETWORK_FROM) * within,
    )
}

/**
 * The last stretch of a start, which the server cannot see: the session is ready (and the
 * connection checked), and the game has yet to draw its first picture (a console's BIOS boots
 * for seconds; a save is loaded once the game is up). The detail counts the wait. (Back, where
 * the web says Escape.)
 */
fun describeFirstPicture(waitedMs: Long): LaunchView {
    val seconds = waitedMs / 1000
    val parts = mutableListOf<String>()
    if (seconds >= 3) parts += "$seconds s"
    if (waitedMs >= NO_PICTURE_HINT_MS) parts += "still no picture: Back opens the menu"
    return LaunchView(
        "Starting the game",
        parts.joinToString(" · "),
        creep(NETWORK_TO, PICTURE_TO, waitedMs, PICTURE_CREEP_MS),
    )
}

/**
 * The loading screen's background: a picture ([url], a server path), and how it is drawn:
 * [pixelated], a save's screenshot at the console's own resolution, drawn with sharp pixels;
 * [blurred], box art, which is not a picture of the game being played, blurred into a backdrop.
 */
data class LoadingBackground(val url: String, val pixelated: Boolean, val blurred: Boolean)

/**
 * What the loading screen shows behind the start (#243), first found of: the screenshot of the
 * save being continued ([saveShot]), the game's own screenshot ([image]), its box art ([boxArt],
 * blurred). Null leaves the system's colour.
 */
fun loadingBackground(saveShot: String?, image: String?, boxArt: String?): LoadingBackground? =
    when {
        !saveShot.isNullOrEmpty() -> LoadingBackground(saveShot, pixelated = true, blurred = false)
        !image.isNullOrEmpty() -> LoadingBackground(image, pixelated = false, blurred = false)
        !boxArt.isNullOrEmpty() -> LoadingBackground(boxArt, pixelated = false, blurred = true)
        else -> null
    }

/** The loading screen's status line once the emulator is up: `FCEUmm · 256×224 @ 60.10 fps`. */
fun streamInfo(core: String, width: Int, height: Int, fps: Double): String =
    String.format(Locale.ROOT, "%s · %d×%d @ %.2f fps", core, width, height, fps)

/** Keeps a bar from ever moving backwards (a phase can report less than the one before). */
class ForwardOnly {
    private var max = 0.0

    fun next(fraction: Double): Double {
        max = maxOf(max, minOf(1.0, maxOf(0.0, fraction)))
        return max
    }
}

/** An id for following one start (letters, digits and dashes, as the server requires). */
fun newStartId(): String = UUID.randomUUID().toString()

/**
 * What the player's loading screen shows: [label] and [detail], over a bar at [fraction] (null:
 * no bar, an error).
 */
data class StartScreen(val label: String, val detail: String = "", val fraction: Double? = null)

/**
 * The loading screen, one state for the whole start: the server's progress while the session
 * starts, then the wait for the first picture once it is ready (a console's BIOS can boot for
 * seconds first, and continuing from a save waits for the core's first frame), then nothing.
 */
object GameStart {
    /**
     * Under the loading screen, how to get out: the in-game menu (which has Quit) opens over it.
     * A controller's B goes to the game while it starts (some wait for a button), so the menu is
     * Back on a remote, or Select+Start on a controller (its guide button works too). As terse
     * as the web's "Menu: Esc / Select+Start".
     */
    const val MENU_HINT = "Menu: Back / Select+Start"

    /**
     * [error] (a failure to start, or a lost connection) wins; then the session starting
     * ([ready] not yet), with the server's [progress] once it has said (a FAILED start shows its
     * error); then, until [pictureShown], connecting until the worker's [link] check is heard
     * from, the check while it is probing, and the wait for the picture, [waitedMs] since the
     * session was ready. An older worker never sends a check: it reads "Connecting" until its
     * picture, which comes at once. [attempt] counts the connections given up on so far
     * ([Reconnect]); [connectionFailed]: reconnecting has given up before any picture.
     */
    fun screen(
        title: String,
        error: String?,
        progress: StartStatus?,
        bar: Double,
        ready: Boolean,
        pictureShown: Boolean,
        waitedMs: Long,
        link: Protocol.Link? = null,
        attempt: Int = 0,
        connectionFailed: Boolean = false,
    ): StartScreen? {
        if (error != null) return StartScreen(error)
        if (connectionFailed) {
            return describeConnectionFailed().let { StartScreen(it.label, it.detail) }
        }
        if (!ready) {
            progress ?: return StartScreen("Starting $title…", fraction = bar)
            val view = describeStart(progress)
            return if (progress.phase == StartPhase.FAILED) {
                StartScreen(view.label, view.detail)
            } else {
                StartScreen(view.label, view.detail, bar)
            }
        }
        if (pictureShown) return null
        val view = when {
            link == null -> describeConnecting(waitedMs, attempt)
            link.probing -> describeLink(link)
            else -> describeFirstPicture(waitedMs)
        }
        return StartScreen(view.label, view.detail, view.fraction)
    }
}
