package media.sovereign.sovemu.mobile

import dagger.hilt.android.AndroidEntryPoint
import media.sovereign.sovemu.SovEmuActivity
import media.sovereign.sovemu.mobile.MobileSurface

/** The app, for phones and tablets: which one it is comes from this app's own [di.ClientInfoModule]. */
@AndroidEntryPoint
class MainActivity : SovEmuActivity() {
    override val surface = MobileSurface
}
