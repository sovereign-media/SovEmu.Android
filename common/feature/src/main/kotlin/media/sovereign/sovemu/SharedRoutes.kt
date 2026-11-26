package media.sovereign.sovemu

import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import media.sovereign.sovemu.core.AuthState
import media.sovereign.sovemu.core.ClientInfo
import media.sovereign.sovemu.core.Game
import media.sovereign.sovemu.core.IdlePicker
import media.sovereign.sovemu.core.SystemSummary
import media.sovereign.sovemu.core.User
import media.sovereign.sovemu.ui.HomeScreen
import media.sovereign.sovemu.ui.HomeViewModel
import media.sovereign.sovemu.ui.LaunchPage
import media.sovereign.sovemu.ui.LaunchScreens
import media.sovereign.sovemu.ui.LocalApi
import media.sovereign.sovemu.ui.LocalBackground
import media.sovereign.sovemu.ui.LocalForm
import media.sovereign.sovemu.ui.Nav
import media.sovereign.sovemu.ui.NavFrame
import media.sovereign.sovemu.ui.PlayerScreen
import media.sovereign.sovemu.ui.PlayerViewModel
import media.sovereign.sovemu.ui.QuitConfirm
import media.sovereign.sovemu.ui.QuitOnRootBack
import media.sovereign.sovemu.ui.Screensaver
import media.sovereign.sovemu.ui.SearchScreen
import media.sovereign.sovemu.ui.SearchViewModel
import media.sovereign.sovemu.ui.Section
import media.sovereign.sovemu.ui.SignInTvScreen
import media.sovereign.sovemu.ui.SignInTvViewModel
import media.sovereign.sovemu.ui.SovTheme
import media.sovereign.sovemu.ui.Start
import media.sovereign.sovemu.ui.SystemScreen
import media.sovereign.sovemu.ui.SystemViewModel
import media.sovereign.sovemu.ui.TvKeyboardHost
import media.sovereign.sovemu.ui.rememberViewModelStoreOwner
import media.sovereign.sovemu.ui.scanlines

/**
 * The screens every surface draws alike, until each surface has its own (`TvSurface`,
 * `MobileSurface`).
 */
fun EntryProviderScope<Route>.sharedRoutes(app: AppRoutes) = with(app) {
    with(shell) {
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
        entry<Route.Accounts>({ it.key }) {
            LaunchScreens(
                settings,
                api,
                LaunchPage.overApp(),
                fromAccount = true,
                onReturn = pop,
            )
        }
        entry<Route.SignInTv>({ it.key }) {
            NavFrame(Section.Home, nav, user, systems) {
                val signInTv = hiltViewModel<SignInTvViewModel>()
                val server by signInTv.server.collectAsStateWithLifecycle()
                val name by signInTv.userName.collectAsStateWithLifecycle()
                SignInTvScreen(
                    server = server,
                    name = name,
                    approve = signInTv::approve,
                    onDone = pop,
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
            NavFrame(
                Section.Systems,
                nav,
                user,
                systems,
                paintsBackground = true,
            ) {
                val games = hiltViewModel<
                    SystemViewModel,
                    SystemViewModel.Factory,
                    >(creationCallback = { it.create(r.system.id) })
                val index by games.games.collectAsStateWithLifecycle()
                val sort by games.sort.collectAsStateWithLifecycle()
                SystemScreen(
                    games, r.system, index, r.open, sort,
                    onSort = games::setSort,
                    onPlay = { game, start ->
                        push(Route.Play(r.system, game, start))
                    },
                    onFavourite = games::setFavourite,
                    onPatch = games::setPatch,
                )
            }
        }
        entry<Route.Play>({ it.key }) { r ->
            val touchControls by presence.touchControls
                .collectAsStateWithLifecycle(presence.touchControlsNow)
            val player = hiltViewModel<
                PlayerViewModel,
                PlayerViewModel.Factory,
                >(creationCallback = { it.create(r.game, r.system, r.start) })
            PlayerScreen(
                player,
                r.system,
                r.game,
                r.start,
                controller,
                touchControls,
                stats = statsOn,
                gameMode = gameMode,
                onStatsChange = {
                    statsOn = it
                    settings.stats = it
                },
                onExit = {
                    gamePaused = false
                    controller.clear()
                    pop()
                },
                onPaused = { gamePaused = it },
            )
        }
    }
}
