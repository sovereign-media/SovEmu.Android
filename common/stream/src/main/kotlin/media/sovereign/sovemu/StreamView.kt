package media.sovereign.sovemu

import android.content.Context
import android.os.Build
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceEglRenderer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/**
 * The game's video: every pixel of the console's frame, scaled to fill this view, whatever
 * shape the view is (the player sizes it: 4:3 for a TV console, the frame's own shape for a
 * handheld). Nothing is ever cropped.
 *
 * WebRTC's SurfaceViewRenderer crops each frame to the view's shape (its "layout aspect ratio"
 * is the view's), so a SNES frame (256×224, nearly square) drawn into a 4:3 view lost its top
 * and bottom. Here the layout aspect ratio is always the frame's own, so the renderer crops
 * nothing and stretches the whole frame onto the surface, drawn by [SharpDrawer] so each console
 * pixel stays a sharp block rather than a blur.
 */
class StreamView(context: Context, egl: EglBase.Context) :
    SurfaceView(context),
    VideoSink {
    private val renderer = SurfaceEglRenderer("stream")

    init {
        renderer.init(
            egl,
            object : RendererCommon.RendererEvents {
                override fun onFirstFrameRendered() {}
                override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                    val (w, h) = if (rotation % 180 == 0) width to height else height to width
                    if (w > 0 && h > 0) renderer.setLayoutAspectRatio(w.toFloat() / h)
                }
            },
            EglBase.CONFIG_PLAIN,
            SharpDrawer(),
        )
        holder.addCallback(renderer)
        holder.addCallback(object : SurfaceHolder.Callback {
            // A new surface has been told nothing yet.
            override fun surfaceCreated(holder: SurfaceHolder) = applyFrameRate()
            override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })
    }

    /** The frame rate asked for ([setFrameRate]); 0 for none. */
    private var frameRate = 0f

    /**
     * Tells the display the stream's frame rate (the console's own, from the worker's pipeline:
     * 60.1 for a NES, 50 for a PAL game), so a display that can match it without a visible
     * switch does, and frames are not shown on an uneven cadence. Seamless switches only: a
     * mode change that blanks the screen mid-game is worse than the judder. Android 12+;
     * Android 11's call has no seamless-only choice, so it is not made there.
     */
    fun setFrameRate(fps: Double) {
        val rate = fps.toFloat()
        if (rate <= 0f || rate == frameRate) return
        frameRate = rate
        applyFrameRate()
    }

    private fun applyFrameRate() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || frameRate <= 0f) return
        val surface = holder.surface
        if (!surface.isValid) return
        surface.setFrameRate(
            frameRate,
            Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
            Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
        )
    }

    /** Frames received so far: a count that stops moving during play is a stalled stream. */
    @Volatile var frames = 0L
        private set

    override fun onFrame(frame: VideoFrame) {
        frames++
        renderer.onFrame(frame)
    }

    fun release() = renderer.release()
}
