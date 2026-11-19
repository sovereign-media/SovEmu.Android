package media.sovereign.sovemu

import android.os.Bundle
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
import androidx.compose.ui.Modifier
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
import media.sovereign.sovemu.ui.Section
import media.sovereign.sovemu.ui.SovTheme
import media.sovereign.sovemu.ui.Start
import media.sovereign.sovemu.ui.SystemScreen
import media.sovereign.sovemu.ui.scanlines

/** A place in the app. [key] names its saved state (scroll position, filters, focus). */
sealed interface Route {
    val key: String

    data object Home : Route {
        override val key = "home"
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
    /** True while a game is playing: controller input goes to the game, not the UI. */
    private var playing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Compose paints every pixel: the window's own background would be a wasted full-screen
        // fill on every frame (fill rate is what a TV's GPU runs out of first).
        window.setBackgroundDrawable(null)
        settings = Settings(this)
        api = SovEmuApi(settings)
        store = Store(api, lifecycleScope)
        sessions = Sessions(api, lifecycleScope)
        controller.start(this)
        presence = InputPresence(this, controller)
        val signedIn = settings.serverUrl.isNotEmpty() && settings.sessionCookie != null
        if (signedIn) store.refresh()
        setContent {
            // Artwork goes through the API's client: /media needs the sign-in cookie.
            setSingletonImageLoaderFactory { context ->
                ImageLoader.Builder(context).components { add(OkHttpNetworkFetcherFactory(callFactory = { api.client })) }.build()
            }
            SovTheme {
                CompositionLocalProvider(LocalApi provides api) { App(signedIn) }
            }
        }
    }

    @Composable
    private fun App(initiallySignedIn: Boolean) {
        var signedIn by remember { mutableStateOf(initiallySignedIn) }
        val expired by store.signedOut.collectAsStateWithLifecycle()
        LaunchedEffect(expired) {
            if (expired) {
                settings.sessionCookie = null
                signedIn = false
            }
        }
        if (!signedIn) {
            SideEffect { playing = false }
            LoginScreen(settings, api) {
                store.clear()
                store.refresh()
                signedIn = true
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
        fun pop() {
            if (stack.size > 1) stack.removeAt(stack.lastIndex) else finish()
        }
        var crt by remember { mutableStateOf(settings.scanlines) }
        val nav = remember {
            Nav(
                home = { stack.removeRange(1, stack.size) },
                system = { push(Route.System(it)) },
                scanlines = { crt },
                toggleScanlines = {
                    crt = !crt
                    settings.scanlines = crt
                },
                signOut = {
                    lifecycleScope.launch {
                        attempt { api.logout() }
                        settings.sessionCookie = null
                        store.clear()
                        signedIn = false
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
        }

        // Scan lines over the menus; never over a game (the stream is the console's own picture).
        Box(Modifier.fillMaxSize().scanlines(crt && route !is Route.Play)) {
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
                            )
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
                            )
                        }
                    }
                    entry<Route.Play>({ it.key }) { r ->
                        val touchControls by presence.touchControls.collectAsStateWithLifecycle(presence.touchControlsNow)
                        PlayerScreen(
                            api, sessions, r.system, r.game, r.start, controller, touchControls,
                            onEnded = { store.played(r.system.id) },
                            onExit = {
                                controller.clear()
                                pop()
                            },
                        )
                    }
                },
            )
        }
    }

    override fun onDestroy() {
        controller.stop()
        super.onDestroy()
    }

    // A remap screen waiting for an input gets it first. While playing (and no menu is open),
    // every controller event goes to the game. Otherwise the system handles them (D-pad focus,
    // A to click, B to go back).
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
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

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (controller.captureMotion(event)) return true
        if (playing && controller.toGame && controller.onMotion(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }
}

/** CRT scan lines over the menus (on by default). */
var Settings.scanlines: Boolean
    get() = prefs.getBoolean("scanlines", true)
    set(value) = prefs.edit().putBoolean("scanlines", value).apply()

/** The game list's sort, kept across launches like the web client's. */
var Settings.sort: GameSort
    get() = runCatching { GameSort.valueOf(prefs.getString("sort", null)!!) }.getOrDefault(GameSort.TITLE)
    set(value) = prefs.edit().putString("sort", value.name).apply()
