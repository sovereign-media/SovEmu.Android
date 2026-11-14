package media.sovereign.sovemu

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import media.sovereign.sovemu.core.Game
import media.sovereign.sovemu.core.SystemSummary
import media.sovereign.sovemu.ui.ConnectScreen
import media.sovereign.sovemu.ui.GamesScreen
import media.sovereign.sovemu.ui.PlayerScreen
import media.sovereign.sovemu.ui.SystemsScreen

/** Where the app is. Plain state rather than a navigation library: four screens, one stack. */
sealed interface Screen {
    data object Connect : Screen
    data object Systems : Screen
    data class Games(val system: SystemSummary) : Screen
    data class Player(val system: SystemSummary, val game: Game) : Screen
}

class MainActivity : ComponentActivity() {
    private lateinit var settings: Settings
    private lateinit var api: SovEmuApi
    private val controller = Controller()
    /** True while a game is playing: controller input goes to the game, not the UI. */
    private var playing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        settings = Settings(this)
        api = SovEmuApi(settings)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF4169E1), background = Color(0xFF0B0D12))) {
                App(settings.serverUrl.isNotEmpty() && settings.sessionCookie != null)
            }
        }
    }

    @Composable
    private fun App(signedIn: Boolean) {
        var screen by remember { mutableStateOf(if (signedIn) Screen.Systems else Screen.Connect) }
        playing = screen is Screen.Player
        when (val s = screen) {
            Screen.Connect -> ConnectScreen(settings, api) { screen = Screen.Systems }
            Screen.Systems -> {
                SystemsScreen(api, onOpen = { screen = Screen.Games(it) }, onSignedOut = { screen = Screen.Connect })
            }
            is Screen.Games -> {
                BackHandler { screen = Screen.Systems }
                GamesScreen(api, s.system, onPlay = { screen = Screen.Player(s.system, it) }, onBack = { screen = Screen.Systems })
            }
            is Screen.Player -> PlayerScreen(api, s.system, s.game, controller) {
                controller.clear()
                screen = Screen.Games(s.system)
            }
        }
    }

    // While playing, every controller event goes to the game. Otherwise the system handles
    // them (D-pad focus, A to click, B to go back).
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (playing && event.keyCode != KeyEvent.KEYCODE_BACK && controller.onKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (playing && controller.onMotion(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }
}
