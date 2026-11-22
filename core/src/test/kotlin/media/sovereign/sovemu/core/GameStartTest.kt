package media.sovereign.sovemu.core

import media.sovereign.sovemu.core.graphql.type.StartPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same cases as SovEmu.Web's launch.test.ts, then how the player's screen folds them in. */
class GameStartTest {
    private val mib = 1024.0 * 1024.0
    private fun status(
        phase: StartPhase = StartPhase.CONTENT,
        read: Double? = null,
        total: Double? = null,
        elapsedMs: Double = 0.0,
        phaseMs: Double = 0.0,
        error: String? = null,
    ) = StartStatus(phase, read, total, elapsedMs, phaseMs, error)

    @Test
    fun `the bar is exact when the total is known`() {
        val half = describeStart(status(read = 32 * mib, total = 64 * mib, elapsedMs = 5000.0))
        assertEquals("Loading the game", half.label)
        assertEquals("32 of 64 MB · 5 s", half.detail)
        assertEquals(0.18 + 0.79 / 2, half.fraction, 1e-9)
    }

    @Test
    fun `it eases forward and counts bytes when the total is not known`() {
        val early = describeStart(status(read = 3 * mib, phaseMs = 1000.0))
        val later = describeStart(status(read = 250 * mib, phaseMs = 30000.0))
        assertEquals("3 MB read", early.detail)
        assertEquals("250 MB read", later.detail)
        assertTrue(later.fraction > early.fraction)
        assertTrue(later.fraction < 0.97)
    }

    @Test
    fun `megabytes keep one decimal below 100`() {
        assertEquals("1.5 MB read", describeStart(status(read = 1.5 * mib)).detail)
        assertEquals("No count before the first byte", "", describeStart(status(read = 0.0)).detail)
    }

    @Test
    fun `it names every phase and reports failure`() {
        assertEquals("Saving your last game", describeStart(status(StartPhase.STOPPING)).label)
        assertEquals(0.12, describeStart(status(StartPhase.CORE)).fraction, 1e-9)
        assertEquals(1.0, describeStart(status(StartPhase.READY)).fraction, 0.0)
        val failed = describeStart(status(StartPhase.FAILED, error = "no core installed"))
        assertEquals(listOf("Could not start", "no core installed"), listOf(failed.label, failed.detail))
    }

    @Test
    fun `a phase this app does not know leaves the bar alone`() {
        assertEquals(0.0, describeStart(status(StartPhase.UNKNOWN__)).fraction, 0.0)
    }

    @Test
    fun `the bar never moves backwards`() {
        val bar = ForwardOnly()
        assertEquals(0.5, bar.next(0.5), 0.0)
        assertEquals(0.5, bar.next(0.3), 0.0)
        assertEquals(1.0, bar.next(2.0), 0.0)
    }

    @Test
    fun `ids are ones the server accepts`() {
        assertTrue(Regex("^[A-Za-z0-9_-]{8,64}$").matches(newStartId()))
    }

    @Test
    fun `waiting for the first picture counts the wait and says how to leave`() {
        assertEquals(LaunchView("Starting the game", "", 1.0), describeFirstPicture(500))
        assertEquals("6 s", describeFirstPicture(6200).detail)
        assertEquals("21 s · still no picture: Back opens the menu", describeFirstPicture(21_000).detail)
    }

    private fun screen(
        error: String? = null,
        progress: StartStatus? = null,
        bar: Double = 0.0,
        ready: Boolean = false,
        pictureShown: Boolean = false,
        waitedMs: Long = 0,
    ) = GameStart.screen("THPS3", error, progress, bar, ready, pictureShown, waitedMs)

    @Test
    fun `before the server says, the game's name`() {
        assertEquals(StartScreen("Starting THPS3…", "", 0.0), screen())
    }

    @Test
    fun `while the session starts, the server's phase over the forward-only bar`() {
        assertEquals(
            StartScreen("Loading the game", "32 of 64 MB · 5 s", 0.6),
            screen(progress = status(read = 32 * mib, total = 64 * mib, elapsedMs = 5000.0), bar = 0.6),
        )
    }

    @Test
    fun `a failed start shows the server's error without a bar`() {
        assertEquals(
            StartScreen("Could not start", "no core installed", null),
            screen(progress = status(StartPhase.FAILED, error = "no core installed"), bar = 0.4),
        )
    }

    @Test
    fun `a ready session waits for the first picture with a full bar`() {
        assertEquals(StartScreen("Starting the game", "", 1.0), screen(progress = status(StartPhase.READY), ready = true))
        assertEquals("21 s · still no picture: Back opens the menu", screen(ready = true, waitedMs = 21_000)!!.detail)
    }

    @Test
    fun `the first picture ends the loading screen`() {
        assertNull(screen(ready = true, pictureShown = true, waitedMs = 30_000))
    }

    @Test
    fun `an error wins, without a bar`() {
        assertEquals(StartScreen("Connection failed"), screen(error = "Connection failed", ready = true, pictureShown = true))
        assertEquals(StartScreen("Could not start: x"), screen(error = "Could not start: x"))
    }
}
