package media.sovereign.sovemu

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.net.wifi.WifiManager
import android.os.Build
import android.view.Display
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * The TV's game mode for as long as the app is on screen, when [enabled] (the "TV game mode"
 * setting): minimal post-processing. Over HDMI this is ALLM, which switches the TV to its game
 * picture mode; a TV's own processing otherwise adds 50-100 ms or more after the frame has
 * arrived. Asked for once, not per game: some TVs (a Mi TV among them) blank the screen for a
 * moment at every switch. Released only when the setting is turned off; the system drops it by
 * itself while the app is not in the foreground. Android 11+; ignored by displays that cannot.
 */
@Composable
fun TvGameMode(enabled: Boolean) {
    val context = LocalContext.current
    DisposableEffect(context, enabled) {
        val window = context.findActivity()?.window?.takeIf { enabled }
        window?.preferMinimalPostProcessing(true)
        onDispose { window?.preferMinimalPostProcessing(false) }
    }
}

/**
 * While a game is on screen, a low-latency Wi-Fi lock, released when the player closes: it turns
 * off power saving that batches incoming packets. A batched stream arrives in bursts, and the
 * jitter buffer then holds frames back to smooth them. Android 10+ (high-performance before); it
 * has no effect on Ethernet. Its state is whether the lock is held, for the worker's stats
 * (`wifi_lock`).
 */
@Composable
fun rememberLowLatencyWifi(): State<Boolean> {
    val context = LocalContext.current
    val held = remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createWifiLock(WIFI_LOCK_MODE, "SovEmu:game")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        held.value = lock?.isHeld == true
        onDispose {
            lock?.release()
            held.value = false
        }
    }
    return held
}

@Suppress("DEPRECATION") // WIFI_MODE_FULL_HIGH_PERF is the only choice before Android 10.
private val WIFI_LOCK_MODE =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        WifiManager.WIFI_MODE_FULL_LOW_LATENCY
    } else {
        WifiManager.WIFI_MODE_FULL_HIGH_PERF
    }

/**
 * Whether the display this app is on says it can be switched to minimal post-processing (its
 * game mode); null before Android 11, where there is no way to ask.
 */
fun minimalPostProcessingSupported(context: Context): Boolean? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    val display = context.getSystemService(
        DisplayManager::class.java,
    )?.getDisplay(Display.DEFAULT_DISPLAY)
    return display?.isMinimalPostProcessingSupported ?: false
}

private fun Window.preferMinimalPostProcessing(prefer: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setPreferMinimalPostProcessing(prefer)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
