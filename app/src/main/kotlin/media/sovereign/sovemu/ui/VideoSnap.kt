package media.sovereign.sovemu.ui

import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
 * after [START_DELAY_MS] and fades in over the artwork once its first frame is drawn, so the
 * artwork shows until then (and stays, if there is no video or it fails). Drawn on a TextureView,
 * so it moves and blends with the panel around it.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoSnap(path: String?, modifier: Modifier = Modifier) {
    val api = LocalApi.current
    val context = LocalContext.current
    val url = api.media(path) ?: return
    var drawing by remember(url) { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (drawing) 1f else 0f, tween(400), label = "snap")
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
    AndroidView(
        factory = { TextureView(it).also(player::setVideoTextureView) },
        modifier = modifier.graphicsLayer { this.alpha = alpha },
    )
}
