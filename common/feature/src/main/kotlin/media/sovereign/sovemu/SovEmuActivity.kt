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
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import javax.inject.Inject
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import media.sovereign.sovemu.core.AuthState
import media.sovereign.sovemu.core.ClientInfo
import media.sovereign.sovemu.core.Game
import media.sovereign.sovemu.core.IdlePicker
import media.sovereign.sovemu.core.SystemSummary
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
 * The app: every app's `MainActivity` extends it, as an `@AndroidEntryPoint`, and Hilt hands it
 * what lives as long as the process (the settings, the server and its session, the library's
 * state) and which app it is ([clientInfo]). The TV apps draw for a D-pad from the sofa and type
 * with the app's own keyboard; the phone and tablet app draws for touch and shows the touch
 * controller. What lives with the window (controllers, music) is made here.
 */
abstract class SovEmuActivity : ComponentActivity() {
    // Injected by the app's MainActivity (Hilt cannot construct an activity).
    @Inject lateinit var clientInfo: ClientInfo

    @Inject lateinit var settings: Settings

    @Inject lateinit var api: SovEmuApi

    @Inject lateinit var store: Store

    /** True in the Android TV and Fire TV apps, false in the phone and tablet app. */
    private val tv: Boolean get() = clientInfo.tv

    private lateinit var presence: InputPresence
    private val controller = Controller()
    private lateinit var music: MenuMusic

    /** The screens want music (not in a game, switched on), and the app is in the foreground. */
    private var menusWantMusic = false
    private var foreground = false

    /** The TV's game mode, and the menus' music: switched from the account menu. */
    private var gameMode by mutableStateOf(false)
    private var musicOn by mutableStateOf(false)

    /** When the app went to the background (monotonic), for the picker that returns after idle. */
    private var backgroundedAt: Long? = null

    /** The screensaver: shown after a while without input, while the app is in the foreground. */
    private val idle = Idle()

    /** A game is paused (its menu is open): the screensaver may start over it. */
    private var gamePaused by mutableStateOf(false)

    /** The key whose press ended the screensaver: its release is swallowed too. */
    private var swallowKey: Int? = null

    /** True while a game is playing: controller input goes to the game, not the UI. */
    private var playing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Awake while the app is open: after a while without input it shows its own screensaver
        // (game videos), rather than the TV dimming or sleeping.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Compose paints every pixel: the window's own background would be a wasted full-screen
        // fill on every frame (fill rate is what a TV's GPU runs out of first).
        window.setBackgroundDrawable(null)
        gameMode = settings.gameMode
        musicOn = settings.music
        ClientEvents.attach(this, api, lifecycleScope)
        controller.start(this)
        presence = InputPresence(this, controller, tv)
        music = MenuMusic(this, lifecycleScope)
        // Where the app opens, from the accounts stored for the server (no request): undispatched,
        // so the state is decided before the first frame.
        lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) { api.session.start() }
        if (settings.serverUrl.isNotEmpty() && api.session.allAccounts.value.isNotEmpty()) {
            // The server's sign-in details, asked again on each launch it can be reached (the
            // stored copy lets a sign-in refresh when it cannot).
            lifecycleScope.launch { attempt { api.bootstrap() } }
        }
        setContent {
            // Artwork goes through the API's client (its timeouts; /media URLs are signed).
            setSingletonImageLoaderFactory { context ->
                ImageLoader.Builder(context)
                    .components { add(OkHttpNetworkFetcherFactory(callFactory = { api.client })) }
                    .build()
            }
            SovTheme(tv) {
                // On a TV, text fields type with the app's own keyboard along the bottom of the
                // screen: Fire TV's covers all of it.
                CompositionLocalProvider(LocalApi provides api) {
                    TvKeyboardHost(enabled = LocalForm.current.tv) { App() }
                }
            }
        }
    }

    @Composable
    private fun App() {
        // Signed in = an account is active. Signing out, or a refresh the server refuses
        // (invalid_grant), comes back here to the sign-in screen.
        val auth by api.session.state.collectAsStateWithLifecycle()
        val account = (auth as? AuthState.Authenticated)?.accountId
        // Each account's own library: whatever the last one loaded, or still has in flight, goes.
        LaunchedEffect(account) { store.showFor(account) }
        // The TV's game mode for the whole app, not per game: each switch blinks some TVs.
        TvGameMode(gameMode)
        if (account == null) {
            SideEffect {
                playing = false
                menusWantMusic = musicOn
                updateMusic()
            }
            // Who's watching, the sign-in form, or Choose a server: started again whenever the
            // session moves (a server change, a sign-in ending).
            key(auth) { LaunchScreens(settings, api, LaunchPage.start(auth, api)) }
            return
        }

        // Each account its own app: its back stack, saved state and navigation, from Home.
        // Its ViewModels too, in a store that goes when the account does.
        key(account) {
            CompositionLocalProvider(
                LocalViewModelStoreOwner provides rememberViewModelStoreOwner(),
            ) {
                Main()
            }
        }
    }

    /** The app, signed in: Home and everything over it. */
    @Composable
    private fun Main() {
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
        var statsOn by remember { mutableStateOf(settings.stats) }
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
                    lifecycleScope.launch { api.signOut() }
                },
                signInTv = { push(Route.SignInTv) },
                accounts = { push(Route.Accounts) },
            )
        }

        val user by store.user.collectAsStateWithLifecycle()
        val systemsLoad by store.systems.collectAsStateWithLifecycle()
        val systems = (systemsLoad as? Load.Ready)?.value.orEmpty()
        val route = stack.last()
        SideEffect {
            playing = route is Route.Play
            if (route is Route.Play) controller.analogSticks = route.system.needsGpu
            // Music in the menus; a game, and the screensaver's videos, have their own sound.
            menusWantMusic = musicOn && route !is Route.Play && !idle.on
            updateMusic()
        }

        // The screensaver, after idleMinutes without input: in the menus, or over a paused game
        // (never over one being played), and only while the app is in the foreground.
        val inGame = route is Route.Play
        val canIdle = (!inGame || gamePaused) && idle.foreground
        // Asleep, the screen no longer stays on: the device's own sleep timer takes over.
        LaunchedEffect(idle.asleep) {
            if (idle.asleep) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
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
                                    onReturn = ::pop,
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
                                        onDone = ::pop,
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
            if (confirmQuit) QuitConfirm(onStay = { confirmQuit = false }, onQuit = ::finish)
        }
    }

    private fun updateMusic() = music.play(menusWantMusic && foreground)

    override fun onStart() {
        super.onStart()
        foreground = true
        idle.started()
        updateMusic()
        // Back after a long while: whoever picks up the remote now chooses who they are.
        val now = SystemClock.elapsedRealtime()
        if (IdlePicker.shouldShow(backgroundedAt, now, api.session.accountsHere().size, playing)) {
            lifecycleScope.launch { api.session.choose() }
        }
        backgroundedAt = null
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && playing) controller.lostFocus()
    }

    override fun onStop() {
        foreground = false
        backgroundedAt = SystemClock.elapsedRealtime()
        // Out of sight, the screensaver ends: nothing could dismiss it, and its videos have sound.
        idle.stopped()
        updateMusic()
        super.onStop()
    }

    override fun onDestroy() {
        music.release()
        controller.stop()
        super.onDestroy()
    }

    // A remap screen waiting for an input gets it first. While playing (and no menu is open),
    // every controller event goes to the game. Otherwise the system handles them (D-pad focus,
    // A to click, B to go back).
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Any button ends the screensaver, and does nothing else (its release included).
        if (idle.on) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                idle.input()
                swallowKey = event.keyCode
            }
            return true
        }
        idle.input()
        if (swallowKey == event.keyCode) {
            if (event.action == KeyEvent.ACTION_UP) swallowKey = null
            return true
        }
        if (controller.captureKey(event)) return true
        // The guide/home button opens (and closes) the in-game menu: with only a controller in
        // hand, B goes to the game, so this and Select+Start are the ways out.
        if (playing && event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (event.action == KeyEvent.ACTION_UP) controller.requestMenu()
            return true
        }
        if (playing && controller.toGame && event.keyCode != KeyEvent.KEYCODE_BACK &&
            controller.onKey(event)
        ) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (idle.on) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) idle.input()
            return true
        }
        idle.input()
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        // A stick pushed well over (not resting noise) counts as input, and ends the screensaver.
        if (stickMoved(event) && idle.input()) return true
        if (idle.on) return true
        if (controller.captureMotion(event)) return true
        if (playing && controller.toGame && controller.onMotion(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }
}

/** The screensaver's choices, in minutes without input (0: never). */
private val SCREENSAVER_CHOICES = listOf(3, 5, 10, 0)

private fun stickMoved(e: MotionEvent): Boolean = listOf(
    MotionEvent.AXIS_X,
    MotionEvent.AXIS_Y,
    MotionEvent.AXIS_Z,
    MotionEvent.AXIS_RZ,
    MotionEvent.AXIS_HAT_X,
    MotionEvent.AXIS_HAT_Y,
)
    .any { kotlin.math.abs(e.getAxisValue(it)) > 0.5f }
