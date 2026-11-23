package media.sovereign.sovemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyHistoryTest {
    @Test
    fun `an empty graph has the minimum scale and no latest value`() {
        val history = LatencyHistory()
        assertEquals(0, history.size)
        assertEquals(50.0, history.scaleMaxMs(), 0.0)
        assertNull(history.latestMeasured)
    }

    @Test
    fun `samples are oldest first and the ring keeps the last ones`() {
        val history = LatencyHistory(capacity = 3)
        for (i in 1..5) history.add(i.toDouble(), i * 10.0)
        assertEquals(3, history.size)
        assertEquals(listOf(3.0, 4.0, 5.0), List(3) { history.measured(it) })
        assertEquals(listOf(30.0, 40.0, 50.0), List(3) { history.estimated(it) })
        assertEquals(5.0, history.latestMeasured!!, 0.0)
    }

    @Test
    fun `unknown values are gaps`() {
        val history = LatencyHistory()
        history.add(null, 40.0)
        assertTrue(history.measured(0).isNaN())
        assertNull(history.latestMeasured)
    }

    @Test
    fun `the scale rounds the highest value up to 25 ms, at least 50`() {
        val history = LatencyHistory()
        history.add(30.0, 49.0)
        assertEquals(50.0, history.scaleMaxMs(), 0.0)
        history.add(51.0, null)
        assertEquals(75.0, history.scaleMaxMs(), 0.0)
        history.add(null, 100.0)
        assertEquals(100.0, history.scaleMaxMs(), 0.0)
        history.add(101.0, 20.0)
        assertEquals(125.0, history.scaleMaxMs(), 0.0)
    }

    @Test
    fun `clearing forgets every sample`() {
        val history = LatencyHistory()
        history.add(200.0, 200.0)
        history.clear()
        assertEquals(0, history.size)
        assertEquals(50.0, history.scaleMaxMs(), 0.0)
    }

    @Test
    fun `the audio's delay is a third line on a scale of its own`() {
        val history = LatencyHistory()
        history.add(40.0, 60.0)
        assertTrue("unknown until the audio's delay is", history.audio(0).isNaN())
        assertNull("and no scale to label", history.audioScaleMaxMs())
        history.add(42.0, 61.0, 310.0)
        assertEquals(310.0, history.audio(1), 0.0)
        // The button lines keep their scale; the audio's rounds up to the next hundred.
        assertEquals(75.0, history.scaleMaxMs(), 0.0)
        assertEquals(400.0, history.audioScaleMaxMs()!!, 0.0)
        history.add(42.0, 61.0, 35.0)
        assertEquals(400.0, history.audioScaleMaxMs()!!, 0.0)
        history.clear()
        history.add(null, null, 35.0)
        assertEquals("never below one step", 100.0, history.audioScaleMaxMs()!!, 0.0)
    }
}
