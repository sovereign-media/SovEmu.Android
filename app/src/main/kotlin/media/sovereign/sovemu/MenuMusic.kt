package media.sovereign.sovemu

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Background music for the menus: every MP3 bundled in `assets/music/`, shuffled, one after
 * another. Fades in when the menus want it and out when they don't (a game starting, the app
 * going to the background, the switch turned off), keeping its place in the track. It gives way
 * to other audio (audio focus) and is silent when no track is bundled. Main thread only.
 */
class MenuMusic(private val context: Context, private val scope: CoroutineScope) {
    private val tracks: List<String> =
        context.assets.list(FOLDER).orEmpty().filter {
            it.endsWith(".mp3", ignoreCase = true)
        }.map { "$FOLDER/$it" }
    private var order: List<String> = tracks.shuffled()
    private var next = 0
    private var player: MediaPlayer? = null
    private var wanted = false
    private var volume = 0f
    private var fade: Job? = null

    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change ->
            when (change) {
                // Another app's audio (or a call): stop rather than talk over it.
                AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> fadeTo(
                    0f,
                    pause = true,
                )

                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> fadeTo(DUCKED)

                AudioManager.AUDIOFOCUS_GAIN -> if (wanted) resume()
            }
        }
        .build()

    /** Whether the menus want music now: fades in or out to match. */
    fun play(on: Boolean) {
        if (on == wanted) return
        wanted = on
        if (on) {
            if (tracks.isEmpty()) return
            if (audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) resume()
        } else {
            fadeTo(0f, pause = true)
            audio.abandonAudioFocusRequest(focus)
        }
    }

    /** Lets go of the player (the activity is going away). */
    fun release() {
        fade?.cancel()
        player?.release()
        player = null
        audio.abandonAudioFocusRequest(focus)
    }

    private fun resume() {
        val p = player ?: start(order[next]) ?: return
        if (!p.isPlaying) p.start()
        fadeTo(VOLUME)
    }

    /**
     * Opens [path] from the assets and plays it; at its end, the next track (reshuffled after the
     * last).
     */
    private fun start(path: String): MediaPlayer? {
        val p = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(attributes)
                context.assets.openFd(path).use {
                    setDataSource(it.fileDescriptor, it.startOffset, it.length)
                }
                setVolume(volume, volume)
                setOnCompletionListener { finished ->
                    finished.release()
                    player = null
                    next = (next + 1) % order.size
                    if (next == 0) order = tracks.shuffled()
                    if (wanted) resume()
                }
                prepare()
            }
        }.getOrNull() ?: return null
        player = p
        return p
    }

    /**
     * Ramps the volume to [target] over [FADE_MS]; at silence, pauses (keeping its place) if
     * [pause].
     */
    private fun fadeTo(target: Float, pause: Boolean = false) {
        fade?.cancel()
        fade = scope.launch {
            val from = volume
            val steps = 20
            for (i in 1..steps) {
                volume = from + (target - from) * i / steps
                player?.setVolume(volume, volume)
                delay(FADE_MS / steps)
            }
            if (pause && target == 0f) player?.takeIf { it.isPlaying }?.pause()
        }
    }

    private companion object {
        const val FOLDER = "music"

        /** Under the menus' sounds and a TV's usual volume: background, not foreground. */
        const val VOLUME = 0.5f
        const val DUCKED = 0.15f
        const val FADE_MS = 600L
    }
}
