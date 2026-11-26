package media.sovereign.sovemu.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import media.sovereign.sovemu.ui.LocalTextInput
import media.sovereign.sovemu.ui.NavFrame
import media.sovereign.sovemu.ui.PhoneTextInput
import media.sovereign.sovemu.ui.SearchScreen
import media.sovereign.sovemu.ui.SearchViewModel
import media.sovereign.sovemu.ui.Section
import media.sovereign.sovemu.ui.SignInTvScreen
import media.sovereign.sovemu.ui.SignInTvViewModel
import media.sovereign.sovemu.ui.SystemScreen
import media.sovereign.sovemu.ui.SystemViewModel

/** The phone and tablet app's screens, over the shared app. */
object MobileSurface : Surface {
    @Composable
    override fun Host(content: @Composable () -> Unit) =
        CompositionLocalProvider(LocalTextInput provides PhoneTextInput, content = content)

    @Composable
    override fun SignedOut(shell: Shell, start: List<LaunchPage>) =
        LaunchScreens(shell.settings, shell.api, start, MobileLaunchPages)

    override fun EntryProviderScope<Route>.routes(app: AppRoutes) = with(app) {
        entry<Route.Accounts>({ it.key }) {
            LaunchScreens(
                shell.settings,
                shell.api,
                LaunchPage.overApp(),
                MobileLaunchPages,
                fromAccount = true,
                onReturn = pop,
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
        entry<Route.System>({ it.key }) { r ->
            NavFrame(Section.Systems, nav, user, systems, paintsBackground = true) {
                val games = hiltViewModel<
                    SystemViewModel,
                    SystemViewModel.Factory,
                    >(creationCallback = { it.create(r.system.id) })
                val index by games.games.collectAsStateWithLifecycle()
                val sort by games.sort.collectAsStateWithLifecycle()
                SystemScreen(
                    games, r.system, index, r.open, sort,
                    onSort = games::setSort,
                    onPlay = { game, start -> push(Route.Play(r.system, game, start)) },
                    onFavourite = games::setFavourite,
                    onPatch = games::setPatch,
                )
            }
        }
        sharedRoutes(app)
    }
}
