package media.sovereign.sovemu.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** A controller pulses as many times as the player it is on. */
class IdentifyTest {
    @Test
    fun `player four pulses four times`() {
        assertEquals(4, Identify.pulses(3))
        assertEquals(
            longArrayOf(0, 180, 140, 180, 140, 180, 140, 180).toList(),
            Identify.rumble(4).toList(),
        )
        assertEquals(4, Identify.rumble(4).count { it == Identify.RUMBLE_MS })
    }

    @Test
    fun `player one pulses once, and a pad on no player once`() {
        assertEquals(1, Identify.pulses(0))
        assertEquals(1, Identify.pulses(null))
        assertArrayEquals(longArrayOf(0, 180), Identify.rumble(1))
    }

    @Test
    fun `a controller that cannot rumble here says it is this device's kernel and this pad`() {
        assertEquals(
            "Xbox Wireless Controller can't rumble on this Fire TV: its kernel has no rumble " +
                "support for this controller, so no app can make it vibrate here, and it has no " +
                "lights apps can use. It may rumble on another device.",
            Identify.unsupported("Xbox Wireless Controller", "this Fire TV"),
        )
    }

    @Test
    fun `every player from one to four`() {
        assertEquals(listOf(1, 2, 3, 4), (0..3).map(Identify::pulses))
        assertEquals(
            listOf(1, 2, 3, 4),
            (0..3).map {
                Identify.rumble(Identify.pulses(it)).count { ms -> ms == Identify.RUMBLE_MS }
            },
        )
    }
}
