package media.sovereign.sovemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameStartTest {
    @Test
    fun `the session starting comes first`() {
        assertEquals(StartScreen("Starting THPS3…", busy = true), GameStart.screen("THPS3", null, ready = false, pictureShown = false, slow = false))
    }

    @Test
    fun `a ready session waits for the first picture`() {
        assertEquals(
            StartScreen("Starting the game…", busy = true),
            GameStart.screen("THPS3", null, ready = true, pictureShown = false, slow = false),
        )
    }

    @Test
    fun `a long wait adds the way out`() {
        assertEquals(
            StartScreen("Starting the game…", busy = true, hint = "Still no picture. Back opens the menu."),
            GameStart.screen("THPS3", null, ready = true, pictureShown = false, slow = true),
        )
    }

    @Test
    fun `the first picture ends the loading screen`() {
        assertNull(GameStart.screen("THPS3", null, ready = true, pictureShown = true, slow = true))
    }

    @Test
    fun `an error wins, without a spinner`() {
        assertEquals(StartScreen("Connection failed", busy = false), GameStart.screen("THPS3", "Connection failed", ready = true, pictureShown = true, slow = false))
        assertEquals(StartScreen("Could not start: x", busy = false), GameStart.screen("THPS3", "Could not start: x", ready = false, pictureShown = false, slow = false))
    }
}
