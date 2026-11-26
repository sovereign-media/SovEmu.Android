package media.sovereign.sovemu.core

import java.util.Locale
import java.util.UUID
import kotlin.math.exp
import kotlin.math.floor
import media.sovereign.sovemu.core.graphql.StartProgressQuery
import media.sovereign.sovemu.core.graphql.type.StartPhase

// The loading screen while a game starts, as the web client's (SovEmu.Web/src/lib/launch.ts):
// the server's phases (saving the last session, starting the emulator, opening the core, reading
// the game) turned into one bar that only moves forward, then the wait for the first picture,
// which the server cannot see.
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
    StartPhase.CONTENT to Phase(0.18, 0.97, "Loading the game", 15000.0),
    StartPhase.READY to Phase(1.0, 1.0, "Ready", 1.0),
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

/**
 * The last stretch of a start, which the server cannot see: the session is ready, and the game
 * has yet to draw its first picture (a console's BIOS boots for seconds; a save is loaded once
 * the game is up). The bar is full; the detail counts the wait. (Back, where the web says Escape.)
 */
fun describeFirstPicture(waitedMs: Long): LaunchView {
    val seconds = waitedMs / 1000
    val parts = mutableListOf<String>()
    if (seconds >= 3) parts += "$seconds s"
    if (waitedMs >= NO_PICTURE_HINT_MS) parts += "still no picture: Back opens the menu"
    return LaunchView("Starting the game", parts.joinToString(" · "), 1.0)
}

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
     * A controller's B goes to the game while it starts (some wait for a button), so on a
     * controller it is the guide/home button or Select+Start; on a remote, Back.
     */
    const val MENU_HINT =
        "To quit, open the menu: Back on the remote, or Home (or Select + Start) on a controller."

    /**
     * [error] (a failure to start, or a lost connection) wins; then the session starting
     * ([ready] not yet), with the server's [progress] once it has said (a FAILED start shows its
     * error); then, until [pictureShown], the wait for it, [waitedMs] so far.
     */
    fun screen(
        title: String,
        error: String?,
        progress: StartStatus?,
        bar: Double,
        ready: Boolean,
        pictureShown: Boolean,
        waitedMs: Long,
    ): StartScreen? {
        if (error != null) return StartScreen(error)
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
        val view = describeFirstPicture(waitedMs)
        return StartScreen(view.label, view.detail, view.fraction)
    }
}
