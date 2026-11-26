package media.sovereign.sovemu.firetv

import dagger.hilt.android.AndroidEntryPoint
import media.sovereign.sovemu.SovEmuActivity
import media.sovereign.sovemu.tv.TvSurface

/** The app, for Fire TV: which one it is comes from this app's own [di.ClientInfoModule]. */
@AndroidEntryPoint
class MainActivity : SovEmuActivity() {
    override val surface = TvSurface
}
