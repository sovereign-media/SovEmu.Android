package media.sovereign.sovemu.ui

import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.delay

/** How long a game must stay shown before its video starts (moving on quickly loads none). */
private const val START_DELAY_MS = 900L

/**
 * A game's video snap, muted and looping, as the web client's game panel plays it: it starts
 * after [START_DELAY_MS], and [cover] (the artwork) is drawn over it until its first frame is
 * shown, then fades out; it stays if there is no video or it fails.
 *
 * Drawn on a SurfaceView, below the window: the system composites the decoder's frames itself,
 * and whatever is drawn after it ([cover], a gradient, a box art) shows over it. On a TextureView
 * (frames copied through the app's GL, faded with the layer's alpha) Fire TV drew a green
 * overlay over the panel; the screensaver, on a SurfaceView, never showed one.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoSnap(path: String?, modifier: Modifier = Modifier, cover: @Composable BoxScope.() -> Unit) {
    val api = LocalApi.current
    val context = LocalContext.current
    val url = api.media(path)
    if (url == null) {
        Box(modifier, content = cover)
        return
    }
    var drawing by remember(url) { mutableStateOf(false) }
    val shown = animateFloatAsState(if (drawing) 1f else 0f, tween(400), label = "snap")
    val player = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(api.client)))
            .build()
            .apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_ONE
                playWhenReady = true
            }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                drawing = true
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(url) {
        player.stop()
        player.clearMediaItems()
        delay(START_DELAY_MS)
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
    }
    Box(modifier) {
        // Clipped: below the window, a SurfaceView punches its hole with a canvas-wide clear
        // (Android 9, Fire OS 7), which unclipped erased the whole screen drawn before it.
        AndroidView(
            factory = { SurfaceView(it).also(player::setVideoSurfaceView) },
            modifier = Modifier.matchParentSize().clipToBounds(),
        )
        // Read in the layer, so the fade redraws the cover without recomposing it.
        Box(Modifier.matchParentSize().graphicsLayer { alpha = 1f - shown.value }, content = cover)
    }
}
