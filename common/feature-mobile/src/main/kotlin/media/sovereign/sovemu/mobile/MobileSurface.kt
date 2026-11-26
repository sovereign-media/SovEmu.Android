package media.sovereign.sovemu.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.EntryProviderScope
import media.sovereign.sovemu.AppRoutes
import media.sovereign.sovemu.Route
import media.sovereign.sovemu.Shell
import media.sovereign.sovemu.Surface
import media.sovereign.sovemu.sharedRoutes
import media.sovereign.sovemu.ui.LaunchPage
import media.sovereign.sovemu.ui.LaunchScreens
import media.sovereign.sovemu.ui.NavFrame
import media.sovereign.sovemu.ui.Section
import media.sovereign.sovemu.ui.SignInTvScreen
import media.sovereign.sovemu.ui.SignInTvViewModel

/** The phone and tablet app's screens, over the shared app. */
object MobileSurface : Surface {
    @Composable
    override fun SignedOut(shell: Shell, start: List<LaunchPage>) =
        LaunchScreens(shell.settings, shell.api, start, MobileLaunchPages)

    override fun EntryProviderScope<Route>.routes(app: AppRoutes) {
        entry<Route.Accounts>({ it.key }) {
            LaunchScreens(
                app.shell.settings,
                app.shell.api,
                LaunchPage.overApp(),
                MobileLaunchPages,
                fromAccount = true,
                onReturn = app.pop,
            )
        }
        // *Sign in a TV*: a phone approves a TV's code (a TV never offers it).
        entry<Route.SignInTv>({ it.key }) {
            NavFrame(Section.Home, app.nav, app.user, app.systems) {
                val signInTv = hiltViewModel<SignInTvViewModel>()
                val server by signInTv.server.collectAsStateWithLifecycle()
                val name by signInTv.userName.collectAsStateWithLifecycle()
                SignInTvScreen(
                    server = server,
                    name = name,
                    approve = signInTv::approve,
                    onDone = app.pop,
                )
            }
        }
        sharedRoutes(app)
    }
}
