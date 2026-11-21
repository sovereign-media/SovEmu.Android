package media.sovereign.sovemu

import android.os.Bundle
import media.sovereign.sovemu.ui.Screensaver
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableIntStateOf
import android.view.WindowManager
import android.os.SystemClock
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.runtime.entryProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.animation.togetherWith
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import kotlinx.coroutines.launch
import media.sovereign.sovemu.core.Game
import media.sovereign.sovemu.core.GameSort
import media.sovereign.sovemu.core.SystemSummary
import media.sovereign.sovemu.ui.HomeScreen
import media.sovereign.sovemu.ui.LocalApi
import media.sovereign.sovemu.ui.LoginScreen
import media.sovereign.sovemu.ui.Nav
import media.sovereign.sovemu.ui.NavFrame
import media.sovereign.sovemu.ui.PlayerScreen
import media.sovereign.sovemu.ui.QuitConfirm
import media.sovereign.sovemu.ui.Section
import media.sovereign.sovemu.ui.SovTheme
import media.sovereign.sovemu.ui.Start
import media.sovereign.sovemu.ui.SearchScreen
import media.sovereign.sovemu.ui.SystemScreen
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
}

class MainActivity : ComponentActivity() {
    private lateinit var settings: Settings
    private lateinit var api: SovEmuApi
    private lateinit var store: Store
    private lateinit var sessions: Sessions
    private lateinit var presence: InputPresence
    private val controller = Controller()
    private lateinit var music: MenuMusic
    /** The screens want music (not in a game, switched on), and the app is in the foreground. */
    private var menusWantMusic = false
    private var foreground = false
    /** Showing the screensaver (input ends it, and goes no further). */
    private var screensaverOn by mutableStateOf(false)
    /** A game is paused (its menu is open): the screensaver may start over it. */
    private var gamePaused by mutableStateOf(false)
    /** When a person last pressed, touched or moved something (uptime ms). */
    private var lastInput = SystemClock.uptimeMillis()
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
        settings = Settings(this)
        api = SovEmuApi(settings)
        store = Store(api, lifecycleScope)
        sessions = Sessions(api, lifecycleScope)
        controller.start(this)
        presence = InputPresence(this, controller)
        music = MenuMusic(this, lifecycleScope)
        if (settings.serverUrl.isNotEmpty() && api.auth.signedIn.value) {
            // The server's sign-in details, asked again on each launch it can be reached (the
            // stored copy lets a sign-in refresh when it cannot).
            lifecycleScope.launch { attempt { api.bootstrap() } }
            store.refresh()
        }
        setContent {
            // Artwork goes through the API's client (its timeouts; /media URLs are signed).
            setSingletonImageLoaderFactory { context ->
                ImageLoader.Builder(context).components { add(OkHttpNetworkFetcherFactory(callFactory = { api.client })) }.build()
            }
            SovTheme {
                CompositionLocalProvider(LocalApi provides api) { App() }
            }
        }
    }

    @Composable
    private fun App() {
        // Signed in = a refresh token is stored. Signing out, or a refresh the server refuses
        // (invalid_grant), clears it and comes back here to the sign-in screen.
        val signedIn by api.auth.signedIn.collectAsStateWithLifecycle()
        val expired by store.signedOut.collectAsStateWithLifecycle()
        LaunchedEffect(expired) {
            if (expired) api.auth.forget()
        }
        var musicOn by remember { mutableStateOf(settings.music) }
        if (!signedIn) {
            SideEffect {
                playing = false
                menusWantMusic = musicOn
                updateMusic()
            }
            LoginScreen(settings, api) {
                store.clear()
                store.refresh()
            }
            return
        }

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
            if (stack.size > 1) stack.removeAt(stack.lastIndex) else confirmQuit = true
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
                    idleMinutes = SCREENSAVER_CHOICES[(SCREENSAVER_CHOICES.indexOf(idleMinutes) + 1) % SCREENSAVER_CHOICES.size]
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
                signOut = {
                    lifecycleScope.launch {
                        api.signOut()
                        store.clear()
                    }
                },
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
            menusWantMusic = musicOn && route !is Route.Play && !screensaverOn
            updateMusic()
        }

        // The screensaver, after idleMinutes without input: in the menus, or over a paused game
        // (never over one being played).
        val inGame = route is Route.Play
        val canIdle = !inGame || gamePaused
        LaunchedEffect(idleMinutes, canIdle) {
            if (idleMinutes == 0 || !canIdle) return@LaunchedEffect
            lastInput = SystemClock.uptimeMillis()
            while (true) {
                delay(5_000)
                if (!screensaverOn && SystemClock.uptimeMillis() - lastInput >= idleMinutes * 60_000L) screensaverOn = true
            }
        }

        // Scan lines over the menus and the screensaver (over a paused game too); never over a
        // game being shown, whose stream is the console's own picture.
        // The screen keeps its own focus while the quit question is up, and gets it back after.
        val screenFocus = remember { FocusRequester() }
        var askedToQuit by remember { mutableStateOf(false) }
        LaunchedEffect(confirmQuit) {
            if (confirmQuit) askedToQuit = true else if (askedToQuit) screenFocus.requestFocus()
        }
        Box(Modifier.fillMaxSize().scanlines(crt && (route !is Route.Play || screensaverOn))) {
            Box(Modifier.fillMaxSize().focusRequester(screenFocus).focusRestorer().focusGroup()) {
                NavDisplay(
                    backStack = stack,
                    onBack = ::pop,
                    // No animation: a transition composes two screens at once, which a TV pays for.
                    transitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                    popTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                    predictivePopTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                    entryProvider = entryProvider {
                        entry<Route.Home>({ it.key }) {
                            NavFrame(Section.Home, nav, user, systems) {
                                val feed by store.home.collectAsStateWithLifecycle()
                                HomeScreen(
                                    feed, systems,
                                    onPlay = { game, start -> systems.firstOrNull { it.id == game.system }?.let { push(Route.Play(it, game, start)) } },
                                    onSystem = { s, open -> push(Route.System(s, open)) },
                                    onSearch = nav.search,
                                )
                            }
                        }
                        entry<Route.Search>({ it.key }) {
                            NavFrame(Section.Search, nav, user, systems) {
                                SearchScreen(api, systems, onOpen = { game ->
                                    systems.firstOrNull { it.id == game.system }?.let { push(Route.System(it, game.id)) }
                                })
                            }
                        }
                        entry<Route.System>({ it.key }) { r ->
                            NavFrame(Section.Systems, nav, user, systems, paintsBackground = true) {
                                val index by remember(r.system.id) { store.games(r.system.id) }.collectAsStateWithLifecycle()
                                var sort by remember { mutableStateOf(settings.sort) }
                                SystemScreen(
                                    api, r.system, index, r.open, sort,
                                    onSort = { sort = it; settings.sort = it },
                                    onPlay = { game, start -> push(Route.Play(r.system, game, start)) },
                                    onFavourite = store::setFavourite,
                                    onCollectionsChanged = store::collectionsChanged,
                                    onPatch = store::setPatch,
                                )
                            }
                        }
                        entry<Route.Play>({ it.key }) { r ->
                            val touchControls by presence.touchControls.collectAsStateWithLifecycle(presence.touchControlsNow)
                            PlayerScreen(
                                api, sessions, r.system, r.game, r.start, controller, touchControls,
                                stats = statsOn,
                                onStatsChange = {
                                    statsOn = it
                                    settings.stats = it
                                },
                                onEnded = { store.played(r.system.id) },
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
            if (screensaverOn && canIdle) {
                Screensaver(api, remember(systems) { systems.associateBy { it.id } }, store::randomVideoGame)
            }
            if (confirmQuit) QuitConfirm(onStay = { confirmQuit = false }, onQuit = ::finish)
        }
    }

    private fun updateMusic() = music.play(menusWantMusic && foreground)

    override fun onStart() {
        super.onStart()
        foreground = true
        updateMusic()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && playing) controller.lostFocus()
    }

    override fun onStop() {
        foreground = false
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
        lastInput = SystemClock.uptimeMillis()
        // Any button ends the screensaver, and does nothing else (its release included).
        if (screensaverOn) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                screensaverOn = false
                swallowKey = event.keyCode
            }
            return true
        }
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
        if (playing && controller.toGame && event.keyCode != KeyEvent.KEYCODE_BACK && controller.onKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        lastInput = SystemClock.uptimeMillis()
        if (screensaverOn) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) screensaverOn = false
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        // A stick pushed well over (not resting noise) counts as input, and ends the screensaver.
        if (stickMoved(event)) {
            lastInput = SystemClock.uptimeMillis()
            if (screensaverOn) {
                screensaverOn = false
                return true
            }
        }
        if (screensaverOn) return true
        if (controller.captureMotion(event)) return true
        if (playing && controller.toGame && controller.onMotion(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }
}

/** The screensaver's choices, in minutes without input (0: never). */
private val SCREENSAVER_CHOICES = listOf(3, 5, 10, 0)

private fun stickMoved(e: MotionEvent): Boolean =
    listOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y)
        .any { kotlin.math.abs(e.getAxisValue(it)) > 0.5f }

/** Minutes without input before the screensaver (5 by default; 0 turns it off). */
var Settings.screensaverMinutes: Int
    get() = prefs.getInt("screensaver", 5)
    set(value) = prefs.edit().putInt("screensaver", value).apply()

/** Background music in the menus (on by default). */
var Settings.music: Boolean
    get() = prefs.getBoolean("music", true)
    set(value) = prefs.edit().putBoolean("music", value).apply()

/** CRT scan lines over the menus (on by default). */
var Settings.scanlines: Boolean
    get() = prefs.getBoolean("scanlines", true)
    set(value) = prefs.edit().putBoolean("scanlines", value).apply()

/** The stats overlay over a game (off by default). */
var Settings.stats: Boolean
    get() = prefs.getBoolean("stats", false)
    set(value) = prefs.edit().putBoolean("stats", value).apply()

/** The game list's sort, kept across launches like the web client's. */
var Settings.sort: GameSort
    get() = runCatching { GameSort.valueOf(prefs.getString("sort", null)!!) }.getOrDefault(GameSort.TITLE)
    set(value) = prefs.edit().putString("sort", value.name).apply()
