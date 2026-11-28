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
    fun `a controller that cannot rumble here names the device and the controller`() {
        val details = Identify.deviceDetails("DEV-FIRESTICK", "AFTMA08C15", fireTv = true)
        assertEquals("Fire TV, AFTMA08C15", details)
        assertEquals(
            "Xbox Wireless Controller can't rumble on DEV-FIRESTICK (Fire TV, AFTMA08C15). " +
                "DEV-FIRESTICK's kernel has no rumble support for Xbox Wireless Controller, so " +
                "no app can make the controller vibrate on this device, and the controller has " +
                "no lights apps can use. It may rumble on other devices.",
            Identify.unsupported("Xbox Wireless Controller", "DEV-FIRESTICK", details),
        )
    }

    @Test
    fun `the brackets leave out what the name already says`() {
        // No name set: the model is the name, and is not said twice.
        assertEquals(null, Identify.deviceDetails("MiTV-AFMU1", "MiTV-AFMU1", fireTv = false))
        assertEquals("Fire TV", Identify.deviceDetails("AFTMA08C15", "AFTMA08C15", fireTv = true))
        assertEquals(
            "8BitDo SN30 Pro can't rumble on MiTV-AFMU1. MiTV-AFMU1's kernel has no rumble " +
                "support for 8BitDo SN30 Pro, so no app can make the controller vibrate on this " +
                "device, and the controller has no lights apps can use. It may rumble on other " +
                "devices.",
            Identify.unsupported("8BitDo SN30 Pro", "MiTV-AFMU1"),
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
