package media.sovereign.sovemu

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import media.sovereign.sovemu.core.SystemSummary
import media.sovereign.sovemu.core.User
import media.sovereign.sovemu.ui.Nav
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Navigation 3 keeps an entry's content from when it was first shown. The bug this guards: the
 * routes got the systems as a plain list, so Home kept the empty one it saw before they loaded
 * and said "No games yet" over a library full of games.
 */
@RunWith(RobolectricTestRunner::class)
class AppRoutesTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun anEntryShowsSystemsThatLoadAfterItIsFirstShown() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = Settings(context)
        val api = SovEmuApi(settings)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val controller = Controller()
        val shell = Shell(
            settings, api, Store(api, scope), controller,
            InputPresence(context, controller, tv = false), Idle(), scope,
            onMusic = {}, keepScreenOn = {}, quit = {},
        )
        val nav = Nav(
            home = {},
            search = {},
            system = {},

            music = { false },
            toggleMusic = {},
            screensaver = { 0 },
            nextScreensaver = {},

            signOut = {},
        )
        val user = mutableStateOf<User?>(null)
        val systems = mutableStateOf(emptyList<SystemSummary>())
        rule.setContent {
            val app = remember {
                AppRoutes(shell, nav, user, systems, {}, {}, mutableStateOf(false))
            }
            NavDisplay(
                backStack = listOf<Route>(Route.Home),
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                entryProvider = entryProvider {
                    entry<Route.Home> { Text("${app.systems.size} systems") }
                },
            )
        }
        rule.onNodeWithText("0 systems").assertExists()
        systems.value = listOf(
            SystemSummary(
                id = "nes", name = "Nintendo Entertainment System", short = "NES",
                manufacturer = "Nintendo", year = 1983, color = "#c0392b", photoUrl = null,
                gameCount = 2, playable = true, needsGpu = false, rendering = null,
                biosMissing = false, boxAspect = 0.72,
            ),
        )
        rule.onNodeWithText("1 systems").assertExists()
    }
}
