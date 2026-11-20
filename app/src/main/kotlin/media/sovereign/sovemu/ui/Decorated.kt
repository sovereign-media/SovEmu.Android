package media.sovereign.sovemu.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import media.sovereign.sovemu.core.GameDecoration
import media.sovereign.sovemu.core.shouldFrame

/**
 * The game's picture, [aspect] wide for its height, centred on the screen, and framed by its
 * [decoration] when it has one and the screen is wider than the picture: the artwork fitted to
 * the screen, the picture (its shape kept) in the artwork's window, the artwork drawn over its
 * edges. The stream's SurfaceView renders below the window, so drawing over it is free.
 */
@Composable
fun Decorated(decoration: GameDecoration?, aspect: Float, modifier: Modifier = Modifier, picture: @Composable () -> Unit) {
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val screenAspect = if (maxHeight.value > 0) maxWidth.value / maxHeight.value else aspect
        if (decoration == null || !shouldFrame(screenAspect, aspect)) {
            Box(Modifier.aspectRatio(aspect)) { picture() }
            return@BoxWithConstraints
        }
        BoxWithConstraints(Modifier.aspectRatio(decoration.aspect)) {
            // The window, in this box's dp: the image is scaled to exactly this box.
            val scaleX = maxWidth / decoration.width.toFloat()
            val scaleY = maxHeight / decoration.height.toFloat()
            Box(
                Modifier.fillMaxSize().padding(
                    start = scaleX * decoration.left.toFloat(),
                    top = scaleY * decoration.top.toFloat(),
                    end = scaleX * decoration.right.toFloat(),
                    bottom = scaleY * decoration.bottom.toFloat(),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.aspectRatio(aspect)) { picture() }
            }
            Art(decoration.url, Modifier.fillMaxSize(), scale = ContentScale.FillBounds)
        }
    }
}
