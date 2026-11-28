package media.sovereign.sovemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameModeTest {
    @Test
    fun `game mode is only on when the display can be switched`() {
        // A TV's own app: asked, and the panel switches.
        assertEquals(GameModeState.ON, GameModeState.of(supported = true))
        assertTrue(GameModeState.ON.applied)
        // A Fire TV Stick: asked, and its TV does not report back (it may still have switched).
        val stick = GameModeState.of(supported = false)
        assertEquals(GameModeState.UNSUPPORTED, stick)
        assertFalse("not known to be in effect", stick.applied)
        assertEquals("Asked - Display does not report status", stick.label)
        // Before Android 11 there is no way to ask.
        assertEquals(GameModeState.UNAVAILABLE, GameModeState.of(supported = null))
    }
}
