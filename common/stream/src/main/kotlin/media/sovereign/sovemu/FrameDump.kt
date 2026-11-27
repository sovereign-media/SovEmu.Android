package media.sovereign.sovemu

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.webrtc.VideoFrame

// Debug builds only: the frames a stream decodes, written as they arrive, so the encoder bench
// (SovEmu's `encode_quality score-dump`, sovereign-media/SovEmu#263) can score exactly what
// this device's decoder produced. Asked for with a file in the app's own files (a debug build
// lets `run-as` reach them), so nothing in the app changes:
//
//   adb shell "run-as <package> sh -c 'mkdir -p files/framedump && echo 1800 > files/framedump/request'"
//
// The next stream writes its first 1800 decoded frames into `files/framedump/<time>/`
// (`frame_NNNNNN.yuv`, packed I420, and `frames.csv`: index, timestamp_ns, received_ns, width,
// height) and
// removes the request. Fetch them with
// `adb exec-out run-as <package> tar cf - files/framedump/<time> | tar xf -`. Writing files
// costs time on a TV: measure decoding with the dump off.

/**
 * Writes the first [limit] frames passed to [onFrame] into [dir]. The frame is copied on the
 * caller's thread and written on a thread of its own, so the renderer is not held up by storage.
 */
internal class FrameDump(private val dir: File, private val limit: Int) {
    private val writer: ExecutorService = Executors.newSingleThreadExecutor()
    private val count = AtomicInteger(0)
    private val index = File(dir, "frames.csv")

    init {
        index.writeText("index,timestamp_ns,received_ns,width,height\n")
    }

    fun onFrame(frame: VideoFrame) {
        val n = count.getAndIncrement()
        if (n >= limit) return
        val i420 = frame.buffer.toI420() ?: return
        val (width, height) = i420.width to i420.height
        val bytes = try {
            packI420(
                width,
                height,
                i420.dataY,
                i420.strideY,
                i420.dataU,
                i420.strideU,
                i420.dataV,
                i420.strideV,
            )
        } finally {
            i420.release()
        }
        // Android's WebRTC leaves a received frame's own time at 0: when it arrived places it.
        val timestampNs = frame.timestampNs
        val receivedNs = System.nanoTime()
        writer.execute {
            File(dir, "frame_%06d.yuv".format(n)).writeBytes(bytes)
            index.appendText("$n,$timestampNs,$receivedNs,$width,$height\n")
            if (n == limit - 1) Log.i(TAG, "frame dump: $limit frames written to $dir")
        }
    }

    /** Stops taking frames; what was already copied is still written. */
    fun close() {
        count.set(limit)
        writer.shutdown()
    }

    companion object {
        private const val TAG = "SovEmu.FrameDump"
        private const val DEFAULT_FRAMES = 1800

        /** A dump for this stream, if this is a debug build and one was asked for. */
        fun ifRequested(context: Context): FrameDump? {
            if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
            val root = File(context.filesDir, "framedump")
            val request = File(root, "request")
            if (!request.exists()) return null
            val limit = request.readText().trim().toIntOrNull() ?: DEFAULT_FRAMES
            request.delete()
            val dir = File(root, System.currentTimeMillis().toString()).apply { mkdirs() }
            Log.i(TAG, "frame dump: writing the next $limit decoded frames to $dir")
            return FrameDump(dir, limit)
        }
    }
}

/**
 * Packs I420 planes (each row [width] or half of it, rows [stride] bytes apart) into one array:
 * all of Y, then U, then V, with no padding. Chroma is rounded up for odd sizes.
 */
internal fun packI420(
    width: Int,
    height: Int,
    y: ByteBuffer,
    strideY: Int,
    u: ByteBuffer,
    strideU: Int,
    v: ByteBuffer,
    strideV: Int,
): ByteArray {
    val (chromaWidth, chromaHeight) = (width + 1) / 2 to (height + 1) / 2
    val out = ByteArray(width * height + 2 * chromaWidth * chromaHeight)
    var at = 0
    for ((plane, stride, w, h) in listOf(
        Plane(y, strideY, width, height),
        Plane(u, strideU, chromaWidth, chromaHeight),
        Plane(v, strideV, chromaWidth, chromaHeight),
    )) {
        val source = plane.duplicate()
        for (row in 0 until h) {
            source.position(row * stride)
            source.get(out, at, w)
            at += w
        }
    }
    return out
}

private data class Plane(val data: ByteBuffer, val stride: Int, val width: Int, val height: Int)
