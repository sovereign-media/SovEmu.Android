package media.sovereign.sovemu

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.wifi.WifiManager
import android.os.Build
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * While a game is on screen, asks the device for its lowest-latency path from the network to the
 * glass; both are released when the player closes.
 *
 * - **The display:** minimal post-processing. Over HDMI this is ALLM, which switches the TV to its
 *   game picture mode; a TV's own processing otherwise adds 50-100 ms or more after the frame has
 *   arrived. Android 11+; ignored by displays that cannot.
 * - **Wi-Fi:** a low-latency lock, which turns off power saving that batches incoming packets. A
 *   batched stream arrives in bursts, and the jitter buffer then holds frames back to smooth them.
 *   Android 10+ (high-performance before); it has no effect on Ethernet.
 */
@Composable
fun LowLatencyWhilePlaying() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val window = context.findActivity()?.window
        window?.preferMinimalPostProcessing(true)
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createWifiLock(WIFI_LOCK_MODE, "SovEmu:game")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        onDispose {
            lock?.release()
            window?.preferMinimalPostProcessing(false)
        }
    }
}

@Suppress("DEPRECATION") // WIFI_MODE_FULL_HIGH_PERF is the only choice before Android 10.
private val WIFI_LOCK_MODE =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
    else WifiManager.WIFI_MODE_FULL_HIGH_PERF

private fun Window.preferMinimalPostProcessing(prefer: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setPreferMinimalPostProcessing(prefer)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
