package media.sovereign.sovemu

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import media.sovereign.sovemu.core.SessionInfo
import media.sovereign.sovemu.core.stopAbandonedStart

/**
 * Game sessions on the server, owned by the app rather than the player screen: a session that
 * is still starting when the player is left is stopped as soon as it exists, instead of running
 * on until the server's idle timeout. Stopping asks the worker to write its saves first.
 */
class Sessions(private val api: SovEmuApi, private val scope: CoroutineScope) {
    /**
     * Starts [gameId] now, named [startId] (its progress: [SovEmuApi.startProgress]); the player
     * awaits it. A start whose answer never comes may still go on on the server: it is followed
     * until it is ready, and stopped ([stopAbandonedStart]).
     */
    fun start(gameId: Int, startId: String, slot: Int? = null): Deferred<SessionInfo> =
        scope.async {
            try {
                api.startSession(gameId, startId, slot)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                scope.launch { attempt { stopAbandonedStart(api, gameId, startId) } }
                throw e
            }
        }

    /** Stops [session] once it has started (if it does), then runs [after]. */
    fun stop(session: Deferred<SessionInfo>, after: () -> Unit) {
        scope.launch {
            attempt { session.await() }.getOrNull()?.let { attempt { api.stopSession(it.id) } }
            after()
        }
    }
}
