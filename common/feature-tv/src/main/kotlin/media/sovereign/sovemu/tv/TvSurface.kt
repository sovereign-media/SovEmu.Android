package media.sovereign.sovemu.tv

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.EntryProviderScope
import media.sovereign.sovemu.AppRoutes
import media.sovereign.sovemu.Route
import media.sovereign.sovemu.Shell
import media.sovereign.sovemu.Surface
import media.sovereign.sovemu.sharedRoutes
import media.sovereign.sovemu.ui.LaunchPage
import media.sovereign.sovemu.ui.LaunchScreens

/** The TV apps' screens, over the shared app. */
object TvSurface : Surface {
    @Composable
    override fun SignedOut(shell: Shell, start: List<LaunchPage>) =
        LaunchScreens(shell.settings, shell.api, start, TvLaunchPages)

    override fun EntryProviderScope<Route>.routes(app: AppRoutes) {
        entry<Route.Accounts>({ it.key }) {
            LaunchScreens(
                app.shell.settings,
                app.shell.api,
                LaunchPage.overApp(),
                TvLaunchPages,
                fromAccount = true,
                onReturn = app.pop,
            )
        }
        sharedRoutes(app)
    }
}
