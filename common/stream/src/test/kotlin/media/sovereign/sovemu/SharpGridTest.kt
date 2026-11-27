package media.sovereign.sovemu

import org.junit.Assert.assertEquals
import org.junit.Test

class SharpGridTest {
    /** A column-major texture matrix scaling x by [sx] and y by [sy] (negative flips). */
    private fun matrix(sx: Float, sy: Float): FloatArray {
        val shift = if (sy < 0) -sy else 0f
        return floatArrayOf(sx, 0f, 0f, 0f, 0f, sy, 0f, 0f, 0f, 0f, 1f, 0f, 0f, shift, 0f, 1f)
    }

    private fun assertGrid(expected: SharpGrid, actual: SharpGrid) {
        assertEquals(expected.width, actual.width, 0.01f)
        assertEquals(expected.height, actual.height, 0.01f)
        assertEquals(expected.scaleX, actual.scaleX, 0.001f)
        assertEquals(expected.scaleY, actual.scaleY, 0.001f)
    }

    @Test
    fun aWholeTextureIsTheFrameScaledToTheView() {
        // A SNES frame, software-decoded and flipped, on a 1080p TV's 4:3 view.
        val grid = SharpGrid.of(matrix(1f, -1f), 256, 224, 1440, 1080)
        assertGrid(SharpGrid(256f, 224f, 1440f / 256, 1080f / 224), grid)
    }

    @Test
    fun aPaddedDecoderOutputIsTheWholeTextureNotTheFrame() {
        // A hardware decoder pads 224 rows to 240; the matrix spans 224/240 of the texture.
        val grid = SharpGrid.of(matrix(1f, -224f / 240), 256, 224, 1440, 1080)
        assertGrid(SharpGrid(256f, 240f, 1440f / 256, 1080f / 224), grid)
    }

    @Test
    fun aQuarterTurnSwapsTheSides() {
        // The frame's width runs along the texture's y.
        val turned = floatArrayOf(0f, 1f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f, 0f, 1f)
        val grid = SharpGrid.of(turned, 256, 224, 1440, 1080)
        assertGrid(SharpGrid(224f, 256f, 1080f / 224, 1440f / 256), grid)
    }

    @Test
    fun chromaIsHalfTheGridAndTwiceTheScale() {
        val luma = SharpGrid(256f, 224f, 5.625f, 4.82f)
        assertGrid(SharpGrid(128f, 112f, 11.25f, 9.64f), luma.halved(256, 224))
        // Odd: chroma rounds up, covering ⌈255/2⌉ = 128 of the luma's 255.
        val odd = SharpGrid(255f, 223f, 4f, 4f).halved(255, 223)
        assertGrid(SharpGrid(128f, 112f, 4f * 255 / 128, 4f * 223 / 112), odd)
    }
}
