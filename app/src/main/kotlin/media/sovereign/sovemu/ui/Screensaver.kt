package media.sovereign.sovemu.ui

import android.view.SurfaceView
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import media.sovereign.sovemu.SovEmuApi
import media.sovereign.sovemu.core.Game
import media.sovereign.sovemu.core.SystemSummary
import media.sovereign.sovemu.core.formatPlayers
import media.sovereign.sovemu.core.formatReleaseDate

/** The longest a game's video plays before the next one (snaps are usually shorter). */
private const val CLIP_MS = 30_000L

/** The videos' own sound, under a TV's usual volume: background, not foreground. */
private const val VIDEO_VOLUME = 0.35f

/**
 * The screensaver: random games' video snaps, one after another, with a panel along the bottom
 * naming the game, its system, release date and players. Shown after a while without input
 * (outside a game); any button or touch ends it (the activity handles that, so the press does
 * nothing else). Videos come from the server's signed `/media/…` URLs through the app's HTTP
 * client.
 */
@OptIn(UnstableApi::class)
@Composable
fun Screensaver(
    api: SovEmuApi,
    systems: Map<String, SystemSummary>,
    nextGame: suspend () -> Game?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(api.client)))
            .build()
            .apply {
                volume = VIDEO_VOLUME
                playWhenReady = true
            }
    }
    var game by remember { mutableStateOf<Game?>(null) }
    var aspect by remember { mutableFloatStateOf(4f / 3f) }
    // Black until the new video draws its first frame, so switching never shows the old one's last.
    var drawing by remember { mutableStateOf(false) }
    val veil by animateFloatAsState(if (drawing) 0f else 1f, tween(400), label = "veil")

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }

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

    // One game after another: until its video ends (or fails), at most CLIP_MS.
    LaunchedEffect(player) {
        while (true) {
            val next = nextGame()
            val url = next?.let { api.media(it.video) }
            if (next == null || url == null) {
                delay(2_000)
                continue
            }
            drawing = false
            delay(400) // the veil closes over the old video first
            game = next
            val done = CompletableDeferred<Unit>()
            val watch = object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) done.complete(Unit)
                }

                override fun onPlayerError(error: PlaybackException) {
                    done.complete(Unit)
                }
            }
            player.addListener(watch)
            try {
                player.setMediaItem(MediaItem.fromUri(url))
                player.prepare()
                withTimeoutOrNull(CLIP_MS) { done.await() }
            } finally {
                player.removeListener(watch)
            }
        }
    }

    // The video fills the space above the bar, at its own shape: the bar covers none of it.
    Column(modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            AndroidView(
                // In front of any other video surface (a paused game's stream), or it would play
                // unseen behind it.
                factory = { SurfaceView(it).apply { setZOrderMediaOverlay(true) }.also(player::setVideoSurfaceView) },
                modifier = Modifier.aspectRatio(aspect),
            )
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = veil }.background(Color.Black))
        }
        AnimatedContent(
            targetState = game,
            transitionSpec = { fadeIn(tween(500)) togetherWith fadeOut(tween(300)) },
            modifier = Modifier.fillMaxWidth(),
            label = "info",
        ) { g -> InfoBar(g, g?.let { systems[it.system] }) }
    }
}

/**
 * The game on screen, as one line of chrome along the bottom: box art, system, title, release
 * date and players.
 */
@Composable
private fun InfoBar(game: Game?, system: SystemSummary?) {
    Row(
        Modifier.fillMaxWidth().height(64.dp)
            .background(Sov.Surface)
            .drawBehind { drawLine(Color.White.copy(alpha = 0.1f), Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(horizontal = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (game == null) return@Row
        // The game's details take what the hint leaves; a long title is the one that gives way.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (game.thumb != null) {
                Art(game.thumb, Modifier.height(48.dp).aspectRatio(3 / 4f).clip(RoundedCornerShape(4.dp)), scale = ContentScale.Fit)
            }
            SystemBadge(system?.short ?: game.system.uppercase(), system?.colour() ?: Sov.Accent)
            Text(
                game.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            system?.let { Text(it.name, color = Sov.Slate400, fontSize = 14.sp, maxLines = 1) }
            formatReleaseDate(game.releaseDate)?.let { Fact(Lucide.CalendarDays, it) }
            formatPlayers(game.players)?.let { Fact(Lucide.Users, it) }
        }
        Text("Press any button", color = Sov.Slate500, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun Fact(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) =
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LucideIcon(icon, tint = Sov.Slate400, size = 16.dp)
        Text(text, color = Sov.Slate300, fontSize = 15.sp)
    }
