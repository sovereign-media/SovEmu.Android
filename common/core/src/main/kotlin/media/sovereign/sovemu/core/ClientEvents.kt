package media.sovereign.sovemu.core

/**
 * One thing that went wrong on this device, for the server's log (`reportClientEvents`, logged
 * as `client event` beside the game's own lines). [kind] is a `ClientEventKind` name.
 */
data class ClientEvent(
    val kind: String,
    val message: String,
    val sessionId: String?,
    val at: Double,
)

/**
 * Gathers events for the server: each distinct one once (a fault repeating every frame is sent
 * once), in batches no larger than the server takes. When to send is the caller's: [take]
 * hands over what has gathered.
 */
class ClientEventQueue(private val now: () -> Double = { System.currentTimeMillis() / 1000.0 }) {
    private val queue = ArrayDeque<ClientEvent>()
    private val seen = HashSet<String>()

    /** Queues an event; false if it was sent before (or too many distinct ones have been). */
    @Synchronized
    fun add(kind: String, message: String, sessionId: String? = null): Boolean {
        val key = "$kind|$message|${sessionId.orEmpty()}"
        if (seen.size >= MAX_DISTINCT || !seen.add(key)) return false
        queue.addLast(ClientEvent(kind, message.take(MAX_MESSAGE), sessionId, now()))
        return true
    }

    /** What has gathered, in batches of at most [MAX_BATCH]; the queue is then empty. */
    @Synchronized
    fun take(): List<List<ClientEvent>> = queue.toList().chunked(MAX_BATCH).also { queue.clear() }

    companion object {
        /** The server takes at most this many a call. */
        const val MAX_BATCH = 20
        const val MAX_MESSAGE = 500
        private const val MAX_DISTINCT = 200
    }
}
