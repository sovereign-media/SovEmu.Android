package media.sovereign.sovemu.core

import kotlin.math.ceil

/**
 * The stats overlay's latency graph: the last [capacity] samples (one a second) of the measured
 * button→frame and the estimated button→pixel, oldest first, and the graph's y scale. A fixed
 * ring, so adding a sample allocates nothing; an unknown value is NaN (a gap in its line).
 */
class LatencyHistory(val capacity: Int = DEFAULT_CAPACITY) {
    private val measured = DoubleArray(capacity) { Double.NaN }
    private val estimated = DoubleArray(capacity) { Double.NaN }
    private var next = 0

    /** Samples held, up to [capacity]. */
    var size = 0
        private set

    fun add(measuredMs: Double?, estimatedMs: Double?) {
        measured[next] = measuredMs ?: Double.NaN
        estimated[next] = estimatedMs ?: Double.NaN
        next = (next + 1) % capacity
        size = minOf(size + 1, capacity)
    }

    /** Forgets every sample (the overlay was turned off, or a new stream began). */
    fun clear() {
        next = 0
        size = 0
    }

    /** The [i]th held sample, 0 the oldest; NaN when unknown. */
    fun measured(i: Int): Double = measured[slot(i)]
    fun estimated(i: Int): Double = estimated[slot(i)]

    /** The newest measured value, or null when the newest sample has none. */
    val latestMeasured: Double?
        get() = if (size == 0) null else measured(size - 1).takeUnless { it.isNaN() }

    /**
     * The top of the y axis in ms: the highest value held, rounded up to a multiple of
     * [SCALE_STEP_MS], and never below [MIN_SCALE_MS].
     */
    fun scaleMaxMs(): Double {
        var highest = 0.0
        for (i in 0 until size) {
            val m = measured(i)
            val e = estimated(i)
            if (!m.isNaN() && m > highest) highest = m
            if (!e.isNaN() && e > highest) highest = e
        }
        return maxOf(MIN_SCALE_MS, ceil(highest / SCALE_STEP_MS) * SCALE_STEP_MS)
    }

    private fun slot(i: Int): Int {
        require(i in 0 until size) { "sample $i of $size" }
        return (next - size + i + capacity) % capacity
    }

    companion object {
        /** A minute, at one stats sample a second. */
        const val DEFAULT_CAPACITY = 60
        const val SCALE_STEP_MS = 25.0
        const val MIN_SCALE_MS = 50.0
    }
}
