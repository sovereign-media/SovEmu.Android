package media.sovereign.sovemu.core

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reconnecting (SovEmu#302), as the web client does (`launch.ts`): a connection that fails, stays
 * disconnected past [DISCONNECTED_GRACE_MS], or never connects within [CONNECT_TIMEOUT_MS] is
 * closed and offered again to the same session, which takes the new client in place of the old.
 * After [ATTEMPTS] reconnects the start has failed ([describeConnectionFailed]).
 */
object Reconnect {
    const val ATTEMPTS = 3
    const val DISCONNECTED_GRACE_MS = 5_000L
    const val CONNECT_TIMEOUT_MS = 15_000L
}

/** A connection's state, as WebRTC's `PeerConnectionState` names it. */
enum class PeerState { NEW, CONNECTING, CONNECTED, DISCONNECTED, FAILED, CLOSED }

/** One connection to the game's session, made and closed by [keepConnected]. */
interface PeerLink {
    /** Its state as it changes. */
    val state: StateFlow<PeerState>

    /** Offers it to the session and takes the answer; throws if that fails. */
    suspend fun connect()

    /** Ends it; safe to call more than once. */
    fun close()
}

/**
 * Keeps the game connected: opens a connection ([open], with the number of reconnects so far,
 * 0 for the first), watches it, and replaces it when it fails, stays disconnected too long or
 * never connects, up to [attempts] times. Returns once it has given up, with the last
 * connection closed; runs until cancelled otherwise. Every connection it opened is closed by the
 * time it returns or is cancelled.
 */
suspend fun keepConnected(
    open: suspend (attempt: Int) -> PeerLink,
    attempts: Int = Reconnect.ATTEMPTS,
) {
    var attempt = 0
    while (true) {
        val link = attemptOrNull { open(attempt) }
        try {
            if (link != null) watch(link)
        } finally {
            link?.close()
        }
        if (attempt >= attempts) return
        attempt++
    }
}

/** Returns once [link] should be replaced. */
private suspend fun watch(link: PeerLink) {
    val connected = withTimeoutOrNull(Reconnect.CONNECT_TIMEOUT_MS) {
        attemptOrNull { link.connect() } ?: return@withTimeoutOrNull false
        link.state.first { it.settled } == PeerState.CONNECTED
    }
    if (connected != true) return
    while (true) {
        // Disconnected often recovers by itself; failed never does.
        if (link.state.first { it.troubled } != PeerState.DISCONNECTED) return
        val back = withTimeoutOrNull(Reconnect.DISCONNECTED_GRACE_MS) {
            link.state.first { it.settled }
        }
        if (back != PeerState.CONNECTED) return
    }
}

private val PeerState.settled: Boolean
    get() = this == PeerState.CONNECTED || this == PeerState.FAILED || this == PeerState.CLOSED

private val PeerState.troubled: Boolean
    get() = this == PeerState.DISCONNECTED || this == PeerState.FAILED ||
        this == PeerState.CLOSED

/** [block]'s result, or null if it threw anything but cancellation. */
private suspend fun <T> attemptOrNull(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}
