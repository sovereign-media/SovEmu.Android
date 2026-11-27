package media.sovereign.sovemu

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class FrameDumpTest {
    /** A plane of [rows] rows, each [width] bytes of [first], [first]+1 … then padding to [stride]. */
    private fun plane(width: Int, rows: Int, stride: Int, first: Int): ByteBuffer {
        val bytes = ByteArray(stride * rows) { 0x7f }
        var value = first
        for (row in 0 until rows) {
            for (x in 0 until width) bytes[row * stride + x] = (value++).toByte()
        }
        return ByteBuffer.wrap(bytes)
    }

    @Test
    fun packsEachPlaneWithoutItsRowPadding() {
        // 3x3: chroma is rounded up to 2x2; every plane has padding after each row.
        val y = plane(3, 3, 8, 0)
        val u = plane(2, 2, 4, 100)
        val v = plane(2, 2, 4, 200)
        val packed = packI420(3, 3, y, 8, u, 4, v, 4)
        val expected = (0 until 9).map { it.toByte() } +
            (100 until 104).map { it.toByte() } +
            (200 until 204).map { it.toByte() }
        assertArrayEquals(expected.toByteArray(), packed)
    }

    @Test
    fun leavesTheSourceBuffersWhereTheyWere() {
        val y = plane(2, 2, 2, 0)
        val u = plane(1, 1, 1, 10)
        val v = plane(1, 1, 1, 20)
        packI420(2, 2, y, 2, u, 1, v, 1)
        // WebRTC's buffers are read again by the renderer after the dump has copied them.
        assertArrayEquals(intArrayOf(0, 0, 0), intArrayOf(y.position(), u.position(), v.position()))
    }
}
