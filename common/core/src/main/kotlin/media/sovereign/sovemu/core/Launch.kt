package media.sovereign.sovemu.core

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** The words *Who's watching?* and its neighbours use, as Sovereign.Android's. */
object LaunchText {
    const val WHO_IS_WATCHING = "Who's watching?"
    const val ADD_USER = "Add user"

    /** The account menu's way into the picker: it stands for switching user and server. */
    const val CHANGE_USER_OR_SERVER = "Change user or server"

    /** Under the current account's tile on the picker opened from the account menu. */
    const val SIGNED_IN_NOW = "Signed in now"

    /** The title of the sign-in form `Add user` opens. */
    const val ADD_A_USER = "Add a user"
    const val CHANGE_SERVER = "Change server"

    /** What the sign-in form says when an account's sign-in ended on the server. */
    const val EXPIRED = "Your sign-in has ended. Sign in again."

    /** Three across: five accounts and `Add user` fill two rows. */
    const val PICKER_COLUMNS = 3
}

/**
 * Whether the app shows *Who's watching?* as it comes back to the foreground, as Sovereign's:
 * an app left in the background keeps running for hours, so a picker shown only at launch would
 * almost never show, and whoever picks up the remote next would carry on as whoever used it
 * last. [backgroundedAt] and [now] are a monotonic clock (ms, counting deep sleep); fewer than
 * two accounts, a threshold of 0, or a game playing never show it.
 */
object IdlePicker {
    /**
     * Sovereign's default for its server's `userPickerAfterMinutes`; SovEmu's server has no such
     * setting yet, so the app uses this.
     */
    const val THRESHOLD_MINUTES = 60

    fun shouldShow(
        backgroundedAt: Long?,
        now: Long,
        accounts: Int,
        playing: Boolean,
        thresholdMinutes: Int = THRESHOLD_MINUTES,
    ): Boolean {
        if (backgroundedAt == null || accounts < 2 || thresholdMinutes <= 0 || playing) return false
        return now - backgroundedAt >= thresholdMinutes * 60_000L
    }
}

/** Where each D-pad direction goes from one tile of the picker. Null: nowhere. */
data class PickerMove(val left: Int?, val right: Int?, val up: Int?, val down: Int?)

/**
 * Every tile's four moves, for [count] tiles wrapped [columns] to a row with each row centred:
 * Up and Down go to the nearest tile in the next row, which on a centred short row is not the
 * same index (the geometric search answers by distance, and can land on the wrong row). A tie
 * goes left.
 */
fun pickerFocusMoves(count: Int, columns: Int): List<PickerMove> {
    if (count <= 0) return emptyList()
    val rows = (count + columns - 1) / columns
    fun size(row: Int) = if (row < rows - 1) columns else count - columns * (rows - 1)
    fun centre(row: Int, column: Int) = column - (size(row) - 1) / 2.0
    fun nearest(row: Int, from: Double): Int {
        var best = 0
        var distance = Double.MAX_VALUE
        for (column in 0 until size(row)) {
            val d = kotlin.math.abs(centre(row, column) - from)
            if (d < distance - 1e-9) {
                best = column
                distance = d
            }
        }
        return row * columns + best
    }
    return List(count) { index ->
        val row = index / columns
        val column = index % columns
        val x = centre(row, column)
        PickerMove(
            left = if (column > 0) index - 1 else null,
            right = if (column < size(row) - 1) index + 1 else null,
            up = if (row > 0) nearest(row - 1, x) else null,
            down = if (row < rows - 1) nearest(row + 1, x) else null,
        )
    }
}

/** The server the app talks to, as *Choose a server* moves it. */
interface ServerService {
    /** The address the app talks to now. */
    val server: String

    /** Makes [address] the server without asking it anything (putting one back). */
    fun setServer(address: String)

    /** Asks [address] how to sign in (`bootstrapConfig`), and keeps it as the server. */
    suspend fun bootstrapAt(address: String): SignInConfig
}

/** Where choosing or removing a server leaves the app. */
sealed interface ServerMove {
    /** Nothing changed: the server in use was chosen. */
    data object Stayed : ServerMove

    /** The app now talks to another server, and has landed there by the launch rule. */
    data class Moved(val landing: LaunchAccounts) : ServerMove

    /** No server is left: the sign-in screen, with an empty address. */
    data object NoServer : ServerMove

    /** The chosen server did not answer: the app is still on the one it was, and why. */
    data class Failed(val message: String) : ServerMove
}

/**
 * *Choose a server*'s choices, as Sovereign's: switching server is a choice rather than retyping
 * an address, and removing one signs out everyone on it. A live sign-in is set aside on a
 * switch, never signed out: its accounts stay, and come back with their server.
 */
class ServerChooser(private val service: ServerService, private val session: Session) {
    /**
     * Moves to [server]: it answers its `bootstrapConfig` (no token request), then the launch
     * rule lands there (its one account's Home, or its picker). A server that does not answer
     * leaves the app where it was.
     */
    suspend fun choose(server: KnownServer): ServerMove {
        if (server.inUse) return ServerMove.Stayed
        val previous = service.server
        try {
            service.bootstrapAt(server.address)
        } catch (e: CancellationException) {
            service.setServer(previous)
            throw e
        } catch (e: Exception) {
            service.setServer(previous)
            return ServerMove.Failed(
                (e as? ApiException ?: e as? OAuthException)?.message
                    ?: "Cannot reach ${server.address}: ${e.message}",
            )
        }
        return ServerMove.Moved(session.launch())
    }

    /**
     * Removes [server] from [servers] (the list as shown) and everyone on it. When it was the one
     * in use, the app moves to the server that took its place in the list (even if that one does
     * not answer: its picker makes no request), or to no server at all.
     *
     * Once begun it finishes, even if the caller goes (#131): removing the server in use signs
     * its account out, and the screens that asked go with the signed-in app. Stopped there, the
     * app would be left signed out on the next server, its stored account never restored.
     */
    suspend fun remove(server: KnownServer, servers: List<KnownServer>): ServerMove {
        val inUse = server.inUse || StoredAccount.sameServer(server.address, service.server)
        val next = KnownServers.successor(servers, server)
        return withContext(NonCancellable) {
            session.removeServer(server.address)
            when {
                !inUse -> ServerMove.Stayed

                next == null -> {
                    service.setServer("")
                    session.launch()
                    ServerMove.NoServer
                }

                else -> {
                    try {
                        service.bootstrapAt(next.address)
                    } catch (_: Exception) {
                        // Its picker makes no request: the app lands there all the same.
                        service.setServer(next.address)
                    }
                    ServerMove.Moved(session.launch())
                }
            }
        }
    }
}
