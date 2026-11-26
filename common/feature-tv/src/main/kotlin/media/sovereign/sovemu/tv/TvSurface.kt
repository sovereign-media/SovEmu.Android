package media.sovereign.sovemu.tv

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
import media.sovereign.sovemu.ui.HomeScreen
import media.sovereign.sovemu.ui.HomeViewModel
import media.sovereign.sovemu.ui.LaunchPage
import media.sovereign.sovemu.ui.LaunchScreens
import media.sovereign.sovemu.ui.NavFrame
import media.sovereign.sovemu.ui.SearchScreen
import media.sovereign.sovemu.ui.SearchViewModel
import media.sovereign.sovemu.ui.Section

/** The TV apps' screens, over the shared app. */
object TvSurface : Surface {
    @Composable
    override fun SignedOut(shell: Shell, start: List<LaunchPage>) =
        LaunchScreens(shell.settings, shell.api, start, TvLaunchPages)

    override fun EntryProviderScope<Route>.routes(app: AppRoutes) = with(app) {
        entry<Route.Accounts>({ it.key }) {
            LaunchScreens(
                shell.settings,
                shell.api,
                LaunchPage.overApp(),
                TvLaunchPages,
                fromAccount = true,
                onReturn = pop,
            )
        }
        entry<Route.Home>({ it.key }) {
            NavFrame(Section.Home, nav, user, systems) {
                val home = hiltViewModel<HomeViewModel>()
                val feed by home.feed.collectAsStateWithLifecycle()
                val server by home.server.collectAsStateWithLifecycle()
                HomeScreen(
                    feed,
                    systems,
                    onPlay = { game, start ->
                        systems.firstOrNull { it.id == game.system }
                            ?.let { push(Route.Play(it, game, start)) }
                    },
                    onSystem = { s, open -> push(Route.System(s, open)) },
                    onSearch = nav.search,
                    server = server,
                    onRetry = home::retry,
                    onSignOut = nav.signOut,
                )
            }
        }
        entry<Route.Search>({ it.key }) {
            NavFrame(Section.Search, nav, user, systems) {
                val search = hiltViewModel<SearchViewModel>()
                val results by search.results.collectAsStateWithLifecycle()
                SearchScreen(results, search::query, systems, onOpen = { game ->
                    systems.firstOrNull { it.id == game.system }
                        ?.let { push(Route.System(it, game.id)) }
                })
            }
        }
        sharedRoutes(app) { section, paints, content ->
            NavFrame(
                section,
                app.nav,
                app.user,
                app.systems,
                paintsBackground = paints,
                content = content,
            )
        }
    }
}
