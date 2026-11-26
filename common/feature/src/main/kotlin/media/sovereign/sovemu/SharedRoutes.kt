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
import media.sovereign.sovemu.ui.SignInTvViewModel
import media.sovereign.sovemu.ui.SovTheme
import media.sovereign.sovemu.ui.Start
import media.sovereign.sovemu.ui.rememberViewModelStoreOwner
import media.sovereign.sovemu.ui.scanlines

/**
 * The screens every surface draws alike, until each surface has its own (`TvSurface`,
 * `MobileSurface`): the player.
 */
fun EntryProviderScope<Route>.sharedRoutes(app: AppRoutes) = with(app) {
    with(shell) {
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
