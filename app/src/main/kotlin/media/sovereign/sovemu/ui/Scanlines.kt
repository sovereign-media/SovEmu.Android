package media.sovereign.sovemu.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap

/** One dark row in every [PERIOD] physical pixels, as on a CRT. */
private const val PERIOD = 3
private const val DARKNESS = 0x73

/** The tile: [PERIOD] pixels tall, the top one darkened. Built once. */
private val tile: ImageBitmap by lazy {
    val bitmap = android.graphics.Bitmap.createBitmap(
        1,
        PERIOD,
        android.graphics.Bitmap.Config.ARGB_8888,
    )
    bitmap.setPixel(0, 0, DARKNESS shl 24)
    bitmap.asImageBitmap()
}

/**
 * CRT scan lines over everything drawn inside. One repeating bitmap shader over the whole area:
 * a single textured fill per frame (no per-line draws, no extra layer), in physical pixels so
 * the lines stay crisp at any density.
 */
fun Modifier.scanlines(enabled: Boolean): Modifier = if (!enabled) {
    this
} else {
    drawWithCache {
        val brush = ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
        onDrawWithContent {
            drawContent()
            drawRect(brush)
        }
    }
}
