package media.sovereign.sovemu

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import media.sovereign.sovemu.core.touchControlsWanted

/**
 * What the player can be controlled with, decided in one place: the on-screen controls show
 * only on a touch screen with no controller or keyboard attached, and follow devices as they
 * connect and disconnect (from [Controller]'s device tracking).
 */
class InputPresence(context: Context, private val controller: Controller) {
    private val pm = context.packageManager
    private val touchscreen = pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) &&
        !pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    /** Whether to draw the touch controller over the game. */
    val touchControls: Flow<Boolean> = controller.devices.map {
        touchControlsWanted(touchscreen, it)
    }.distinctUntilChanged()

    /** The value right now, for the first frame. */
    val touchControlsNow: Boolean get() = touchControlsWanted(touchscreen, controller.devices.value)
}
