package media.sovereign.sovemu.core

/**
 * The most recent input round trips (ms), as acks bring them: a fixed ring, written for every
 * ack (up to once a frame) without boxing a number, and read once a second for the stats.
 */
class RecentRtts(private val capacity: Int = 60) {
    private val values = LongArray(capacity)
    private var next = 0
    private var size = 0

    @Synchronized
    fun add(ms: Long) {
        values[next] = ms
        next = (next + 1) % capacity
        if (size < capacity) size++
    }

    /** The round trips held, oldest first. */
    @Synchronized
    fun toList(): List<Long> = List(size) { values[(next - size + it + capacity) % capacity] }
}
