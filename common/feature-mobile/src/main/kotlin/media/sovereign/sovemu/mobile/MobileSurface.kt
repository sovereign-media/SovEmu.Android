package media.sovereign.sovemu.mobile

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.EntryProviderScope
import media.sovereign.sovemu.AppRoutes
import media.sovereign.sovemu.Route
import media.sovereign.sovemu.Shell
import media.sovereign.sovemu.Surface
import media.sovereign.sovemu.sharedRoutes
import media.sovereign.sovemu.ui.LaunchPage
import media.sovereign.sovemu.ui.LaunchScreens

/** The phone and tablet app's screens, over the shared app. */
object MobileSurface : Surface {
    @Composable
    override fun SignedOut(shell: Shell, start: List<LaunchPage>) =
        LaunchScreens(shell.settings, shell.api, start)

    override fun EntryProviderScope<Route>.routes(app: AppRoutes) = sharedRoutes(app)
}
