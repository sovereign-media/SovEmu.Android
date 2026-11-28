package media.sovereign.sovemu

import android.opengl.GLES11Ext
import android.opengl.GLES20
import java.nio.FloatBuffer
import kotlin.math.abs
import org.webrtc.GlShader
import org.webrtc.GlUtil
import org.webrtc.RendererCommon

/**
 * Draws the game's picture with sharp edges: each console pixel a solid block, with at most one
 * screen pixel blended where two meet.
 *
 * WebRTC's own drawer ([org.webrtc.GlRectDrawer]) samples its textures bilinearly, which
 * spreads every edge over a whole console pixel: 7–14 screen pixels on a 1080p TV (measured on a
 * Fire TV Stick for SovEmu#263). Nearest-neighbour would keep edges sharp but, at a scale that
 * is not a whole number (5.6× for a 240-line console on a 1080p screen), makes some pixels a
 * screen pixel wider than others, which shows as shimmer when the picture scrolls. This is the
 * "sharp bilinear" filter emulators use: inside each console pixel it samples the pixel's
 * centre, and in the last screen pixel before an edge it blends, using the textures' own
 * bilinear filtering.
 *
 * Each plane is sharpened on its own grid: a software decoder's chroma planes are half the
 * luma's size, and sampling them where luma's pixels are would blend each colour into its
 * neighbour's.
 */
class SharpDrawer : RendererCommon.GlDrawer {
    private enum class Kind { Oes, Rgb, Yuv }

    private var kind: Kind? = null
    private var shader: GlShader? = null
    private var uniforms = IntArray(0)

    /**
     * The grids last worked out, and what they were worked out from (the texture matrix's scale
     * and the sizes): the same for every frame until the stream's size or the view's changes,
     * so not made again for each.
     */
    private var grid: SharpGrid? = null
    private var chroma: SharpGrid? = null
    private val gridFrom = FloatArray(8)

    override fun drawOes(
        oesTextureId: Int,
        texMatrix: FloatArray,
        frameWidth: Int,
        frameHeight: Int,
        viewportX: Int,
        viewportY: Int,
        viewportWidth: Int,
        viewportHeight: Int,
    ) {
        prepare(Kind.Oes, texMatrix, frameWidth, frameHeight, viewportWidth, viewportHeight)
        draw(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            intArrayOf(oesTextureId),
            viewportX,
            viewportY,
            viewportWidth,
            viewportHeight,
        )
    }

    override fun drawRgb(
        textureId: Int,
        texMatrix: FloatArray,
        frameWidth: Int,
        frameHeight: Int,
        viewportX: Int,
        viewportY: Int,
        viewportWidth: Int,
        viewportHeight: Int,
    ) {
        prepare(Kind.Rgb, texMatrix, frameWidth, frameHeight, viewportWidth, viewportHeight)
        draw(
            GLES20.GL_TEXTURE_2D,
            intArrayOf(textureId),
            viewportX,
            viewportY,
            viewportWidth,
            viewportHeight,
        )
    }

    override fun drawYuv(
        yuvTextures: IntArray,
        texMatrix: FloatArray,
        frameWidth: Int,
        frameHeight: Int,
        viewportX: Int,
        viewportY: Int,
        viewportWidth: Int,
        viewportHeight: Int,
    ) {
        prepare(Kind.Yuv, texMatrix, frameWidth, frameHeight, viewportWidth, viewportHeight)
        draw(GLES20.GL_TEXTURE_2D, yuvTextures, viewportX, viewportY, viewportWidth, viewportHeight)
    }

    override fun release() {
        shader?.release()
        shader = null
        kind = null
    }

    private fun prepare(
        kind: Kind,
        texMatrix: FloatArray,
        frameWidth: Int,
        frameHeight: Int,
        viewportWidth: Int,
        viewportHeight: Int,
    ) {
        val shader = if (kind == this.kind) shader!! else newShader(kind)
        shader.useProgram()
        shader.setVertexAttribArray("in_pos", 2, FULL_RECTANGLE)
        shader.setVertexAttribArray("in_tc", 2, FULL_RECTANGLE_TEXTURE)
        GLES20.glUniformMatrix4fv(uniforms[0], 1, false, texMatrix, 0)
        val grid = grid(texMatrix, frameWidth, frameHeight, viewportWidth, viewportHeight)
        GLES20.glUniform4f(uniforms[1], grid.width, grid.height, grid.scaleX, grid.scaleY)
        if (kind == Kind.Yuv) {
            // Chroma is half the size: ⌈w/2⌉ × ⌈h/2⌉ of the frame's luma.
            val chroma = chroma ?: grid.halved(frameWidth, frameHeight).also { chroma = it }
            GLES20.glUniform4f(
                uniforms[2],
                chroma.width,
                chroma.height,
                chroma.scaleX,
                chroma.scaleY,
            )
        }
    }

    private fun grid(
        texMatrix: FloatArray,
        frameWidth: Int,
        frameHeight: Int,
        viewportWidth: Int,
        viewportHeight: Int,
    ): SharpGrid {
        val from = gridFrom
        val known = grid
        if (known != null && from[0] == texMatrix[0] && from[1] == texMatrix[1] &&
            from[2] == texMatrix[4] && from[3] == texMatrix[5] &&
            from[4] == frameWidth.toFloat() && from[5] == frameHeight.toFloat() &&
            from[6] == viewportWidth.toFloat() && from[7] == viewportHeight.toFloat()
        ) {
            return known
        }
        from[0] = texMatrix[0]
        from[1] = texMatrix[1]
        from[2] = texMatrix[4]
        from[3] = texMatrix[5]
        from[4] = frameWidth.toFloat()
        from[5] = frameHeight.toFloat()
        from[6] = viewportWidth.toFloat()
        from[7] = viewportHeight.toFloat()
        chroma = null
        return SharpGrid.of(texMatrix, frameWidth, frameHeight, viewportWidth, viewportHeight)
            .also { grid = it }
    }

    private fun newShader(kind: Kind): GlShader {
        shader?.release()
        val shader = GlShader(VERTEX_SHADER, fragmentShader(kind))
        shader.useProgram()
        uniforms = intArrayOf(
            shader.getUniformLocation("tex_mat"),
            shader.getUniformLocation("grid"),
            if (kind == Kind.Yuv) shader.getUniformLocation("chroma_grid") else -1,
        )
        when (kind) {
            Kind.Yuv -> {
                GLES20.glUniform1i(shader.getUniformLocation("y_tex"), 0)
                GLES20.glUniform1i(shader.getUniformLocation("u_tex"), 1)
                GLES20.glUniform1i(shader.getUniformLocation("v_tex"), 2)
            }

            else -> GLES20.glUniform1i(shader.getUniformLocation("tex"), 0)
        }
        GlUtil.checkNoGLES2Error("SharpDrawer: a new shader")
        this.shader = shader
        this.kind = kind
        return shader
    }

    private fun draw(target: Int, textures: IntArray, x: Int, y: Int, width: Int, height: Int) {
        for ((unit, texture) in textures.withIndex()) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
            GLES20.glBindTexture(target, texture)
        }
        GLES20.glViewport(x, y, width, height)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        for (unit in textures.indices.reversed()) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
            GLES20.glBindTexture(target, 0)
        }
    }

    private companion object {
        val FULL_RECTANGLE: FloatBuffer =
            GlUtil.createFloatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        val FULL_RECTANGLE_TEXTURE: FloatBuffer =
            GlUtil.createFloatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

        const val VERTEX_SHADER = """
attribute vec4 in_pos;
attribute vec4 in_tc;
uniform mat4 tex_mat;
varying vec2 tc;
void main() {
  gl_Position = in_pos;
  tc = (tex_mat * in_tc).xy;
}
"""

        /**
         * `grid` is a plane's size in texels (xy) and the screen pixels each texel covers (zw).
         * Texel coordinates reach the hundreds, beyond what `mediump` can tell apart, so the
         * shader asks for `highp`, which every GPU these apps run on has in fragment shaders.
         */
        const val SHARP = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 tc;
uniform vec4 grid;
vec2 sharp(vec2 p, vec4 g) {
  vec2 texel = p * g.xy;
  vec2 base = floor(texel);
  vec2 d = texel - base - 0.5;
  vec2 scale = max(g.zw, 1.0);
  vec2 inside = 0.5 - 0.5 / scale;
  return (base + (d - clamp(d, -inside, inside)) * scale + 0.5) / g.xy;
}
"""

        fun fragmentShader(kind: Kind): String = when (kind) {
            Kind.Oes ->
                "#extension GL_OES_EGL_image_external : require\n" + SHARP + """
uniform samplerExternalOES tex;
void main() { gl_FragColor = texture2D(tex, sharp(tc, grid)); }
"""

            Kind.Rgb ->
                SHARP + """
uniform sampler2D tex;
void main() { gl_FragColor = texture2D(tex, sharp(tc, grid)); }
"""

            // BT.601 limited range, as WebRTC's own drawer converts.
            Kind.Yuv ->
                SHARP + """
uniform vec4 chroma_grid;
uniform sampler2D y_tex;
uniform sampler2D u_tex;
uniform sampler2D v_tex;
void main() {
  float y = texture2D(y_tex, sharp(tc, grid)).r * 1.16438;
  vec2 c = sharp(tc, chroma_grid);
  float u = texture2D(u_tex, c).r;
  float v = texture2D(v_tex, c).r;
  gl_FragColor = vec4(y + 1.59603 * v - 0.874202,
    y - 0.391762 * u - 0.812968 * v + 0.531668,
    y + 2.01723 * u - 1.08563, 1.0);
}
"""
        }
    }
}

/**
 * A texture's grid as [SharpDrawer] samples it: its size in texels along the texture's own x
 * and y, and the screen pixels one texel covers along each.
 *
 * The frame is not always the whole texture: a hardware decoder's output is padded (to 16 rows,
 * say) and the texture matrix crops it, so the texture's size is the frame's divided by the
 * share of the texture the matrix spans. A matrix that rotates the frame a quarter turn swaps
 * which of the frame's sides runs along the texture's x.
 */
data class SharpGrid(val width: Float, val height: Float, val scaleX: Float, val scaleY: Float) {
    /** The grid of a plane a quarter the size (4:2:0 chroma) of a `frameWidth` × `frameHeight` frame. */
    fun halved(frameWidth: Int, frameHeight: Int): SharpGrid {
        val fx = ((frameWidth + 1) / 2).toFloat() / frameWidth
        val fy = ((frameHeight + 1) / 2).toFloat() / frameHeight
        return SharpGrid(width * fx, height * fy, scaleX / fx, scaleY / fy)
    }

    companion object {
        /** [texMatrix] is column-major, as WebRTC hands it to a drawer. */
        fun of(
            texMatrix: FloatArray,
            frameWidth: Int,
            frameHeight: Int,
            viewportWidth: Int,
            viewportHeight: Int,
        ): SharpGrid {
            // How far along the texture's x and y the frame's width (column 0) and height
            // (column 1) run.
            val wx = abs(texMatrix[0])
            val wy = abs(texMatrix[1])
            val hx = abs(texMatrix[4])
            val hy = abs(texMatrix[5])
            val turned = wy > wx
            val alongX = if (turned) frameHeight else frameWidth
            val alongY = if (turned) frameWidth else frameHeight
            val screenX = if (turned) viewportHeight else viewportWidth
            val screenY = if (turned) viewportWidth else viewportHeight
            val spanX = (if (turned) hx else wx).coerceAtLeast(1e-6f)
            val spanY = (if (turned) wy else hy).coerceAtLeast(1e-6f)
            return SharpGrid(
                width = alongX / spanX,
                height = alongY / spanY,
                scaleX = screenX.toFloat() / alongX.coerceAtLeast(1),
                scaleY = screenY.toFloat() / alongY.coerceAtLeast(1),
            )
        }
    }
}
