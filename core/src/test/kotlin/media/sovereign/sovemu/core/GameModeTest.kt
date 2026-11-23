package media.sovereign.sovemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameModeTest {
    @Test
    fun `game mode is only on when the display can be switched`() {
        // A TV's own app: asked, and the panel switches.
        assertEquals(GameModeState.ON, GameModeState.of(setting = true, supported = true))
        assertTrue(GameModeState.ON.applied)
        // A Fire TV Stick: asked, and its TV does not report back (it may still have switched).
        val stick = GameModeState.of(setting = true, supported = false)
        assertEquals(GameModeState.UNSUPPORTED, stick)
        assertFalse("not known to be in effect", stick.applied)
        assertEquals("Asked - Display does not report status", stick.label)
        // The setting off asks nothing, whatever the display could do.
        assertEquals(GameModeState.OFF, GameModeState.of(setting = false, supported = true))
        // Before Android 11 there is no way to ask.
        assertEquals(GameModeState.UNAVAILABLE, GameModeState.of(setting = true, supported = null))
    }
}
