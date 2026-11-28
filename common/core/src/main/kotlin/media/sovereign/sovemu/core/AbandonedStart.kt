package media.sovereign.sovemu.core

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import media.sovereign.sovemu.core.graphql.type.StartPhase

/** What [stopAbandonedStart] asks the server; `SovEmuApi` implements it. */
interface StartService {
    suspend fun startProgress(startId: String): StartStatus
    suspend fun currentSession(): SessionInfo?
    suspend fun stopSession(sessionId: String)
}

/** How [stopAbandonedStart] follows a start the app gave up on. */
object AbandonedStartDefaults {
    /** Between two looks at the start's progress. */
    const val POLL_MS = 1_000L

    /** The longest it follows one: as long as the app would have waited for the answer. */
    const val MAX_WAIT_MS = LONG_WAIT_SECONDS * 1_000L

    /** Unanswered looks in a row (the server forgot the start, or cannot be reached). */
    const val MISSES = 5
}

/**
 * Stops the session of a start whose answer never came (the connection dropped, the app timed
 * out) though the server goes on with it: otherwise it runs, unplayed, until the server's idle
 * timeout. Follows [startId]'s progress until it is ready, then stops this user's current
 * session if it is [gameId] (the server runs one session a user, so it is this start's), and
 * returns whether it stopped one. A start that fails, is forgotten or outlasts
 * [AbandonedStartDefaults.MAX_WAIT_MS] stops nothing.
 */
suspend fun stopAbandonedStart(
    api: StartService,
    gameId: Int,
    startId: String,
    pollMs: Long = AbandonedStartDefaults.POLL_MS,
    maxWaitMs: Long = AbandonedStartDefaults.MAX_WAIT_MS,
): Boolean {
    var waited = 0L
    var misses = 0
    while (waited <= maxWaitMs) {
        val phase = try {
            api.startProgress(startId).phase.also { misses = 0 }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (++misses >= AbandonedStartDefaults.MISSES) return false
            null
        }
        when (phase) {
            StartPhase.READY -> {
                val session = api.currentSession()?.takeIf { it.gameId == gameId } ?: return false
                api.stopSession(session.id)
                return true
            }

            StartPhase.FAILED -> return false

            else -> {}
        }
        delay(pollMs)
        waited += pollMs
    }
    return false
}
