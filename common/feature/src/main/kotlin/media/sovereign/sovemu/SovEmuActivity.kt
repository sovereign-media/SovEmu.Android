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
import media.sovereign.sovemu.ui.HomeViewModel
import media.sovereign.sovemu.ui.LaunchPage
import media.sovereign.sovemu.ui.LaunchScreens
import media.sovereign.sovemu.ui.LocalApi
import media.sovereign.sovemu.ui.LocalBackground
import media.sovereign.sovemu.ui.Nav
import media.sovereign.sovemu.ui.PlayerScreen
import media.sovereign.sovemu.ui.PlayerViewModel
import media.sovereign.sovemu.ui.QuitConfirm
import media.sovereign.sovemu.ui.QuitOnRootBack
import media.sovereign.sovemu.ui.Screensaver
import media.sovereign.sovemu.ui.SearchViewModel
import media.sovereign.sovemu.ui.Section
import media.sovereign.sovemu.ui.SignInTvViewModel
import media.sovereign.sovemu.ui.SovTheme
import media.sovereign.sovemu.ui.Start
import media.sovereign.sovemu.ui.rememberViewModelStoreOwner
import media.sovereign.sovemu.ui.scanlines

/**
 * The app: every app's `MainActivity` extends it, as an `@AndroidEntryPoint`, and Hilt hands it
 * what lives as long as the process (the settings, the server and its session, the library's
 * state) and which app it is ([clientInfo]). The TV apps draw for a D-pad from the sofa and type
 * with the app's own keyboard; the phone and tablet app draws for touch and shows the touch
 * controller. What lives with the window (controllers, music, the screensaver's timer, input
 * routing) is made here; the app over it is [SovEmuApp], drawing its app's [surface].
 */
abstract class SovEmuActivity : ComponentActivity() {
    // Injected by the app's MainActivity (Hilt cannot construct an activity).
    @Inject lateinit var clientInfo: ClientInfo

    @Inject lateinit var settings: Settings

    @Inject lateinit var api: SovEmuApi

    @Inject lateinit var store: Store

    /** True in the Android TV and Fire TV apps, false in the phone and tablet app. */
    private val tv: Boolean get() = clientInfo.tv

    /** This app's screens: the TV apps' or the phone and tablet app's. */
    protected abstract val surface: Surface

    /** What the app over this activity shares with it. */
    private lateinit var shell: Shell

    private lateinit var presence: InputPresence
    private val controller = Controller()
    private lateinit var music: MenuMusic

    /** The app is in the foreground (the menus' music plays only then). */
    private var foreground = false

    /** When the app went to the background (monotonic), for the picker that returns after idle. */
    private var backgroundedAt: Long? = null

    /** The screensaver: shown after a while without input, while the app is in the foreground. */
    private val idle = Idle()

    /** The key whose press ended the screensaver: its release is swallowed too. */
    private var swallowKey: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Awake while the app is open: after a while without input it shows its own screensaver
        // (game videos), rather than the TV dimming or sleeping.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Compose paints every pixel: the window's own background would be a wasted full-screen
        // fill on every frame (fill rate is what a TV's GPU runs out of first).
        window.setBackgroundDrawable(null)
        ClientEvents.attach(this, api, lifecycleScope)
        controller.start(this)
        presence = InputPresence(this, controller, tv)
        music = MenuMusic(this, lifecycleScope)
        shell = Shell(
            settings, api, store, controller, presence, idle, lifecycleScope,
            onMusic = ::updateMusic,
            keepScreenOn = { on ->
                if (on) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            },
            quit = ::finish,
        )
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
                CompositionLocalProvider(LocalApi provides api) {
                    surface.Host { SovEmuApp(shell, surface) }
                }
            }
        }
    }

    private fun updateMusic() = music.play(shell.menusWantMusic && foreground)

    override fun onStart() {
        super.onStart()
        foreground = true
        idle.started()
        updateMusic()
        // Back after a long while: whoever picks up the remote now chooses who they are.
        val now = SystemClock.elapsedRealtime()
        if (IdlePicker.shouldShow(
                backgroundedAt,
                now,
                api.session.accountsHere().size,
                shell.playing,
            )
        ) {
            lifecycleScope.launch { api.session.choose() }
        }
        backgroundedAt = null
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && shell.playing) controller.lostFocus()
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
        if (shell.playing && event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (event.action == KeyEvent.ACTION_UP) controller.requestMenu()
            return true
        }
        if (shell.playing && controller.toGame && event.keyCode != KeyEvent.KEYCODE_BACK &&
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
        if (shell.playing && controller.toGame && controller.onMotion(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }
}

/** The screensaver's choices, in minutes without input (0: never). */
internal val SCREENSAVER_CHOICES = listOf(3, 5, 10, 0)

private fun stickMoved(e: MotionEvent): Boolean = listOf(
    MotionEvent.AXIS_X,
    MotionEvent.AXIS_Y,
    MotionEvent.AXIS_Z,
    MotionEvent.AXIS_RZ,
    MotionEvent.AXIS_HAT_X,
    MotionEvent.AXIS_HAT_Y,
)
    .any { kotlin.math.abs(e.getAxisValue(it)) > 0.5f }
