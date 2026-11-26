package media.sovereign.sovemu.firetv

import dagger.hilt.android.HiltAndroidApp
import media.sovereign.sovemu.SovEmuApplication

/**
 * The application; `@HiltAndroidApp` starts the Hilt graph. Everything it does is
 * [SovEmuApplication]'s, shared by all three apps.
 */
@HiltAndroidApp
class SovEmuFireTvApplication : SovEmuApplication()
