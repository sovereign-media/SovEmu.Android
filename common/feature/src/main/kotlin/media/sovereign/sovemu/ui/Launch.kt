package media.sovereign.sovemu.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.launch
import media.sovereign.sovemu.BrowserSignIn
import media.sovereign.sovemu.Settings
import media.sovereign.sovemu.SovEmuApi
import media.sovereign.sovemu.core.AuthState
import media.sovereign.sovemu.core.LaunchAccounts
import media.sovereign.sovemu.core.LaunchText
import media.sovereign.sovemu.core.ServerMove
import media.sovereign.sovemu.core.attempt

/** A page of the account screens. */
sealed interface LaunchPage {
    /** *Who's watching?* */
    data object Picker : LaunchPage

    /** *Choose a server*. */
    data object Servers : LaunchPage

    /** The proxy headers sent to the server. */
    data object Headers : LaunchPage

    /**
     * The sign-in form: [addUser] for *Add a user*, [username] and [expired] after a sign-in
     * ended, [newServer] for *Add a server*, which puts [returnTo] back as the server on Back.
     */
    data class SignIn(
        val addUser: Boolean = false,
        val username: String = "",
        val expired: Boolean = false,
        val newServer: Boolean = false,
        val returnTo: String? = null,
    ) : LaunchPage

    companion object {
        /**
         * Where the app opens when nobody is signed in, for [state]: the picker while choosing;
         * after a sign-in ended, the sign-in form with its username (over the picker when others
         * remain here); else the sign-in form.
         */
        fun start(state: AuthState, api: SovEmuApi): List<LaunchPage> =
            waitingForBrowser(startFor(state, api))

        /** Opened over the app (*Change user or server*): the picker. */
        fun overApp(): List<LaunchPage> = waitingForBrowser(listOf(Picker))

        /**
         * A browser sign-in that came back after the app was ended needs a sign-in form to take
         * its redirect: one goes on top when [pages] end without one.
         */
        private fun waitingForBrowser(pages: List<LaunchPage>): List<LaunchPage> =
            if (BrowserSignIn.redirect.value != null && pages.last() !is SignIn) {
                pages + SignIn(addUser = true)
            } else {
                pages
            }

        private fun startFor(state: AuthState, api: SovEmuApi): List<LaunchPage> = when (state) {
            AuthState.Choosing -> listOf(Picker)

            AuthState.Expired -> {
                val ended = SignIn(
                    username = api.session.lastExpired?.userName.orEmpty(),
                    expired = true,
                )
                if (api.session.accountsHere().isEmpty()) listOf(ended) else listOf(Picker, ended)
            }

            else -> listOf(SignIn())
        }
    }
}

/**
 * The account screens, as Sovereign.Android's: *Who's watching?*, *Choose a server* and the
 * sign-in form, each over the last; Back goes down the stack. Each page is a Navigation 3 entry
 * with its own saved state and ViewModel ([AccountPickerViewModel], [ChooseServerViewModel],
 * [CustomHeadersViewModel], [LoginViewModel]), kept in a store that goes with these screens.
 * Opened [fromAccount] (the account menu's *Change user or server*), the picker marks the account
 * signed in now, and choosing it, or Back at the bottom, calls [onReturn]. Signing in, choosing
 * someone else or another server moves the app's session, and the activity follows it.
 */
@Composable
fun LaunchScreens(
    settings: Settings,
    api: SovEmuApi,
    start: List<LaunchPage>,
    modifier: Modifier = Modifier,
    fromAccount: Boolean = false,
    onReturn: () -> Unit = {},
) {
    val stack = remember { mutableStateListOf<LaunchPage>(*start.toTypedArray()) }
    val scope = rememberCoroutineScope()
    val owner = rememberViewModelStoreOwner()

    fun pop() {
        val top = stack.lastOrNull()
        if (top is LaunchPage.SignIn && top.newServer && top.returnTo != null &&
            settings.serverUrl != top.returnTo
        ) {
            // A new address was tried and abandoned: the server it replaced comes back.
            api.setServer(top.returnTo)
            scope.launch { attempt { api.bootstrap() } }
        }
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else onReturn()
    }

    /** Where choosing or removing a server left the app. */
    fun moved(move: ServerMove) {
        when (move) {
            ServerMove.Stayed -> if (stack.lastOrNull() == LaunchPage.Servers) pop()

            is ServerMove.Moved -> {
                stack.clear()
                val signIn = move.landing == LaunchAccounts.Login
                stack.add(if (signIn) LaunchPage.SignIn() else LaunchPage.Picker)
            }

            ServerMove.NoServer -> {
                stack.clear()
                stack.add(LaunchPage.SignIn(newServer = true))
            }

            is ServerMove.Failed -> Unit
        }
    }

    // The bottom page's Back, over the app: Navigation 3 handles Back only above it.
    if (fromAccount && stack.size == 1) BackHandler(onBack = ::pop)

    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        NavDisplay(
            backStack = stack,
            onBack = ::pop,
            modifier = modifier,
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            // No animation: a transition composes two screens at once, which a TV pays for.
            transitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
            popTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
            predictivePopTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
            entryProvider = entryProvider {
                entry<LaunchPage.Picker> {
                    val model = hiltViewModel<AccountPickerViewModel>()
                    val state by model.uiState.collectAsStateWithLifecycle()
                    val current = state.currentId
                    WhoIsWatchingScreen(
                        accounts = state.accounts,
                        server = state.server,
                        onChoose = { account ->
                            if (account.id == current) onReturn() else model.choose(account)
                        },
                        onAddUser = { stack.add(LaunchPage.SignIn(addUser = true)) },
                        onChangeServer = { stack.add(LaunchPage.Servers) },
                        focusOn = current.takeIf { fromAccount } ?: state.lastUsedId,
                        current = current.takeIf { fromAccount },
                    )
                }
                entry<LaunchPage.Servers> {
                    val model = hiltViewModel<ChooseServerViewModel>()
                    val state by model.uiState.collectAsStateWithLifecycle()
                    ChooseServerScreen(
                        servers = state.servers,
                        onChoose = { model.choose(it, ::moved) },
                        onRemove = { model.remove(it, ::moved) },
                        onAddServer = {
                            stack.add(
                                LaunchPage.SignIn(newServer = true, returnTo = settings.serverUrl),
                            )
                        },
                        error = state.error,
                        busy = state.busy,
                    )
                }
                entry<LaunchPage.Headers> {
                    val model = hiltViewModel<CustomHeadersViewModel>()
                    val headers by model.headers.collectAsStateWithLifecycle()
                    CustomHeadersScreen(headers, onSave = model::save, onDone = ::pop)
                }
                entry<LaunchPage.SignIn> { page ->
                    val model = hiltViewModel<LoginViewModel, LoginViewModel.Factory>(
                        creationCallback = { it.create(page.newServer) },
                    )
                    val all by api.session.allAccounts.collectAsStateWithLifecycle()
                    LoginScreen(
                        model,
                        // Signing in again as the account signed in now moves nothing: back to
                        // where it was.
                        onSignedIn = { if (fromAccount) onReturn() },
                        addUser = page.addUser,
                        username = page.username,
                        notice = LaunchText.EXPIRED.takeIf { page.expired },
                        onCustomHeaders = { stack.add(LaunchPage.Headers) },
                        onChangeServer = if (!page.newServer && !page.addUser && all.isNotEmpty()) {
                            { stack.add(LaunchPage.Servers) }
                        } else {
                            null
                        },
                    )
                }
            },
        )
    }
}
