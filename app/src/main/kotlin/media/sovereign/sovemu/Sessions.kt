package media.sovereign.sovemu

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import media.sovereign.sovemu.core.SessionInfo

/**
 * Game sessions on the server, owned by the app rather than the player screen: a session that
 * is still starting when the player is left is stopped as soon as it exists, instead of running
 * on until the server's idle timeout. Stopping asks the worker to write its saves first.
 */
class Sessions(private val api: SovEmuApi, private val scope: CoroutineScope) {
    /** Starts [gameId] now; the player awaits it. */
    fun start(gameId: Int): Deferred<SessionInfo> = scope.async { api.startSession(gameId) }

    /** Stops [session] once it has started (if it does), then runs [after]. */
    fun stop(session: Deferred<SessionInfo>, after: () -> Unit) {
        scope.launch {
            attempt { session.await() }.getOrNull()?.let { attempt { api.stopSession(it.id) } }
            after()
        }
    }
}
