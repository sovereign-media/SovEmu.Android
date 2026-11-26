package media.sovereign.sovemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecorationTest {
    @Test
    fun framesOnlyWhenTheScreenIsWiderThanThePicture() {
        assertTrue(shouldFrame(16f / 9f, 4f / 3f))
        assertFalse("a phone held upright", shouldFrame(9f / 16f, 4f / 3f))
        assertFalse("no bars to fill", shouldFrame(4f / 3f, 4f / 3f))
    }

    @Test
    fun theImagesShape() {
        assertEquals(16f / 9f, GameDecoration("u", 1920, 1080, 0, 240, 0, 240).aspect, 0.001f)
        assertEquals(16f / 9f, GameDecoration("u", 0, 0, 0, 0, 0, 0).aspect, 0.001f)
    }
}
