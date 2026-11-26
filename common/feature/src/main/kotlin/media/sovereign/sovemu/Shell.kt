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

/** A place in the app. [key] names its saved state (scroll position, filters, focus). */
sealed interface Route {
    val key: String

    data object Home : Route {
        override val key = "home"
    }

    data object Search : Route {
        override val key = "search"
    }

    data class System(val system: SystemSummary, val open: Int? = null) : Route {
        override val key = "system:${system.id}"
    }

    data class Play(val system: SystemSummary, val game: Game, val start: Start) : Route {
        override val key = "play:${game.id}"
    }

    /** *Sign in a TV*, from the account menu on a phone or tablet. */
    data object SignInTv : Route {
        override val key = "sign-in-tv"
    }

    /** *Change user or server*, from the account menu: *Who's watching?* over the app. */
    data object Accounts : Route {
        override val key = "accounts"
    }
}

/**
 * What the shared app holds of the activity: the singletons, the window's controllers, music and
 * screensaver, and the app-wide settings the account menu switches. The activity makes one and
 * reads back whether a game is playing ([playing]) for its input routing.
 */
class Shell(
    val settings: Settings,
    val api: SovEmuApi,
    val store: Store,
    val controller: Controller,
    val presence: InputPresence,
    val idle: Idle,
    /** Work that outlives a screen (a sign-out's revoke): the activity's. */
    val scope: CoroutineScope,
    /** The menus' music should start or stop ([menusWantMusic] changed). */
    private val onMusic: () -> Unit,
    /** Keep the screen on (false: the device's own sleep timer takes over). */
    val keepScreenOn: (Boolean) -> Unit,
    /** Leave the app (Back on Home, confirmed). */
    val quit: () -> Unit,
) {
    /** The TV's game mode, asked for app-wide; the menus' music. */
    var gameMode by mutableStateOf(settings.gameMode)
    var musicOn by mutableStateOf(settings.music)

    /** A game is paused (its menu is open): the screensaver may start over it. */
    var gamePaused by mutableStateOf(false)

    /** True while a game is playing: controller input goes to the game, not the UI. */
    var playing = false
        private set

    /** The screens want music (not in a game, switched on). */
    var menusWantMusic = false
        private set

    /** What is on screen now: a game ([playing]), and whether the menus' [music] should play. */
    fun showing(playing: Boolean, music: Boolean) {
        this.playing = playing
        menusWantMusic = music
        onMusic()
    }
}

/**
 * What one surface draws over the shared app, as Sovereign.Android's two UI stacks: the TV apps
 * (`:common:feature-tv`) and the phone and tablet app (`:common:feature-mobile`) each give their
 * own screens. The back stack, settings, screensaver and quit question are shared.
 */
interface Surface {
    /** The screens while nobody is signed in: [start]'s pages (sign-in, *Who's watching?*). */
    @Composable
    fun SignedOut(shell: Shell, start: List<LaunchPage>)

    /** The signed-in app's screens, one Navigation 3 entry per [Route]. */
    fun EntryProviderScope<Route>.routes(app: AppRoutes)
}

/** What a surface's screens get from the signed-in app. */
class AppRoutes(
    val shell: Shell,
    val nav: Nav,
    val user: User?,
    val systems: List<SystemSummary>,
    /** Opens a route (going back to it when it is on the stack already). */
    val push: (Route) -> Unit,
    /** Back one route. */
    val pop: () -> Unit,
    stats: MutableState<Boolean>,
) {
    /** The stats overlay is on (a setting, kept across games). */
    var statsOn by stats
}

/**
 * The app over [shell], drawing [surface]'s screens: the sign-in screens while nobody is signed
 * in, else the signed-in app (its back stack, the account menu's settings, the screensaver, scan
 * lines and the quit question), shared by every app.
 */
@Composable
fun SovEmuApp(shell: Shell, surface: Surface): Unit = with(shell) {
    // Signed in = an account is active. Signing out, or a refresh the server refuses
    // (invalid_grant), comes back here to the sign-in screen.
    val auth by api.session.state.collectAsStateWithLifecycle()
    val account = (auth as? AuthState.Authenticated)?.accountId
    // Each account's own library: whatever the last one loaded, or still has in flight, goes.
    LaunchedEffect(account) { store.showFor(account) }
    // The TV's game mode for the whole app, not per game: each switch blinks some TVs.
    TvGameMode(gameMode)
    if (account == null) {
        SideEffect { showing(playing = false, music = musicOn) }
        // Who's watching, the sign-in form, or Choose a server: started again whenever the
        // session moves (a server change, a sign-in ending).
        key(auth) { surface.SignedOut(shell, LaunchPage.start(auth, api)) }
        return
    }

    // Each account its own app: its back stack, saved state and navigation, from Home.
    // Its ViewModels too, in a store that goes when the account does.
    key(account) {
        CompositionLocalProvider(
            LocalViewModelStoreOwner provides rememberViewModelStoreOwner(),
        ) {
            SignedIn(shell, surface)
        }
    }
}

/** The app, signed in: Home and everything over it. */
@Composable
private fun SignedIn(shell: Shell, surface: Surface): Unit = with(shell) {
    // The back stack is ours (a list of routes); Navigation 3 shows its top, keeps each
    // entry's saved state (scroll, filters, focus) while it is on the stack, drops it when
    // popped, and routes system Back to [pop].
    val stack = remember { mutableStateListOf<Route>(Route.Home) }
    fun push(route: Route) {
        // Going to a screen already on the stack goes back to it (dropping those above).
        val at = stack.indexOfFirst { it.key == route.key }
        if (at >= 0) stack.removeRange(at, stack.size)
        stack.add(route)
    }
    // Back on the home screen asks first: one press too many would leave for the launcher.
    var confirmQuit by remember { mutableStateOf(false) }
    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
    var crt by remember { mutableStateOf(settings.scanlines) }
    var idleMinutes by remember { mutableIntStateOf(settings.screensaverMinutes) }
    val stats = remember { mutableStateOf(settings.stats) }
    val nav = remember {
        Nav(
            home = { stack.removeRange(1, stack.size) },
            search = { push(Route.Search) },
            system = { push(Route.System(it)) },
            scanlines = { crt },
            music = { musicOn },
            screensaver = { idleMinutes },
            nextScreensaver = {
                val next = SCREENSAVER_CHOICES.indexOf(idleMinutes) + 1
                idleMinutes = SCREENSAVER_CHOICES[next % SCREENSAVER_CHOICES.size]
                settings.screensaverMinutes = idleMinutes
            },
            toggleMusic = {
                musicOn = !musicOn
                settings.music = musicOn
            },
            toggleScanlines = {
                crt = !crt
                settings.scanlines = crt
            },
            gameMode = { gameMode },
            toggleGameMode = {
                gameMode = !gameMode
                settings.gameMode = gameMode
            },
            signOut = {
                // The revoke is sent without waiting: this works with the server down.
                scope.launch { api.signOut() }
            },
            signInTv = { push(Route.SignInTv) },
            accounts = { push(Route.Accounts) },
        )
    }

    val user by store.user.collectAsStateWithLifecycle()
    val systemsLoad by store.systems.collectAsStateWithLifecycle()
    val systems = (systemsLoad as? Load.Ready)?.value.orEmpty()
    val route = stack.last()
    val app = AppRoutes(shell, nav, user, systems, ::push, ::pop, stats)
    SideEffect {
        if (route is Route.Play) controller.analogSticks = route.system.needsGpu
        // Music in the menus; a game, and the screensaver's videos, have their own sound.
        showing(playing = route is Route.Play, music = musicOn && route !is Route.Play && !idle.on)
    }

    // The screensaver, after idleMinutes without input: in the menus, or over a paused game
    // (never over one being played), and only while the app is in the foreground.
    val inGame = route is Route.Play
    val canIdle = (!inGame || gamePaused) && idle.foreground
    // Asleep, the screen no longer stays on: the device's own sleep timer takes over.
    LaunchedEffect(idle.asleep) {
        keepScreenOn(!idle.asleep)
    }
    LaunchedEffect(idleMinutes, canIdle) {
        if (idleMinutes == 0 || !canIdle) return@LaunchedEffect
        idle.restart()
        while (true) {
            delay(5_000)
            idle.check(idleMinutes)
        }
    }

    // Scan lines over the menus and the screensaver (over a paused game too); never over a
    // game being shown, whose stream is the console's own picture.
    // The screen keeps its own focus while the quit question is up, and gets it back after.
    val screenFocus = remember { FocusRequester() }
    var askedToQuit by remember { mutableStateOf(false) }
    LaunchedEffect(confirmQuit) {
        if (confirmQuit) {
            askedToQuit = true
        } else if (askedToQuit) {
            screenFocus.requestFocus()
        }
    }
    QuitOnRootBack(atRoot = stack.size == 1 && !confirmQuit) { confirmQuit = true }
    // A browser sign-in that came back after the app was ended (Add user, from the account
    // menu): its sign-in form opens again to take it.
    LaunchedEffect(Unit) {
        if (BrowserSignIn.redirect.value != null) push(Route.Accounts)
    }
    val background by store.background.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().scanlines(crt && (route !is Route.Play || idle.on))) {
        Box(Modifier.fillMaxSize().focusRequester(screenFocus).focusRestorer().focusGroup()) {
            CompositionLocalProvider(LocalBackground provides background) {
                NavDisplay(
                    backStack = stack,
                    onBack = ::pop,
                    // Each screen its own saved state and ViewModels, gone when it is popped.
                    entryDecorators = listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator(),
                    ),
                    // No animation: a transition composes two screens at once, which a TV
                    // pays for.
                    transitionSpec = {
                        EnterTransition.None togetherWith ExitTransition.None
                    },
                    popTransitionSpec = {
                        EnterTransition.None togetherWith ExitTransition.None
                    },
                    predictivePopTransitionSpec = {
                        EnterTransition.None togetherWith ExitTransition.None
                    },
                    entryProvider = entryProvider {
                        with(surface) { routes(app) }
                    },
                )
            }
        }
        if (idle.on && canIdle) {
            if (idle.asleep) {
                // Played long enough: dark and silent until a press (the device may sleep).
                Box(Modifier.fillMaxSize().background(Color.Black))
            } else {
                Screensaver(
                    api,
                    remember(systems) { systems.associateBy { it.id } },
                    store::randomVideoGame,
                )
            }
        }
        if (confirmQuit) QuitConfirm(onStay = { confirmQuit = false }, onQuit = quit)
    }
}
