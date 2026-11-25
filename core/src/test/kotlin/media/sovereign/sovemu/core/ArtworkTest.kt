package media.sovereign.sovemu.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkTest {
    @Test
    fun artOfNearlyTheCardsShapeFillsIt() {
        assertTrue(artFills(1.4f, 1.4f))
        assertTrue("a US SNES box in a SNES card", artFills(1.33f, 1.4f))
        assertTrue(artFills(0.7f, 0.72f))
    }

    @Test
    fun artOfAnotherShapeIsShownWhole() {
        assertFalse("a Super Famicom box in a SNES card", artFills(0.72f, 1.4f))
        assertFalse(artFills(1.4f, 0.72f))
        assertFalse("a screenshot in a jewel-case card", artFills(4f / 3f, 1f))
    }

    @Test
    fun unknownShapesFill() {
        assertTrue(artFills(0f, 1.4f))
        assertTrue(artFills(Float.NaN, 1.4f))
    }
}
