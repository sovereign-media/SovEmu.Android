package media.sovereign.sovemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PatchTest {
    private val patches = listOf(GamePatch(1, "IPS patch", true), GamePatch(2, "Hard Mode", false))

    @Test
    fun stepsThroughTheOriginalThenEachPatchAndRoundAgain() {
        assertEquals("IPS patch", nextPatch(patches, null)?.name)
        assertEquals("Hard Mode", nextPatch(patches, "IPS patch")?.name)
        assertNull("back to the original", nextPatch(patches, "Hard Mode"))
    }

    @Test
    fun aPatchNoLongerThereStartsFromTheOriginal() {
        assertEquals("IPS patch", nextPatch(patches, "Gone")?.name)
    }
}
